package org.wsalineage.vpntest;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;

/**
 * VPN test app (item 6 of the #700 checklist). Brings up a VpnService with a route to a single
 * address (10.111.0.1) and answers ICMP echo in the tun itself — no external server needed: if ping to
 * this address gets replies, the traffic went through the system VPN stack and back. All other
 * traffic bypasses the VPN. logcat tag — VpnTest.
 *
 *   appops set org.wsalineage.vpntest ACTIVATE_VPN allow     (instead of the consent dialog)
 *   am start -S -n org.wsalineage.vpntest/.MainActivity
 *   ping -c 3 10.111.0.1
 *   am start -n org.wsalineage.vpntest/.MainActivity -e stop 1
 */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent svc = new Intent(this, EchoVpnService.class);
        if (getIntent().hasExtra("stop")) {
            svc.setAction(EchoVpnService.ACTION_STOP);
        } else if (VpnService.prepare(this) != null) {
            Log.e(EchoVpnService.TAG, "no VPN consent: appops set " + getPackageName() + " ACTIVATE_VPN allow");
            finish();
            return;
        }
        startService(svc);
        finish();
    }
}
