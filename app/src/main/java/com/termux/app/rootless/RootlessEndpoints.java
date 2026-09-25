package com.termux.app.rootless;

/**
 * Where the VM assets are hosted.
 *
 * Ported from strykerapp's ota/StrykerEndpoints.java, repointed at OUR release. Public repo on
 * purpose: a private repo's assets would need a token baked into the app, which would kill a
 * credential-free first run.
 *
 * The manifest carries per-asset sha256 + size and is the source of truth; these constants are the
 * fallback for when it cannot be fetched (offline, or the manifest is temporarily unavailable).
 */
public final class RootlessEndpoints {

    private RootlessEndpoints() {}

    public static final String REPO = "https://github.com/VV-B0Y/Termux-Black";

    public static final String ROOTLESS_BASE = REPO + "/releases/download/rootless-main/";

    /** Manifest, served from the same release so there is only one host to depend on. */
    public static final String MANIFEST_URL = ROOTLESS_BASE + "stryker_manifest.json";

    public static final String FALLBACK_ROOTLESS_QEMU     = ROOTLESS_BASE + "qemu-system-aarch64";
    public static final String FALLBACK_ROOTLESS_KERNEL   = ROOTLESS_BASE + "Image";
    public static final String FALLBACK_ROOTLESS_INITRD   = ROOTLESS_BASE + "initrd.img";
    public static final String FALLBACK_ROOTLESS_LIBSLIRP = ROOTLESS_BASE + "libslirp.so";
    public static final String FALLBACK_ROOTLESS_ROOTFS   = ROOTLESS_BASE + "rootfs.imgz";

    public static final String PREFS = "rootless_ota";
}
