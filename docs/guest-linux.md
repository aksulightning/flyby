# Alpine riscv64 guest

Run `python3 scripts/prepare-alpine-riscv64.py`. It produces `kernel`, `firmware`,
`initrd`, `disk.seed` and a SHA-256 manifest in `app/src/main/assets/vm/` using pinned official
Alpine archives. These generated resources are packaged into the APK, not Git.
`out/downloads` caches the original archives. No random prebuilt image is accepted.

Inputs:
- Alpine Edge minirootfs 20260805 riscv64 (`edge/releases/riscv64`).
- Alpine Edge linux-lts 6.18.53-r0: gzip-compressed Image and its package kernel config.
- Alpine Edge OpenSBI 1.9-r0: `generic/firmware/fw_jump.bin`.

Exact URLs/checksums are source-controlled in the script. Kernel config confirms
built-in initramfs, gzip, devtmpfs and 8250 serial console. Optional ISA features
are discovered at runtime from the generated FDT. No patched Linux ABI or fake
Alpine identification is used. To rebuild instead of using official packages,
check out each exact aports revision in `docs/licenses.md` and use that package's
APKBUILD, sources, config and patches with Alpine's `abuild` for riscv64. That full
cross-build has not been run here; provisioning and booting the official artifacts
have been run. Do not substitute arbitrary firmware or kernels without retesting.

The script creates a root-owned newc archive with fixed timestamps and gzip mtime.
It uses the real minirootfs and installs a small `/init`: mount proc/sys/devtmpfs/
devpts, set hostname, exec BusyBox init. Its inittab starts a login shell on ttyS0
and a respawning private control daemon on ttyS1. The shell prints
`FLYBY_ALPINE_READY` and uses `TERM=xterm-256color`. This is an intentional development
autologin root environment, no password/login management. Alpine's default SSL CA
bundle is retained for HTTPS. The RTL8169 NIC uses RVVM user-mode sockets.
Matching ext4, overlay, fuse, realtek PHY, r8169, af_packet and 9p kernel modules
are extracted from the pinned linux-lts APK, together with their transitive
dependencies. OverlayFS and FUSE are available in the initramfs and newly created
Minimal and Service disks via `modprobe overlay` and `modprobe fuse`. Existing
persistent disks retain their previous module files. FUSE userspace programs are
installed separately with apk as needed. Both image tests verify OverlayFS copy-up
and opening `/dev/fuse` after loading the modules.

The firmware, Image, FDT and initrd layout is in `native/runtime/vm.h` and
[the runtime document](riscv-runtime.md). Serial console is NS16550 `ttyS0`, verified
by the running guest. The control daemon handles Stop via BusyBox poweroff and
resize via `stty -F /dev/ttyS0`; a native readiness gate prevents boot-time loss.

Run `python3 scripts/smoke-boot.py` after building the host target. It boots the
same board, waits for the real Alpine shell, sends commands, checks output and
powers off. `ctest` additionally tests the private resize/Stop path and Ctrl+C.
Failure keeps serial diagnostics in `out/smoke-boot.log`. The root filesystem is
in RAM except `/root` and `/data`, which use the private ext4/NVMe disk. See
[storage/network](storage-network.md) for seed generation, lifecycle and tests.

## Edge and existing installations

New installations and Disk Creator use the official Edge 20260805 snapshot
(`VERSION_ID=3.25.0_alpha20260805`, `PRETTY_NAME="Alpine Linux edge"`).
Both initramfs and SYSTEM seed configure exactly these repositories:

```text
https://dl-cdn.alpinelinux.org/alpine/edge/main
https://dl-cdn.alpinelinux.org/alpine/edge/community
```

`testing` is not enabled by default. Build inputs remain version/checksum-pinned;
`apk update` fetches the current Edge package indexes. Edge packages can change
between builds and runtime upgrades. If a pinned package disappears upstream,
refresh the pin and provenance together and run the acceptance tests again;
never silently accept different bytes for the same pin.

## Image variants

Disk Creator offers **Minimal Alpine** (the default, existing BusyBox init) and
**Service Alpine** (OpenRC 0.63.2-r1, including `openrc-init` as PID 1).
Both use the same pinned Edge base, kernel, networking, 1–100 GiB persistent
root and Android `/shared` support. Minimal's boot and package set stay unchanged.
Service adds the pinned OpenRC packages and dependencies listed in provenance.
The build uses a pinned x86_64 Linux `apk.static` with signature verification,
offline dependency resolution and no cross-architecture install scripts.
Runlevels are configured explicitly; no package metadata is fabricated.

Service enables `flyby-boot`, `flyby-control`, `flyby-console` and `flyby-network`.
The console and private control channel are supervised. The initramfs already
mounts the virtual filesystems; generic hardware discovery is not needed.
Use `rc-service NAME start|stop|restart|status` and `rc-update add NAME default`
to manage services. Service shutdown uses `openrc-shutdown`, runs stop hooks,
and remounts filesystems read-only; app Stop allows up to 30 seconds for this.

Updating the Android app retains the existing disk. To try either image, stop
Linux and create a disk in Settings, confirming replacement. Export first if
its contents are needed. There is no in-place Edge migration button in this
prototype. Imported disks retain their own init and packages; the creation
selector is not an assertion about the active disk.

`scripts/test-service.py` boots the real Service seed twice and checks PID 1,
package registration, service start/stop/restart, the shutdown hook and enabled
service persistence. Android instrumentation creates a Service disk through the
service intent, boots it at 128 MiB, verifies OpenRC and exercises export/import.
