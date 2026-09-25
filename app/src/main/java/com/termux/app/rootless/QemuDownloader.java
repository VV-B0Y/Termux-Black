package com.termux.app.rootless;

import android.content.Context;

/**
 * Resolves the five VM assets: from the manifest when it is reachable, otherwise from the
 * hardcoded release URLs.
 *
 * Ported from strykerapp's ota/QemuDownloader.java.
 */
public final class QemuDownloader {

    private QemuDownloader() {}

    public static final class Bundle {
        public final RemoteManifest.Asset qemu;
        public final RemoteManifest.Asset kernel;
        public final RemoteManifest.Asset initrd;
        public final RemoteManifest.Asset libslirp;
        public final RemoteManifest.Asset rootfs;

        Bundle(RemoteManifest.Asset qemu, RemoteManifest.Asset kernel, RemoteManifest.Asset initrd,
               RemoteManifest.Asset libslirp, RemoteManifest.Asset rootfs) {
            this.qemu = qemu;
            this.kernel = kernel;
            this.initrd = initrd;
            this.libslirp = libslirp;
            this.rootfs = rootfs;
        }
    }

    public static Bundle resolve(Context context) {
        RemoteManifest manifest = ManifestService.fetch(context);
        if (manifest != null && manifest.rootless != null && manifest.rootless.isComplete()) {
            RemoteManifest.RootlessAssets r = manifest.rootless;
            return new Bundle(r.qemu, r.kernel, r.initrd, r.libslirp, r.rootfs);
        }
        return new Bundle(
                asset(RootlessEndpoints.FALLBACK_ROOTLESS_QEMU, null),
                asset(RootlessEndpoints.FALLBACK_ROOTLESS_KERNEL, null),
                asset(RootlessEndpoints.FALLBACK_ROOTLESS_INITRD, null),
                asset(RootlessEndpoints.FALLBACK_ROOTLESS_LIBSLIRP, null),
                // The rootfs entry carries the compression/format hints; without a manifest we
                // still know what we published.
                new RemoteManifest.Asset(RootlessEndpoints.FALLBACK_ROOTLESS_ROOTFS,
                        null, 0, "qcow2", "gzip"));
    }

    private static RemoteManifest.Asset asset(String url, String sha256) {
        return new RemoteManifest.Asset(url, sha256, 0, "", "");
    }
}
