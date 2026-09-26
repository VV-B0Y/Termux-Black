package com.termux.app.rootless;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The bridge that turns the guest's serial line into a normal Termux terminal session.
 *
 * The guest auto-logs-in as root on ttyAMA0 and QEMU publishes that line as a UNIX socket
 * ({@link RootlessPaths#serialSock}). A Termux session, however, is a pty handed to a child
 * process, so {@code rootless-console} (built from cpp/rootless-console.c) sits in the middle and
 * shovels bytes both ways. That is why the VM console can be a real terminal session - with the
 * extra-keys row, scrollback, copy/paste and Ctrl-C actually reaching the guest - instead of a
 * bespoke "send command" screen.
 *
 * The bridge is a native executable. It is packaged with a {@code lib*.so} name (the only shape
 * Android ships out of an APK's lib directory), then copied into the app's own data dir, which is
 * the location this app is allowed to execute from - the same place qemu itself runs from.
 *
 * The bridge also mirrors everything the GUEST writes into {@link #consoleLog} (see
 * {@link RootlessScan} for what that is used for): a scan prints framed rows, the app parses the
 * frames off that file, and the user gets a real list to choose from instead of reading a console.
 */
public final class RootlessConsole {

    /** Name of the session that hosts the console, so it can be found again later. */
    public static final String SESSION_NAME = "VM console";

    private static final String PACKAGED_NAME = "librootless-console.so";
    private static final String INSTALLED_NAME = "rootless-console";

    /** A freshly created bridge needs a moment to connect to the serial socket before it can take
     * a command line, or the write is simply dropped. */
    private static final long CONNECT_GRACE_MS = 900;

    private RootlessConsole() {}

    /** The packaged bridge inside the APK's native library directory. */
    public static File packaged(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, PACKAGED_NAME);
    }

    /** Where the bridge is executed from, inside the app's private data dir. */
    public static File bridge(Context c) {
        return new File(RootlessPaths.base(c), INSTALLED_NAME);
    }

    /** True when this build actually contains the bridge. */
    public static boolean isAvailable(Context c) {
        return packaged(c).isFile();
    }

    /**
     * Copy the packaged bridge into the data dir (refreshing it when the build changes) and make it
     * executable. Returns false when the bridge is missing from the APK or cannot be made
     * executable, so the caller can report that instead of opening a session that dies instantly.
     */
    public static boolean ensureInstalled(Context c) {
        try {
            File src = packaged(c);
            if (!src.isFile()) return false;

            File dst = bridge(c);
            if (!dst.isFile() || dst.length() != src.length()) {
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dst.setExecutable(true, false);
            return dst.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    /** Absolute path of the guest serial socket the bridge connects to. */
    public static String serialSocketPath(Context c) {
        return RootlessPaths.serialSock(c).getAbsolutePath();
    }

    /** Where the bridge mirrors the guest's output, for the app to parse. */
    public static File consoleLog(Context c) {
        return new File(RootlessPaths.base(c), "console.out");
    }

    /** Arguments handed to the bridge: the serial socket and the mirror log. */
    public static String[] bridgeArgs(Context c) {
        return new String[]{serialSocketPath(c), consoleLog(c).getAbsolutePath()};
    }

    /** The live console session, or null when there is none. */
    public static TerminalSession findSession(Context c, TermuxService service) {
        if (service == null) return null;
        String wanted = c.getString(R.string.rootless_console_session_name);
        for (TermuxSession session : service.getTermuxSessions()) {
            TerminalSession terminalSession = session.getTerminalSession();
            if (terminalSession != null && wanted.equals(terminalSession.mSessionName)
                && terminalSession.isRunning()) {
                return terminalSession;
            }
        }
        return null;
    }

    /**
     * Focus the console session, starting one when needed. Null means the VM is stopped, the
     * service is not up, or the bridge is missing from this build - the caller reports which.
     */
    public static TerminalSession openSession(Context c, TermuxService service) {
        if (service == null || !ensureInstalled(c)) return null;
        TerminalSession existing = findSession(c, service);
        if (existing != null) return existing;
        TermuxSession created = service.createTermuxSession(
            bridge(c).getAbsolutePath(), bridgeArgs(c), null,
            c.getFilesDir().getAbsolutePath(), false,
            c.getString(R.string.rootless_console_session_name));
        return created == null ? null : created.getTerminalSession();
    }

    /**
     * Type one command line into the guest's root shell. A CR is appended, not an LF: the serial
     * tty is in canonical mode. Returns false when there is no console to write to.
     */
    public static boolean runCommand(Context c, TermuxService service, String command) {
        TerminalSession session = openSession(c, service);
        if (session == null) return false;
        final byte[] line = (command + "\r").getBytes(StandardCharsets.UTF_8);
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> session.write(line, 0, line.length), CONNECT_GRACE_MS);
        return true;
    }
}
