package com.termux.app.rootless;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches and stops the rootless QEMU guest.
 *
 * Ported from strykerapp's engine/RootlessEngine (the argument builder and process spawn), with
 * three deliberate differences from theirs, each one deliberate and load-bearing:
 *
 *  1. The rootfs drive is passed as {@code format=qcow2}, not {@code format=raw}: our published
 *     rootfs.imgz is a gzip-compressed QCOW2. A qcow2 grows on its own, so their
 *     RandomAccessFile.setLength() disk-growing step has no equivalent here.
 *  2. The kernel cmdline does NOT carry {@code net.ifnames=0}. Their image expects eth0;
 *     OURS is provisioned for enp0s2, so adding that flag would rename the interface and
 *     networking would silently fail to come up. Do not "fix" this without re-provisioning
 *     the guest image first.
 *  3. No {@code -L} data-dir flag. Like them we pass an empty {@code romfile=} on the virtio-net
 *     device, which removes the need for QEMU's external ROM files entirely - that is what makes
 *     this work on a phone that has never had Termux's qemu package installed.
 *
 * We DO use {@code -daemonize} + {@code -pidfile} (their Engine keeps QEMU a child of its
 * foreground service) so the guest survives the app process being killed.
 */
public final class RootlessVm {

    private RootlessVm() {}

    public static final int DEFAULT_CPUS = 4;
    public static final int DEFAULT_RAM_MB = 2048;
    private static final int TB_SIZE_MB = 512;

    /** Absolute path of the Unix socket the app attaches USB devices through. */
    public static String qmpSocketPath(Context c) {
        return RootlessPaths.qmpSock(c).getAbsolutePath();
    }

    public static boolean isRunning(Context c) {
        if (RootlessPaths.qmpSock(c).exists() && pidOf(c) > 0) return true;
        return pidOf(c) > 0;
    }

    /** PID from the pidfile, or -1. Verifies the process is actually alive. */
    public static int pidOf(Context c) {
        File pidFile = new File(RootlessPaths.base(c), "vm.pid");
        if (!pidFile.isFile()) return -1;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(pidFile));
            String line = r.readLine();
            r.close();
            if (line == null) return -1;
            int pid = Integer.parseInt(line.trim());
            return new File("/proc/" + pid).exists() ? pid : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static List<String> buildArgs(Context c, int cpus, int ramMb) {
        File base = RootlessPaths.base(c);
        List<String> a = new ArrayList<>();

        a.add(RootlessPaths.qemuBin(c).getAbsolutePath());
        a.add("-nodefaults");
        a.add("-M"); a.add("virt,gic-version=3");

        File kvm = new File("/dev/kvm");
        if (kvm.exists() && kvm.canWrite()) {
            a.add("-cpu"); a.add("host");
            a.add("-accel"); a.add("kvm");
        } else {
            a.add("-cpu"); a.add("max,sve=off,pmu=off,pauth=off");
            a.add("-accel"); a.add("tcg,thread=multi,tb-size=" + TB_SIZE_MB);
        }

        a.add("-smp"); a.add(String.valueOf(cpus));
        a.add("-m");   a.add(String.valueOf(ramMb));

        a.add("-kernel"); a.add(RootlessPaths.kernel(c).getAbsolutePath());
        a.add("-initrd"); a.add(RootlessPaths.initrd(c).getAbsolutePath());
        // NOTE: no net.ifnames=0 - our guest is provisioned for enp0s2. See the class comment.
        a.add("-append"); a.add("root=/dev/vda rw rootwait rootflags=noatime "
                + "console=ttyAMA0 loglevel=4 mitigations=off");

        // format=qcow2: our rootfs is a QCOW2 (gzip'd for transport), not a raw image.
        a.add("-drive"); a.add("file=" + RootlessPaths.rootfs(c).getAbsolutePath()
                + ",if=none,id=drive0,format=qcow2,cache=writeback,aio=threads");
        a.add("-device"); a.add("virtio-blk-pci,drive=drive0");

        a.add("-netdev"); a.add("user,id=net0,ipv6=off"
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_EXEC_PORT
                + "-:" + RootlessPaths.GUEST_EXEC_PORT
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_TERM_PORT
                + "-:" + RootlessPaths.GUEST_TERM_PORT
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_PTY_PORT
                + "-:" + RootlessPaths.GUEST_PTY_PORT
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_SSH_PORT
                + "-:" + RootlessPaths.GUEST_SSH_PORT);
        // Empty romfile: removes the dependency on QEMU's external ROM files (and on a -L datadir).
        a.add("-device"); a.add("virtio-net-pci,netdev=net0,romfile=");

        // XHCI controller: this is the bus the app's USB passthrough attaches the WiFi dongle to.
        a.add("-device"); a.add("qemu-xhci,id=usbhc0,p2=8,p3=8");
        a.add("-device"); a.add("virtio-rng-pci");

        a.add("-chardev"); a.add("socket,id=serial0,path=" + RootlessPaths.serialSock(c).getAbsolutePath()
                + ",server=on,wait=off,logfile=" + RootlessPaths.serialLog(c).getAbsolutePath());
        a.add("-serial"); a.add("chardev:serial0");
        a.add("-device"); a.add("virtio-serial-pci");
        a.add("-chardev"); a.add("socket,id=term0,path=" + RootlessPaths.termSock(c).getAbsolutePath()
                + ",server=on,wait=off");
        a.add("-device"); a.add("virtconsole,chardev=term0,name=org.termux.term");

        a.add("-display"); a.add("none");
        a.add("-qmp"); a.add("unix:" + RootlessPaths.qmpSock(c).getAbsolutePath() + ",server,nowait");
        a.add("-daemonize");
        a.add("-pidfile"); a.add(new File(base, "vm.pid").getAbsolutePath());
        return a;
    }

    /**
     * Start the guest if it is not already running, then wait for qmp.sock.
     *
     * The socket wait matters: attaching a USB device before QMP is listening fails with
     * "QMP socket not connected or VM not running", which looks like a passthrough bug.
     */
    public static boolean start(Context c) throws IOException, InterruptedException {
        File base = RootlessPaths.base(c);
        if (!base.exists() && !base.mkdirs()) throw new IOException("cannot create " + base);

        if (pidOf(c) > 0) return waitForQmp(c, 5000);

        // Stale sockets from a previous run would confuse the attach path.
        //noinspection ResultOfMethodCallIgnored
        RootlessPaths.qmpSock(c).delete();
        //noinspection ResultOfMethodCallIgnored
        RootlessPaths.serialSock(c).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(base, "vm.pid").delete();

        try { RootlessPaths.qemuBin(c).setExecutable(true, false); } catch (Exception ignored) {}

        ProcessBuilder pb = new ProcessBuilder(buildArgs(c, DEFAULT_CPUS, DEFAULT_RAM_MB));
        pb.directory(base);
        pb.redirectErrorStream(true);
        pb.redirectOutput(Redirect.appendTo(RootlessPaths.bootLog(c)));
        pb.environment().put("LD_LIBRARY_PATH",
                base.getAbsolutePath() + File.pathSeparator + "/system/lib64");
        pb.environment().put("HOME", base.getAbsolutePath());
        pb.environment().put("TMPDIR", c.getCacheDir().getAbsolutePath());

        RootlessLog.i("starting VM: " + String.join(" ", buildArgs(c, DEFAULT_CPUS, DEFAULT_RAM_MB)));
        Process p = pb.start();
        // With -daemonize the parent exits promptly, so this returns quickly either way.
        p.waitFor();
        return waitForQmp(c, 30000);
    }

    public static boolean waitForQmp(Context c, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (RootlessPaths.qmpSock(c).exists() && pidOf(c) > 0) return true;
            Thread.sleep(500);
        }
        return RootlessPaths.qmpSock(c).exists() && pidOf(c) > 0;
    }

    /** Stop the guest. SIGTERM so QEMU flushes the qcow2 on the way out. */
    public static boolean stop(Context c) {
        int pid = pidOf(c);
        if (pid <= 0) return false;
        try {
            Runtime.getRuntime().exec(new String[]{"kill", "-TERM", String.valueOf(pid)}).waitFor();
        } catch (Exception e) {
            return false;
        }
        for (int i = 0; i < 20; i++) {
            if (!new File("/proc/" + pid).exists()) break;
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        }
        //noinspection ResultOfMethodCallIgnored
        new File(RootlessPaths.base(c), "vm.pid").delete();
        return true;
    }
}
