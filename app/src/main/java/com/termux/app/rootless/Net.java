package com.termux.app.rootless;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal HTTP helper, replacing strykerapp's utils/Net.java (which pulls in their whole app).
 * HTTPS only, bounded read, no caching - the manifest service handles caching.
 */
public final class Net {

    private Net() {}

    /**
     * GET {@code url} and return the body as a String.
     *
     * @param maxBytes hard cap on how much is read, so a hostile or broken endpoint cannot
     *                 exhaust memory. The caller is expected to fail gracefully on throw.
     */
    public static String getString(String url, int maxBytes) throws Exception {
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalArgumentException("Refusing non-HTTPS URL: " + url);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "termux-rootless");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " for " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    if (out.size() > maxBytes) {
                        throw new IllegalStateException("Response exceeds " + maxBytes + " bytes: " + url);
                    }
                }
                return out.toString("UTF-8");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
