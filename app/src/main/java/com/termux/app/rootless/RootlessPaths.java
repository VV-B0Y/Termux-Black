package com.termux.app.rootless;

import android.content.Context;

import java.io.File;

/**
 * Where the rootless VM lives.
 *
 * Ported from strykerapp's engine/RootlessPaths.java: the guest belongs to the APP, under its own
 * private filesDir - not in a Termux home and not behind shell scripts. That is what lets the app
 * install, launch and attach with no PC and no external tooling.
 */
public final class RootlessPaths {

    private RootlessPaths() {}

    public static File base(Context c) {
        return new File(c.getFilesDir(), "rootless");
    }

    public static File qemuBin(Context c)     { return new File(base(c), "qemu-system-aarch64"); }
    public static File libslirp(Context c)    { return new File(base(c), "libslirp.so"); }
    public static File kernel(Context c)      { return new File(base(c), "Image"); }
    public static File initrd(Context c)      { return new File(base(c), "initrd.img"); }
    public static File rootfs(Context c)      { return new File(base(c), "rootfs.img"); }
    public static File rootfsGz(Context c)    { return new File(base(c), "rootfs.img.gz"); }

    public static File qmpSock(Context c)     { return new File(base(c), "qmp.sock"); }
    public static File serialSock(Context c)  { return new File(base(c), "serial.sock"); }
    public static File serialLog(Context c)   { return new File(base(c), "serial.log"); }
    public static File termSock(Context c)    { return new File(base(c), "term.sock"); }
    public static File bootLog(Context c)     { return new File(base(c), "boot.log"); }

    public static File activeFlag(Context c)  { return new File(base(c), ".active"); }

    /** Guest-side ports reachable through SLIRP hostfwd. */
    public static final int GUEST_EXEC_PORT = 1050;
    public static final int HOST_EXEC_PORT  = 1050;
    public static final String HOST_LOOPBACK = "127.0.0.1";

    public static final int GUEST_TERM_PORT = 1051;
    public static final int HOST_TERM_PORT  = 1051;

    public static final int GUEST_PTY_PORT = 1052;
    public static final int HOST_PTY_PORT  = 1052;

    public static final int GUEST_SSH_PORT = 22;
    public static final int HOST_SSH_PORT  = 2222;
}
