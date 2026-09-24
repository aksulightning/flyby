# Flyby

Flyby is a lightweight QEMU-based Linux terminal environment for Android. It focuses on running an ARM64 Linux guest with a fast, simple terminal interface without requiring root access, GPU acceleration, or a graphical desktop.

## Current stage

Phase 1: Android foundation with Compose UI, observable VM state management,
validated configuration/command construction and a tested QEMU controller
contract. **No QEMU or Linux binaries are bundled yet. This is not a bootable VM
release.** Start checks the expected runtime and reports the missing QEMU file;
it never simulates Linux boot. Terminal is a selectable diagnostic preview with
disabled extra keys, not a working terminal emulator.

## Building

Use a full **JDK 17** (including javac), Android SDK 35 and Build Tools 35.0.0.
Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`. The standard
Gradle wrapper pins Gradle 8.11.1 and its distribution checksum. AGP is 8.9.2,
Kotlin/Compose compiler 2.1.20, Compose BOM 2025.03.01, minSdk 26 and targetSdk 35.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The primary native ABI is arm64-v8a; NDK 27.2.12479018 is pinned for the next
milestone. Phase 1 needs no native QEMU compilation or CMake. The generated APK
is `app/build/outputs/apk/debug/app-debug.apk`. Build artifacts, signing material,
machine paths, downloaded sources and guest/native binaries are ignored by Git.

## Architecture and next steps

Compose UI → `VmManager` / `TerminalSession` → `QemuController` interface.
`FlybyApplication` owns the manager independently of Activity instances. States
are STOPPED, STARTING, RUNNING, STOPPING and ERROR. Starts are serialized; stop
and failure paths have tests. Defaults: 1 vCPU, 512 MiB, guest aarch64.

Before connecting the production controller, add a foreground service, channel,
ongoing notification and correct target-35 service declarations. Application
ownership alone does not guarantee Android background survival. See
[architecture](docs/architecture.md) for transitions, process contracts and logs.

The planned QEMU integration is a separate Android/bionic PIE child process
packaged under `jniLibs/arm64-v8a`. It uses TCG, `virt`, headless PL011 serial and
structured argv, without a host shell. No root, KVM or Termux dependency exists.
See [QEMU integration decision](docs/qemu-android.md).

## QEMU and guest builds

The next milestone is an Android ARM64 build of **QEMU 9.2.4**,
`aarch64-softmmu` only. Its real configure/Meson options and required dependencies
have been inspected. Run the prerequisite check against the source tree:

```sh
ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018 \
  scripts/check-native-environment.sh /path/to/qemu-9.2.4
```

This is a prerequisite check, **not a working QEMU build script**. Android GLib
and libfdt builds, bionic fixes, ELF/packaging checks and physical-device execution
remain to be implemented. [Native build preparation](docs/qemu-android.md)
documents the concrete next steps and inspected flags.

The subsequent guest is an ARM64 kernel plus a BusyBox initramfs. Console
`ttyAMA0` was checked against QEMU's PL011 UART and the Linux driver.
[Guest build requirements](docs/guest-linux.md) specify `/init`, mounts, the real
`Hello from Linux` greeting, controlling tty and boot smoke-test acceptance. No
guest-build success or interactive shell is claimed in this phase.

## Physical device testing

Install the APK with `adb install -r`, open Flyby and confirm Linux VM / Stopped.
Press Start: the expected Phase 1 result is an actionable missing-runtime error.
Rotate the Activity and verify the state/error survives, then navigate to
Terminal and Back. See [validation](docs/validation.md) for the full checklist.

Local debug build and JVM tests have passed. No physical Android device was
available: UI rendering, native execution, guest boot and background behavior
have **not** been device-tested.

## Tests and CI

25 unit tests cover config bounds, unsafe/missing/empty paths, symlink escapes,
structured argv including shell metacharacters, state transitions, duplicate
start, cancellation during startup, timeout/force-stop, failed cleanup, input
routing and split UTF-8/bounded diagnostics. Test controllers are only fixtures;
they are not evidence of a Linux boot.

`.github/workflows/android.yml` runs unit tests, debug build and lint using
Java 17 and SDK 35 on pushes and PRs. Full native QEMU builds are deliberately
not in CI yet; no unverified native pipeline is advertised.

## Known limitations

No Linux boot, interactive terminal emulator, foreground service, persistent
guest disk or networking yet. No QEMU process backend is connected. No settings
UI, terminal resize or host process-death recovery. Native ABI is intentionally
ARM64 only. No production/stable or alpha release is being made before the
actual Start → boot → interactive shell path passes on physical ARM64 Android.

## Licenses

Flyby Android frontend source is Apache-2.0. AndroidX, Kotlin, coroutines and Gradle are
Apache-2.0; JUnit 4 is EPL-1.0 and Hamcrest BSD-3-Clause. QEMU as a whole is
GPL-2.0; planned Linux and BusyBox guest components are GPL-2.0-only overall.
No terminal emulator dependency has been selected.

See [licenses and redistribution](docs/licenses.md) for exact sources, dependency
licenses, separate-process implications, LGPL relinking considerations and
corresponding-source requirements. A future binary release must include exact
source/configs/patches/build materials; upstream links alone are insufficient.
