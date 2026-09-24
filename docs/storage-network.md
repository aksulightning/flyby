# Persistent storage and outbound networking

## Storage

The app installs a 256 MiB raw ext4 seed into `filesDir/vm/default/disk.raw` only
when the disk does not exist. It verifies the decompressed size and SHA-256,
fsyncs a temporary file and atomically renames it. Existing disks are never
replaced on APK/guest updates, including when they are corrupt. Symlinks and
unexpected sizes are rejected; a failed mount is surfaced in Terminal/error state.
Uninstalling or clearing Android app data deletes the disk.

RVVM's MPL-2.0 NVMe controller exposes this image as `/dev/nvme0n1`. PCI ECAM is
at `0x30000000`, IO window at `0x03000000`, MMIO window `0x40000000–0x7fffffff`,
INTx IRQs 3–6. These constants live in `native/runtime/vm.h`; RVVM generates the
PCI device tree. The pinned Alpine kernel has built-in PCI/NVMe and its exact
ext4/jbd2/mbcache/crc16 modules are extracted into the initramfs.

The guest mounts the disk at `/data` and binds `/data/root` onto `/root`.
**Only `/data` and `/root` persist.** `/etc`, installed packages and the remainder
of the initramfs are still temporary. A persistent full root filesystem is a
separate migration, not claimed here. Stop asks BusyBox init to sync/unmount before
poweroff; forced termination can still require filesystem recovery. There is no
automatic formatting, destructive repair or disk reset.

`prepare-alpine-riscv64.py` now requires host `mke2fs` (e2fsprogs, GPL-2.0;
Ubuntu: `sudo apt-get install e2fsprogs`). It creates an unmounted seed, with fixed
UUID/hash seed/time and disabled lazy initialization, then packages gzip bytes as `disk.seed` (a `.gz` asset would be transparently
expanded and renamed by aapt).
The host mke2fs version may affect the exact filesystem bytes; the generated
manifest always hashes the actual seed. No unverified filesystem binary is used.
The developer seed lives in `out/guest/disk-seed.raw`; neither disks nor generated
assets are committed. No new runtime license dependency is introduced.

Run `python3 scripts/test-storage.py` after the native build. It boots two separate
RVVM processes with one disposable disk, writes a unique token to `/root` and
`/data`, shuts down, and verifies both files in the second boot. This test passed
locally. The Android instrumentation runner performs a Stop/Start `/root` check
as well; its execution result must be checked in CI.

## Network

The board includes RVVM's RTL8169 PCI NIC and **`tap_user.c`**, its MPL-2.0
userspace socket network backend. Despite the upstream filename, this build does
not compile `tap_linux.c`, open `/dev/net/tun`, create host TAP interfaces, or use
root/bridges. Android needs only the normal `INTERNET` permission. No incoming
port forwards are configured. The guest shares the app's outbound network access.

The matching `r8169`, `realtek` PHY and `af_packet` modules are included. A bounded
BusyBox DHCP request runs in the background so an offline network does not hold up
the shell. RVVM leases `192.168.0.100/24`, gateway `192.168.0.1`, DNS `1.1.1.1` and
`8.8.8.8`. This initial DNS setup is RVVM's default, not Android Private DNS
integration; networks blocking those resolvers require guest `/etc/resolv.conf`
configuration. Run `/etc/flyby-network` to retry DHCP. There is no network settings
UI, forwarding, VPN bypass or automatic proxy configuration.

A Goldfish RTC at `0x00101000`, IRQ 7 supplies host wall time for TLS verification.
`flyby-network-check` runs DNS lookup plus HTTP and HTTPS downloads of Alpine's
mirror list. HTTPS uses the packaged Alpine CA bundle and its ssl_client; no
certificate verification bypass is set. The script only prints NETWORK_OK after
both nonempty downloads succeed. `python3 scripts/test-network.py` invokes it in
the real guest. Android CI invokes the same command through TerminalSession using
`python3 scripts/test-android.py --network` (omit --network for offline devices).

Local verification reached a DHCP lease and NETWORK_READY. Direct UDP DNS is
unavailable in this execution workspace (downloads here use a host HTTPS proxy),
so external DNS/HTTP/HTTPS acceptance is delegated to CI rather than falsely
reported as locally passing. See docs/validation.md for final CI results.
