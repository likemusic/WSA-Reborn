/*
 * The "FileSystem" channel: files between Android and Windows.
 *
 * Guest → host: send a file to Windows (in chunks, asking to open it there), open in Windows
 * a file that already lies in the shared folder, publish a Windows file by GUID and report its name and
 * size, report that the external storage scan has finished.
 * Host → guest: the next chunk is accepted, does the guest see the shared folder, rescan a shared-folder file.
 * Package verification on install: the WsaPackageVerifier verifier, through PackageVerificationProvider,
 * hands every install here (the apk as bytes through a pipe), the apk is copied into
 * /mnt/shared_tmp/quarantine, the host runs it through the Windows antivirus (AMSI) and sends
 * a PackageVerificationResult decision.
 *
 * Where sends come from: SendToWindowsActivity — the "Share" and "Open" target labelled
 * "Windows". Where Windows file GUIDs come from: the clipboard (an item of type File) and
 * "Share" from Windows to an Android app (the "Intents" channel, LaunchPackage of type FileShare);
 * they are served to apps by WindowsFileProvider.
 *
 * Schema — proto/filesystem.proto (the genuine one, from the host WsaClient.exe). Reference for the guest
 * half — FileSystemRedirectionHandler and FileTransferInstance from UserApp.apk; the code here is our own.
 * Deliberate differences from the reference: replies to the host about the probe file and rescans are prepared outside the channel's
 * receive thread (the reference holds it for up to ten seconds); files with extensions executable in Windows
 * are sent without asking to open them. Analysis — docs/wsa-shell-protocol.md, section "The
 * "FileSystem" channel".
 */
package org.wsalineage.shellbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.util.Slog;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.Common.ActivityId;
import org.wsalineage.shellbridge.proto.FileSystem.CreateSharedTempFile;
import org.wsalineage.shellbridge.proto.FileSystem.FileSystemMessage;
import org.wsalineage.shellbridge.proto.FileSystem.FileSystemMessage.MessageType;
import org.wsalineage.shellbridge.proto.FileSystem.FileTransferRequest;
import org.wsalineage.shellbridge.proto.FileSystem.FileTransferResult;
import org.wsalineage.shellbridge.proto.FileSystem.FolderSharingTestResult;
import org.wsalineage.shellbridge.proto.FileSystem.OpenSharedFile;
import org.wsalineage.shellbridge.proto.FileSystem.PackageVerificationRequest;
import org.wsalineage.shellbridge.proto.FileSystem.ProvideContentFile;
import org.wsalineage.shellbridge.proto.FileSystem.ProvideContentFileDetails;
import org.wsalineage.shellbridge.proto.FileSystem.SharedFilePath;
import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class FileSystemBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.FileSystem";
    private static final String CHANNEL = "FileSystem";
    private static final long RECONNECT_MS = 2_000;
    private static final long DETAILS_TIMEOUT_MS = 15_000;
    /** The host copies the Windows file in full before replying; a big file takes long. */
    private static final long PROVIDE_TIMEOUT_MS = 120_000;

    /** File chunk per message. The reference uses 10 MB; smaller is gentler on memory, the host does not care. */
    private static final int CHUNK_BYTES = 4 * 1024 * 1024;

    /** Where the host puts Windows files on a ProvideContentFile request. */
    private static final File CONTENT_DIR = new File("/mnt/shared_tmp/content");
    /** Temporary directory shared with the host; apks for verification go into its quarantine subdirectory. */
    private static final File SHARED_TMP = new File("/mnt/shared_tmp");
    private static final String QUARANTINE = "quarantine";
    /** Wait for the host's decision less than the system waits for the verifier (120 s in the reference). */
    private static final long VERIFY_TIMEOUT_MS = 110_000;

    /** The Windows user folder on the guest side — if folder sharing is enabled. */
    private static final File WINDOWS_USER_FOLDER =
            new File(Environment.getExternalStorageDirectory(), "Windows");
    /** Set by wsa_mount_shared_folder when the Windows user folder is mounted. */
    private static final String PROP_USER_FOLDER_MOUNTED = "external_storage.windows_user.mounted";
    /** virtiofs disk tag with the Windows user folder. */
    private static final String USER_FOLDER_TAG = "windows_user";
    private static final int MOUNT_WAIT_STEPS = 100;
    private static final long MOUNT_WAIT_STEP_MS = 100;

    /**
     * Name of a Windows file in /mnt/shared_tmp/content: a GUID with the original file's extension
     * ("2f0c…-….txt", 40 bytes in the clipboard). Goes into a path, so no separators and no "..".
     */
    private static final Pattern GUID = Pattern.compile("[0-9A-Za-z{}_-][0-9A-Za-z{}._-]{0,127}");

    /**
     * Extensions that Windows executes on double-click rather than displays. An Android app
     * can slip such a file in under any name, so it does reach Windows, but
     * we do not ask to open it there — a human decides. The reference has no such check.
     */
    private static final Set<String> NO_LAUNCH_EXTENSIONS = new HashSet<>(Arrays.asList(
            "appref-ms", "bat", "cmd", "com", "cpl", "exe", "hta", "inf", "jar", "js", "jse",
            "lnk", "msc", "msi", "msix", "msp", "pif", "ps1", "psm1", "reg", "scf", "scr", "url",
            "vb", "vbe", "vbs", "ws", "wsc", "wsf", "wsh"));

    private static volatile FileSystemBridge sInstance;

    private final Context mContext;
    private final Handler mH;
    private final Handler mWorker;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final SecureRandom mRandom = new SecureRandom();
    private final Map<Long, Transfer> mTransfers = new ConcurrentHashMap<>();
    /** Package verifications awaiting the host's decision, by verificationId. */
    private final Map<Integer, CompletableFuture<Boolean>> mVerifications = new ConcurrentHashMap<>();
    /** Name and size of Windows files: they are asked for several times per paste. */
    private final LruCache<String, ProvideContentFileDetails> mDetails = new LruCache<>(64);

    public FileSystemBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        HandlerThread worker = new HandlerThread(TAG);
        worker.start();
        mWorker = new Handler(worker.getLooper());
        sInstance = this;
    }

    /** The bridge lives in the same process as the "Send to Windows" activity and the provider. */
    static FileSystemBridge get() {
        return sInstance;
    }

    public void start() {
        mH.post(() -> {
            registerMediaScanReceiver();
            connect();
        });
    }

    private void connect() {
        try {
            mChannel.open();
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "open failed: " + e.getMessage() + ", retry in " + RECONNECT_MS + "ms");
            mH.postDelayed(this::connect, RECONNECT_MS);
        }
    }

    @Override
    public void onClosed() {
        for (Transfer t : mTransfers.values()) t.close();
        mTransfers.clear();
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    // ---------------------------------------------------------------- host → guest

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        FileSystemMessage msg;
        try {
            msg = FileSystemMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad FileSystemMessage: " + e,
                    ResultCode.InvalidProtocolFormat);
        }
        Slog.i(TAG, "host -> " + msg.getMessageType());
        // The reference answers host requests with an empty frame and sends the result as a separate message.
        switch (msg.getMessageType()) {
            case FileTransferResult:
                if (!msg.hasFileTransferResult()) break;
                FileTransferResult r = msg.getFileTransferResult();
                mWorker.post(() -> onChunkAccepted(r));
                return null;
            case FolderSharingTestRequest:
                if (!msg.hasFolderSharingTestRequest()) break;
                SharedFilePath testFile = msg.getFolderSharingTestRequest().getTestFile();
                mWorker.post(() -> answerFolderSharingTest(testFile));
                return null;
            case MediaScanRequest:
                if (!msg.hasMediaScanRequest()) break;
                SharedFilePath path = msg.getMediaScanRequest().getFilePath();
                boolean shouldExist = msg.getMediaScanRequest().getShouldExist();
                mWorker.post(() -> rescan(path, shouldExist));
                return null;
            case PackageVerificationResult:
                if (!msg.hasPackageVerificationResult()) break;
                int id = msg.getPackageVerificationResult().getVerificationId();
                boolean allow = msg.getPackageVerificationResult().getAllowInstall();
                Slog.i(TAG, "package verification " + id + ": " + (allow ? "allowed" : "REJECTED"));
                CompletableFuture<Boolean> f = mVerifications.remove(id);
                if (f != null) f.complete(allow);
                else Slog.w(TAG, "nobody waits for verification " + id);
                return null;
            default:
                throw new ApiChannel.ApiException("unexpected message type " + msg.getMessageType(),
                        ResultCode.InvalidProtocolFormat);
        }
        throw new ApiChannel.ApiException(msg.getMessageType() + " without body",
                ResultCode.InvalidProtocolFormat);
    }

    private void sendAsync(FileSystemMessage msg) {
        try {
            mChannel.sendAsync(msg.toByteArray());
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "cannot send " + msg.getMessageType() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- sending a file to Windows

    /** Send a file to Windows. Called from SendToWindowsActivity, the work runs in its own thread. */
    void sendFile(Uri uri, boolean launch) {
        mWorker.post(() -> {
            if (openSharedFileOnWindows(uri, launch)) return;
            Transfer t;
            try {
                t = Transfer.open(mContext, uri, mRandom.nextLong(), launch);
            } catch (IOException | RuntimeException e) {
                Slog.e(TAG, "cannot read " + uri + ": " + e);
                return;
            }
            if (t.launch && !launchAllowed(t.fileName)) {
                Slog.w(TAG, "file type is executable on Windows, sending without launch: "
                        + t.fileName);
                t.launch = false;
            }
            Slog.i(TAG, "sending to Windows: " + t.fileName + " (" + t.fileSize + " bytes, launch="
                    + t.launch + ")");
            mTransfers.put(t.cookie, t);
            sendNextChunk(t);
        });
    }

    private void onChunkAccepted(FileTransferResult r) {
        Transfer t = mTransfers.get(r.getCookie());
        if (t == null) {
            // The reply to the final empty chunk arrives when the transfer has already been unregistered.
            return;
        }
        if (!r.getSucceeded() || r.getCancel()) {
            Slog.w(TAG, "host stopped transfer of " + t.fileName + " at " + t.offset
                    + (r.hasErrorMessage() ? ": " + r.getErrorMessage() : ""));
            finish(t);
            return;
        }
        sendNextChunk(t);
    }

    private void sendNextChunk(Transfer t) {
        byte[] buf = new byte[CHUNK_BYTES];
        int n = 0;
        boolean error = false;
        try {
            // read() may return less than requested, and that is not yet the end of the file.
            while (n < buf.length) {
                int got = t.in.read(buf, n, buf.length - n);
                if (got < 0) break;
                n += got;
            }
        } catch (IOException e) {
            Slog.e(TAG, "read failed for " + t.fileName + " at " + t.offset, e);
            error = true;
        }
        boolean complete = n == 0 && !error;
        FileTransferRequest req = FileTransferRequest.newBuilder()
                .setCookie(t.cookie)
                .setFileName(t.fileName)
                .setFileContents(ByteString.copyFrom(buf, 0, n))
                .setFileOffset(t.offset)
                .setFileSize(t.fileSize)
                .setTransferComplete(complete)
                .setTransferError(error)
                .setLaunch(t.launch)
                .build();
        t.offset += n;
        sendAsync(FileSystemMessage.newBuilder()
                .setMessageType(MessageType.FileTransferRequest)
                .setActivityId(t.activityId)
                .setFileTransferRequest(req)
                .build());
        if (complete) {
            Slog.i(TAG, "sent to Windows: " + t.fileName + ", " + t.offset + " bytes");
            finish(t);
        } else if (error) {
            finish(t);
        }
    }

    private void finish(Transfer t) {
        mTransfers.remove(t.cookie);
        t.close();
    }

    private static boolean launchAllowed(String fileName) {
        // Windows drops trailing dots and spaces: "a.exe ." opens as a.exe.
        String name = fileName.replaceAll("[. ]+$", "");
        int dot = name.lastIndexOf('.');
        if (dot < 0) return true;
        return !NO_LAUNCH_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** One outgoing transfer. */
    private static final class Transfer {
        final long cookie;
        final ActivityId activityId;
        final String fileName;
        final long fileSize;
        final InputStream in;
        boolean launch;
        long offset;

        private Transfer(long cookie, String fileName, long fileSize, InputStream in,
                boolean launch) {
            this.cookie = cookie;
            this.activityId = ActivityId.newBuilder().setValue(UUID.randomUUID().toString()).build();
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.in = in;
            this.launch = launch;
        }

        static Transfer open(Context context, Uri uri, long cookie, boolean launch)
                throws IOException {
            String name = null;
            long size = -1;
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int s = c.getColumnIndex(OpenableColumns.SIZE);
                    if (n >= 0) name = c.getString(n);
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                }
            }
            if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "file";
            // The name goes into a path on the Windows side — only the last component.
            name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("no stream for " + uri);
            return new Transfer(cookie, name, size, in, launch);
        }

        void close() {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- shared user folder

    private boolean isUserFolderMounted() {
        if (!MediaStore.getExternalVolumeNames(mContext).contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            return false;
        }
        return SystemProperties.getBoolean(PROP_USER_FOLDER_MOUNTED, false) || userFolderInMounts();
    }

    /**
     * Fallback check: the property is set by wsa_mount_shared_folder, but policy may not let
     * an app read system_prop — then we check whether the windows_user disk is mounted.
     */
    private static boolean userFolderInMounts() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/self/mounts"))) {
                if (line.startsWith(USER_FOLDER_TAG + " ")) return true;
            }
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "cannot read /proc/self/mounts: " + e);
        }
        return false;
    }

    private boolean waitForUserFolder() {
        for (int i = 0; i < MOUNT_WAIT_STEPS && !isUserFolderMounted(); i++) {
            SystemClock.sleep(MOUNT_WAIT_STEP_MS);
        }
        boolean mounted = isUserFolderMounted();
        if (!mounted) Slog.w(TAG, "Windows user folder is not mounted");
        return mounted;
    }

    private static File resolve(SharedFilePath path) {
        if (path.getFolder() != SharedFilePath.Folder.UserProfile) return null;
        String rel = path.getRelativePath();
        if (rel.startsWith("/")) return null;
        File f = new File(WINDOWS_USER_FOLDER, rel);
        try {
            // ".." in a relative path must not lead outside the shared folder.
            String base = WINDOWS_USER_FOLDER.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(base)) return null;
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    private void answerFolderSharingTest(SharedFilePath testFile) {
        File f = resolve(testFile);
        boolean ok = f != null && waitForUserFolder() && f.exists();
        Slog.i(TAG, "folder sharing test: " + (ok ? "file visible" : "file NOT visible"));
        sendAsync(FileSystemMessage.newBuilder()
                .setMessageType(MessageType.FolderSharingTestResult)
                .setFolderSharingTestResult(FolderSharingTestResult.newBuilder()
                        .setTestFile(testFile).setSucceeded(ok))
                .build());
    }

    private void rescan(SharedFilePath path, boolean shouldExist) {
        File f = resolve(path);
        if (f == null || !waitForUserFolder()) return;
        MediaScannerConnection.scanFile(mContext, new String[] { f.getPath() }, null,
                (p, uri) -> {
                    boolean indexed = uri != null;
                    if (indexed == shouldExist) {
                        Slog.i(TAG, "media scan ok: indexed=" + indexed);
                    } else {
                        Slog.w(TAG, "media scan mismatch: indexed=" + indexed
                                + ", expected " + shouldExist);
                    }
                });
    }

    /**
     * The file already lies in the Windows shared folder — no need to push its bytes through the channel, it is enough
     * to ask the host to open it in place.
     */
    private boolean openSharedFileOnWindows(Uri uri, boolean launch) {
        if (!isUserFolderMounted()) return false;
        String path = filePathOf(uri);
        if (path == null) return false;
        String rel;
        try {
            String base = WINDOWS_USER_FOLDER.getCanonicalPath() + File.separator;
            String abs = new File(path).getCanonicalPath();
            if (!abs.startsWith(base)) return false;
            rel = abs.substring(base.length());
        } catch (IOException e) {
            return false;
        }
        Slog.i(TAG, "opening file from the shared folder on Windows");
        sendAsync(FileSystemMessage.newBuilder()
                .setMessageType(MessageType.OpenSharedFile)
                .setOpenSharedFile(OpenSharedFile.newBuilder()
                        .setFilePath(SharedFilePath.newBuilder()
                                .setFolder(SharedFilePath.Folder.UserProfile)
                                .setRelativePath(rel))
                        .setLaunch(launch))
                .build());
        return true;
    }

    /** File system path behind a content:// link — only for MediaStore links. */
    private String filePathOf(Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getPath();
        if (!"content".equals(uri.getScheme())) return null;
        Uri media = uri;
        try {
            if (DocumentsContract.isDocumentUri(mContext, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                if ("com.android.providers.downloads.documents".equals(uri.getAuthority())
                        && id != null && id.startsWith("msf:")) {
                    media = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL,
                            Long.parseLong(id.substring(4)));
                } else {
                    media = MediaStore.getMediaUri(mContext, uri);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (media == null || !MediaStore.AUTHORITY.equals(media.getAuthority())) return null;
        try (Cursor c = mContext.getContentResolver().query(media,
                new String[] { MediaStore.MediaColumns.DATA }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot resolve file path: " + e);
        }
        return null;
    }

    private void registerMediaScanReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addDataScheme("file");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Uri data = intent.getData();
                if (data == null || !Environment.getExternalStorageDirectory().getPath()
                        .equals(data.getPath())) {
                    return;
                }
                Slog.i(TAG, "media scan of primary external storage finished");
                sendAsync(FileSystemMessage.newBuilder()
                        .setMessageType(MessageType.ExternalStorageMediaScanFinished).build());
            }
            // MEDIA_SCANNER_FINISHED is sent by MediaProvider, not the system: without an explicit flag Android 16
            // throws SecurityException and the bridge crashes at start (30.09.2026, aosp16-vanilla).
        }, filter, Context.RECEIVER_EXPORTED);
    }

    // ---------------------------------------------------------------- Windows files by GUID

    static boolean isValidGuid(String guid) {
        return guid != null && GUID.matcher(guid).matches() && !guid.contains("..");
    }

    /** Name and size of a Windows file; null — the host did not answer or does not know such a file. */
    ProvideContentFileDetails details(String guid) {
        if (!isValidGuid(guid)) return null;
        ProvideContentFileDetails cached = mDetails.get(guid);
        if (cached != null) return cached;
        FileSystemMessage reply = request(FileSystemMessage.newBuilder()
                .setMessageType(MessageType.ProvideContentFileDetails)
                .setActivityId(newActivityId())
                .setProvideContentFileDetails(ProvideContentFileDetails.newBuilder()
                        .setFileGuid(guid))
                .build(), DETAILS_TIMEOUT_MS);
        if (reply == null || !reply.hasProvideContentFileDetails()) return null;
        ProvideContentFileDetails d = reply.getProvideContentFileDetails();
        mDetails.put(guid, d);
        return d;
    }

    /**
     * Ask the host to put a Windows file into /mnt/shared_tmp/content/<GUID> and return it.
     * null — failed. Blocks until the host replies: it first copies the whole file.
     */
    File fetch(String guid) {
        if (!isValidGuid(guid)) return null;
        long started = SystemClock.uptimeMillis();
        FileSystemMessage reply = request(FileSystemMessage.newBuilder()
                .setMessageType(MessageType.ProvideContentFile)
                .setActivityId(newActivityId())
                .setProvideContentFile(ProvideContentFile.newBuilder().setFileGuid(guid))
                .build(), PROVIDE_TIMEOUT_MS);
        if (reply == null || !reply.hasProvideContentFileResult()
                || !reply.getProvideContentFileResult().getResult()) {
            Slog.w(TAG, "host did not provide file " + guid);
            return null;
        }
        File f = new File(CONTENT_DIR, guid);
        if (!f.isFile()) {
            Slog.e(TAG, "host reported success, but " + f + " is not readable");
            return null;
        }
        Slog.i(TAG, "file " + guid + " provided by host: " + f.length() + " bytes in "
                + (SystemClock.uptimeMillis() - started) + " ms");
        return f;
    }

    // ---------------------------------------------------------------- package verification

    /**
     * Check a package with the Windows antivirus. As in the reference: each apk is copied into a file
     * created by the host (CreateSharedTempFile in quarantine), then the request goes out, the decision arrives
     * as a separate message. Blocks the calling thread until the decision; false — reject (including
     * when the request was not sent or the host did not answer in time).
     */
    boolean verifyPackage(int id, List<ParcelFileDescriptor> apks, String packageName,
            int versionCode, long longVersionCode, String installer, int sessionId) {
        Slog.i(TAG, "package verification " + id + ": " + packageName + " v" + longVersionCode
                + ", installer " + installer + ", " + apks.size() + " apk");
        List<File> copies = new ArrayList<>();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        mVerifications.put(id, result);
        try {
            for (ParcelFileDescriptor apk : apks) {
                File copy = createSharedTempFile(QUARANTINE);
                copies.add(copy);
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(apk);
                     OutputStream out = Files.newOutputStream(copy.toPath())) {
                    in.transferTo(out);
                }
            }
            PackageVerificationRequest.Builder req = PackageVerificationRequest.newBuilder()
                    .setVerificationId(id)
                    .setPackageName(packageName != null ? packageName : "")
                    .setVersionCode(versionCode)
                    .setLongVersionCode(longVersionCode)
                    .setInstallerPackageName(installer != null ? installer : "ADB")
                    .setSessionId(sessionId);
            for (File c : copies) req.addApkFileNames(c.getName());
            // The host acknowledges receipt (usually with an empty reply) and sends the decision separately;
            // only a channel error or an acknowledgement timeout counts as a failure.
            mChannel.send(FileSystemMessage.newBuilder()
                    .setMessageType(MessageType.PackageVerificationRequest)
                    .setActivityId(newActivityId())
                    .setPackageVerificationRequest(req).build().toByteArray(),
                    DETAILS_TIMEOUT_MS);
            return result.get(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Slog.e(TAG, "package verification " + id + ": no answer from host, rejecting");
            return false;
        } catch (IOException | ApiChannel.ApiException | InterruptedException
                | ExecutionException | RuntimeException e) {
            Slog.e(TAG, "package verification " + id + " failed, rejecting: " + e);
            for (File c : copies) c.delete();
            return false;
        } finally {
            mVerifications.remove(id);
        }
    }

    /** Ask the host to create an empty file in /mnt/shared_tmp/<subfolder> and return its path. */
    private File createSharedTempFile(String subfolder) throws IOException {
        FileSystemMessage reply = request(FileSystemMessage.newBuilder()
                .setMessageType(MessageType.CreateSharedTempFile)
                .setActivityId(newActivityId())
                .setCreateSharedTempFile(CreateSharedTempFile.newBuilder()
                        .setSubfolderPath(subfolder)).build(), DETAILS_TIMEOUT_MS);
        if (reply == null || !reply.hasCreateSharedTempFile()
                || !reply.getCreateSharedTempFile().hasTempFileName()) {
            throw new IOException("no CreateSharedTempFile answer");
        }
        String name = reply.getCreateSharedTempFile().getTempFileName();
        if (!isValidGuid(name)) throw new IOException("bad temp file name: " + name);
        File f = new File(new File(SHARED_TMP, subfolder), name);
        if (!f.isFile()) throw new IOException("host reported " + f + ", but it is not there");
        return f;
    }

    private FileSystemMessage request(FileSystemMessage msg, long timeoutMs) {
        try {
            byte[] reply = mChannel.send(msg.toByteArray(), timeoutMs);
            return reply == null ? null : FileSystemMessage.parseFrom(reply);
        } catch (ApiChannel.ApiException | InvalidProtocolBufferException e) {
            Slog.w(TAG, msg.getMessageType() + " failed: " + e.getMessage());
            return null;
        }
    }

    private static ActivityId newActivityId() {
        return ActivityId.newBuilder().setValue(UUID.randomUUID().toString()).build();
    }
}
