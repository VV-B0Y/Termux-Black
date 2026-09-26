package com.termux.app.rootless;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the guest is doing right now, and what it has captured so far.
 *
 * Every action prints framed markers into the console stream - {@code @@RUN <tool> <epoch>},
 * {@code @@DONE <tool>}, {@code @@HASH <kind> <count>}, {@code @@SSID <name>} - and the bridge
 * mirrors that stream to a file the app can read. That is the whole mechanism, and it is deliberate:
 *
 * - No polling command is ever typed into the guest. Anything typed while a capture tool is running
 *   is delivered to that tool, not to the shell (hcxdumptool treats keystrokes as commands), so a
 *   "what is running?" query would actively disturb the run it is asking about.
 * - Only one tool can own the adapter at a time, so knowing what is running is what lets the app
 *   warn before a second run turns both of them into noise.
 *
 * A run that never printed its {@code @@DONE} is only treated as active while it is still young
 * enough to be one of our own bounded runs; otherwise a VM killed mid-run would leave a permanent
 * "running" badge behind.
 *
 * <p>The reader looks at the last megabyte of the log, not the last few kilobytes: a capture tool
 * prints its live table continuously, which is easily tens of kilobytes by the time a 240s run is
 * half done, and the run's opening marker would otherwise scroll out of view - the indicator would
 * go dark in the middle of the run it is describing.</p>
 */
public final class RootlessRuns {

    /** Longest run the app starts is under 5 minutes; past this a missing @@DONE means it died. */
    private static final long STALE_AFTER_MS = 8 * 60 * 1000;
    /**
     * A live run prints continuously - every capture tool redraws its table at least once a second -
     * so a log that has been silent this long means nothing is running. This is what keeps a run
     * whose VM was torn down mid-run from sitting there as a phantom "running" badge, and from
     * warning the user off starting anything else.
     */
    private static final long LOG_QUIET_MS = 90 * 1000;
    private static final int TAIL_BYTES = 1024 * 1024;

    /**
     * Markers are matched by their shape rather than by where they sit on a line. Two reasons: the
     * guest's shell prompt shares the first line with the marker it precedes, and every marker's own
     * text also appears in the command that was echoed back. The trailing anchor and the strict
     * argument shapes ("@@RUN pmkid $(date +%s)" is not an epoch) keep the echo from matching.
     */
    private static final Pattern RUN_MARKER = Pattern.compile("@@RUN ([a-z][a-z-]*) ([0-9]{9,12})\\s*$");
    private static final Pattern DONE_MARKER = Pattern.compile("@@DONE ([a-z][a-z-]*)\\s*$");
    private static final Pattern HASH_MARKER = Pattern.compile("@@HASH ([a-z][a-z-]*) ([0-9]+)\\s*$");
    private static final Pattern SSID_MARKER = Pattern.compile("^@@SSID (.+?)\\s*$");

    private RootlessRuns() {}

    /** One run as the guest reported it. */
    public static final class Run {
        public final String tool;
        public final long startedAt;
        public boolean finished;

        Run(String tool, long startedAt) {
            this.tool = tool;
            this.startedAt = startedAt;
        }

        /** A name for the user, not the tool's internal marker. */
        public String label() {
            String t = tool == null ? "" : tool.toLowerCase(Locale.US);
            switch (t) {
                case "scan": return "network scan";
                case "target-scan": return "target sweep";
                case "handshake": return "handshake capture";
                case "pmkid": return "PMKID scan";
                case "deauth": return "deauth";
                case "mass-deauth": return "mass deauth";
                case "mass-handshake": return "mass handshake capture";
                case "results": return "reading results";
                default: return TextUtils.isEmpty(tool) ? "a run" : tool;
            }
        }
    }

    /** Everything the markers say about the guest. */
    public static final class State {
        /** The run holding the adapter, or null when nothing is running. */
        public Run active;
        /** The most recent run, finished or not. */
        public Run last;
        /** Hash counts by kind, from @@HASH markers: "handshake", "pmkid". */
        public int handshakes;
        public int pmkids;
        /** SSIDs whose material is in the capture files, newest first, no duplicates. */
        public final List<String> capturedSsids = new ArrayList<>();

        public boolean busy() {
            return active != null;
        }
    }

    /** Parse the mirror log's most recent markers. Never null. */
    public static State read(Context c) {
        State state = new State();
        File log = RootlessConsole.consoleLog(c);
        String text = readTail(log, TAIL_BYTES);
        if (text == null) return state;

        for (String raw : text.split("\n")) {
            String line = raw.replace("\r", "").trim();

            Matcher marker = RUN_MARKER.matcher(line);
            if (marker.find()) {
                // The guest prints epoch seconds; everything here compares milliseconds.
                state.last = new Run(marker.group(1), parseLong(marker.group(2), 0L) * 1000L);
                continue;
            }
            marker = DONE_MARKER.matcher(line);
            if (marker.find()) {
                if (state.last != null && state.last.tool.equals(marker.group(1))) {
                    state.last.finished = true;
                }
                continue;
            }
            marker = HASH_MARKER.matcher(line);
            if (marker.find()) {
                int count = (int) parseLong(marker.group(2), 0L);
                if ("handshake".equals(marker.group(1))) state.handshakes = count;
                else if ("pmkid".equals(marker.group(1))) state.pmkids = count;
                continue;
            }
            marker = SSID_MARKER.matcher(line);
            if (marker.find()) {
                String ssid = marker.group(1).trim();
                if (!ssid.isEmpty() && !state.capturedSsids.contains(ssid)) state.capturedSsids.add(ssid);
            }
        }

        // Newest SSIDs first: the tail reads oldest to newest.
        java.util.Collections.reverse(state.capturedSsids);

        if (state.last != null && !state.last.finished) {
            boolean logIsLive = log != null
                && System.currentTimeMillis() - log.lastModified() < LOG_QUIET_MS;
            if (logIsLive && System.currentTimeMillis() - state.last.startedAt < STALE_AFTER_MS) {
                state.active = state.last;
            }
        }
        return state;
    }

    /** "3 min ago", for an indicator that has to fit on one line. */
    public static String ago(long millis) {
        if (millis <= 0) return "just now";
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min ago";
        return (minutes / 60) + "h ago";
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String readTail(File file, int maxBytes) {
        if (file == null || !file.isFile()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long from = Math.max(0, length - maxBytes);
            raf.seek(from);
            byte[] buf = new byte[(int) (length - from)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
