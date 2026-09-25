package com.termux.app.rootless;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The remote asset manifest.
 *
 * Ported from strykerapp's ota/RemoteManifest.java, trimmed to just what we need: the rootless
 * asset block. Their news/notifications and the chroot64 "core" block are dropped (they belong to
 * the full app), and the BuildConfig check is removed since we have no such versioning.
 *
 * JSON shape:
 * <pre>
 * {"manifest_version":1,
 *  "rootless":{"qemu":{"url":…,"sha256":…,"size":…}, "kernel":{…}, "initrd":{…},
 *              "libslirp":{…}, "rootfs":{…,"format":"qcow2","compression":"gzip"}}}
 * </pre>
 */
public final class RemoteManifest {

    public static final class Asset {
        public final String url;
        public final String sha256;
        public final long size;
        /** Our addition: "qcow2" or "raw". Their launcher assumes raw; ours is qcow2. */
        public final String format;
        /** Our addition: "gzip" when the stored file must be decompressed after download. */
        public final String compression;

        public Asset(String url, String sha256, long size, String format, String compression) {
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.format = format;
            this.compression = compression;
        }

        /** Their usability rule, kept verbatim: HTTPS only. */
        public boolean isUsable() {
            return url != null && url.startsWith("https://");
        }
    }

    public static final class RootlessAssets {
        public final Asset qemu;
        public final Asset kernel;
        public final Asset initrd;
        public final Asset libslirp;
        public final Asset rootfs;

        RootlessAssets(Asset qemu, Asset kernel, Asset initrd, Asset libslirp, Asset rootfs) {
            this.qemu = qemu;
            this.kernel = kernel;
            this.initrd = initrd;
            this.libslirp = libslirp;
            this.rootfs = rootfs;
        }

        public boolean isComplete() {
            return qemu != null && qemu.isUsable()
                    && kernel != null && kernel.isUsable()
                    && initrd != null && initrd.isUsable()
                    && libslirp != null && libslirp.isUsable()
                    && rootfs != null && rootfs.isUsable();
        }
    }

    public int manifestVersion = 1;
    public RootlessAssets rootless;

    public static RemoteManifest fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        RemoteManifest manifest = new RemoteManifest();
        manifest.manifestVersion = root.optInt("manifest_version", 1);

        JSONObject rootless = root.optJSONObject("rootless");
        if (rootless != null) {
            manifest.rootless = new RootlessAssets(
                    asset(rootless.optJSONObject("qemu")),
                    asset(rootless.optJSONObject("kernel")),
                    asset(rootless.optJSONObject("initrd")),
                    asset(rootless.optJSONObject("libslirp")),
                    asset(rootless.optJSONObject("rootfs")));
        }
        return manifest;
    }

    private static Asset asset(JSONObject o) {
        if (o == null) return null;
        return new Asset(
                o.optString("url", ""),
                o.optString("sha256", ""),
                o.optLong("size", 0),
                o.optString("format", ""),
                o.optString("compression", ""));
    }
}
