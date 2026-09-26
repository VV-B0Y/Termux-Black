package com.termux.app.rootless;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * What is actually in range, as the targeted attacks need to see it.
 *
 * The picker sweep and the parse live together here because they are two halves of one contract:
 * the guest prints framed rows ({@code @@ROW}, {@code @@CLI}) and this class reads them back out of
 * the console mirror log. Doing the sweep as thirteen short per-channel captures, instead of one
 * channel-hopping capture, is what makes the client counts trustworthy - a hopping radio misses
 * frames, and a station only shows up in the CSV while it is heard.
 *
 * Ordering answers the question the user actually has in front of a target list: "which of these is
 * easiest". Rank is by advertised security, weakest first (open, WEP, WPA, WPA2, WPA3), then by the
 * number of clients already associated (a client that reconnects is what yields a handshake), then
 * by signal strength.
 */
public final class RootlessScan {

    public static final String IFACE = "wlan0";

    private static final String PREFS = "rootless_scan";
    private static final int TAIL_BYTES = 256 * 1024;
    /** The sweep's own progress ticks: a first one 8s in, then one every {@link #SECONDS_PER_STEP}. */
    private static final int STEPS = 8;
    private static final int SECONDS_PER_STEP = 10;

    private RootlessScan() {}

    /** Progress steps a sweep reports, for a readout that cannot know the guest's own timing. */
    public static int totalSteps() {
        return STEPS;
    }

    // ------------------------------------------------------------------ networks

    /** One access point in range. */
    public static final class ScanAp implements Comparable<ScanAp> {
        public final String ssid;
        public final String bssid;
        public final String security;
        public final int channel;
        public final int power;
        /** Client stations heard associated with this BSSID. */
        public int clients;

        ScanAp(String ssid, String bssid, int channel, String security, int power) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.channel = channel;
            this.security = security;
            this.power = power;
        }

        public boolean isHidden() {
            return "<hidden>".equals(ssid);
        }

        /**
         * 0 open, 1 WEP, 2 WPA, 3 WPA2, 4 WPA3.
         *
         * Checked most-specific-last on purpose: an AP advertising a transition mode ("WPA2 WPA3")
         * is ranked by the weakest mode it still accepts, because that is the mode an attack can
         * actually take, and a WPA2+WPA3 AP is a WPA2 AP as far as a handshake is concerned.
         */
        public int securityRank() {
            String s = security == null ? "" : security.toUpperCase(Locale.US).trim();
            if (s.isEmpty() || s.contains("OPN")) return 0;
            if (s.contains("WEP")) return 1;
            if (s.contains("WPA2")) return 3;
            if (s.contains("WPA3")) return 4;
            if (s.contains("WPA")) return 2;
            return 0;   // unrecognised privacy value: show it, rank it as the weakest
        }

        public String securityLabel() {
            switch (securityRank()) {
                case 1: return "WEP";
                case 2: return "WPA";
                case 3: return "WPA2";
                case 4: return "WPA3";
                default: return "OPEN";
            }
        }

        /** Least secure first, then most clients, then strongest signal. */
        @Override public int compareTo(ScanAp o) {
            int c = Integer.compare(securityRank(), o.securityRank());
            if (c != 0) return c;
            c = Integer.compare(o.clients, clients);
            if (c != 0) return c;
            return Integer.compare(o.power, power);
        }
    }

    /** One sweep's results, as read back from the console mirror log. */
    public static final class ScanResult {
        public final List<ScanAp> networks = new ArrayList<>();
        /** A sweep has been started (its opening marker is present). */
        public boolean started;
        /** The sweep finished and its rows were printed. */
        public boolean complete;
        /** The guest answered that it has no wireless interface at all. */
        public boolean adapterMissing;
        /** Channels already reported, for a progress readout. */
        public int channelsDone;
        public boolean sawClients;

        public int totalSteps() {
            return STEPS;
        }

        /** True when the sweep stopped reporting and never closed: killed, VM down, adapter gone. */
        public boolean stalled(long startMillis, long timeoutMillis) {
            return started && !complete && System.currentTimeMillis() - startMillis > timeoutMillis;
        }
    }

    // ------------------------------------------------------------------- sweep

    /**
     * Sweep the band and print the results as framed rows.
     *
     * Framing is the whole point: the console mirror log is a stream of terminal output that also
     * carries everything else the guest prints, so results are delimited by markers
     * ({@code @@SCAN-START}, {@code @@CH n}, {@code @@SCAN-BEGIN}, {@code @@ROW}, {@code @@CLI},
     * {@code @@SCAN-END}) that nothing else in the guest emits.
     *
     * One channel-hopping capture, not one capture per channel: this adapter ignores a channel set
     * while a monitor interface is up, so thirteen fixed-channel captures all end up listening to
     * whichever channel the radio was already on. airodump's own hop is the thing that works here
     * (the mass-deauth sweep relies on it), and it is also what keeps the wait to a minute or so.
     * The tick markers are progress only - they say the capture is still running.
     *
     * Output goes to /dev/null because the picker shows a list - airodump's wide table is unreadable
     * on a phone - but the capture still runs under its own pty, or it writes 0-byte files.
     */
    public static String scanCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("echo \"@@RUN target-scan $(date +%s)\"; ");
        sb.append("echo '@@SCAN-START'; ");
        // A sweep needs the adapter, and a guest that has just booted has not been handed it yet.
        // Checking here turns a silent stall into a message the picker can show.
        sb.append("if ! iw dev ").append(IFACE).append(" 2>/dev/null | grep -q ").append(IFACE).append("; then ");
        sb.append("echo '@@SCAN-BEGIN'; echo '@@NOADAPTER'; echo '@@SCAN-END'; ");
        sb.append("echo '@@DONE target-scan'; ");
        sb.append("else ");
        sb.append(RootlessActions.lootRunDir("picker"));
        sb.append("echo \\\"@@LOOT $D\\\"; ");
        sb.append("ip link set ").append(IFACE).append(" down; ");
        sb.append("iw dev ").append(IFACE).append(" set type monitor; ");
        sb.append("ip link set ").append(IFACE).append(" up; sleep 1; ");
        // Unquoted $D throughout: the run path has no spaces, and quoting it here would nest quotes
        // inside the string that script -qec already has to wrap.
        sb.append("script -qec \\\"airodump-ng -w $D/pick --write-interval 2 --output-format csv ")
          .append(IFACE).append("\\\" $D/pick-tty.log >/dev/null 2>&1 & ");
        sb.append("sleep 8; echo \"@@CH 1\"; ");
        for (int tick = 2; tick <= STEPS; tick++) {
            sb.append("sleep ").append(SECONDS_PER_STEP).append("; echo \"@@CH ").append(tick).append("\"; ");
        }
        sb.append("pkill -x airodump-ng 2>/dev/null; sleep 1; ");
        sb.append("echo '@@SCAN-BEGIN'; ");
        sb.append(rowsAwk()).append(" $D/pick-01.csv 2>/dev/null; ");
        sb.append("echo '@@SCAN-END'; ");
        sb.append("echo '@@DONE target-scan'; ");
        sb.append("fi");
        return sb.toString();
    }

    /**
     * Emit one {@code @@ROW ssid bssid channel security power} per AP and one
     * {@code @@CLI bssid clients} per AP that has clients.
     *
     * The section counter is what keeps the station table out of the results: both sections begin
     * with a MAC address, and they are separated by a blank line, so the blank line - not the shape
     * of the row - is what says which table is being read. Client rows carry the AP's BSSID in
     * column 6, and are only counted when that field really is a BSSID, because a probe-request-only
     * station row has an empty one.
     */
    private static String rowsAwk() {
        return "awk -F', *' 'FNR==1{sec=0} /^[[:space:]]*$/{sec++;next} "
            + "$1 ~ /^([0-9A-Fa-f]{2}:){5}/ {"
            + "if(sec<=1){ if(!($1 in seen)){ seen[$1]=1; "
            + "e=$14; gsub(/[^[:print:]]/,\"\",e); gsub(/^ +/,\"\",e); gsub(/ +$/,\"\",e); "
            + "if(e==\"\")e=\"<hidden>\"; "
            + "p=$6; gsub(/^ +/,\"\",p); gsub(/ +$/,\"\",p); if(p==\"\")p=\"OPN\"; "
            + "printf \"@@ROW\\t%s\\t%s\\t%s\\t%s\\t%s\\n\", e, $1, $4, p, $9 } } "
            + "else { if($6 ~ /^([0-9A-Fa-f]{2}:){5}$/) cli[$6]++ } } "
            + "END{ for(b in cli) printf \"@@CLI\\t%s\\t%d\\n\", b, cli[b] }'";
    }

    // ------------------------------------------------------------------ reading

    /** Parse the newest framed sweep out of the console mirror log. Never null. */
    public static ScanResult readResults(Context c) {
        ScanResult result = new ScanResult();
        String text = readTail(RootlessConsole.consoleLog(c), TAIL_BYTES);
        if (text == null) return result;

        int begin = text.lastIndexOf("@@SCAN-BEGIN");
        int start = text.lastIndexOf("@@SCAN-START");
        if (start >= 0 && begin > start) {
            result.started = true;
            String progress = text.substring(start, begin);
            int at = 0;
            while ((at = progress.indexOf("@@CH ", at)) >= 0) {
                result.channelsDone++;
                at += 4;
            }
        }
        if (begin < 0) return result;

        int end = text.indexOf("@@SCAN-END", begin);
        String block = end > begin ? text.substring(begin, end) : text.substring(begin);
        result.complete = end > begin;
        result.adapterMissing = block.contains("@@NOADAPTER");

        String[] lines = block.split("\n");

        HashMap<String, Integer> clients = new HashMap<>();
        for (String line : lines) {
            if (!line.startsWith("@@CLI\t")) continue;
            String[] f = line.replace("\r", "").split("\t");
            if (f.length < 3) continue;
            try {
                clients.put(f[1].trim().toLowerCase(Locale.US), Integer.parseInt(f[2].trim()));
                result.sawClients = true;
            } catch (NumberFormatException ignored) {
                // a malformed count is not worth failing the whole list over
            }
        }

        for (String line : lines) {
            if (!line.startsWith("@@ROW\t")) continue;
            String[] f = line.replace("\r", "").split("\t");
            if (f.length < 6) continue;
            ScanAp ap = new ScanAp(f[1].trim(), f[2].trim().toLowerCase(Locale.US),
                parseInt(f[3], 0), f[4].trim(), parseInt(f[5], -100));
            Integer count = clients.get(ap.bssid);
            ap.clients = count == null ? 0 : count;
            result.networks.add(ap);
        }
        return result;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
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

    // ------------------------------------------------------------------ filters

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Filter names: {@code hidden}, {@code wpa3}, {@code open}, {@code clients}. */
    public static boolean filter(Context c, String name) {
        // Hiding suppressed SSIDs is on by default: they cannot be named, so they are not targets.
        return prefs(c).getBoolean("filter_" + name, "hidden".equals(name));
    }

    public static void setFilter(Context c, String name, boolean on) {
        prefs(c).edit().putBoolean("filter_" + name, on).apply();
    }

    /** The visible networks, sorted weakest-and-busiest first. */
    public static List<ScanAp> visible(Context c, ScanResult result) {
        boolean hideHidden = filter(c, "hidden");
        boolean hideWpa3 = filter(c, "wpa3");
        boolean hideOpen = filter(c, "open");
        boolean clientsOnly = filter(c, "clients");

        List<ScanAp> out = new ArrayList<>(result.networks.size());
        for (ScanAp ap : result.networks) {
            if (hideHidden && ap.isHidden()) continue;
            if (hideWpa3 && ap.securityRank() >= 4) continue;
            if (hideOpen && ap.securityRank() <= 1) continue;
            if (clientsOnly && ap.clients <= 0) continue;
            out.add(ap);
        }
        Collections.sort(out);
        return out;
    }

    /** Adopt a network as the target every targeted attack then uses. */
    public static void select(Context c, ScanAp ap) {
        RootlessActions.setTarget(c, ap.bssid, ap.channel, ap.isHidden() ? "" : ap.ssid);
    }
}
