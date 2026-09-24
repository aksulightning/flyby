# First Linux guest (Phase 3, not built yet)

No kernel, initramfs, BusyBox binary or guest build script is bundled in Phase 1.
This document records the requirements for implementing the next guest build,
not a successful boot report. Use an independently built Linux userspace: the
Android NDK/bionic toolchain is for the **host QEMU**, not the guest BusyBox.

## Kernel and console

The initial source reference is Linux **v6.12**, arm64 `defconfig`. Before
producing a distributed image choose and pin a maintained 6.12.y patch release,
verify its signature/checksum and preserve its full `.config` and patches.

QEMU 9.2.4 `virt` provides a PL011 UART. The Linux v6.12
[`amba-pl011.c`](https://github.com/torvalds/linux/blob/v6.12/drivers/tty/serial/amba-pl011.c)
names the driver/console `ttyAMA`; the first UART is `ttyAMA0`.
The ARM64 [`defconfig`](https://github.com/torvalds/linux/blob/v6.12/arch/arm64/configs/defconfig)
enables both `CONFIG_SERIAL_AMBA_PL011=y` and
`CONFIG_SERIAL_AMBA_PL011_CONSOLE=y`. The command builder therefore uses
`console=ttyAMA0,115200 rdinit=/init panic=0`.

Required built-in options include ARM64, initramfs/initrd and gzip support,
`CONFIG_DEVTMPFS`, `CONFIG_PROC_FS`, `CONFIG_SYSFS`, `CONFIG_TTY`, the PL011
driver/console, and ELF binary support. Do not build critical boot drivers as
modules. Start with `make ARCH=arm64 defconfig` using a pinned aarch64 Linux
cross-compiler, preserve the resulting `.config`, and produce
`arch/arm64/boot/Image`. `CONFIG_DEVTMPFS_MOUNT` alone does not mount `/dev` for
an initramfs `/init`; mount it explicitly.

## Initramfs implementation criteria

Use a pinned BusyBox release, GPL-2.0-only, built statically against a guest
Linux libc. Its required applets include `sh`/ash, `mount`, `setsid`, `cttyhack`,
`stty`, `poweroff`, `reboot`, `cat`, `echo` and filesystem utilities. The actual
BusyBox version and libc must be selected and licensed before adding the build.

The future `scripts/build-test-initramfs.sh` must make a reproducible `newc`
archive (root UID/GID and deterministic timestamps/order), containing `/init`,
BusyBox and applet links, empty `/proc`, `/sys`, `/dev`, `/tmp` and `/root`.
Provide `/dev/console` (5:1) and `/dev/null` (1:3) using a cpio manifest/fakeroot,
without requiring host root or privileged mknod.

`/init` must mount proc, sysfs and devtmpfs; attach standard streams to the
PL011 console; print exactly `Hello from Linux`; and run an interactive shell
with a controlling tty (`setsid` plus `cttyhack`). Keep PID 1 alive and handle
shell exit. Set `TERM` to match the implemented emulator (initial diagnostics
are not an ANSI emulator). Verify Ctrl-C/job control rather than assuming pipes
alone provide a tty. Serial-size changes will need a guest-visible mechanism;
resizing a host pipe does not change the guest tty window size.

## Acceptance test to implement with the images

A developer smoke test must launch QEMU as an argv list with `Image` and the
initramfs, drain diagnostics, fail on early exit, and wait with a finite timeout
for the real `Hello from Linux` serial marker. Then send a unique echo token,
require the shell's reply, stop and reap QEMU. Running this with host Linux QEMU
tests the guest only; the same scenario inside the APK on ARM64 Android is still
required. Test restart and process cleanup before adding persistent storage.

No persistent disk or outbound networking is configured at this stage.
