# Hands-free, one-APK install

Design goal: **a user installs one APK, taps once, and ends up with a monitor-mode Wi-Fi
interface.** No PC. No adb. No GitHub token. No shell script to hunt down and run.

## The flow

1. **Install the APK** (arm64-v8a build from the release, or build it yourself).
2. **Open it.** `TermuxActivity.onCreate` → `maybeRunRootlessSetup()` finds the VM assets missing
   and offers the download. Nothing is bundled in the APK — the guest is ~1.2 GB, far past what an
   APK can carry.
3. **Download.** `ManifestService` fetches `stryker_manifest.json` from the release of this repo
   over HTTPS, then `VerifiedDownloader` pulls each asset to a `.part` file, checks the manifest's
   sha256, and only then moves it into place. Retries resume rather than restart. A cached copy of
   the manifest keeps a reinstall working when the network is down.
4. **Install.** `QemuInstaller` places the assets in `filesDir/rootless/` and gunzips
   `rootfs.imgz` → `rootfs.img`. No disk-growing step: a QCOW2 grows itself.
5. **Start.** `RootlessVm.start()` writes the pidfile and unix sockets, then daemonises QEMU. The
   app waits for `qmp.sock` before it will attach anything.
6. **Plug in the adapter.** The `USB_DEVICE_ATTACHED` intent auto-grants USB permission via
   `device_filter.xml` and attaches the device to the guest over QMP. `wlan0` appears inside the
   VM with the driver already bound and the module options already applied.
7. **Use it.** Tap **Console** — an in-app root shell on the guest's serial line, no credentials
   and no SSH client. `iw dev`, `iwconfig wlan0 channel 10`, `airodump-ng`, captures to disk.

## What makes it credential-free

The asset host is a **public** repo, so the first run is a plain HTTPS GET. A private repo's
release assets require an `Authorization` header, which would mean baking a token into the APK —
extractable from the APK, revocable out from under users, and dead the moment it expires. That is
why this project is public.

## What still needs a human (and why)

- **Plugging in the adapter.** Android USB permission is granted on the attach intent. A replug is
  the reliable retry if the first attach does nothing.
- **A client to capture.** A handshake needs a client that (re)connects; the app cannot conjure
  one. See [MONITOR-MODE-DATA-FRAME-ISSUE.md](MONITOR-MODE-DATA-FRAME-ISSUE.md) — and note that
  the app **does** detach/attach the adapter, which is exactly the USB re-enumeration that
  restores the data-frame path when it dies. A fully automated recovery would call that sequence
  automatically when a capture yields zero data frames; today it is one tap (Detach → Attach) or
  the guest script in [`scripts/guest-bringup.sh`](../scripts/guest-bringup.sh).

## First-run gotchas

- **Budget ~2 GB free** on the device (download + unpacked rootfs).
- **The download takes minutes** on Wi-Fi; progress is shown in the VM screen. A silent spinner
  reads as a hang.
- **`vm: command not found` in the Termux shell is expected** — the app owns install and launch,
  so there is no `vm` helper in `$PREFIX/bin`. Check the VM screen instead.
- **First run wants network**, for both the download and, in the guest, nothing else — the driver
  and the module options are already inside the shipped rootfs.
