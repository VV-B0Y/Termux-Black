package com.termux.app.rootless;

import com.termux.R;

import android.app.Activity;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Interactive console into the guest, over QEMU's serial socket.
 *
 * Why the serial socket and not a shell: the guest auto-logs-in as root on ttyAMA0, so a console
 * attached here is a real root shell inside the VM with no credentials, no SSH client and no extra
 * binaries in the app. The Telnet/SSH alternatives all need something installed on one side or the
 * other; this needs nothing at all.
 *
 * The socket is app-private, so this only works from inside the app process - which is also why it
 * cannot be driven from `adb shell`.
 */
public class RootlessConsoleActivity extends Activity {

    private static final String TAG = "RootlessConsole";
    private static final int MAX_CHARS = 60000;

    /** Strips ANSI CSI/OSC/escape sequences so the console is readable in a plain TextView. */
    private static final Pattern ANSI = Pattern.compile(
        "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");

    private TextView mOutput;
    private ScrollView mScroll;
    private EditText mInput;
    private LocalSocket mSocket;
    private OutputStream mOut;
    private volatile boolean mRunning;
    private final StringBuilder mBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.rootless_console_title);
        setContentView(R.layout.activity_rootless_console);

        mOutput = findViewById(R.id.rootless_console_output);
        mScroll = findViewById(R.id.rootless_console_scroll);
        mInput = findViewById(R.id.rootless_console_input);

        findViewById(R.id.rootless_console_send).setOnClickListener(v -> sendLine());

        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendLine();
                return true;
            }
            return false;
        });

        append(getString(R.string.rootless_console_connecting));
        connect();
    }

    @Override
    protected void onDestroy() {
        mRunning = false;
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void connect() {
        final String path = RootlessPaths.serialSock(this).getAbsolutePath();
        new Thread(() -> {
            try {
                LocalSocket s = new LocalSocket();
                s.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                mSocket = s;
                mOut = s.getOutputStream();
                mRunning = true;
                append(getString(R.string.rootless_console_connected) + "\n");
                InputStream in = s.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                while (mRunning && (n = in.read(buf)) != -1) {
                    append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.w(TAG, "console connect failed", e);
                append("\n" + getString(R.string.rootless_console_error) + " " + e.getMessage() + "\n");
            }
        }, "rootless-console").start();
    }

    private void sendLine() {
        String text = mInput.getText().toString();
        mInput.setText("");
        if (mOut == null) return;
        try {
            // A CR, not an LF: this is a serial tty in canonical mode.
            mOut.write((text + "\r").getBytes(StandardCharsets.UTF_8));
            mOut.flush();
        } catch (Exception e) {
            append("\n[send failed: " + e.getMessage() + "]\n");
        }
    }

    private void append(final String chunk) {
        runOnUiThread(() -> {
            mBuffer.append(ANSI.matcher(chunk.replace("\r\n", "\n")).replaceAll(""));
            if (mBuffer.length() > MAX_CHARS) {
                mBuffer.delete(0, mBuffer.length() - MAX_CHARS);
            }
            mOutput.setText(mBuffer.toString());
            mScroll.post(() -> mScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
