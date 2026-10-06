/*
 * Links from Android open in Windows: a web page in the default Windows browser, mailto in
 * mail, tel/sip in the dialer, geo in Maps. Sent to the host as a LaunchUri message on the
 * "Intents" channel (IntentsBridge.launchUri). There is no window (Theme.NoDisplay): the activity turns the intent
 * into a URI, hands it to the bridge and finishes. In Microsoft's image the same is done by BrowserIntentHandler,
 * EmailIntentHandler, PhoneIntentHandler, MapsIntentHandler and SearchIntentHandler from UserApp.apk;
 * this code is our own.
 *
 * Intentional differences from the reference:
 *   * only schemes from ALLOWED_SCHEMES go to the host. Microsoft also has LaunchUriIntentHandler, exported without
 *     a permission (action com.microsoft.windows.LAUNCH_URI), which
 *     passes ANY URI to the host, i.e. any Android app can open file:,
 *     ms-settings: and the like in Windows. It is not included here;
 *   * no BrowserWebView, the built-in browser the reference sends OAuth links to (with
 *     redirect_uri etc.) so that a web sign-in returns to the Android app. The bridge runs as
 *     uid system, and the platform itself forbids WebView in privileged processes; such links
 *     also open in Windows, and a sign-in that returns to the app via its own scheme will not complete;
 *   * email attachments (ClipData in SENDTO) are not sent; the reference pushes them over the "FileSystem" channel
 *     separately, into Downloads.
 */
package org.wsalineage.shellbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.MailTo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Slog;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenInWindowsActivity extends Activity {
    private static final String TAG = "WsaShellBridge.Intents";

    private static final Set<String> ALLOWED_SCHEMES =
            Set.of("http", "https", "mailto", "tel", "sip", "voicemail", "bingmaps");

    /** Reference flag: the link came as an App Link, so the host must not look up a Windows app for it. */
    private static final String EXTRA_APP_LINK = "AppLink";

    private static final Pattern GEO_POINT_WITH_LABEL =
            Pattern.compile("(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)\\((.+)\\)");
    private static final Pattern GEO_POINT = Pattern.compile("(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handle(getIntent());
        } finally {
            // Theme.NoDisplay requires finishing before onResume.
            finish();
        }
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        IntentsBridge bridge = IntentsBridge.get();
        if (bridge == null) {
            Slog.e(TAG, "Intents bridge is not running");
            return;
        }
        String uri;
        try {
            uri = toWindowsUri(intent);
        } catch (RuntimeException e) {
            Slog.w(TAG, "bad intent " + intent + ": " + e);
            return;
        }
        if (uri == null) {
            Slog.w(TAG, "nothing to open in Windows: " + intent);
            return;
        }
        String scheme = Uri.parse(uri).getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            Slog.w(TAG, "scheme not allowed for Windows: " + scheme);
            return;
        }
        boolean appLink = intent.getBooleanExtra(EXTRA_APP_LINK, false);
        if (bridge.launchUri(uri, appLink)) setResult(RESULT_OK);
    }

    /** Android intent -> URI that Windows understands; null if there is nothing to open in it. */
    private static String toWindowsUri(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_WEB_SEARCH.equals(action) || "android.search.action.GLOBAL_SEARCH".equals(action)) {
            String query = intent.getStringExtra("query");
            return query != null ? "https://www.bing.com/search?q=" + Uri.encode(query) : null;
        }
        Uri data = intent.getData();
        if (data == null || data.getScheme() == null) return null;
        switch (data.getScheme().toLowerCase(Locale.ROOT)) {
            case "mailto":
                return mailto(intent);
            case "geo":
                return bingMaps(data);
            default:
                // http(s), tel, sip, voicemail: as is.
                return data.toString();
        }
    }

    /**
     * mailto: with recipients, subject and body. Apps put them both into the URI itself and into extras
     * (EXTRA_EMAIL/CC/BCC/SUBJECT/TEXT); extras take precedence, same as in the reference.
     */
    private static String mailto(Intent intent) {
        String dataString = intent.getDataString();
        if (intent.getExtras() == null) return dataString;
        MailTo parsed = dataString != null && MailTo.isMailTo(dataString) ? MailTo.parse(dataString) : null;
        Uri.Builder q = new Uri.Builder();
        String to = joined(intent.getStringArrayExtra(Intent.EXTRA_EMAIL));
        if (to == null && parsed != null) to = parsed.getTo();
        String cc = joined(intent.getStringArrayExtra(Intent.EXTRA_CC));
        if (cc == null && parsed != null) cc = parsed.getCc();
        if (cc != null) q.appendQueryParameter("cc", cc);
        String bcc = joined(intent.getStringArrayExtra(Intent.EXTRA_BCC));
        if (bcc == null && parsed != null) bcc = parsed.getHeaders().get("bcc");
        if (bcc != null) q.appendQueryParameter("bcc", bcc);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subject == null && parsed != null) subject = parsed.getSubject();
        if (subject != null) q.appendQueryParameter("subject", subject);
        CharSequence body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String bodyText = body != null ? body.toString() : parsed != null ? parsed.getBody() : null;
        if (bodyText != null) q.appendQueryParameter("body", bodyText);
        return new Uri.Builder().scheme("mailto").opaquePart(to != null ? to : "").build().toString()
                + q.build().toString();
    }

    private static String joined(String[] values) {
        return values != null && values.length > 0 ? String.join(",", values) : null;
    }

    /**
     * geo:lat,long?z=…  /  geo:0,0?q=lat,long(label)  /  geo:0,0?q=address → bingmaps:?… —
     * Windows has no geo scheme of its own. Conversion rules follow the reference.
     */
    private static String bingMaps(Uri geo) {
        Uri rel = Uri.parse(geo.getSchemeSpecificPart());
        Uri.Builder b = new Uri.Builder();
        String query = rel.getQueryParameter("q");
        if (query != null) {
            Matcher poi = GEO_POINT_WITH_LABEL.matcher(query);
            if (poi.find()) {
                b.appendQueryParameter("collection",
                        "point." + poi.group(1) + "_" + poi.group(2) + "_" + poi.group(3));
            } else {
                b.appendQueryParameter("q", query);
            }
        } else if (rel.getPath() != null) {
            Matcher point = GEO_POINT.matcher(rel.getPath());
            if (point.find()) b.appendQueryParameter("cp", point.group(1) + "~" + point.group(2));
        }
        String zoom = rel.getQueryParameter("z");
        if (zoom != null) b.appendQueryParameter("lvl", zoom);
        return "bingmaps:" + b.build().toString();
    }
}
