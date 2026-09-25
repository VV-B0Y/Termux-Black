# Lessons learned

Hard-won lessons from building this stack. Several cost days; each one is written as a rule so it
does not get re-learned.

## Wireless

1. **Internal monitor mode is not injection.** A rooted phone with an internal-monitor-mode patch
   (e.g. the Qualcomm Android patch used by NetHunter builds) can bring monitor mode up and receive
   fine while **injected frames never go out** — so deauth does nothing and no handshake is ever
   forced. Do not treat "monitor mode works" as "this radio can transmit". A USB adapter passed
   through to the guest sidesteps the whole class of problem, and is the design here.
2. **Zero data frames is a driver-state fault until proven otherwise.** Beacons, probe responses and
   ACKs arriving while data frames do not is *not* a broken passthrough, and repeating a deauth
   cannot fix it. Reset the adapter's USB state first, then capture. Full write-up:
   [MONITOR-MODE-DATA-FRAME-ISSUE.md](MONITOR-MODE-DATA-FRAME-ISSUE.md).
3. **Channel control is driver-specific.** This out-of-tree RTL8812AU build ignores the nl80211
   channel setter; `iwconfig wlan0 channel N` is what works, and a type transition can silently
   drop it. Confirm `type monitor` + the channel *before* every capture, never after.
4. **Sweep, do not assume 1/6/11.** Enumerate APs by locking each channel and reading the `CH`
   column of the output; a target on a non-standard channel is invisible otherwise.
5. **Verify the capture, not the attempt.** A growing capture file proves nothing; a non-empty one
   proves nothing; `aircrack-ng` prompts prove nothing. Count frame classes and validate with
   `hcxpcapngtool` (M1–M4 counts + a written hash).
6. **PMF turns a deauth into noise.** On a WPA3/SAE-capable AP the client ACKs forged deauth frames
   at the radio level and discards them. A real reconnect (user toggles Wi-Fi, client roams) is the
   reliable trigger, and it needs no attack at all.

## QEMU guest, USB and tooling

7. **A QEMU built for Android needs no root and no LD_PRELOAD shim.** Build it with the NDK against
   Android's own loader and link slirp as an external `libslirp.so`; the shim belongs to the
   distro-QEMU-on-Termux path and is unnecessary here. Ship the library next to the binary and set
   `LD_LIBRARY_PATH`.
8. **Order is VM first, dongle second** — attach after the QMP socket exists. Gate on `test -S
   qmp.sock`, never on the pid: a live QEMU whose sockets were removed can never accept an attach and
   the failure is silent.
9. **The app must own install *and* launch.** If a user has to run a shell script the app is not
   finished. With an app-owned guest there is no launcher in `$PREFIX/bin`, so a "command not found"
   for the old helper is expected, not a failed install.
10. **`airodump-ng` needs a controlling terminal** on this guest: without a pty it writes a 0-byte
    capture and ignores SIGINT. Run it under `script -qec "…"` with `timeout -k`.
11. **Count frames by the labels tcpdump really prints** — `Data IV:` (protected), `CF +QoS` (QoS),
    `Data (`. A narrower grep reports a false zero on a full capture and sends you chasing a driver
    bug that does not exist.

## Transfer, backup and verification

12. **Verify copied files by hash, not by presence.** In practice a copy "succeeded" and left a
    **0-byte file** (the empty-input sha256 `e3b0c442…`), and a large download was silently truncated
    while `ls` looked plausible. Check `sha256sum` against the published digest every time, and
    `tar tf` an archive before trusting it — a truncated tar still exits 0 and lists its earlier
    entries.
13. **A shallow clone cannot be pushed.** `remote: fatal: did not receive expected object …` /
    `remote unpack failed: index-pack failed` means the local clone's history stops at a shallow
    boundary. Either `git fetch --unshallow`, or commit the working tree as a fresh root commit for
    the new repo (what this repo does).

## Working with the user / process

14. **Batch destructive steps into ONE command and one approval.** Splitting them into several
    prompts gets them denied or timed out, and a denied command must not be retried or rephrased to
    reach the same outcome.
15. **Durable knowledge belongs in the repo and in skills, not only in a session.** This project
    exists in its current form partly because a lost session had to be reconstructed from logs; the
    conclusions that survived were the ones written down.
16. **Never publish real network identifiers or keys.** SSIDs, BSSIDs, client MACs, device serials
    and LAN addresses stay out of the repo (they identify a home and its neighbours); keep keys out
    entirely. Note that this tree still carries `app/testkey_untrusted.jks`, the upstream Termux
    *test* key that upstream publishes and the release signing config expects — it is not a secret,
    but a fork that signs with its own key should replace it rather than keep it.
