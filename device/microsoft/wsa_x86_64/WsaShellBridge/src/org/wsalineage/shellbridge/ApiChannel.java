/*
 * Transport of one named RCM channel guest ↔ WsaClient.exe.
 *
 * Implemented per the specification in docs/wsa-shell-protocol.md (reconstructed by static analysis of
 * SystemUIApp.apk, WSA 2407.40000.4.0); the code is our own. Frame on the wire:
 *
 *   uint32 LE length | ApiMessageHeader{callId, isReply, returnCode, payload}
 *
 * where payload is the serialized channel message (for "Shell" — Shell.ShellMessage).
 * Each side keeps its own callId counter. A request with isReply=false must get a reply with the same
 * callId and isReply=true; the reply to a host request is the very same message (an echo).
 */
package org.wsalineage.shellbridge;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Slog;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.Common.ApiMessageHeader;
import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;

import windows.services.rcm.IRemoteConnectionManager;

import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class ApiChannel {
    private static final String TAG = "WsaShellBridge.ApiChannel";
    private static final String RCM_SERVICE =
            IRemoteConnectionManager.DESCRIPTOR + "/default";
    // A sane limit: a header with an app window is hundreds of bytes, not megabytes.
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    /** Handler for incoming host requests. Returns the reply payload (usually the request itself). */
    public interface Handler {
        byte[] onRequest(byte[] payload) throws ApiException;
        void onClosed();
    }

    public static final class ApiException extends Exception {
        public final ResultCode code;
        public ApiException(String msg, ResultCode code) { super(msg); this.code = code; }
        public ApiException(String msg) { this(msg, ResultCode.Fail); }
    }

    private final String mName;
    private final Handler mHandler;
    private final Object mSendLock = new Object();
    private final AtomicLong mNextCallId = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<byte[]>> mPending =
            new ConcurrentHashMap<>();
    private volatile ParcelFileDescriptor mFd;

    public ApiChannel(String name, Handler handler) {
        mName = name;
        mHandler = handler;
    }

    public boolean isOpen() { return mFd != null; }

    /** Open the channel via rcmsvc and start the receive thread. Blocks until the service shows up. */
    public void open() throws ApiException {
        IBinder binder = ServiceManager.waitForDeclaredService(RCM_SERVICE);
        IRemoteConnectionManager rcm = IRemoteConnectionManager.Stub.asInterface(binder);
        if (rcm == null) {
            throw new ApiException("RCM service not found: " + RCM_SERVICE, ResultCode.NotReady);
        }
        ParcelFileDescriptor fd;
        try {
            fd = rcm.createChannel(mName);
        } catch (RemoteException e) {
            throw new ApiException("createChannel(" + mName + ") failed: " + e, ResultCode.Fail);
        }
        if (fd == null) {
            throw new ApiException("createChannel(" + mName + ") returned null", ResultCode.NotReady);
        }
        mFd = fd;
        Thread t = new Thread(this::receiveLoop, "ApiChannel(" + mName + ")");
        t.setDaemon(true);
        t.start();
        Slog.i(TAG, "channel " + mName + " opened, fd=" + fd.getFd());
    }

    public void close() {
        ParcelFileDescriptor fd = mFd;
        mFd = null;
        if (fd != null) {
            try { fd.close(); } catch (IOException ignored) { }
            Slog.i(TAG, "channel " + mName + " closed");
            ApiException closed = new ApiException("channel closed", ResultCode.Abort);
            for (CompletableFuture<byte[]> f : mPending.values()) f.completeExceptionally(closed);
            mPending.clear();
            mHandler.onClosed();
        }
    }

    /** Request to the host without waiting for the reply (the reply still arrives and is cleared from tracking). */
    public void sendAsync(byte[] payload) throws ApiException {
        request(payload);
    }

    /** Request to the host, waiting for the reply. */
    public byte[] send(byte[] payload, long timeoutMs) throws ApiException {
        CompletableFuture<byte[]> f = request(payload);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiException("reply timed out", ResultCode.Timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("interrupted", ResultCode.Abort);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof ApiException) throw (ApiException) c;
            throw new ApiException(String.valueOf(c));
        }
    }

    private CompletableFuture<byte[]> request(byte[] payload) throws ApiException {
        long callId = mNextCallId.getAndIncrement();
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        mPending.put(callId, f);
        try {
            writeFrame(callId, false, null, payload);
        } catch (ApiException e) {
            mPending.remove(callId);
            throw e;
        }
        return f;
    }

    private void writeFrame(long callId, boolean isReply, ResultCode code, byte[] payload)
            throws ApiException {
        ApiMessageHeader.Builder h = ApiMessageHeader.newBuilder()
                .setCallId(callId)
                .setIsReply(isReply);
        if (code != null && code != ResultCode.Success) h.setReturnCode(code);
        if (payload != null) h.setPayload(ByteString.copyFrom(payload));
        byte[] body = h.build().toByteArray();
        byte[] frame = ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(body.length).put(body).array();
        synchronized (mSendLock) {
            ParcelFileDescriptor pfd = mFd;
            if (pfd == null) throw new ApiException("channel not open", ResultCode.NotReady);
            FileDescriptor fd = pfd.getFileDescriptor();
            int off = 0;
            while (off < frame.length) {
                try {
                    off += Os.write(fd, frame, off, frame.length - off);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) continue;
                    close();
                    throw new ApiException("write failed: " + e.getMessage(), ResultCode.Fail);
                } catch (InterruptedIOException e) {
                    // a partial write was interrupted by a signal — retry from the same offset
                    off += e.bytesTransferred;
                }
            }
        }
    }

    private void readFully(FileDescriptor fd, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n;
            try {
                n = Os.read(fd, buf, off, buf.length - off);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) continue;
                if (e.errno == OsConstants.EBADF) throw new EOFException();
                throw e.rethrowAsIOException();
            }
            if (n <= 0) throw new EOFException();
            off += n;
        }
    }

    private void receiveLoop() {
        try {
            while (true) {
                ParcelFileDescriptor pfd = mFd;
                if (pfd == null) break;
                FileDescriptor fd = pfd.getFileDescriptor();
                byte[] lenBuf = new byte[4];
                readFully(fd, lenBuf);
                int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (len <= 0 || len > MAX_FRAME) {
                    Slog.e(TAG, mName + ": bad frame length " + len);
                    break;
                }
                byte[] body = new byte[len];
                readFully(fd, body);
                ApiMessageHeader h;
                try {
                    h = ApiMessageHeader.parseFrom(body);
                } catch (InvalidProtocolBufferException e) {
                    Slog.e(TAG, mName + ": bad header: " + e);
                    break;
                }
                dispatch(h);
            }
        } catch (EOFException e) {
            Slog.i(TAG, mName + ": EOF");
        } catch (IOException e) {
            Slog.e(TAG, mName + ": read failed", e);
        } finally {
            close();
        }
    }

    private void dispatch(ApiMessageHeader h) {
        long callId = h.getCallId();
        ResultCode code = h.hasReturnCode() ? h.getReturnCode() : ResultCode.Success;
        byte[] payload = h.hasPayload() ? h.getPayload().toByteArray() : null;
        if (h.getIsReply()) {
            CompletableFuture<byte[]> f = mPending.remove(callId);
            if (f == null) {
                Slog.w(TAG, mName + ": unexpected reply callId=" + callId);
            } else if (code != ResultCode.Success) {
                f.completeExceptionally(new ApiException("host returned " + code, code));
            } else {
                f.complete(payload);
            }
            return;
        }
        // Host request: handle it and reply with the same callId.
        try {
            if (code != ResultCode.Success) {
                throw new ApiException("request carries error " + code, code);
            }
            byte[] reply = mHandler.onRequest(payload);
            writeFrame(callId, true, null, reply);
        } catch (ApiException e) {
            Slog.w(TAG, mName + ": request callId=" + callId + " failed: " + e.getMessage());
            try {
                writeFrame(callId, true, e.code, null);
            } catch (ApiException e2) {
                Slog.e(TAG, mName + ": cannot send error reply: " + e2.getMessage());
            }
        }
    }
}
