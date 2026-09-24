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
UUID/hash seed/time and disabled lazy initialization, then packages it with gzip.
The host mke2fs version may affect the exact filesystem bytes; the generated
manifest always hashes the actual seed. No unverified filesystem binary is used.
The developer seed lives in `out/guest/disk-seed.raw`; neither disks nor generated
assets are committed. No new runtime license dependency is introduced.

Run `python3 scripts/test-storage.py` after the native build. It boots two separate
RVVM processes with one disposable disk, writes a unique token to `/root` and
`/data`, shuts down, and verifies both files in the second boot. This test passed
locally. The Android instrumentation runner performs a Stop/Start `/root` check
as well; its execution result must be checked in CI.
