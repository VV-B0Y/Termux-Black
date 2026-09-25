package com.termux.app.rootless;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Download a single asset and verify it by sha256 before it is allowed to replace the destination.
 *
 * Interface-compatible with strykerapp's ota/VerifiedDownloader: same
 * {@code download(url, dest, expectedSha256, expectedSize, progress)} signature and the same
 * {@link Result} shape, so the installer port is a straight call.
 *
 * Behaviour kept from theirs: HTTPS only; download to a {@code .part} file; hash the part file and
 * only then rename it into place, so a truncated or tampered download can never be mistaken for a
 * good one; retry a couple of times; report progress.
 */
public final class VerifiedDownloader {

    public interface Progress {
        void onBytes(long done, long total);
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final String sha256;

        private Result(boolean ok, String error, String sha256) {
            this.ok = ok;
            this.error = error;
            this.sha256 = sha256;
        }

        static Result ok(String sha256)       { return new Result(true, null, sha256); }
        static Result fail(String error)      { return new Result(false, error, null); }
    }

    private static final int ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER = 1 << 16;

    private VerifiedDownloader() {}

    public static Result download(String url, File dest, String expectedSha256, long expectedSize,
                                  Progress progress) {
        if (url == null || !url.startsWith("https://")) {
            return Result.fail("Refusing non-HTTPS URL");
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return Result.fail("Cannot create " + parent.getAbsolutePath());
        }
        File part = new File(dest.getAbsolutePath() + ".part");

        String lastError = "download failed";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Result r = attempt(url, part, expectedSha256, expectedSize, progress);
            if (r.ok) {
                if (!part.renameTo(dest)) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    if (!part.renameTo(dest)) {
                        return Result.fail("rename failed for " + dest);
                    }
                }
                return r;
            }
            lastError = r.error;
            RootlessLog.w("download attempt " + attempt + " failed: " + lastError);
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        return Result.fail(lastError);
    }

    private static Result attempt(String url, File part, String expectedSha256, long expectedSize,
                                  Progress progress) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "termux-rootless");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return Result.fail("HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            if (expectedSize > 0 && total > 0 && total != expectedSize) {
                return Result.fail("size mismatch: manifest says " + expectedSize + ", server says " + total);
            }
            if (total <= 0) total = expectedSize;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long done = 0;
            long lastReport = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[BUFFER];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    digest.update(buf, 0, read);
                    done += read;
                    if (done - lastReport > 4 * 1024 * 1024) {
                        lastReport = done;
                        if (progress != null) progress.onBytes(done, total);
                    }
                }
                out.flush();
                out.getFD().sync();
            }
            if (progress != null) progress.onBytes(done, total);

            String actual = hex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.isEmpty()
                    && !expectedSha256.equalsIgnoreCase(actual)) {
                return Result.fail("sha256 mismatch: expected " + expectedSha256 + ", got " + actual);
            }
            if (expectedSize > 0 && done != expectedSize) {
                return Result.fail("truncated: got " + done + " of " + expectedSize + " bytes");
            }
            return Result.ok(actual);
        } catch (Exception e) {
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Hash an existing file, for verifying something already on disk. */
    public static String sha256Of(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER];
            int read;
            while ((read = raf.read(buf)) != -1) digest.update(buf, 0, read);
            return hex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
