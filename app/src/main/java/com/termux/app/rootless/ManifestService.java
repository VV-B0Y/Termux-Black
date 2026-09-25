package com.termux.app.rootless;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

/**
 * Fetches the asset manifest and caches the last good copy.
 *
 * Ported from strykerapp's ota/ManifestService.java: fetch over the network, cache the raw JSON in
 * SharedPreferences, and fall back to the cache when the network fails. That cache is what makes a
 * reinstall work offline - the installer then uses whatever checksums it last saw.
 */
public final class ManifestService {

    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final String KEY_CACHE = "manifest_cache";

    private ManifestService() {}

    public static RemoteManifest fetch(Context context) {
        SharedPreferences prefs = prefs(context);
        try {
            String json = Net.getString(RootlessEndpoints.MANIFEST_URL, MAX_MANIFEST_BYTES);
            RemoteManifest manifest = RemoteManifest.fromJson(json);
            prefs.edit().putString(KEY_CACHE, json).apply();
            return manifest;
        } catch (Exception e) {
            RootlessLog.w("manifest fetch failed, using cache: " + e.getMessage());
            return cached(context);
        }
    }

    public static RemoteManifest cached(Context context) {
        String json = prefs(context).getString(KEY_CACHE, null);
        if (json == null || json.isEmpty()) return null;
        try {
            return RemoteManifest.fromJson(json);
        } catch (JSONException e) {
            return null;
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(RootlessEndpoints.PREFS, Context.MODE_PRIVATE);
    }
}
