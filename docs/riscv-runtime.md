# Flyby RV64 runtime

Phase 2 replaces the proposed QEMU child process with an in-process NDK library.
No QEMU source or executable is linked. The unchanged RVVM library at
`ce8ca7c00ba4058e5f26811057573b3ff23e9316` is MPL-2.0. This is a staging API, pinned
because upstream's v0.6 release is GPL-3.0-or-later, not MPL. The GPL command-line
entry points are excluded. See `native/CMakeLists.txt` for the exact source set.
The small board builds with Android NDK 27.2.12479018 for arm64-v8a and on Linux.
Upstream's CMake directory assumptions are avoided with our own source selection.
There are no upstream source patches. JIT, KVM, GUI, network, VFIO and process
isolation are disabled; ART retains ownership of host signals.

RVVM implements integer, multiply/divide, atomics, compressed, F/D floating-point,
privileged execution, traps, interrupts and virtual memory (including Sv39).
The standard Alpine kernel discovers optional extensions from RVVM's generated
FDT. We do not invent a custom ISA. Native vCPU threads sleep on WFI using RVVM's
condition variables/timers. JNI is only used for batched serial data and control.

## Boot chain and memory map

`Flyby -> OpenSBI fw_jump -> Linux Image -> Alpine initramfs -> BusyBox init -> sh`

The single board definition is in `native/runtime/vm.h`:

| Region | Address / configuration |
| --- | --- |
| RAM / OpenSBI entry | 0x80000000, 512 MiB default |
| Linux Image | 0x80200000 (RVVM RV64 loader and OpenSBI fw_jump agree) |
| initramfs | 0x88000000, up to 64 MiB |
| Device tree | RVVM generates it at the top of RAM |
| CLINT timer | 0x02000000, 10 MHz |
| PLIC | 0x0c000000 |
| NS16550 console | 0x10000000, PLIC IRQ 1, Linux ttyS0 |
| NS16550 control | 0x10001000, PLIC IRQ 2, Linux ttyS1 |
| syscon poweroff/reset | 0x00100000 |

No PCI, disk, display or network device is needed for this RAM-root milestone.
A private second UART carries `stop` and validated `resize ROWS COLS` requests to
`/etc/flyby-control`. It never runs Android shell commands or injects management
commands into the user's interactive shell. Stop asks BusyBox init to shut down;
the Android controller will fall back to stopping/joining the native threads.
RAM-root changes disappear on shutdown. Guest `poweroff` is also supported.

## Reproduce native and guest inputs

```
python3 scripts/prepare-native.py
python3 scripts/prepare-alpine-riscv64.py
cmake -S native -B out/host -DCMAKE_BUILD_TYPE=Release
cmake --build out/host -j2
python3 scripts/smoke-boot.py
```

Python 3, CMake >=3.22, a C/C++ compiler and HTTPS access are required. Downloads
are cached in `out/downloads`; all archives are SHA-256 pinned. Generated files
and third-party source downloads are ignored by Git. No unidentified binaries
are committed. The native archive URLs and hashes are in `prepare-native.py`.

The guest uses Alpine 3.23.6 riscv64 minirootfs, Alpine linux-lts 6.18.53-r0 and
OpenSBI 1.7-r0. The script extracts the exact official APK members, decompresses
the Linux Image, and constructs a deterministic gzip/newc initramfs without root
or mounting images. It validates the RISC-V Image magic. Alpine's kernel config
has built-in initramfs/gzip/devtmpfs/8250 console support. Exact package archive
hashes and URLs are in the script. The generated asset manifest pins the final
firmware, kernel and initrd copied to app-private storage.

**Development guest:** automatic root shell, no login, no network. This is the
actual Alpine userspace (`/etc/os-release`), not an imitation string. The boot
script prints `FLYBY_ALPINE_READY` from the login shell. The smoke test then sends
commands and checks their output before powering off. Root applies only inside
the emulated machine.

## Licensing

Flyby source remains Apache-2.0. RVVM library files are MPL-2.0; libvterm 0.3.3 is
MIT (full notices in `docs/licenses`). The libvterm screen parser is compiled
into the native library. RVVM's GPL CLI is not built. Preserve upstream notices
and make the pinned MPL source plus Flyby build files available with binaries.

The separate guest includes Linux GPL-2.0-only, BusyBox GPL-2.0-only, OpenSBI
BSD-2-Clause, musl MIT and additional Alpine packages. Redistributing an APK that
contains the guest also distributes these binaries: preserve their notices and
provide the corresponding source, configuration and patches for GPL components.
Do not treat this document or a generic upstream link as a complete source offer.
No public binary release is published by this change. Before distributing one,
archive the exact Alpine aports package recipes and corresponding distfiles as
part of its source bundle. See `docs/licenses.md` for Android dependency licenses.
