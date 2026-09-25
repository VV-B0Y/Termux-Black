package com.termux.app.rootless;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Installs the rootless VM: fetch the five assets, verify each by sha256, decompress the rootfs,
 * place everything under {@link RootlessPaths#base}.
 *
 * Ported from strykerapp's engine/QemuInstaller.java. Two deliberate differences:
 *
 *  1. Our rootfs ships as a GZIP-compressed QCOW2 rather than a raw image, so the
 *     {@code RandomAccessFile.setLength()} disk-growing step is NOT ported - a qcow2 grows on its
 *     own as the guest writes. Their grow logic exists only because a raw image has a fixed size.
 *  2. Because of (1) the QEMU launch must pass {@code format=qcow2}. See {@link RootlessEngine}.
 *
 * Their bundled-asset path is kept: if assets/rootless/ ever contains the files, install works with
 * no network at all. Today it does not, so install() falls through to the network path.
 */
public final class QemuInstaller {

    private static final String ASSET_DIR = "rootless";

    public interface Progress {
        void onStage(Stage stage);
        void onBytes(String label, long done);
        void onLog(int level, String message);
    }

    public enum Stage {
        PREPARING("Preparing"),
        EXTRACTING_QEMU("Installing QEMU"),
        EXTRACTING_KERNEL("Installing kernel"),
        EXTRACTING_LIBS("Installing libraries"),
        DECOMPRESSING_ROOTFS("Decompressing rootfs"),
        FINALIZING("Finalizing"),
        DONE("Done");

        public final String title;
        Stage(String title) { this.title = title; }
    }

    private QemuInstaller() {}

    /** True when every asset is present and the QEMU binary is executable. */
    public static boolean isInstalled(Context context) {
        File base = RootlessPaths.base(context);
        return new File(base, "qemu-system-aarch64").isFile()
                && RootlessPaths.qemuBin(context).canExecute()
                && RootlessPaths.kernel(context).isFile()
                && RootlessPaths.initrd(context).isFile()
                && RootlessPaths.libslirp(context).isFile()
                && RootlessPaths.rootfs(context).isFile();
    }

    private static boolean assetsPresent(Context c) {
        try {
            String[] files = c.getAssets().list(ASSET_DIR);
            if (files == null || files.length == 0) return false;
            boolean q = false, k = false, l = false, ird = false;
            for (String f : files) {
                if (f.equals("qemu-system-aarch64")) q = true;
                else if (f.equals("Image")) k = true;
                else if (f.equals("libslirp.so")) l = true;
                else if (f.equals("initrd.img")) ird = true;
            }
            return q && k && l && ird;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean install(Context context, Progress p) {
        if (!assetsPresent(context)) {
            log(p, 1, "Fetching the VM engine from the release");
            return installFromNetwork(context, p);
        }
        return installFromAssets(context, p);
    }

    private static boolean installFromAssets(Context context, Progress p) {
        AssetManager am = context.getAssets();
        File base = RootlessPaths.base(context);
        try {
            stage(p, Stage.PREPARING);
            if (!base.exists() && !base.mkdirs()) {
                log(p, 3, "Cannot create " + base.getAbsolutePath());
                return false;
            }
            stage(p, Stage.EXTRACTING_QEMU);
            copyAsset(am, ASSET_DIR + "/qemu-system-aarch64", RootlessPaths.qemuBin(context), p, "QEMU");
            RootlessPaths.qemuBin(context).setExecutable(true, false);

            stage(p, Stage.EXTRACTING_KERNEL);
            copyAsset(am, ASSET_DIR + "/Image", RootlessPaths.kernel(context), p, "kernel");
            copyAsset(am, ASSET_DIR + "/initrd.img", RootlessPaths.initrd(context), p, "initrd");

            stage(p, Stage.EXTRACTING_LIBS);
            copyAsset(am, ASSET_DIR + "/libslirp.so", RootlessPaths.libslirp(context), p, "libslirp.so");

            return finish(context, p);
        } catch (Exception e) {
            RootlessLog.w("install from assets failed", e);
            log(p, 3, "Install error: " + e.getMessage());
            return false;
        }
    }

    private static boolean installFromNetwork(Context context, Progress p) {
        File base = RootlessPaths.base(context);
        try {
            stage(p, Stage.PREPARING);
            if (!base.exists() && !base.mkdirs()) {
                log(p, 3, "Cannot create " + base.getAbsolutePath());
                return false;
            }
            QemuDownloader.Bundle b = QemuDownloader.resolve(context);

            stage(p, Stage.EXTRACTING_QEMU);
            if (!fetch(b.qemu, RootlessPaths.qemuBin(context), "QEMU", p)) return false;
            RootlessPaths.qemuBin(context).setExecutable(true, false);

            stage(p, Stage.EXTRACTING_KERNEL);
            if (!fetch(b.kernel, RootlessPaths.kernel(context), "kernel", p)) return false;
            if (!fetch(b.initrd, RootlessPaths.initrd(context), "initrd", p)) return false;

            stage(p, Stage.EXTRACTING_LIBS);
            if (!fetch(b.libslirp, RootlessPaths.libslirp(context), "libslirp.so", p)) return false;

            stage(p, Stage.DECOMPRESSING_ROOTFS);
            File rootfs = RootlessPaths.rootfs(context);
            boolean compressed = b.rootfs != null
                    && ("gzip".equalsIgnoreCase(b.rootfs.compression)
                        || (b.rootfs.url != null
                            && (b.rootfs.url.endsWith(".imgz") || b.rootfs.url.endsWith(".gz"))));
            if (!compressed) {
                if (!fetch(b.rootfs, rootfs, "rootfs", p)) return false;
            } else {
                File archive = new File(base, "rootfs.download");
                if (!fetch(b.rootfs, archive, "rootfs", p)) return false;
                log(p, 1, "Decompressing rootfs (this can take a minute)");
                if (!gunzipFile(archive, rootfs, p)) {
                    //noinspection ResultOfMethodCallIgnored
                    archive.delete();
                    return false;
                }
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }

            return finish(context, p);
        } catch (Exception e) {
            RootlessLog.w("network install failed", e);
            log(p, 3, "Install error: " + e.getMessage());
            return false;
        }
    }

    private static boolean finish(Context context, Progress p) {
        stage(p, Stage.FINALIZING);
        // NOTE: no disk-growing step here, on purpose. Our rootfs is a qcow2, which grows itself;
        // strykerapp extends a raw image with RandomAccessFile.setLength() because raw cannot.
        boolean ok = isInstalled(context);
        if (ok) {
            stage(p, Stage.DONE);
            log(p, 2, "VM installed");
        } else {
            log(p, 3, "Post-install verification failed");
        }
        return ok;
    }

    private static boolean fetch(RemoteManifest.Asset asset, File dest, String label, Progress p) {
        if (asset == null || !asset.isUsable()) {
            log(p, 3, label + ": no download URL in the manifest");
            return false;
        }
        // Already present and matching? Skip the transfer entirely.
        if (dest.isFile() && asset.sha256 != null && !asset.sha256.isEmpty()) {
            String have = VerifiedDownloader.sha256Of(dest);
            if (asset.sha256.equalsIgnoreCase(have)) {
                log(p, 2, label + " already present (" + mb(dest.length()) + ")");
                return true;
            }
        }
        log(p, 1, "GET " + asset.url);
        VerifiedDownloader.Result r = VerifiedDownloader.download(
                asset.url, dest, asset.sha256, asset.size,
                (done, total) -> { if (p != null) p.onBytes(label, done); });
        if (!r.ok) {
            log(p, 3, label + ": " + r.error);
            return false;
        }
        log(p, 2, label + " ready (" + mb(dest.length()) + ")");
        return true;
    }

    private static boolean gunzipFile(File src, File dest, Progress p) {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        long total = 0;
        try (GZIPInputStream in = new GZIPInputStream(new java.io.FileInputStream(src), 1 << 16);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int read;
            long lastReport = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                total += read;
                if (total - lastReport > 16 * 1024 * 1024) {
                    lastReport = total;
                    if (p != null) p.onBytes("rootfs.img", total);
                }
            }
            out.flush();
        } catch (IOException e) {
            log(p, 3, "Decompression failed: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) {
                log(p, 3, "rename failed for " + dest);
                return false;
            }
        }
        log(p, 2, "rootfs.img ready (" + mb(total) + ")");
        return true;
    }

    private static void copyAsset(AssetManager am, String assetPath, File dest, Progress p, String label)
            throws IOException {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        long total = 0;
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int r;
            long lastReport = 0;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                total += r;
                if (total - lastReport > 4 * 1024 * 1024) {
                    lastReport = total;
                    if (p != null) p.onBytes(label, total);
                }
            }
            out.flush();
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) throw new IOException("rename failed for " + dest);
        }
        if (p != null) p.onBytes(label, total);
        log(p, 2, label + " extracted (" + mb(total) + ")");
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static void stage(Progress p, Stage s) { if (p != null) p.onStage(s); }
    private static void log(Progress p, int level, String msg) { if (p != null) p.onLog(level, msg); }
}
