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
| `./gradlew assembleDebugAndroidTest` | SDK-only runner APK generated; execution verified in CI below |
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

No physical Android device was connected locally. Android API 35 x86_64 execution
on GitHub Actions is now verified below, including JNI on ART, Start UI, terminal
IME input and Activity/background lifecycle. Physical ARM64 execution, actual
keyboard apps, screen-off operation and OEM battery restrictions remain untested.
Allocation-failure recovery and abrupt Android process-death filesystem recovery
were not fault-injected. Full physical-device acceptance is still outstanding.

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

## Storage/network continuation

- ARM64 debug APK and instrumentation APK build; 36 JVM tests per variant pass.
- Host persistence: a unique token survives poweroff and a new RVVM process in
  both `/root` and `/data` (`scripts/test-storage.py`).
- GitHub run 36024038220, job 107716256707: native tests, Alpine shell,
  persistence, real DNS/HTTP/HTTPS and Gradle test/build/lint all passed.
- Earlier Android API 35 x86_64 execution reached the real Alpine shell, passed
  commands, duplicate Start, recreation/background/return and graceful Stop.
  The wrapper initially misread the formatted instrumentation output; it now
  requests raw output (`am instrument -r`) and checks result code plus PASS.
- Final APK inspection caught aapt expanding `disk.raw.gz` to `disk.raw`.
  The gzip bytes now use `disk.seed`; `assembleDebug` checks the actual APK entry
  and gzip magic. This fixes the app-private disk provisioning path.
- The extended UI test's framework text search did not find Compose's virtual
  Start node. It now traverses the accessibility tree and includes it in failure
  diagnostics. Initialization errors fail immediately instead of waiting five minutes.

The extended Android Start/IME/storage/network acceptance execution is tracked in
GitHub Actions. Physical ARM64, actual keyboard app behavior, screen-off/OEM power
management and abrupt process-death filesystem recovery still require device tests.

## Final Android acceptance — passed

Code commit `c866e3ea61528f6a3c81f88163a50d451a3e17f4`:
[GitHub run 36025172275](https://github.com/aksulightning/flyby/actions/runs/36025172275).
Both `build` and `android-runtime` jobs succeeded. The latter installed both test
APKs on Android API 35 x86_64 and returned:

```
PASS: JNI validation, Start UI, Alpine shell, terminal IME, network=true,
duplicate start, Activity recreate/background/return, session identity,
persistent /root after restart and Stop
INSTRUMENTATION_CODE: -1
```

The test unbinds its own service connection while Activity is backgrounded. It
requires the same service/terminal object on return, sends real shell commands,
checks `/etc/os-release`, verifies outbound DNS/HTTP/HTTPS, writes a unique file,
stops, boots again and verifies that file. Terminal IME also prints the ordinary
text `Kernel panic`, exercising the fix for falsely interpreting user output as a
kernel crash. Native invalid-handle/missing-resource errors in this test are
intentional negative checks, not unexpected failures.

Local final build: `./gradlew clean test assembleDebug assembleDebugAndroidTest
lintDebug`, then `assembleDebug` after removing stale generated aapt intermediates.
All tasks passed; 36 tests per debug/release variant, zero failures/errors. The APK
contains only arm64-v8a native libraries, the gzip-encoded `disk.seed`, and no stale
uncompressed disk asset. `apksigner verify` and `zipalign -c -P 16 4` passed.

APK: `app/build/outputs/apk/debug/app-debug.apk` (37,507,413 bytes).
SHA-256: `e6a8f79a88ac74c8aaae91565d5a5d6854b7909ed50c8a6232f14487813429f6`.
This is an ARM64 build artifact; the successful execution above used the explicit
x86_64 CI test build. These must not be confused with physical ARM64 validation.
