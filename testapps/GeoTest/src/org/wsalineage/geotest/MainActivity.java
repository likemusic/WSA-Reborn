package org.wsalineage.geotest;

import android.app.Activity;
import android.location.Address;
import android.location.Geocoder;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * Test app for the WSA geocoder (GeocodeService from Microsoft's SystemApp; the Azure Maps key
 * is supplied by the host over the "Geocode" channel): forward or reverse query, result goes to logcat (tag GeoTest).
 *
 *   am start -S -n org.wsalineage.geotest/.MainActivity                          — "Minsk", forward
 *   ... -e q 'Any address'                                                        — forward
 *   ... -e lat 53.9 -e lon 27.56                                                  — reverse
 *   ... -e loc 20                                                                 — 20 s of GPS subscription
 */
public final class MainActivity extends Activity {
    private static final String TAG = "GeoTest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String q = getIntent().getStringExtra("q");
        final String lat = getIntent().getStringExtra("lat");
        final String lon = getIntent().getStringExtra("lon");
        final String loc = getIntent().getStringExtra("loc");
        if (loc != null) {
            listen(Integer.parseInt(loc));
            return;
        }
        new Thread(() -> run(q, lat, lon), "geo").start();
    }

    /**
     * Holds a GPS subscription for the given number of seconds: for the system this is active use of
     * location (startOp), which the "PermissionUsage" channel reports to the host, and Windows should
     * show the location icon. The permission is granted beforehand: pm grant … ACCESS_FINE_LOCATION.
     */
    private void listen(int seconds) {
        final LocationManager lm = getSystemService(LocationManager.class);
        final LocationListener listener = l -> Log.i(TAG, "location " + l.getLatitude() + "," + l.getLongitude());
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper());
            Log.i(TAG, "listening gps for " + seconds + " s");
        } catch (SecurityException e) {
            Log.w(TAG, "no location permission", e);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            lm.removeUpdates(listener);
            Log.i(TAG, "stopped gps");
        }, seconds * 1000L);
    }

    private void run(String q, String lat, String lon) {
        Log.i(TAG, "Geocoder.isPresent=" + Geocoder.isPresent());
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        long t0 = SystemClock.elapsedRealtime();
        try {
            List<Address> list;
            if (lat != null && lon != null) {
                Log.i(TAG, "reverse " + lat + "," + lon);
                list = geocoder.getFromLocation(Double.parseDouble(lat), Double.parseDouble(lon), 3);
            } else {
                if (q == null) q = "Минск";
                Log.i(TAG, "forward '" + q + "'");
                list = geocoder.getFromLocationName(q, 3);
            }
            long ms = SystemClock.elapsedRealtime() - t0;
            Log.i(TAG, "result: " + (list == null ? "null" : list.size() + " addresses") + ", " + ms + " ms");
            if (list != null) {
                for (Address a : list) {
                    Log.i(TAG, "  " + a.getLatitude() + "," + a.getLongitude() + " " + a.getAddressLine(0));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "failed after " + (SystemClock.elapsedRealtime() - t0) + " ms", e);
        }
    }
}
