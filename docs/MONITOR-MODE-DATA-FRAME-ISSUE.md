# The monitor-mode scan issue: 0 data frames, 0 EAPOL — cause and resolution

Everything here was measured on an **unrooted Android 16 phone** through the Termux-Black stack
(app-owned QEMU guest + USB passthrough of a Realtek RTL8812AU). Symptom, the false leads we
chased, the two real causes, and the exact fix.

## Symptom

Monitor mode comes up and the radio demonstrably receives, but the capture is useless for
handshakes:

- `iw dev wlan0 info` reports `type monitor`; `iwconfig wlan0` shows the right frequency.
- `airodump-ng` lists APs, or a live `tcpdump -i wlan0` shows beacons, probe responses and
  ACKs — plenty of them.
- **Data frames never appear, and therefore EAPOL never appears**, no matter how many deauth
  bursts are sent. `aircrack-ng` never reports a handshake.

Measured baseline on the failing path: a 100 s capture on the target channel = 5,825 beacons,
5,376 ACKs, 1,536 deauth frames (ours), **0 data, 0 EAPOL**, and only 24 authentication frames
(each of which any tool will tell you is "the client trying to join" — and then it never
associates).

## False leads (do not repeat these)

1. **`aircrack-ng` printing `1 potential targets` / `Please specify a dictionary (option -w)`.**
   It prints that for any target it holds, *before* validating EAPOL. Not evidence of anything.
2. **`tcpdump`'s `EAPOL key (3)`.** The `(3)` is the EAPOL *type* = key, not the handshake message
   number. It is not an M1–M4 breakdown.
3. **Counting data frames with `grep -c Data`.** tcpdump prints protected data frames as
   `Data IV:<n>`, QoS data as `CF +QoS`, plain data as `Data (`. A narrow pattern reports a
   **false zero** on a capture that is full of traffic — and that false zero is what produces a
   bogus "the driver drops data frames" verdict.
4. **Assuming an idle channel.** A capture on a channel where nobody is transmitting shows
   beacons and ACKs and *nothing else*, which looks identical to a broken RX path. Confirm data
   frames exist on a channel where a device you control is transferring traffic (e.g. pull a file
   over the 5 GHz AP the test phone itself is associated to) before blaming the driver.
5. **Chasing deauth against a WPA3/PMF AP.** Forged deauth gets **radio-level ACKs from the
   client and is then discarded** (802.11w is mandatory on the WPA3 side of a transition-mode AP).
   69 ACKs on a directed deauth, and no re-association. High ACK counts are not success.
6. **PMKID as a general fallback.** Many WPA2 routers never put a PMKID in their first EAPOL
   message; `hcxpcapngtool` then reports `does not contain enough EAPOL M1 frames` and writes no
   hashes. That is a negative result, not a bug to retry.

## Cause 1 — USB RX aggregation (the known one)

The out-of-tree `88XXau` build drops data frames unless USB RX aggregation and power-save are
disabled **at module load time**. Baked into the guest at `/etc/modprobe.d/88xxau.conf`:

```
options 88XXau rtw_usb_rxagg_mode=0 rtw_dynamic_agg_enable=0 rtw_ips_mode=0 rtw_lps_level=0 rtw_country_code=US
```

Plus, because the image ships a second driver for the same chip and both bind nondeterministically:

```
# /etc/modprobe.d/blacklist-8812au.conf
blacklist 8812au
```

Verify what is actually in effect (module parameters apply at LOAD time; `modinfo` only lists
what exists):

```
for p in rtw_usb_rxagg_mode rtw_dynamic_agg_enable rtw_ips_mode rtw_lps_level; do
  echo "$p = $(cat /sys/module/88XXau/parameters/$p 2>/dev/null || echo MISSING)"
done
# expect 0 0 0 0, and lsmod showing 88XXau alone
```

Measured effect of this fix alone, on a passive capture with no deauth:
**0 data / 0 EAPOL → 991 data / 10 EAPOL.**

## Cause 2 — the data path dies after repeated monitor/airodump cycles (the one that cost the most)

Those module options are **necessary but not sufficient.** After the interface has been through
several `iw dev wlan0 set type monitor` / `airodump-ng` / deauth cycles, RX keeps delivering
beacons, probe responses and ACKs while **data frames silently stop arriving** — so a client can
associate (association frames *are* captured) and the 4-way handshake still never shows up,
because EAPOL rides inside data frames.

This is a driver-state fault, and the recovery is a USB re-enumeration. Inside the guest, with no
hardware access needed:

```bash
DEV=$(for d in /sys/bus/usb/devices/*/; do
        v=$(cat $d/idVendor 2>/dev/null); p=$(cat $d/idProduct 2>/dev/null)
        [ "$v" = "0bda" ] && [ "$p" = "8812" ] && basename $d
      done)
ip link set wlan0 down
echo "$DEV" > /sys/bus/usb/drivers/usb/unbind; sleep 3
echo "$DEV" > /sys/bus/usb/drivers/usb/bind
```

The USB core re-probes the device, `wlan0` is recreated with the modprobe options reapplied, and
the data path comes back. (`modprobe -r 88XXau` + replug or a physical replug also works; a
physical replug additionally re-triggers Android's `USB_DEVICE_ATTACHED` permission grant.)

### Evidence — same channel, same AP, same window length, 100 seconds apart

| | before reset | after reset |
|---|---|---|
| data frames | **0** | **4,008** |
| EAPOL frames | **0** | **24** |
| association frames | 9 (client joined, handshake never seen) | 9 |
| verdict | nothing to crack | **complete 4-way handshake: M1 ×6, M2 ×6, M3 ×6, M4 ×6** |

`hcxpcapngtool` on the after-capture:

```
EAPOL messages (total)...................: 24
EAPOL RSN messages.......................: 24
EAPOL M1 messages (total)................: 6
EAPOL M2 messages (total)................: 6
EAPOL M3 messages (total)................: 6
EAPOL M4 messages (total)................: 6
```

and it wrote a hashcat-ready hash (type `02` = EAPOL 4-way, PSK-based ⇒ dictionary-crackable):

```
WPA*02*<PMKID/MIC>*<AP BSSID>*<client MAC>*<ESSID hex>*…
```

A `WPA*01*` line would be a PMKID instead.

## What actually produced the handshake

**No deauth at all.** The forged-deauth route was a dead end (see false lead 5). The handshake
was captured by arming the capture and letting the client reconnect for real — a user toggling
Wi-Fi off/on, or a client roaming. Arm the capture *first*, keep it running across attempts, and
let the reconnect happen inside the window.

## Working procedure, end to end (guest side)

```bash
# 1. reset the adapter's data path after any previous monitor/airodump session
#    (see the unbind/bind block above)

# 2. monitor mode + channel. Use iwconfig for the channel:
#    this driver ignores the nl80211 setter — `iw dev wlan0 set channel N` returns
#    success and leaves the old channel, and fails -16 (busy) while the link is down.
ip link set wlan0 down
iw dev wlan0 set type monitor
ip link set wlan0 up
sleep 2
iwconfig wlan0 channel 10
iw dev wlan0 info | grep -E 'type|channel'     # confirm BEFORE capturing

# 3. arm the capture (tcpdump gives the full radiotap record)
tcpdump -i wlan0 -nn -e -w /root/loot/airodump/diag/cap.pcap &

# 4. let the client reconnect — no deauth needed; toggle Wi-Fi on a device you own

# 5. count frame classes correctly, then validate with hcxpcapngtool
tcpdump -r /root/loot/airodump/diag/cap.pcap -nn | grep -icE 'Data IV:|CF \+QoS|Data \('
tcpdump -r /root/loot/airodump/diag/cap.pcap -nn | grep -ic eapol
hcxpcapngtool -o /root/loot/airodump/diag/cap.hc22000 /root/loot/airodump/diag/cap.pcap     # M1..M4 counts + hash file
```

Two more traps that cost time:

- **`airodump-ng` needs a controlling terminal on this setup.** Without a pty it writes a
  **0-byte capture** and ignores SIGINT (a plain `timeout -s INT` never ends it), which reads as
  a broken adapter. Run it as
  `timeout -k 5 120 script -qec "airodump-ng -c 10 -w /root/loot/airodump/diag/cap wlan0" /root/loot/airodump/diag/ad.log`.
- **Sweep, do not assume 1/6/11.** A target sat on channel 10; APs must be enumerated by fixing
  each channel with `iwconfig` and reading the `CH` column out of the CSV. `iw dev wlan0 scan` is
  unusable here (aborts, and the guest's regulatory domain is `country 00` = passive scan only).

## Resolution summary

| layer | change |
|---|---|
| guest `/etc/modprobe.d/88xxau.conf` | `rtw_usb_rxagg_mode=0 rtw_dynamic_agg_enable=0 rtw_ips_mode=0 rtw_lps_level=0 rtw_country_code=US` — baked into the shipped rootfs |
| guest `/etc/modprobe.d/blacklist-8812au.conf` | `blacklist 8812au` — stop two drivers fighting over one chip |
| driver state | USB re-enumeration (`unbind`/`bind`) before a capture session; required after monitor/airodump cycling |
| capture tooling | `airodump-ng` under a pty; channel via `iwconfig`; count frames by the labels tcpdump really prints; validate with `hcxpcapngtool`, never with aircrack's prompts |
| attack choice | let the client reconnect for real instead of forging deauth against PMF |

`scripts/guest-bringup.sh` in this repo performs the whole sequence (reset → monitor → channel →
arm → validate) as one command.
