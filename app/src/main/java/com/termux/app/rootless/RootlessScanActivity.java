package com.termux.app.rootless;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Pick a target from what is actually in range.
 *
 * The sweep itself runs in the guest (see {@link RootlessScan#scanCommand()}); this screen reads
 * the framed results back out of the console mirror log and turns them into something you can
 * choose from: name, security level and how many clients are already associated, least secure and
 * busiest first, with checkboxes for the networks you do not want to see at all.
 *
 * Choosing a network writes it to the same target slot the drawer's targeted attacks read, so a
 * pick here is all that is needed before Deauth, Capture handshake or PMKID scan.
 */
public class RootlessScanActivity extends Activity {

    /** A sweep is 13 channels x 5s plus the guest's parse; past this the guest is not answering. */
    private static final long SCAN_TIMEOUT_MS = 150_000;
    /** A sweep that reports no channel within this long is re-sent: a command typed before the
     * guest's shell is listening is simply dropped, and a guest that just booted is exactly that. */
    private static final long PROGRESS_TIMEOUT_MS = 30_000;
    private static final int MAX_SWEEPS = 3;
    private static final long POLL_MS = 1200;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private ListView mList;
    private TextView mStatus;
    private ScanAdapter mAdapter;
    private TermuxService mService;

    /** How many sweeps have been sent, and when the last one showed progress. */
    private int mSweepsSent;
    private long mLastProgressAt;
    private int mLastChannelsDone = -1;
    /** Whether the one-adapter warning has been raised for this sweep, and whether it was cancelled. */
    private boolean mBusyChecked;
    private boolean mCancelled;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TermuxService.LocalBinder) binder).service;
            // The sweep is started here, not by the screen that opened this one, so it cannot race
            // the guest's boot: if the first attempt lands too early it is repeated in refresh().
            startSweep();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private final Runnable mPoller = new Runnable() {
        @Override public void run() {
            refresh();
            mHandler.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rootless_scan);

        if (TermuxAppSharedProperties.getProperties().isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        mList = findViewById(R.id.scan_list);
        mStatus = findViewById(R.id.scan_status);
        mAdapter = new ScanAdapter();
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((parent, view, position, id) -> {
            RootlessScan.ScanAp ap = mAdapter.getItem(position);
            RootlessScan.select(this, ap);
            Toast.makeText(this,
                getString(R.string.rootless_scan_selected, RootlessActions.targetLabel(this)),
                Toast.LENGTH_LONG).show();
            finish();
        });

        bindFilter(R.id.filter_hidden, "hidden");
        bindFilter(R.id.filter_wpa3, "wpa3");
        bindFilter(R.id.filter_open, "open");
        bindFilter(R.id.filter_clients, "clients");

        findViewById(R.id.scan_rescan).setOnClickListener(v -> {
            mSweepsSent = 0;
            mBusyChecked = false;
            mCancelled = false;
            startSweep();
        });
        findViewById(R.id.scan_manual).setOnClickListener(v -> showManualEntry());

        // This screen owns the sweep; binding to the service is what gives it a console to type into.
        bindService(new Intent(this, TermuxService.class), mConnection, Context.BIND_AUTO_CREATE);
        mHandler.post(mPoller);
    }

    @Override protected void onDestroy() {
        mHandler.removeCallbacks(mPoller);
        try {
            unbindService(mConnection);
        } catch (Exception ignored) {
            // never bound (service already gone) - nothing to release
        }
        super.onDestroy();
    }

    private void bindFilter(int viewId, String name) {
        CheckBox box = findViewById(viewId);
        box.setChecked(RootlessScan.filter(this, name));
        box.setOnCheckedChangeListener((buttonView, isChecked) -> {
            RootlessScan.setFilter(this, name, isChecked);
            refresh();
        });
    }

    /**
     * Type a fresh sweep into the guest and start watching for its rows.
     *
     * The line is preceded by Ctrl-C so the guest starts from a clean shell line: it clears a
     * partially typed line, abandons a continuation prompt, and kills a sweep that is still
     * running. Without it, a re-sent sweep is fed into the previous one's airodump instead of the
     * shell, and the mangled line takes the console down with it.
     */
    private void startSweep() {
        // A sweep needs the adapter exclusively, so a run already in progress is worth interrupting
        // the user for rather than quietly starting a fight with.
        if (!mBusyChecked) {
            mBusyChecked = true;
            com.termux.app.rootless.RootlessRuns.Run active =
                com.termux.app.rootless.RootlessRuns.read(this).active;
            if (active != null) {
                new AlertDialog.Builder(this)
                    .setTitle(R.string.rootless_busy_title)
                    .setMessage(getString(R.string.rootless_busy_message,
                        active.label(), RootlessRuns.ago(active.startedAt)))
                    .setPositiveButton(R.string.rootless_busy_stop, (d, w) -> sendSweep())
                    .setNeutralButton(R.string.rootless_busy_anyway, (d, w) -> sendSweep())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        mCancelled = true;
                        mStatus.setText(R.string.rootless_scan_busy_cancelled);
                    })
                    .show();
                return;
            }
        }
        sendSweep();
    }

    /** Type the sweep into the guest. The leading Ctrl-C clears the line and stops anything else. */
    private void sendSweep() {
        if (mCancelled) return;
        if (mSweepsSent >= MAX_SWEEPS) {
            mStatus.setText(getString(R.string.rootless_scan_stalled,
                Math.max(mLastChannelsDone, 0), RootlessScan.totalSteps()));
            return;
        }
        if (!RootlessConsole.runCommand(this, mService, "\u0003" + RootlessScan.scanCommand())) {
            mStatus.setText(R.string.rootless_scan_needs_vm);
            return;
        }
        mSweepsSent++;
        mLastProgressAt = System.currentTimeMillis();
        mLastChannelsDone = -1;
        mStatus.setText(R.string.rootless_scan_starting);
        refresh();
    }

    private void refresh() {
        RootlessScan.ScanResult result = RootlessScan.readResults(this);
        List<RootlessScan.ScanAp> visible = RootlessScan.visible(this, result);
        mAdapter.setNetworks(visible);

        if (result.channelsDone != mLastChannelsDone) {
            mLastChannelsDone = result.channelsDone;
            mLastProgressAt = System.currentTimeMillis();
        }

        if (result.adapterMissing) {
            mStatus.setText(R.string.rootless_scan_no_adapter);
            return;
        }
        if (result.complete) {
            mStatus.setText(getString(R.string.rootless_scan_found, visible.size()));
            return;
        }

        long silence = System.currentTimeMillis() - mLastProgressAt;
        if (silence > PROGRESS_TIMEOUT_MS && !mCancelled) {
            // Only a sweep that never even acknowledged its opening marker is re-sent. A sweep that
            // is already running must never be interrupted by a second command line.
            if (!result.started && mSweepsSent < MAX_SWEEPS) {
                startSweep();
            } else {
                mStatus.setText(getString(R.string.rootless_scan_stalled,
                    Math.max(result.channelsDone, 0), result.totalSteps()));
            }
            return;
        }

        int step = Math.min(result.channelsDone + 1, result.totalSteps());
        mStatus.setText(getString(R.string.rootless_scan_progress,
            step, result.totalSteps(), visible.size()));
    }

    /** Last resort for a network the sweep cannot see (out of range now, or suppressed). */
    private void showManualEntry() {
        View view = getLayoutInflater().inflate(R.layout.dialog_target, null);
        EditText ssidInput = view.findViewById(R.id.target_ssid);
        EditText bssidInput = view.findViewById(R.id.target_bssid);
        EditText channelInput = view.findViewById(R.id.target_channel);

        ssidInput.setText(RootlessActions.getTargetSsid(this));
        bssidInput.setText(RootlessActions.getTargetBssid(this));
        channelInput.setText(String.valueOf(RootlessActions.getTargetChannel(this)));

        new AlertDialog.Builder(this)
            .setTitle(R.string.rootless_target_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String ssid = ssidInput.getText().toString().trim();
                String bssid = bssidInput.getText().toString().trim();
                if (!RootlessActions.isValidBssid(bssid)) {
                    Toast.makeText(this, R.string.rootless_target_bssid_hint, Toast.LENGTH_LONG).show();
                    return;
                }
                int channel;
                try {
                    channel = Integer.parseInt(channelInput.getText().toString().trim());
                } catch (NumberFormatException e) {
                    channel = RootlessActions.DEFAULT_CHANNEL;
                }
                if (channel < 1 || channel > 196) channel = RootlessActions.DEFAULT_CHANNEL;
                RootlessActions.setTarget(this, bssid, channel, ssid);
                Toast.makeText(this,
                    getString(R.string.rootless_scan_selected, RootlessActions.targetLabel(this)),
                    Toast.LENGTH_LONG).show();
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Rows: SSID on top, then the things the ordering is based on. */
    private final class ScanAdapter extends BaseAdapter {
        private final List<RootlessScan.ScanAp> mItems = new ArrayList<>();

        void setNetworks(List<RootlessScan.ScanAp> items) {
            mItems.clear();
            mItems.addAll(items);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return mItems.size(); }
        @Override public RootlessScan.ScanAp getItem(int position) { return mItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null
                ? convertView
                : getLayoutInflater().inflate(R.layout.list_item_scan_ap, parent, false);
            RootlessScan.ScanAp ap = getItem(position);

            TextView ssid = row.findViewById(R.id.ap_ssid);
            TextView detail = row.findViewById(R.id.ap_detail);
            ssid.setText(ap.isHidden() ? getString(R.string.rootless_scan_hidden_name) : ap.ssid);

            // Same tiering as the console tables: the security level is the column that decides
            // whether a network is worth the time, so it is the one that carries colour here too.
            String label = ap.securityLabel();
            String line = getString(R.string.rootless_scan_detail,
                label, ap.clients, ap.bssid, ap.channel, ap.power);
            android.text.SpannableString styled = new android.text.SpannableString(line);
            int end = Math.min(label.length(), line.length());
            styled.setSpan(new android.text.style.ForegroundColorSpan(securityColor(label)), 0, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            detail.setText(styled);
            return row;
        }

        /** Red for the easy tier, amber for the middle one, plain for WPA3. */
        private int securityColor(String label) {
            String l = label == null ? "" : label.toUpperCase(java.util.Locale.US);
            if (l.contains("WPA3")) return 0xFFFFFFFF;
            if (l.contains("WPA2")) return 0xFFFFC107;
            return 0xFFFF3B30;
        }
    }
}
