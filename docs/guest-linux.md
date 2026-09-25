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
Matching ext4, realtek PHY, r8169 and af_packet kernel modules are extracted from
the pinned linux-lts APK, together with their transitive dependencies.

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

Updating the Android app retains an existing SYSTEM disk and its repository
configuration. Disk Creator makes a fresh Edge installation after confirmation,
replacing that disk. Export it first if its files or installed packages are needed.

To migrate an existing stock Flyby Alpine installation in place instead, export
its system disk in Settings, start Linux, then follow Alpine's
[release-branch upgrade procedure](https://wiki.alpinelinux.org/wiki/Upgrading_Alpine_Linux_to_a_new_release_branch):

```sh
cp -p /etc/apk/repositories /etc/apk/repositories.before-edge
printf '%s\n' \
  https://dl-cdn.alpinelinux.org/alpine/edge/main \
  https://dl-cdn.alpinelinux.org/alpine/edge/community > /etc/apk/repositories
apk update && apk add --upgrade apk-tools && apk upgrade --available
```

Check that the upgrade succeeds, then Stop and Start Linux. The app supplies the
kernel/firmware; installing a guest kernel package does not change the boot image.
Custom or pinned packages may need manual resolution. The migration is opt-in and
requires networking; the app does not run package upgrades on an existing disk.
