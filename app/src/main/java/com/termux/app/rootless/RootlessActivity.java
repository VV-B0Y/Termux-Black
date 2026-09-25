package com.termux.app.rootless;

import com.termux.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

/**
 * VM control screen: start, stop, open a console, attach/detach the Wi-Fi adapter.
 *
 * The app auto-starts the guest on first run, but a VM you cannot stop, restart or talk to is not
 * usable, so this is the control surface for it. Status is polled rather than pushed, because the
 * guest can also die on its own (OOM kill, host reboot) and the screen has to reflect that.
 */
public class RootlessActivity extends Activity {

    private static final String TAG = "RootlessActivity";
    private static final long POLL_MS = 2000;

    private TextView mStatus;
    private TextView mLog;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoller = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            mHandler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.rootless_vm_title);
        setContentView(R.layout.activity_rootless);

        mStatus = findViewById(R.id.rootless_status);
        mLog = findViewById(R.id.rootless_log);

        findViewById(R.id.rootless_start).setOnClickListener(v -> startVm());
        findViewById(R.id.rootless_stop).setOnClickListener(v -> stopVm());
        findViewById(R.id.rootless_console).setOnClickListener(v -> openConsole());
        findViewById(R.id.rootless_attach).setOnClickListener(v -> sendServiceAction(
                com.termux.app.TermuxService.ACTION_ATTACH_USB,
                getString(R.string.rootless_attach_requested)));
        findViewById(R.id.rootless_detach).setOnClickListener(v -> sendServiceAction(
                com.termux.app.TermuxService.ACTION_DETACH_USB,
                getString(R.string.rootless_detach_requested)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mPoller);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mPoller);
        super.onPause();
    }

    private void refreshStatus() {
        if (!QemuInstaller.isInstalled(this)) {
            mStatus.setText(R.string.rootless_status_not_installed);
            return;
        }
        int pid = RootlessVm.pidOf(this);
        boolean sock = RootlessPaths.qmpSock(this).exists();
        if (pid > 0 && sock) {
            mStatus.setText(getString(R.string.rootless_status_running, pid));
        } else if (pid > 0) {
            mStatus.setText(R.string.rootless_status_no_socket);
        } else {
            mStatus.setText(R.string.rootless_status_stopped);
        }
    }

    private void startVm() {
        if (!QemuInstaller.isInstalled(this)) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.rootless_vm_title)
                .setMessage(R.string.rootless_install_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        appendLog(getString(R.string.rootless_starting));
        new Thread(() -> {
            boolean up;
            try {
                up = RootlessVm.start(this);
            } catch (Exception e) {
                Log.w(TAG, "start failed", e);
                up = false;
            }
            final boolean result = up;
            runOnUiThread(() -> {
                appendLog(result ? getString(R.string.rootless_started) : getString(R.string.rootless_start_failed));
                refreshStatus();
            });
        }, "rootless-start").start();
    }

    private void stopVm() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.rootless_stop_vm)
            .setMessage(R.string.rootless_stop_confirm)
            .setPositiveButton(R.string.rootless_stop_vm, (d, w) -> new Thread(() -> {
                RootlessVm.stop(RootlessActivity.this);
                runOnUiThread(() -> {
                    appendLog(getString(R.string.rootless_stopped));
                    refreshStatus();
                });
            }, "rootless-stop").start())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void openConsole() {
        if (RootlessVm.pidOf(this) <= 0) {
            appendLog(getString(R.string.rootless_console_needs_vm));
            return;
        }
        startActivity(new Intent(this, RootlessConsoleActivity.class));
    }

    private void sendServiceAction(String action, String note) {
        if (RootlessVm.pidOf(this) <= 0) {
            appendLog(getString(R.string.rootless_attach_needs_vm));
            return;
        }
        Intent svc = new Intent(this, com.termux.app.TermuxService.class);
        svc.setAction(action);
        startService(svc);
        appendLog(note);
    }

    private void appendLog(String line) {
        mLog.append(line + "\n");
    }
}
