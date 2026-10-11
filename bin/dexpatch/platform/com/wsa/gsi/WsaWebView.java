package com.wsa.gsi;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Path A (ready GSI) counterpart of docs/patches.md `a15/0014`, called from
 * com.android.server.webkit.WebViewUpdateServiceImpl2.findPreferredWebViewPackage after
 * bin/dexpatch/WebViewFallbackPatch.java has redirected its getUserChosenWebViewProvider() call here.
 * Goes into services.jar as an extra classesN.dex. Everything is reflective: SystemInterface and
 * WebViewProviderInfo are not in the public SDK.
 *
 * The default provider comes from a config overlay (GApps for Android 13 declares com.google.android.webview)
 * and may be absent from the image; Android 15+ then leaves the system without WebView (Google sign-in crashes
 * with MissingWebViewPackageException), while Android 14 took any valid provider. Here: when the user has not
 * chosen a provider and the default one is not installed, the first installed provider from the list is offered
 * as the "user choice" — the platform code that follows still checks its validity and that it is enabled for
 * all users, and falls back to the default path otherwise.
 */
public final class WsaWebView {
    private WsaWebView() {}

    public static String chosenProvider(Object systemInterface, Object service) {
        try {
            Class<?> si = systemInterface.getClass();
            // Android 16+: getUserChosenWebViewProvider(); Android 15: getUserChosenWebViewProvider(Context)
            Method choice = null;
            for (Method m : si.getMethods()) {
                if (m.getName().equals("getUserChosenWebViewProvider")) choice = m;
            }
            if (choice == null) throw new NoSuchMethodException("getUserChosenWebViewProvider");
            choice.setAccessible(true);
            Object[] args = new Object[choice.getParameterCount()];
            if (args.length == 1) {
                Field ctx = service.getClass().getDeclaredField("mContext");
                ctx.setAccessible(true);
                args[0] = ctx.get(service);
            }
            String chosen = (String) choice.invoke(systemInterface, args);
            if (chosen != null && !chosen.isEmpty()) return chosen;
            Field def = service.getClass().getDeclaredField("mDefaultProvider");
            def.setAccessible(true);
            Object defaultProvider = def.get(service);
            if (defaultProvider == null || installed(systemInterface, defaultProvider)) return chosen;
            Object[] providers = (Object[]) find(si, "getWebViewPackages").invoke(systemInterface);
            for (Object p : providers) {
                if (p != defaultProvider && installed(systemInterface, p)) {
                    String name = (String) p.getClass().getField("packageName").get(p);
                    android.util.Log.w("WebViewUpdateServiceImpl2", "Default WebView package missing, using " + name);
                    return name;
                }
            }
            return chosen;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WSA a15/0014 helper", e);
        }
    }

    private static boolean installed(Object systemInterface, Object provider) throws ReflectiveOperationException {
        Method get = null;
        for (Method m : systemInterface.getClass().getMethods()) {
            if (m.getName().equals("getPackageInfoForProvider") && m.getParameterCount() == 1) get = m;
        }
        if (get == null) throw new NoSuchMethodException("getPackageInfoForProvider");
        get.setAccessible(true); // the implementation (SystemImpl) need not be a public class
        try {
            return get.invoke(systemInterface, provider) != null;
        } catch (InvocationTargetException e) {
            return false; // NameNotFoundException: not installed
        }
    }

    private static Method find(Class<?> c, String name) throws NoSuchMethodException {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
