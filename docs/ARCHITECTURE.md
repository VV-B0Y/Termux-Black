# Architecture

How a rootless Android app ends up with a monitor-mode Wi-Fi interface inside a Debian VM.

## Pieces

```
TermuxActivity.create()  →  maybeRunRootlessSetup()
   │  assets missing? offer the download, then start the guest
   ▼
RootlessActivity  (VM control screen: Start / Stop / Console / Attach / Detach)
   ▼
QemuInstaller ── ManifestService ── Net ── RemoteManifest ── VerifiedDownloader
   │  resolve assets, sha256-verify, retry .part downloads, gunzip the rootfs
   ▼
RootlessVm.start()  →  QEMU (daemonised, pidfile + unix sockets under filesDir/rootless/)
   ▼
TermuxService.ACTION_ATTACH_USB  →  UsbPassthroughManager  →  QMP usb-host  →  guest wlan0
   ▼
RootlessConsoleActivity  →  serial.sock  →  root shell on ttyAMA0
```

| class | job |
|---|---|
| `RootlessPaths` | `filesDir/rootless/` layout + hostfwd ports |
| `RootlessEndpoints` | where the assets live (this repo's release) |
| `ManifestService`, `RemoteManifest` | manifest fetch with a cached fallback |
| `Net`, `VerifiedDownloader` | HTTPS-only, sha256-verified, resuming downloads |
| `QemuDownloader`, `QemuInstaller` | resolve → install → gunzip the rootfs |
| `RootlessVm` | QEMU argv, spawn, pidfile/socket checks, stop |
| `RootlessActivity` | control screen |
| `RootlessConsoleActivity` | in-app guest console over `serial.sock` |
| `QmpClient`, `UsbPassthroughManager`, `UsbAttachReceiver` | USB attach over QMP |
| `UsbShimInstaller` | deploys the native shim the terminal path needs |

## The guest is owned by the app, not by Termux's home

Everything lives under the app's private `filesDir/rootless/`:

```
filesDir/rootless/
  qemu-system-aarch64   Image   initrd.img   libslirp.so   rootfs.img
  qmp.sock   serial.sock   term.sock   vm.pid   boot.log   serial.log
```

Because the app owns install *and* launch, there is no `vm` script in `$PREFIX/bin` and nothing to
type. `vm: command not found` is the expected state, not a failed install. The proof of a good
install is the app-owned tree above plus a live `vm.pid`.

## Launch command (read back off the running VM)

```
/data/user/0/com.termux/files/rootless/qemu-system-aarch64 -nodefaults
  -M virt,gic-version=3 -cpu max,sve=off,pmu=off,pauth=off
  -accel tcg,thread=multi,tb-size=512 -smp 4 -m 2048
  -kernel .../Image -initrd .../initrd.img
  -append "root=/dev/vda rw rootwait rootflags=noatime console=ttyAMA0 loglevel=4 mitigations=off"
  -drive file=.../rootfs.img,if=none,id=drive0,format=qcow2,cache=writeback,aio=threads
  -device virtio-blk-pci,drive=drive0
  -netdev user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:1050-:1050,hostfwd=tcp:127.0.0.1:1051-:1051,
          hostfwd=tcp:127.0.0.1:1052-:1052,hostfwd=tcp:127.0.0.1:2222-:22
  -device virtio-net-pci,netdev=net0,romfile=
  -device qemu-xhci,id=usbhc0,p2=8,p3=8
  -device virtio-rng-pci
  -chardev socket,id=serial0,path=.../serial.sock,server=on,wait=off,logfile=.../serial.log
  -serial chardev:serial0
  -device virtio-serial-pci -chardev socket,id=term0,path=.../term.sock,server=on,wait=off
  -device virtconsole,chardev=term0,name=org.termux.term
  -display none
  -qmp unix:.../qmp.sock,server,nowait -daemonize -pidfile .../vm.pid
```

Three of these are load-bearing and must not be "fixed":

- **`format=qcow2`** — the published `rootfs.imgz` is a *gzip-compressed QCOW2*, not a raw image.
  (This is a deliberate divergence from strykerapp, whose rootfs is raw; there is no `qemu-img`
  in Termux to convert it, and a qcow2 grows itself, so their disk-growing step is not ported.)
- **`romfile=` (empty) on virtio-net** — removes any need for QEMU's external ROM files, so no
  `-L <datadir>` is required. Without it QEMU aborts on `failed to find romfile "efi-virtio.rom"`
  *even though the file exists at the stated path*.
- **the kernel cmdline omits `net.ifnames=0`** — the shipped guest is provisioned for `enp0s2`.
  Adding the flag renames the NIC and networking silently never comes up.

GUI access to the guest (no adapter needed): `adb forward tcp:12222 tcp:2222`, then
`ssh -p 12222 root@127.0.0.1`. The app's console path does not use SSH at all — it attaches to
`serial.sock`, where the guest auto-logs-in as root on `ttyAMA0`.

## USB passthrough

`UsbPassthroughManager.isWifiCandidate()` accepts interface class `0xFF` (vendor-specific) or
`0xE0` (wireless controller); `res/xml/device_filter.xml` whitelists VID:PIDs for the
`USB_DEVICE_ATTACHED` auto-grant. The device is handed to QEMU as `usb-host` over QMP:

```
QMP: device_add usb-host,id=termux_usb_1003,hostbus=1,hostaddr=3,hostdevice=/dev/bus/usb/001/003
```

Things that are easy to get wrong:

- **Order: VM first, dongle second.** Attaching before `qmp.sock` exists fails with
  `QmpClient: connect failed: No such file or directory` → `Attached 0 USB Wi-Fi dongles to VM`.
  That is sequencing, not a passthrough bug. Gate on `test -S .../qmp.sock`, never on the pid: a
  running QEMU whose sockets were removed can never accept an attach, and the failure is silent.
- **The dongle must be plugged in *after* the guest is up** the first time on a fresh install, so
  the `USB_DEVICE_ATTACHED` intent grants the USB permission (a physical replug is also the
  reliable retry when a broadcast-triggered attach does nothing).
- **`am force-stop com.termux` kills the app's VM too** (Android kills the cgroup even though the
  VM is daemonised with PPID 1). The app relaunches it; wait for `qmp.sock` and re-trigger the
  attach. From adb, the attach trigger needs the explicit component:
  `am broadcast -a com.termux.app.ATTACH_USB -n com.termux/com.termux.app.usb.UsbAttachReceiver`.
- **QEMU's QMP socket serves one client at a time** — a host-side QMP query times out while the
  app holds the connection, and that timeout is itself evidence the link is live.

## Guest network

SLIRP user-mode networking; the guest gets `10.0.2.15/24` on `enp0s2` via DHCP. The guest's DNS is
broken until `/etc/resolv.conf` points at a public resolver by IP
(`printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf`) — SLIRP's `10.0.2.3`
does not answer, which shows up as `apt` failing with `Temporary failure resolving`.

## Adapter driver

The shipped guest kernel is `6.12.107+deb13-arm64`. `rtw88_8812au` only landed in-tree at 6.14, so
RTL8812AU support is the out-of-tree `88XXau` module (DKMS lineage), already built and installed
inside the rootfs at
`/lib/modules/6.12.107+deb13-arm64/kernel/drivers/net/wireless/88XXau.ko`, with the module options
and the `8812au` blacklist in `/etc/modprobe.d`. See [../drivers/README.md](../drivers/README.md)
and [MONITOR-MODE-DATA-FRAME-ISSUE.md](MONITOR-MODE-DATA-FRAME-ISSUE.md) — the module options alone
do not guarantee data frames; the USB re-enumeration recovery is documented there too.
