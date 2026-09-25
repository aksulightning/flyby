# Flyby

Flyby is a lightweight, local Linux terminal environment for Android. An embedded
native RISC-V interpreter runs Alpine Linux without root, KVM, a graphical desktop,
Termux, a remote server, or QEMU.

## Current stage

**Start → native RV64 VM → OpenSBI → Linux → Alpine root shell**, with a
persistent Alpine system disk and outbound user-mode networking.
Settings offers **128–768 MiB RAM**, **1–100 GiB Disk Creator**, Android folder
sharing at **/shared**, terminal appearance, offline licenses and disk **Export / Import**.
See [nightly downloads and disk backups](docs/nightly-and-backups.md).

Verified on **Android API 35 x86_64 in GitHub Actions**: real Start UI, terminal IME
input, Alpine shell, DNS/HTTP/HTTPS, Activity recreation/background/return, session
identity, Stop/Start disk persistence and graceful Stop. The default **ARM64** APK
and NDK library build; JVM tests for both variants and native/guest tests run in CI.
**Physical ARM64 hardware, keyboard apps and screen-off/OEM power behavior remain
acceptance gates. Nightlies are development prereleases, not stable releases.**

The Compose UI retains Start / Stop / Terminal. The terminal uses libvterm for
ANSI, colors, cursor movement, UTF-8, alternate screen and 2,000 lines of scrollback.
It supports Enter, Backspace, Ctrl, Alt, Tab, Esc, arrows, copy/paste and guest resize.
A started foreground service owns the VM and terminal independently of Activities.

## Build the Android app

Keep the Phase 1 toolchain: **JDK 17**, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20,
Compose BOM 2025.03.01, minSdk 26, compile/targetSdk 35. Native ABI: **arm64-v8a**.
Set `ANDROID_HOME`; local SDK paths and signing material must not be committed.
Python 3, host `mke2fs` and `debugfs` (e2fsprogs) and HTTPS access are needed for the verified provisioning scripts.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' \
  'ndk;27.2.12479018' 'cmake;3.22.1'
python3 scripts/prepare-native.py
python3 scripts/prepare-alpine-riscv64.py
./gradlew test assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
The APK contains `libflyby.so`, OpenSBI, the Linux Image and an Alpine initramfs;
there is no first-launch network download. Start verifies/copies the packaged
resources into `filesDir/vm/default/` on an IO dispatcher. An APK build fails
explicitly if guest resources have not been prepared.

## Native and guest provisioning

The emulator is **RVVM**, pinned to commit
`ce8ca7c00ba4058e5f26811057573b3ff23e9316`, compiled as an MPL-2.0 library.
Its GPL command-line tools are excluded. This staging revision has the required
library license and Linux support; its API is deliberately pinned. There is no JIT.

`prepare-native.py` downloads checksum-verified RVVM and **libvterm 0.3.3 (MIT)**
source archives. `native/CMakeLists.txt` selects the interpreter and minimal board.
For a standalone Android native build:

```sh
scripts/build-native-android.sh
```

`prepare-alpine-riscv64.py` downloads checksum-pinned official Alpine artifacts:
**Alpine Edge riscv64 (20260805 snapshot)**, **linux-lts 6.18.53-r0**, **OpenSBI 1.9-r0**. It extracts
the firmware and Image, then builds the initramfs without root or mounting images.
Boot uses a generated device tree, CLINT, PLIC and NS16550 UART (`ttyS0`).
The development guest intentionally opens an automatic root shell. This is root
inside the VM, not Android root. A second UART carries shutdown/resize requests,
with a readiness handshake to avoid losing requests during boot.

Existing disks retain their current Alpine installation. See [Edge migration](docs/guest-linux.md#edge-and-existing-installations) for an in-place upgrade or create a fresh disk in Settings.

Details: [native runtime and memory map](docs/riscv-runtime.md),
[architecture](docs/architecture.md), [guest resources](docs/guest-linux.md).
The old QEMU proposal is archived in `docs/qemu-android.md`; it is not built or used.

## Test on the host

With CMake >=3.22 and a C/C++ compiler:

```sh
cmake -S native -B out/host -DCMAKE_BUILD_TYPE=Release
cmake --build out/host -j2
ctest --test-dir out/host --output-on-failure
python3 scripts/smoke-boot.py
```

These run the **same native interpreter and board** used by Android, not a fake
controller. They check initialization, invalid images, duplicate start, Linux and
Alpine boot, serial commands, Ctrl+C, resize, pause/resume, idle CPU usage, graceful
shutdown and restart. Terminal tests cover ANSI/cursor/color/history/UTF-8 behavior.
The original Phase 1 lifecycle tests remain, adapted to `VmController`; the obsolete
QEMU argument/path fixtures are retained only under `src/test` as historical
regression coverage and are never packaged into the APK.

GitHub Actions provisions the exact inputs, runs native/boot/persistence/network tests,
Gradle unit tests, debug APK build and lint, plus a separate Android API 35 runtime job. No binaries or downloaded vendor trees are in Git.

## Run on a physical ARM64 Android device

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.aksulightning.flyby/.MainActivity
adb logcat -s FlybyVM FlybyRVVM
```

Press **Start**. Boot output appears in Terminal; wait for `root@flyby:~#`.
Run `uname -a`, `cat /etc/os-release`, `ls`, `cd /`, `echo hello` and `free`.
Tap the terminal to show the keyboard; swipe to scroll; long press or Copy copies
the visible screen. Ctrl/Alt buttons apply to the next key. Stop is on MainScreen
and the ongoing notification. Graceful shutdown has a 10-second deadline followed
by native thread cleanup. Closing an Activity does not request Stop.

The service declares Android's `specialUse` foreground type and a documented subtype,
not `dataSync`. Notification permission is requested on Android 13+; denial does
not prevent the foreground VM, but limits notification visibility. A partial wake
lock is held only while active and released on stop/error/service destruction.
It allows screen-off execution but consumes battery. Android may still kill the
process; RAM state is then lost. No automatic restart or snapshot is claimed.

An SDK-only device integration runner is included:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
python3 scripts/test-android.py --network
```

See [validation and manual acceptance](docs/validation.md). Screen-off and OEM
battery behavior still need a physical-device check in addition to the runner.

## Known limitations

- Android emulator execution passes; physical ARM64 hardware, keyboard apps and
  screen-off/OEM behavior still need testing. Nightlies are development builds.
- The SYSTEM disk keeps the entire root filesystem, including package installs.
  Legacy DATA disks remain untouched but are no longer selectable in Settings.
  Kernel/firmware stay app-managed.
- User-mode outbound networking is implemented; no forwarding or network settings UI.
- One vCPU; RAM is configurable from 128–768 MiB, default 512 MiB.
- Interpreter performance depends on the device. The host idle check is not an
  Android benchmark. RVVM's staging API must be reviewed before any version update.
- Copy copies the visible screen; character-range selection is not implemented.
  Scrollback is bounded; resize does not reflow old history lines.
- Expected initialization failures become errors, but in-process native bugs or
  extreme host memory exhaustion can still terminate the app. This is not an
  audited sandbox for hostile guest kernels. Android process death loses the VM.

## Licenses

Flyby source: **Apache-2.0**. RVVM library: **MPL-2.0**. libvterm: **MIT**.
Linux and BusyBox: **GPL-2.0-only**. OpenSBI: **BSD-2-Clause**. Alpine contains
additional packages; exact versions/licenses/source revisions are documented in
[licenses](docs/licenses.md). Building with these libraries does not relicense
Flyby's original source. Distributing a guest-containing APK carries the guest's
source/notice obligations: provide complete corresponding sources/configs/patches
alongside any binary release. The nightly job builds and uploads the matching
corresponding-source bundle before it publishes a prerelease.

## Persistent disk milestone

The initial milestone used a 256 MiB DATA disk for `/data` and `/root`.
The current default is a complete Alpine SYSTEM disk; existing DATA files are retained.
Host two-boot and Android restart persistence tests pass. DHCP, DNS, HTTP and
HTTPS pass on the host CI and inside Android. See [storage and networking](docs/storage-network.md)
for provisioning, limits and validation. Android runtime CI uses an explicit
`-PflybyAbi=x86_64` test build; default APKs remain ARM64.

## Configurable VM and shared folder

Settings now includes RAM (128–768 MiB), a complete Alpine Disk Creator
(1–100 GiB), Android folder selection for `/shared`, terminal customization and
an offline Licenses screen. See [settings and sharing](docs/settings-and-sharing.md)
for upgrade behavior, storage semantics and validation.

Disk Creator offers **Minimal Alpine** (the existing Alpine Edge system with BusyBox init) and **Service Alpine** (Alpine Edge with OpenRC init and service management). Minimal remains the default; choosing an image only affects a newly created disk after replacement confirmation.
