# Termux-Black

Termux with a QEMU VM framework that unlocks **monitor mode, packet injection and other
root-gated tooling on a completely rootless phone.**

No root. No unlocked bootloader. No PC, no adb, no shell scripts to find and run — one APK
installs a Debian guest, boots it, and passes a USB Wi-Fi adapter straight through to it.

```
┌─────────────────────────────────────────────┐
│ unrooted Android phone                      │
│                                             │
│  Termux-Black APK (com.termux)              │
│    ├── first run: downloads ~1.2 GB of VM   │
│    │   assets, sha256-verified              │
│    ├── installs + launches a QEMU guest     │
│    │   under the app's own filesDir         │
│    ├── USB passthrough over QEMU QMP        │
│    └── in-app guest console (no SSH needed) │
│                                             │
│  Debian trixie arm64 guest                  │
│    └── wlan0 = your Realtek dongle          │
│        monitor mode / injection / captures  │
└─────────────────────────────────────────────┘
```

## What it does

- **Rootless QEMU VM** — a Debian trixie arm64 guest runs as the app's own uid. Verified on a
  fully non-rooted Android 16 device (no `su` anywhere in the chain).
- **USB passthrough into the guest** — Android `UsbManager` → usbfs fd → QEMU `usb-host` over the
  QMP socket, so `0bda:8812` (RTL8812AU) and friends show up as `wlan0` inside the VM.
- **Monitor mode + injection that actually produce handshakes** — with the driver tuning and the
  data-path reset documented in [docs/MONITOR-MODE-DATA-FRAME-ISSUE.md](docs/MONITOR-MODE-DATA-FRAME-ISSUE.md).
  A complete WPA 4-way handshake captured through this stack, plus a hashcat-ready `WPA*02*` hash.
- **App-owned install and control** — download, verify, install, start/stop, open a console,
  attach/detach the adapter: all in-app. A user never needs a terminal.
- **Hands-free, credential-free first run** — assets are fetched from the public releases of this
  repo over HTTPS. Nothing is bundled in the APK and no GitHub token is baked into it.

## Install (end user)

1. Install `Termux-Black.apk` (arm64-v8a build is attached to the
   [release](https://github.com/VV-B0Y/Termux-Black/releases/tag/rootless-main)).
2. Open it and tap **VM** in the drawer → **Start VM**. On first run it offers the ~1.2 GB asset
   download, verifies every file against the manifest sha256, unpacks the rootfs and boots.
3. Plug the USB Wi-Fi adapter into the phone *after* the VM is up (the `USB_DEVICE_ATTACHED`
   intent auto-grants permission and attaches it). Open **Console** and run `iw dev`.

Nothing else is required — no PC, no adb, no Termux scripting.

## Repository layout

| path | what |
|---|---|
| `app/src/main/java/com/termux/app/rootless/` | the VM framework: install, launch, console |
| `app/src/main/java/com/termux/app/usb/` | USB passthrough: QMP client, attach receiver, shim |
| `app/src/main/cpp/` | native shim + JNI for the USB/terminal paths |
| `docs/` | architecture, hands-free install, and the monitor-mode issue write-up |
| `drivers/` | the working RTL8812AU driver (`88XXau`), its `.ko`, and the module options |
| `release/` | the asset manifest + checksums the APK consumes |
| `scripts/` | guest-side bring-up / repair script (dev + troubleshooting path) |
| `app/src/main/res/xml/device_filter.xml` | USB VID:PID whitelist for auto-attach |

## VM assets (published on this repo's release)

`rootless-main` carries every file the app downloads. Sizes and sha256 are in
[release/SHA256SUMS](release/SHA256SUMS); the app reads the same values from
[release/stryker_manifest.json](release/stryker_manifest.json).

| asset | size | role |
|---|---|---|
| `rootfs.imgz` | 1,051,932,051 | Debian trixie arm64 rootfs, gzip-compressed qcow2, driver-tuned |
| `qemu-system-aarch64` | 43,800,304 | QEMU built with the NDK against Android's own linker |
| `Image` | 37,660,608 | guest kernel `6.12.107+deb13-arm64` |
| `initrd.img` | 36,485,420 | guest initrd |
| `libslirp.so` | 1,145,496 | user-mode networking, linked externally |
| `stryker_manifest.json` | — | per-asset `url` / `sha256` / `size` |

The rootfs is a **gzip-compressed QCOW2**, not a raw image: launch it with `format=qcow2`.
The RX-aggregation fix and the module blacklist are baked into `/etc/modprobe.d` inside that
image, so a fresh install captures data frames with no guest configuration at all.

## Build

```
# Android SDK + JDK 17; the guest assets are NOT needed to build
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug --console=plain
# -> app/build/outputs/apk/debug/termux-app_apt-android-7-debug_<abi>.apk
```

Gradle 9.2.1 (wrapper), AGP 8.13.2, `versionCode 118`, `versionName 0.118.0`.
Debug builds are signed with the keystore configured in `app/build.gradle`
(`app/testkey_untrusted.jks`, the upstream test key).

**Signing gotcha:** Termux add-ons (`com.termux.api`, `.x11`, `.styling`) declare
`sharedUserId="com.termux"`, so every package named `com.termux` must carry the *same*
certificate. A build with a different key cannot coexist with store-signed add-ons — install
add-ons built from the same key, or drop them. Dropping them does not bring Termux:API back.

## Proven on

- Moto G Stylus 2025 (`mona`), **Android 16, unrooted** — VM boots, adapter passes through,
  monitor mode, injection, handshake captured.
- Guest: Debian trixie arm64, kernel `6.12.107+deb13-arm64`, NIC `enp0s2` on SLIRP.
- Adapter: Realtek RTL8812AU `0bda:8812`, driver `88XXau v5.13.6-15-gc40b977e2` (see `drivers/`).

## License and credits

GPLv3 — this is a fork of [termux/termux-app](https://github.com/termux/termux-app) (the original
README is preserved at `docs/UPSTREAM-TERMUX-README.md`). The rootless VM engine is ported from
[zalexdev/strykerapp](https://github.com/zalexdev/strykerapp); the driver is
[aircrack-ng/rtl8812au](https://github.com/aircrack-ng/rtl8812au).

Use the wireless tooling only on networks you own or are authorised to test.
