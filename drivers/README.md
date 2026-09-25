# Wi-Fi adapter driver (RTL8812AU) — the working build

The guest kernel is `6.12.107+deb13-arm64`. In-tree `rtw88_8812au` support for RTL8812AU only
landed in Linux 6.14, so this stack uses the out-of-tree **`88XXau`** module (the
[aircrack-ng/rtl8812au](https://github.com/aircrack-ng/rtl8812au) DKMS lineage), already built for
that exact kernel and shipped **inside** `rootfs.imgz` — an end user installs nothing.

## What's here

| file | what it is |
|---|---|
| `88XXau.ko` | the built module, verbatim from the guest: `/lib/modules/6.12.107+deb13-arm64/kernel/drivers/net/wireless/88XXau.ko` (5,365,280 B) |
| `88xxau.conf` | module options — goes to `/etc/modprobe.d/88xxau.conf` in the guest |
| `blacklist-8812au.conf` | goes to `/etc/modprobe.d/blacklist-8812au.conf` in the guest |

```
version:    v5.13.6-15-gc40b977e2.20210629
srcversion: E3C907A9EBE7666D4DA519C
vermagic:   6.12.107+deb13-arm64 SMP mod_unload modversions aarch64
```

## Module options (load-time only)

```
# /etc/modprobe.d/88xxau.conf
options 88XXau rtw_usb_rxagg_mode=0 rtw_dynamic_agg_enable=0 rtw_ips_mode=0 rtw_lps_level=0 rtw_country_code=US

# /etc/modprobe.d/blacklist-8812au.conf
blacklist 8812au
```

Why each one:

- `rtw_usb_rxagg_mode=0` — **the critical one.** Disables USB RX aggregation. Left on, the driver
  delivers beacons and ACKs but no data frames, and EAPOL rides inside data frames, so a handshake
  is impossible no matter how much you deauth. Measured: 0 data / 0 EAPOL → 991 data / 10 EAPOL on
  a purely passive capture.
- `rtw_dynamic_agg_enable=0` — dynamic aggregation off for the same reason.
- `rtw_ips_mode=0`, `rtw_lps_level=0` — no power save; power saving stalls RX on a
  monitor interface that never associates.
- `rtw_country_code=US` — a sane regulatory domain instead of `country 00` (which is
  passive-scan-only and leaves `iw dev wlan0 scan` unusable).
- `blacklist 8812au` — the image also ships a second driver for the same chip; when both load,
  `cfg80211` has two users and which one binds is not deterministic, so monitor behaviour changes
  between attach cycles. Keep exactly one.

**Parameters apply at module LOAD time.** Editing the file does nothing to an already-loaded
module — unload and re-attach (a physical replug is the reliable trigger). Verify what is live in
sysfs, because `modinfo` only lists what exists, not what is in effect:

```bash
for p in rtw_usb_rxagg_mode rtw_dynamic_agg_enable rtw_ips_mode rtw_lps_level rtw_country_code; do
  echo "$p = $(cat /sys/module/88XXau/parameters/$p 2>/dev/null || echo MISSING)"
done
# expect 0 0 0 0 US, with lsmod listing 88XXau alone
```

`MISSING` means the module is not loaded at all (adapter detached) — not that a value was refused.

## Monitor mode and the channel

```bash
ip link set wlan0 down
iw dev wlan0 set type monitor
ip link set wlan0 up
iwconfig wlan0 channel <n>        # NOT `iw dev wlan0 set channel` — this build ignores nl80211
iw dev wlan0 info | grep type     # expect: type monitor
```

`iw dev wlan0 set channel N` returns success and silently leaves the interface on its previous
channel (and fails `-16` with the link down). The symptom downstream is `airodump-ng` pinned at
`CH 0` with no APs — which looks like a passthrough failure and is not one. Re-assert the channel
before every capture; an interface-type transition can drop it.

`iw dev wlan0 scan` does not work here (aborts; `country 00` = passive scan). Enumerate APs by
fixing each channel with `iwconfig` and reading the `CH` column out of an airodump CSV.

## Rebuilding the module after a kernel bump

The `.ko` is kernel-ABI-tied: it must be rebuilt for whatever kernel the guest boots. Inside the
guest, as root:

```bash
apt-get update && apt-get install -y build-essential git dkms bc linux-headers-$(uname -r) firmware-realtek
git clone https://github.com/aircrack-ng/rtl8812au.git && cd rtl8812au && make dkms_install
modprobe 88XXau && iw dev        # expect wlan0
```

Then re-apply `88xxau.conf` / `blacklist-8812au.conf` and re-attach the adapter. A bump of the
guest kernel to ≥ 6.14 would make RTL8812AU work in-tree and obsolete all of this.

## Other chips

`isWifiCandidate()` in `UsbPassthroughManager` only accepts interface class `0xFF`
(vendor-specific) or `0xE0` (wireless controller), which is why odd dongles can be detected in the
UI but never reach the VM. RTL8187 (`0bda:8187`) has its driver and firmware in the 6.12 guest but
is blocked by that same filter.

## Captures

Driver state matters as much as driver options: after repeated monitor/airodump cycles the data
path can go dead until the adapter is re-enumerated. See
[../docs/MONITOR-MODE-DATA-FRAME-ISSUE.md](../docs/MONITOR-MODE-DATA-FRAME-ISSUE.md) and
[`../scripts/guest-bringup.sh`](../scripts/guest-bringup.sh).
