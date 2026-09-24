# Alpine riscv64 guest

Run `python3 scripts/prepare-alpine-riscv64.py`. It produces `kernel`, `firmware`,
`initrd`, `disk.seed` and a SHA-256 manifest in `app/src/main/assets/vm/` using pinned official
Alpine archives. These generated resources are packaged into the APK, not Git.
`out/downloads` caches the original archives. No random prebuilt image is accepted.

Inputs:
- Alpine minirootfs 3.23.6 riscv64 (`releases/riscv64` under Alpine v3.23).
- Alpine linux-lts 6.18.53-r0: gzip-compressed Image and its package kernel config.
- Alpine OpenSBI 1.7-r0: `generic/firmware/fw_jump.bin`.

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
