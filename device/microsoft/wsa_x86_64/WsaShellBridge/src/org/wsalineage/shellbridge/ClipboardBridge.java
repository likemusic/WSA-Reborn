/*
 * The "Clipboard" channel: a shared Windows ↔ Android clipboard.
 *
 * Content is not sent over the channel — only metadata (type, offset, length). The bytes live in a
 * named Windows shared memory section, which the guest sees as the file /mnt/shared_mem/<name>
 * (virtiofs, the `shared_mem` line in fstab.*.windows). The exchange order and analysis of the reference are in
 * docs/wsa-shell-protocol.md, section "The "Clipboard" channel"; the schema is proto/clipboard.proto.
 *
 * At Microsoft this is ClipboardRedirectionHandler + util.ClipboardContentHelper from UserApp.apk, which
 * is absent from our builds. The code here is our own; two differences from the reference are intentional and marked
 * in place: an item is written at its declared offset (Microsoft takes the wrong slice of the source array
 * starting from the second item) and all work is moved off the main thread.
 */
package org.wsalineage.shellbridge;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Handler;
import android.os.PersistableBundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.Slog;

import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.Clipboard;
import org.wsalineage.shellbridge.proto.Clipboard.ClipboardContentItem;
import org.wsalineage.shellbridge.proto.Clipboard.ClipboardContentItem.ContentItemType;
import org.wsalineage.shellbridge.proto.Clipboard.ClipboardData;
import org.wsalineage.shellbridge.proto.Clipboard.ClipboardMessage;
import org.wsalineage.shellbridge.proto.Clipboard.SetClipboardContent;
import org.wsalineage.shellbridge.proto.Common.ActivityId;
import org.wsalineage.shellbridge.proto.Common.SharedMemory;
import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ClipboardBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.Clipboard";
    private static final String CHANNEL = "Clipboard";
    private static final long RECONNECT_MS = 2_000;
    private static final long REPLY_TIMEOUT_MS = 15_000;

    /** Directory where the host's shared memory is mounted (fstab.*.windows). */
    private static final String SHARED_MEM_DIR = "/mnt/shared_mem";

    /**
     * ClipData label that marks everything that came from the host. The clipboard listener uses it to tell
     * our own setPrimaryClip from a copy inside Android and does not send the content back.
     * The value is local: it does not go on the wire, the host never sees it.
     */
    private static final String LABEL_FROM_HOST = "WsaClipboardContents";

    /**
     * Suppresses SystemUI's "copied" preview (ClipboardOverlay, Android 13+). Otherwise every
     * copy in Windows shows a guest preview, and the host creates a separate Overlay window on the desktop
     * for this taskless layer for 2–7 s (host ETW 25.09.2026, also the source of
     * "Failed to find ColorBuffer"). ClipboardListener.shouldSuppressOverlay() hides the preview only
     * if the clip source is com.android.shell and the description has this extra; the bridge sets the source
     * via setPrimaryClipAsPackage (SET_CLIP_SOURCE is signature, the platform signature
     * is enough). A copy inside Android shows the preview as usual.
     */
    private static final String SUPPRESS_OVERLAY_SOURCE = "com.android.shell";
    private static final String EXTRA_SUPPRESS_OVERLAY =
            "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY";

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final ClipboardManager mClipboard;

    /** Content already sent to the host: protection against a repeated notification (see onPrimaryClipChanged). */
    private String mLastSentSignature = "";

    private final ClipboardManager.OnPrimaryClipChangedListener mClipListener =
            this::onPrimaryClipChanged;

    public ClipboardBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        mClipboard = context.getSystemService(ClipboardManager.class);
    }

    public void start() {
        mH.post(() -> {
            if (!sharedMemoryAvailable()) {
                // The channel is useless without the section: the exchange will run, but there is nothing to read or write.
                // A line in the boot log saves a whole round of investigation on the live subsystem.
                Slog.e(TAG, SHARED_MEM_DIR + " is not mounted — clipboard will not work; "
                        + "check the shared_mem line in fstab.*.windows");
            }
            mClipboard.addPrimaryClipChangedListener(mClipListener);
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
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    // ---------------------------------------------------------------- host → guest

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        ClipboardMessage msg;
        try {
            msg = ClipboardMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad ClipboardMessage: " + e,
                    ResultCode.InvalidProtocolFormat);
        }
        Slog.i(TAG, "host -> " + msg.getMessageType());
        if (msg.getMessageType() != ClipboardMessage.MessageType.SetServerClipboardContent) {
            throw new ApiChannel.ApiException("unexpected message type " + msg.getMessageType(),
                    ResultCode.InvalidProtocolFormat);
        }
        if (!msg.hasSetClipboardContent()
                || !msg.getSetClipboardContent().hasClipboardSharedMemory()) {
            throw new ApiChannel.ApiException("SetClipboardContent without shared memory",
                    ResultCode.InvalidProtocolFormat);
        }
        Clipboard.Result.Builder result = Clipboard.Result.newBuilder().setSucceeded(false);
        try {
            result.setSucceeded(applyFromHost(msg.getSetClipboardContent()));
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Slog.e(TAG, "cannot apply host clipboard", e);
            result.setErrorMessage(String.valueOf(e));
        }
        return msg.toBuilder().setResult(result).build().toByteArray();
    }

    private boolean applyFromHost(SetClipboardContent set) throws IOException {
        SharedMemory sm = set.getClipboardSharedMemory();
        ClipData clip;
        try (Section section = Section.open(sm)) {
            clip = unpack(set.getClipboardData(), section.buffer());
        }
        if (clip == null) {
            StringBuilder items = new StringBuilder();
            for (ClipboardContentItem item : set.getClipboardData().getClipboardContentList()) {
                items.append(' ').append(item.getContentItemType()).append('/')
                        .append(item.getSize());
            }
            Slog.w(TAG, "host clipboard had no usable content, items:"
                    + (items.length() == 0 ? " none" : items));
            return false;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_SUPPRESS_OVERLAY, true);
        clip.getDescription().setExtras(extras);
        mClipboard.setPrimaryClipAsPackage(clip, SUPPRESS_OVERLAY_SOURCE);
        Slog.i(TAG, "host clipboard applied, items=" + clip.getItemCount());
        return true;
    }

    /** Build ClipData from the section items. null means no usable content was found. */
    private ClipData unpack(ClipboardData data, MappedByteBuffer buf) {
        Set<String> mimeTypes = new LinkedHashSet<>();
        List<ClipData.Item> items = new ArrayList<>();
        for (ClipboardContentItem item : data.getClipboardContentList()) {
            byte[] bytes = read(item, buf);
            if (bytes == null) continue;
            switch (item.getContentItemType()) {
                case Html: {
                    String html = new String(bytes, StandardCharsets.UTF_8);
                    items.add(new ClipData.Item(
                            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString(), html));
                    mimeTypes.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
                    mimeTypes.add(ClipDescription.MIMETYPE_TEXT_HTML);
                    break;
                }
                case Uri: {
                    items.add(new ClipData.Item(
                            Uri.parse(new String(bytes, StandardCharsets.UTF_8))));
                    mimeTypes.add(ClipDescription.MIMETYPE_TEXT_URILIST);
                    break;
                }
                case Bitmap: {
                    Uri uri = ClipboardImageProvider.store(mContext, bytes);
                    if (uri == null) break;
                    items.add(new ClipData.Item(uri));
                    mimeTypes.add(ClipboardImageProvider.MIME_TYPE);
                    break;
                }
                case File: {
                    // The file itself is not in the section: only its GUID is there. The host will deliver the bytes over the
                    // "FileSystem" channel when an app opens the link (WindowsFileProvider).
                    String guid = new String(bytes, StandardCharsets.UTF_8).trim();
                    if (!FileSystemBridge.isValidGuid(guid)) {
                        Slog.w(TAG, "clipboard item of type File with bad GUID skipped");
                        break;
                    }
                    items.add(new ClipData.Item(WindowsFileProvider.uriFor(guid)));
                    mimeTypes.add(WindowsFileProvider.mimeTypeOf(guid));
                    break;
                }
                case Text:
                    // Plain text is taken in a second pass — only if neither html nor text has
                    // made it into the set yet (the reference does the same).
                    break;
            }
        }
        if (!mimeTypes.contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                && !mimeTypes.contains(ClipDescription.MIMETYPE_TEXT_HTML)) {
            for (ClipboardContentItem item : data.getClipboardContentList()) {
                if (item.getContentItemType() != ContentItemType.Text) continue;
                byte[] bytes = read(item, buf);
                if (bytes == null) continue;
                items.add(new ClipData.Item(new String(bytes, StandardCharsets.UTF_8)));
                mimeTypes.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
                break;
            }
        }
        if (items.isEmpty()) return null;
        ClipData clip = new ClipData(LABEL_FROM_HOST,
                mimeTypes.toArray(new String[0]), items.get(0));
        for (int i = 1; i < items.size(); i++) clip.addItem(items.get(i));
        return clip;
    }

    private static byte[] read(ClipboardContentItem item, MappedByteBuffer buf) {
        int offset = item.getOffset();
        int size = item.getSize();
        if (offset < 0 || size < 0 || offset + size > buf.capacity()) {
            Slog.e(TAG, "item " + item.getContentItemType() + " out of section: offset=" + offset
                    + " size=" + size + " capacity=" + buf.capacity());
            return null;
        }
        byte[] bytes = new byte[size];
        buf.position(offset);
        buf.get(bytes);
        return bytes;
    }

    // ---------------------------------------------------------------- guest → host

    private void onPrimaryClipChanged() {
        // ClipboardManager calls the listener on the main thread; we read the content here
        // (uid system's clipboard access is not restricted by focus), and the exchange with the host runs on the worker.
        if (!mChannel.isOpen()) return;
        if (!mClipboard.hasPrimaryClip()) return;
        ClipData clip = mClipboard.getPrimaryClip();
        if (clip == null) return;
        ClipDescription desc = clip.getDescription();
        // The label may be missing: "Files" copies without one, and String.contentEquals(null) crashed the bridge
        // (30.09.2026) — and with it the host closed and reopened all Android windows.
        if (desc != null && TextUtils.equals(LABEL_FROM_HOST, desc.getLabel())) return;
        // For ONE paste ClipboardService calls the listeners TWICE: immediately and again when
        // the asynchronous TextClassifier adds scores and links to the description
        // (applyClassificationAndSendBroadcastLocked). The content is the same, while each
        // send is a section request to the host and a full exchange. Noticed 24.09.2026 on a live slot.
        String signature = signatureOf(clip);
        if (signature.equals(mLastSentSignature)) return;
        mLastSentSignature = signature;
        mH.post(() -> publishToHost(clip));
    }

    /** What distinguishes the clipboard content from the previous one; compared to avoid sending twice. */
    private static String signatureOf(ClipData clip) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            CharSequence text = item.getText();
            sb.append(text == null ? "" : text).append('\u0000')
                    .append(item.getHtmlText() == null ? "" : item.getHtmlText()).append('\u0000')
                    .append(item.getUri() == null ? "" : item.getUri()).append('\u0001');
        }
        return sb.toString();
    }

    private void publishToHost(ClipData clip) {
        try {
            ClipboardMessage request = ClipboardMessage.newBuilder()
                    .setMessageType(ClipboardMessage.MessageType.RequestSharedMemory)
                    .setActivityId(newActivityId())
                    .build();
            byte[] replyPayload = mChannel.send(request.toByteArray(), REPLY_TIMEOUT_MS);
            if (replyPayload == null) {
                Slog.e(TAG, "RequestSharedMemory: empty reply");
                return;
            }
            ClipboardMessage reply = ClipboardMessage.parseFrom(replyPayload);
            if (!reply.hasRequestSharedMemory()) {
                Slog.e(TAG, "RequestSharedMemory: reply without shared memory");
                return;
            }
            SharedMemory sm = reply.getRequestSharedMemory().getClipboardSharedMemory();
            ClipboardData data;
            try (Section section = Section.open(sm)) {
                data = pack(clip, section.buffer());
            }
            if (data.getClipboardContentCount() == 0) {
                Slog.i(TAG, "nothing to send to host");
                return;
            }
            ClipboardMessage set = ClipboardMessage.newBuilder()
                    .setMessageType(ClipboardMessage.MessageType.SetClientClipboardContent)
                    .setActivityId(newActivityId())
                    .setSetClipboardContent(SetClipboardContent.newBuilder()
                            .setClipboardSharedMemory(sm)
                            .setClipboardData(data))
                    .build();
            mChannel.send(set.toByteArray(), REPLY_TIMEOUT_MS);
            Slog.i(TAG, "sent to host, items=" + data.getClipboardContentCount());
        } catch (ApiChannel.ApiException | IOException | RuntimeException | OutOfMemoryError e) {
            mLastSentSignature = "";  // not delivered — let the next notification try again
            Slog.e(TAG, "cannot publish clipboard to host", e);
        }
    }

    /** Lay out ClipData into the section; return the inventory (type, offset, length) for the host. */
    private ClipboardData pack(ClipData clip, MappedByteBuffer buf) {
        ClipboardData.Builder data = ClipboardData.newBuilder();
        int cursor = 0;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            // What to give the host for this item. Difference from the reference: for an item with markup Microsoft
            // takes ONLY plain text — the getText() check comes first, and getText() of an
            // html item returns the same content without markup, so the Html type never goes on the wire
            // at all. Checked 24.09.2026: bold text copied in Android arrives in
            // Windows as plain. We send both representations — Html first, then Text; the host
            // puts the formats into one DataPackage, and paste keeps the formatting where
            // the app supports it and stays plain text where it does not.
            List<byte[]> payloads = new ArrayList<>(2);
            List<ContentItemType> types = new ArrayList<>(2);
            if (!TextUtils.isEmpty(item.getHtmlText())) {
                payloads.add(htmlForHost(item.getHtmlText()));
                types.add(ContentItemType.Html);
            }
            if (!TextUtils.isEmpty(item.getText())) {
                payloads.add(item.getText().toString().getBytes(StandardCharsets.UTF_8));
                types.add(ContentItemType.Text);
            }
            if (payloads.isEmpty() && item.getUri() != null) {
                Uri uri = item.getUri();
                byte[] png = encodeImage(uri);
                payloads.add(png != null ? png : uri.toString().getBytes(StandardCharsets.UTF_8));
                types.add(png != null ? ContentItemType.Bitmap : ContentItemType.Uri);
            }
            for (int k = 0; k < payloads.size(); k++) {
                byte[] bytes = payloads.get(k);
                ContentItemType type = types.get(k);
                if (cursor + bytes.length > buf.capacity()) {
                    Slog.w(TAG, "item " + i + " (" + type + ", " + bytes.length
                            + " B) does not fit into section of " + buf.capacity() + " B, dropped");
                    continue;
                }
                // Difference from the reference: we write at the declared offset. Microsoft has
                // put(bytes, offset, size) here — the offset goes into the SOURCE array argument, and starting
                // from the second item the wrong data lands in the section (most often an IndexOutOfBounds is thrown).
                buf.position(cursor);
                buf.put(bytes, 0, bytes.length);
                data.addClipboardContent(ClipboardContentItem.newBuilder()
                        .setContentItemType(type)
                        .setOffset(cursor)
                        .setSize(bytes.length));
                cursor += bytes.length;
            }
        }
        buf.force();
        return data.build();
    }

    /**
     * Markup for the host: everything outside ASCII as numeric character references (&#1055;).
     *
     * The host accepts text as UTF-8, but parses the bytes of an Html item in the CURRENT Windows
     * code page: "Проверка" sent as UTF-8 arrives in the clipboard as "РџСЂРѕРІРµСЂРєР°"
     * (checked 24.09.2026 on a live slot, host locale cp1251). The guest cannot learn the host's code page,
     * and Microsoft apparently never tested this path at all: their guest never sends the Html type.
     * Character references settle the question — the bytes are the same in any single-byte encoding.
     */
    private static byte[] htmlForHost(String html) {
        StringBuilder sb = new StringBuilder(html.length());
        for (int i = 0; i < html.length(); ) {
            int cp = html.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x80) {
                sb.append((char) cp);
            } else {
                sb.append("&#").append(cp).append(';');
            }
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** PNG for an image URI; null if it is not an image or cannot be read. */
    private byte[] encodeImage(Uri uri) {
        ContentResolver resolver = mContext.getContentResolver();
        String mime = resolver.getType(uri);
        if (mime == null || !mime.startsWith("image/")) return null;
        try {
            Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "cannot decode image " + uri + ": " + e);
            return null;
        }
    }

    private static ActivityId newActivityId() {
        return ActivityId.newBuilder().setValue(UUID.randomUUID().toString()).build();
    }

    // ---------------------------------------------------------------- shared memory

    /** Mapped section /mnt/shared_mem/<sectionName>. */
    private static final class Section implements AutoCloseable {
        private final FileChannel mFileChannel;
        private final MappedByteBuffer mBuffer;

        private Section(FileChannel fileChannel, MappedByteBuffer buffer) {
            mFileChannel = fileChannel;
            mBuffer = buffer;
        }

        static Section open(SharedMemory sm) throws IOException {
            String name = sm.getSectionName();
            if (TextUtils.isEmpty(name) || name.indexOf('/') >= 0 || name.equals("..")) {
                throw new IOException("bad section name: \"" + name + "\"");
            }
            int size = sm.getSize();
            if (size <= 0) throw new IOException("bad section size: " + sm.getSize());
            Path path = Paths.get(SHARED_MEM_DIR, name);
            // Open read-write in both directions of the exchange, like the reference: the host creates the section,
            // and there is nothing to test other access modes against.
            FileChannel ch = FileChannel.open(path, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            try {
                return new Section(ch, ch.map(FileChannel.MapMode.READ_WRITE, 0, size));
            } catch (IOException | RuntimeException e) {
                ch.close();
                throw e;
            }
        }

        MappedByteBuffer buffer() {
            return mBuffer;
        }

        @Override
        public void close() throws IOException {
            mFileChannel.close();
        }
    }

    /**
     * Whether shared memory exists at all — for diagnostics on the first boot of a slot.
     *
     * We look at the mount list, NOT isDirectory(): stat of the directory itself is not allowed for a system
     * app (Microsoft's vendor policy for system_app has `(dir (search))`, but not
     * getattr), and the "directory exists" check produced a loud "/mnt/shared_mem is not mounted" with
     * a fully working channel — caught 24.09.2026 on the very first live run. Opening a file
     * INSIDE the directory is still possible: search is enough for that.
     */
    static boolean sharedMemoryAvailable() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/self/mounts"))) {
                // <source> <mount point> <type> ...
                String[] parts = line.split(" ");
                if (parts.length > 1 && SHARED_MEM_DIR.equals(parts[1])) return true;
            }
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "cannot read /proc/self/mounts: " + e);
        }
        return false;
    }
}
