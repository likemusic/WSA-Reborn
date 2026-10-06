package org.wsalineage.vpntest;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** A tun with a route to 10.111.0.1/32; answers an ICMP echo request to it with an echo reply. */
public final class EchoVpnService extends VpnService {
    static final String TAG = "VpnTest";
    static final String ACTION_STOP = "org.wsalineage.vpntest.STOP";

    private ParcelFileDescriptor mTun;
    private Thread mThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mTun != null) return START_NOT_STICKY;
        mTun = new Builder().setSession("VpnTest").setMtu(1500).setBlocking(true)
                .addAddress("10.111.0.2", 32).addRoute("10.111.0.1", 32).establish();
        if (mTun == null) {
            Log.e(TAG, "establish() returned null (no consent?)");
            stopSelf();
            return START_NOT_STICKY;
        }
        Log.i(TAG, "tun established, fd " + mTun.getFd());
        final ParcelFileDescriptor tun = mTun;
        mThread = new Thread(() -> loop(tun), "vpn-echo");
        mThread.start();
        return START_NOT_STICKY;
    }

    private static void loop(ParcelFileDescriptor tun) {
        byte[] p = new byte[32767];
        int echoed = 0;
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            while (true) {
                int n = in.read(p);
                if (n <= 0) {
                    Log.i(TAG, "tun read returned " + n);
                    break;
                }
                int ihl = (p[0] & 0x0f) * 4;
                if ((p[0] >> 4) != 4 || p[9] != 1 || n < ihl + 8 || p[ihl] != 8) {
                    Log.i(TAG, "packet " + n + " bytes, not ICMPv4 echo, dropped");
                    continue;
                }
                for (int i = 0; i < 4; i++) { // src <-> dst; the header checksum does not change
                    byte t = p[12 + i]; p[12 + i] = p[16 + i]; p[16 + i] = t;
                }
                p[ihl] = 0; // echo reply
                p[ihl + 2] = 0; p[ihl + 3] = 0;
                int sum = checksum(p, ihl, n - ihl);
                p[ihl + 2] = (byte) (sum >> 8); p[ihl + 3] = (byte) sum;
                out.write(p, 0, n);
                echoed++;
                Log.i(TAG, "echo reply #" + echoed + ", " + n + " bytes");
            }
        } catch (IOException e) {
            Log.i(TAG, "tun closed: " + e.getMessage());
        }
    }

    private static int checksum(byte[] b, int off, int len) {
        long s = 0;
        for (int i = 0; i < len - 1; i += 2) s += ((b[off + i] & 0xff) << 8) | (b[off + i + 1] & 0xff);
        if ((len & 1) != 0) s += (b[off + len - 1] & 0xff) << 8;
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    private void stop() {
        if (mTun != null) {
            try { mTun.close(); } catch (IOException ignored) { }
            mTun = null;
            Log.i(TAG, "tun closed by request");
        }
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "VPN revoked");
        stop();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }
}
