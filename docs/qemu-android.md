# Android ARM64 QEMU: next milestone

Status: source/build-system investigation and Android packaging settings are
prepared. **No Android QEMU binary has been built or device-tested.** There is
no working `build-qemu-android.sh` yet. Do not substitute a glibc Linux binary.

## Integration decision

Use an Android/bionic ARM64 PIE executable in a child process. Package it as
`app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so`; the `.so` name is a
packaging convention, not an in-process JNI library. Gradle uses legacy native
packaging/extraction and does not strip this file. Resolve its installed path
from `ApplicationInfo.nativeLibraryDir` and pass an argument list to
`ProcessBuilder`. Validate APK extraction, execution and ELF alignment on real
Android devices, including a 16 KiB page-size device, before accepting the design.

Keep kernel/initramfs under `filesDir/vm/default/`. Never extract an executable
there and chmod it: Android 10+ disallows executing files from the application's
writable home directory. Child processes isolate QEMU's global state and crashes
and make stdio/exit handling simpler than adapting QEMU's main loop for JNI.
No root, Termux, KVM, GPU, VNC, SPICE, X11 or Wayland is involved.

## Investigated source

Candidate baseline: **QEMU 9.2.4**, upstream tag `v9.2.4`. This is an explicit
development baseline, not a claim to be the current security release. Review
upstream fixes before redistributing a native build.

The following files were read at that tag before selecting flags:

- [configure](https://github.com/qemu/qemu/blob/v9.2.4/configure)
- [meson.build](https://github.com/qemu/qemu/blob/v9.2.4/meson.build)
- [meson_options.txt](https://github.com/qemu/qemu/blob/v9.2.4/meson_options.txt)
- [scripts/meson-buildoptions.sh](https://github.com/qemu/qemu/blob/v9.2.4/scripts/meson-buildoptions.sh)
- [qemu-options.hx](https://github.com/qemu/qemu/blob/v9.2.4/qemu-options.hx)
- [virt machine](https://github.com/qemu/qemu/blob/v9.2.4/docs/system/arm/virt.rst)
- [LICENSE](https://github.com/qemu/qemu/blob/v9.2.4/LICENSE)

`configure` fronts Meson/Ninja. It recognizes Android using compiler macros;
there is no invented `--target-os=android` flag in this plan. Required host
dependencies include GLib >= 2.66 (including gmodule), zlib and libfdt for `virt`.
GLib must be cross-built for bionic, not resolved from the host's pkg-config.
Its enabled features may require libffi and PCRE2. Pixman is optional and should
be disabled with the other display features. Networking/libslirp is deferred.

## Concrete next build step

1. Install NDK **27.2.12479018** (r27c), Python 3, Ninja, Meson, pkg-config and a
   native host C compiler on Linux x86_64. QEMU configures its own Python build
   environment; record those Python dependency versions too.
2. Obtain the official 9.2.4 release source archive and verify the upstream
   release signature. Record archive SHA-256 and source commit in a manifest;
   include subprojects. Do not rely on an unverified prebuilt binary.
3. Run `scripts/check-native-environment.sh /absolute/path/to/qemu-9.2.4` with
   `ANDROID_NDK_HOME` set. This checks tooling/source prerequisites only, not
   whether QEMU builds. It exits nonzero for missing prerequisites.
4. Cross-build GLib and dependencies into a dedicated ARM64 Android prefix.
   Pin source versions/checksums, preserve licenses, and set `PKG_CONFIG_LIBDIR`
   to that prefix only. Record actual bionic errors and minimal patches under
   `native/qemu/patches/`. **No Android compatibility patch is verified yet.**
5. Implement `scripts/build-qemu-android.sh` using the tested toolchain and
   dependency prefix. The initial option set below exists in 9.2.4; it is **not
   an executed or validated Android build recipe**:

```text
--target-list=aarch64-softmmu
--without-default-features
--disable-user
--enable-tcg
--enable-fdt=system
--enable-pie
--disable-tools
--disable-guest-agent
--disable-docs
--disable-pixman
--disable-gtk
--disable-sdl
--disable-vnc
--disable-opengl
--disable-slirp
--disable-kvm
--disable-xen
--audio-drv-list=
```

Use NDK `toolchains/llvm/prebuilt/linux-x86_64/bin/clang` with target
`aarch64-linux-android26` (and matching clang++/llvm-ar/llvm-ranlib). Supply host
compiler separately with `--host-cc`; set `--cc`/`--cxx` explicitly. Link PIE
with `-Wl,-z,max-page-size=16384`. Prefer Android system libraries plus a
documented set of bundled dependencies; verify every `DT_NEEDED` entry using
NDK `llvm-readelf`. If statically linking LGPL libraries, preserve the relinking
materials described in [licenses.md](licenses.md).

Do not initially use `--without-default-devices`: retaining the aarch64 target's
default devices avoids accidentally dropping the `virt` machine/PL011 before
boot is proved. This still builds **only** the `aarch64-softmmu` target. Prune
devices later based on a successful boot and measured binary size.

6. Build `qemu-system-aarch64` with Ninja; verify ELF AArch64, Android linker
   `/system/bin/linker64`, dynamic dependencies and page alignment. Stage only
   generated artifacts under ignored `jniLibs`. Preserve build logs/source
   manifest externally before distributing any APK containing QEMU.
7. Add the process controller and foreground service, then first run `--version`
   from the APK on a physical ARM64 Android device. Capture stderr and exit code.
8. Boot the guest described in [guest-linux.md](guest-linux.md), require its
   actual serial greeting and interactive shell. Do not add disks/network first.

## Planned serial CLI

`QemuCommandBuilder` uses `-machine virt -accel tcg,thread=single -cpu cortex-a53`,
1 vCPU and 512 MiB by default. `-display none -monitor none -serial stdio`
explicitly avoids the stdio monitor multiplexing that `-nographic` can introduce.
`-nic none` prevents default networking. `-no-reboot` makes guest reboot terminate
the process. All options were checked against 9.2.4 `qemu-options.hx`; acceptance
by an actual Android build remains untested. QMP and terminal resize need a
separate implementation decision and device tests in the following milestones.

Sources:
- [NDK with other build systems](https://developer.android.com/ndk/guides/other_build_systems)
- [Android 10 execution restrictions](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
- [Native library extraction](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs)
- [16 KiB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [QEMU official releases and signature key](https://www.qemu.org/download/)
