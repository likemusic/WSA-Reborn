/*
 * Android geocoder provider (android.location.Geocoder) on top of OpenStreetMap — the Nominatim service.
 *
 * Why our own. Microsoft's geocoder is GeocodeService from SystemApp: it gets an Azure Maps key from the host over the
 * "Geocode" channel and talks to atlas.microsoft.com. The key baked into host 2407.40000.4.0
 * is rejected by the server (401 InvalidKey, docs/architecture.md, "Known limitations"), i.e. the geocoder is dead even
 * on stock. This provider replaces it without modifying Microsoft programs: the system (ServiceWatcher,
 * config_enableGeocoderOverlay=true by default) picks among system services with the action
 * GeocodeProviderBase.ACTION_GEOCODE_PROVIDER the one with the higher serviceVersion metadata, and
 * Microsoft's service has none at all (Integer.MIN_VALUE).
 *
 * The GeocodeProviderBase interface is the same in Android 14 r75 — 17 (in 17 the OutcomeReceiver error type is
 * Throwable instead of Exception, after type erasure it is the same method), so one apk fits all
 * slots.
 *
 * Server. The default is the public https://nominatim.openstreetmap.org; a custom one is set with
 *   settings put global wsa_geocoder_url https://my.server
 * (same API: /search and /reverse, format=jsonv2). An empty string or "off" disables the geocoder —
 * apps get an error, as before. Rules of the public server
 * (https://operations.osmfoundation.org/policies/nominatim/): at most one request per second,
 * a recognisable User-Agent, caching of repeats — all observed here. Data attribution:
 * © OpenStreetMap contributors, ODbL.
 *
 * Privacy: coordinates and address strings go to the chosen server (at Microsoft they went to
 * Azure Maps). The requests themselves are not logged — only lengths and timing.
 */
package org.wsalineage.shellbridge;

import android.app.Service;
import android.content.Intent;
import android.location.Address;
import android.location.provider.ForwardGeocodeRequest;
import android.location.provider.GeocodeProviderBase;
import android.location.provider.ReverseGeocodeRequest;
import android.net.Uri;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OsmGeocodeService extends Service {
    private static final String TAG = "WsaShellBridge.Geocoder";
    static final String SETTING_URL = "wsa_geocoder_url";
    private static final String DEFAULT_URL = "https://nominatim.openstreetmap.org";
    private static final String USER_AGENT =
            "WSA-Lineage-Geocoder/1.0 (Android in Windows Subsystem for Android; Android Geocoder provider)";
    /** The public Nominatim allows at most one request per second. */
    private static final long MIN_INTERVAL_MS = 1100;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESULTS = 10;

    /** One thread: requests go strictly in turn, which makes it easier to keep the interval. */
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "geocoder"));
    private long mLastRequest;
    private Provider mProvider;

    @Override
    public void onCreate() {
        mProvider = new Provider();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mProvider.getBinder();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
    }

    private final class Provider extends GeocodeProviderBase {
        Provider() {
            super(OsmGeocodeService.this, TAG);
        }

        @Override
        public void onForwardGeocode(ForwardGeocodeRequest request, OutcomeReceiver<List<Address>, Exception> receiver) {
            mExecutor.execute(() -> {
                try {
                    receiver.onResult(forward(request));
                } catch (Exception e) {
                    Slog.w(TAG, "forward failed: " + e);
                    receiver.onError(e);
                }
            });
        }

        @Override
        public void onReverseGeocode(ReverseGeocodeRequest request, OutcomeReceiver<List<Address>, Exception> receiver) {
            mExecutor.execute(() -> {
                try {
                    receiver.onResult(reverse(request));
                } catch (Exception e) {
                    Slog.w(TAG, "reverse failed: " + e);
                    receiver.onError(e);
                }
            });
        }
    }

    private String baseUrl() throws IOException {
        String url = Settings.Global.getString(getContentResolver(), SETTING_URL);
        if (url == null) url = DEFAULT_URL;
        url = url.trim();
        if (url.isEmpty() || url.equalsIgnoreCase("off")) throw new IOException("geocoder is turned off (" + SETTING_URL + ")");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private List<Address> forward(ForwardGeocodeRequest r) throws IOException, JSONException {
        Uri.Builder b = Uri.parse(baseUrl() + "/search").buildUpon()
                .appendQueryParameter("format", "jsonv2")
                .appendQueryParameter("addressdetails", "1")
                .appendQueryParameter("q", r.getLocationName())
                .appendQueryParameter("limit", String.valueOf(limit(r.getMaxResults())));
        // The bounding box is set if at least one bound is non-zero (Geocoder passes zeros when there is none).
        if (r.getLowerLeftLatitude() != 0 || r.getLowerLeftLongitude() != 0
                || r.getUpperRightLatitude() != 0 || r.getUpperRightLongitude() != 0) {
            b.appendQueryParameter("viewbox", r.getLowerLeftLongitude() + "," + r.getUpperRightLatitude() + ","
                    + r.getUpperRightLongitude() + "," + r.getLowerLeftLatitude());
            b.appendQueryParameter("bounded", "1");
        }
        Locale locale = r.getLocale();
        String body = fetch(b, locale);
        JSONArray arr = new JSONArray(body);
        List<Address> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(toAddress(arr.getJSONObject(i), locale));
        Slog.d(TAG, "forward: " + r.getLocationName().length() + " chars -> " + out.size() + " addresses");
        return out;
    }

    private List<Address> reverse(ReverseGeocodeRequest r) throws IOException, JSONException {
        Uri.Builder b = Uri.parse(baseUrl() + "/reverse").buildUpon()
                .appendQueryParameter("format", "jsonv2")
                .appendQueryParameter("addressdetails", "1")
                .appendQueryParameter("lat", String.valueOf(r.getLatitude()))
                .appendQueryParameter("lon", String.valueOf(r.getLongitude()));
        Locale locale = r.getLocale();
        String body = fetch(b, locale);
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            // A point in the ocean etc.: for Geocoder this is an empty list, not an error.
            Slog.d(TAG, "reverse: no address (" + o.optString("error") + ")");
            return Collections.emptyList();
        }
        Slog.d(TAG, "reverse: 1 address");
        return Collections.singletonList(toAddress(o, locale));
    }

    private static int limit(int max) {
        return Math.max(1, Math.min(max, MAX_RESULTS));
    }

    /** GET with a cache and an interval between requests; returns the response body. */
    private String fetch(Uri.Builder b, Locale locale) throws IOException {
        String lang = locale == null ? "" : locale.toLanguageTag();
        if (!lang.isEmpty()) b.appendQueryParameter("accept-language", lang);
        String url = b.build().toString();
        String cached = sBodies.get(url);
        if (cached != null) return cached;

        long wait = mLastRequest + MIN_INTERVAL_MS - SystemClock.elapsedRealtime();
        if (wait > 0) SystemClock.sleep(wait);
        mLastRequest = SystemClock.elapsedRealtime();

        long t0 = SystemClock.elapsedRealtime();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (!lang.isEmpty()) c.setRequestProperty("Accept-Language", lang);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("geocoder server answered HTTP " + code);
            }
            String body = read(c.getInputStream());
            Slog.d(TAG, "HTTP 200, " + body.length() + " bytes, " + (SystemClock.elapsedRealtime() - t0) + " ms");
            sBodies.put(url, body);
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** Cache of server responses by full request URL — repeats do not go to the network (geocoder thread only). */
    private static final LruCache<String, String> sBodies = new LruCache<>(200);

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Nominatim response (jsonv2 with addressdetails) -> android.location.Address. */
    static Address toAddress(JSONObject o, Locale locale) throws JSONException {
        Address a = new Address(locale == null ? Locale.getDefault() : locale);
        a.setLatitude(Double.parseDouble(o.getString("lat")));
        a.setLongitude(Double.parseDouble(o.getString("lon")));
        String display = o.optString("display_name", "");
        if (!display.isEmpty()) a.setAddressLine(0, display);
        JSONObject ad = o.optJSONObject("address");
        if (ad != null) {
            String house = str(ad, "house_number");
            String road = first(ad, "road", "pedestrian", "footway", "street", "residential", "path");
            a.setSubThoroughfare(house);
            a.setThoroughfare(road);
            a.setPremises(first(ad, "building", "amenity", "shop", "office", "tourism"));
            a.setSubLocality(first(ad, "suburb", "city_district", "district", "neighbourhood", "quarter"));
            a.setLocality(first(ad, "city", "town", "village", "hamlet", "municipality"));
            a.setSubAdminArea(first(ad, "county", "state_district"));
            a.setAdminArea(first(ad, "state", "region", "province"));
            a.setPostalCode(str(ad, "postcode"));
            a.setCountryName(str(ad, "country"));
            String cc = str(ad, "country_code");
            if (cc != null) a.setCountryCode(cc.toUpperCase(Locale.ROOT));
        }
        String name = o.optString("name", "");
        if (!name.isEmpty()) {
            a.setFeatureName(name);
        } else if (a.getSubThoroughfare() != null) {
            a.setFeatureName(a.getSubThoroughfare());
        } else if (a.getThoroughfare() != null) {
            a.setFeatureName(a.getThoroughfare());
        } else if (a.getLocality() != null) {
            a.setFeatureName(a.getLocality());
        }
        return a;
    }

    private static String str(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = str(o, k);
            if (v != null) return v;
        }
        return null;
    }
}
