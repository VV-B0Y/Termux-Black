package com.termux.app.usb;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Deploys the USB passthrough shim into the Termux rootfs.
 *
 * <p>QEMU's {@code usb-host} backend calls {@code libusb_init()}, which enumerates
 * {@code /dev/bus/usb} and {@code /sys/bus/usb}. Inside an unprivileged Android app sandbox
 * {@code opendir()} on those paths is denied by SELinux, so {@code libusb_init()} fails and QEMU
 * rejects every {@code device_add usb-host} with {@code "failed to init libusb"}.</p>
 *
 * <p>{@code libtermux-usb-shim.so} interposes {@code opendir()} to report those paths as
 * non-existent ({@code ENOENT}), which makes {@code libusb_init()} succeed with an empty device
 * list. The actual device is then handed to QEMU out-of-band as an inherited file descriptor
 * ({@code add-fd} + {@code hostdevice=/dev/fdset/N}), never through libusb enumeration.</p>
 *
 * <p>The shim has to be {@code LD_PRELOAD}ed into the qemu process, and shipped libs live in the
 * app's native library directory (inside {@code /data/app/...}), which the shell scripts cannot
 * know about. So we copy it to a stable path under the Termux home and wrap {@code vm} to export
 * it. Without this step the shim never reaches qemu and passthrough always fails.</p>
 */
public final class UsbShimInstaller {

    private static final String LOG_TAG = "UsbShimInstaller";
    private static final String SHIM_NAME = "libtermux-usb-shim.so";

    private UsbShimInstaller() {}

    public static void install(Context context) {
        try {
            File filesDir = context.getFilesDir();                  // .../files
            File homeDir = new File(filesDir, "home");              // Termux $HOME
            File prefixBin = new File(filesDir, "usr/bin");

            File libDir = new File(homeDir, "lib");
            File shimDst = new File(libDir, SHIM_NAME);
            File shimSrc = new File(context.getApplicationInfo().nativeLibraryDir, SHIM_NAME);

            if (!shimSrc.exists()) {
                Log.w(LOG_TAG, "shim not found in nativeLibraryDir: " + shimSrc);
                return;
            }

            //noinspection ResultOfMethodCallIgnored
            libDir.mkdirs();

            // Refresh whenever size differs so a rebuilt shim replaces a stale one.
            if (!shimDst.exists() || shimDst.length() != shimSrc.length()) {
                try (InputStream in = new FileInputStream(shimSrc);
                     OutputStream out = new FileOutputStream(shimDst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Log.i(LOG_TAG, "deployed shim to " + shimDst);
            }

            if (prefixBin.isDirectory()) {
                writeHelper(prefixBin, "termux-usb-attach", "com.termux.service_attach_usb");
                writeHelper(prefixBin, "termux-usb-detach", "com.termux.service_detach_usb");
                installVmWrapper(prefixBin, homeDir);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "failed to install USB shim", e);
        }
    }

    private static void writeHelper(File binDir, String name, String action) throws Exception {
        String script =
            "#!/data/data/com.termux/files/usr/bin/sh\n" +
            "am startservice -a " + action + " com.termux/.app.TermuxService\n" +
            "echo '[*] Sent USB request (" + action + ") to TermuxService'\n";
        writeExecutable(new File(binDir, name), script);
    }

    /**
     * Wrap {@code vm} so the shim is preloaded into the qemu process, then delegate to
     * whichever runner the VM installer deployed. Any pre-existing vm script is preserved as
     * {@code vm.pre-usb-shim.bak} so a hand-edited runner can be recovered.
     */
    private static void installVmWrapper(File binDir, File homeDir) throws Exception {
        String wrapper =
            "#!/data/data/com.termux/files/usr/bin/bash\n" +
            "# Installed by TermuxUsbShim: preload the USB shim, then run the real VM runner.\n" +
            "LIB_SHIM=\"$HOME/lib/" + SHIM_NAME + "\"\n" +
            "if [ -f \"$LIB_SHIM\" ]; then\n" +
            "    export LD_PRELOAD=\"$LIB_SHIM${LD_PRELOAD:+:$LD_PRELOAD}\"\n" +
            "else\n" +
            "    echo \"[!] USB shim missing at $LIB_SHIM - USB passthrough will fail\" >&2\n" +
            "fi\n" +
            "for RUNNER in \"$HOME/trixie_vm/start-vm.sh\" \"$HOME/trixie_vm/start-rootless-vm.sh\"; do\n" +
            "    if [ -f \"$RUNNER\" ]; then exec \"$RUNNER\" \"$@\"; fi\n" +
            "done\n" +
            "echo \"[!] No VM runner found in $HOME/trixie_vm\" >&2\n" +
            "exit 1\n";

        File vm = new File(binDir, "vm");
        if (vm.exists()) {
            String existing = new String(readAll(vm), StandardCharsets.UTF_8);
            if (existing.contains("LD_PRELOAD")) return;                 // already shim-aware
            File backup = new File(binDir, "vm.pre-usb-shim.bak");
            if (!backup.exists()) {
                try (OutputStream out = new FileOutputStream(backup)) {
                    out.write(existing.getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                backup.setExecutable(true, false);
            }
        }
        writeExecutable(vm, wrapper);
    }

    private static void writeExecutable(File file, String content) throws Exception {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(true, false);
    }

    private static byte[] readAll(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
