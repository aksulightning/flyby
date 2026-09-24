# Validation — Phase 2, 2026-09-24

## Actually executed locally

| Check | Result |
| --- | --- |
| `python3 scripts/prepare-native.py` | Verified RVVM/libvterm source archive hashes, extracted sources |
| `python3 scripts/prepare-alpine-riscv64.py` | Verified official Alpine archives; produced firmware, Image, initramfs, manifest/provenance |
| `scripts/build-native-android.sh` | NDK 27.2.12479018, arm64-v8a, API 26: linked `libflyby.so` |
| `cmake -S native -B out/host` / `cmake --build out/host -j2` | Linux x86_64 host build passed |
| `ctest --test-dir out/host --output-on-failure` | Real native runtime and libvterm tests passed |
| `python3 scripts/smoke-boot.py` | Real RISC-V Linux → Alpine shell; commands and poweroff passed |
| `./gradlew test` | 31 JVM tests per debug/release variant passed |
| `./gradlew assembleDebug` | Debug APK generated |
| `./gradlew assembleDebugAndroidTest` | SDK-only device runner APK generated; not executed |
| `./gradlew lintDebug` | No errors; existing dependency-update advisory retained |
| `llvm-readelf` on packaged native libraries | AArch64; all LOAD segment alignments 0x4000 (16 KiB) |
| `adb devices -l` | No connected device/emulator |

The local Gradle invocation used a small environment launcher outside the repository
to select the installed full JDK 17, SDK, temporary directory and environment proxy;
it ran the repository's actual wrapper and Gradle tasks above.

Native tests exercise invalid RAM/CPU parameters, missing resources, invalid Linux
Image magic, real initialization, duplicate start, pause/resume, serial input/output,
control-UART resize, Ctrl+C, graceful Stop and immediate stop/restart. Terminal tests
cover ANSI colors/cursor placement, cursor-position replies, key encoding, scrollback,
resize and split UTF-8. A two-second idle check observed roughly 0.5–1.4% of one host
CPU in local runs; this is a WFI regression check, not an Android performance claim.

The separate smoke test observed real output including:

```
Hello from Linux
NAME="Alpine Linux"
VERSION_ID=3.23.6
FLYBY_ALPINE_READY
Linux flyby 6.18.53-0-lts #1-Alpine SMP PREEMPT_DYNAMIC 2026-09-22 11:13:27 riscv64 Linux
FLYBY_INPUT_OK
Mem: ...
Swap: ...
PASS: Linux, Alpine, shell input/output, guest poweroff
```

The test sends `uname -a`, `cat /etc/os-release`, `ls /`, `cd /`, `echo`, `free`
and an ANSI-colored printf **after** detecting the shell marker. It verifies returned
command output, not just echoed command text. Full serial output is retained locally
in `out/smoke-boot.log`; generated logs are not committed.

## Errors found and corrected

- The first Phase 2 CI build lacked source preparation and failed at CMake with
  `Run python3 scripts/prepare-native.py first`. The workflow now provisions native
  sources and guest assets before host/Android builds.
- A native test caught an early control-UART resize being lost during Linux UART
  initialization. The private guest daemon now sends READY after opening/configuring
  its UART; native input stays queued until that handshake. Resize and Stop pass.
- Kotlin's existing incremental cache reported duplicate old top-level declarations
  during the large source migration. A non-incremental compile succeeded; subsequent
  normal incremental builds also succeeded. No dependency/toolchain upgrade was used.
- The Ctrl+C test initially sent the next command before tty input flushing completed.
  It now waits for the shell prompt after interrupting the foreground command.

## Not verified here

No Android device or emulator was connected. APK installation, actual ARM64 Android
execution, JNI on ART, UI drawing/IME, Activity reconnect, foreground notifications,
screen-off operation and OEM battery restrictions have **not** been run on a device.
The instrumented runner only has a successful build, not a passing execution result.
Allocation-failure recovery and Android process-death behavior were not fault-injected.
Phase 2 is not declared fully accepted until the physical-device gates pass.

## Device acceptance

1. Install the debug APK on ARM64 Android (API 26+), launch, grant notifications if
   desired, press Start, observe OpenSBI/Linux output and the Alpine root prompt.
2. Execute the six shell commands above; verify Alpine `/etc/os-release` and riscv64.
3. Exercise software keyboard, Enter/Backspace, Ctrl+C, Ctrl+D, Tab completion, Esc,
   arrow history/editing, colors, cursor movement, scrollback, copy and paste.
4. Run `stty size`, open/close the keyboard and rotate, then check dimensions again.
5. Run a guest workload, Home, open another app, return and verify the same PID/file
   and shell remain. Rotate repeatedly. Check notification opens the same session.
6. Turn the screen off for several minutes, return and verify continued guest work.
   Confirm the wake lock disappears after Stop/error; note OEM battery behavior.
7. Stop through both UI and notification. Verify graceful guest shutdown or the
   documented ten-second fallback, no running vCPU threads and no notification.
8. Run the SDK-only instrumentation command in README. It exercises JNI validation,
   boot, shell input, duplicate Start, recreation/background/return and Stop.
9. Force-stop the app with Android: a later launch must be Stopped, not claim a restored
   VM. No snapshot/persistence is implemented. Do not label a release stable from
   build/host evidence alone.
