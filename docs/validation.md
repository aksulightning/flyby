# Phase 1 validation

The repository was empty when inspected: no existing Gradle, Android, Kotlin,
Compose, NDK/CMake configuration, native source or tests to preserve. Work is on
`codex/android-qemu-mvp`.

## Local build environment

- OpenJDK 17, Gradle 8.11.1, AGP 8.9.2
- Android platform 35 and Build Tools 35.0.0
- Kotlin/Compose compiler 2.1.20
- Compose BOM 2025.03.01; minSdk 26; targetSdk 35
- Native ABI arm64-v8a, future NDK 27.2.12479018

The workspace initially had a JRE without javac and no Android SDK/Gradle. A
full JDK and SDK were installed outside the repository. Temporary-directory,
proxy and JVM trust-store settings were needed for this isolated environment;
none of those machine paths/settings is committed. Initial failures from the
missing `/tmp`, stale proxy settings, missing javac and the downloaded JDK's
trust store were corrected before compilation succeeded.

The official Gradle 8.11.1 binary distribution SHA-256 was checked against the
published checksum before generating the standard wrapper:
`f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`.
The same checksum is pinned in `gradle-wrapper.properties`.

## Commands

Local results: debug APK and **25 unit tests passed**; lint passed. The initial
lint run reported missing launcher icon and Android 12 backup rules, which were
fixed. The ChromeOS x86 ABI warning is explicitly disabled because this MVP
intentionally targets arm64-v8a; no x86 QEMU target was added.

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
bash -n scripts/check-native-environment.sh
scripts/check-native-environment.sh --help
scripts/check-native-environment.sh /nonexistent
adb devices -l
```

The missing-source native prerequisite check must fail (exit 1); `--help` and
shell syntax checks must pass. It does not prove a QEMU build. No Android device
is attached, so no installation, rendering, native execution or guest boot has
been verified on hardware. Unit tests exercise a test-only controller, not QEMU.

## Device acceptance checklist (not yet run)

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk` on API 26+.
2. Launch Flyby: Linux VM, Stopped, Start/Stop/Terminal controls are visible.
3. Start reports the missing Android ARM64 QEMU runtime; never shows a fake boot.
4. Rotate the Activity: state/error remain, no second VM manager is created.
5. Open Terminal, copy diagnostics, navigate Back; extra keys are visibly disabled.
6. Check logcat `FlybyVM` for start/exit events and no terminal input payloads.

Native/guest acceptance is specified in `qemu-android.md` and `guest-linux.md`.
No alpha release should be made until real ARM64 Android spawn, boot and an
interactive shell pass.

## GitHub Actions

The first CI attempt failed before Gradle because `sdkmanager` was not on the
runner PATH. The workflow now explicitly sets up Android command-line tools.
Its checkout/setup-java actions use supported Node 24 versions after the runner
reported their previous Node 20 versions as deprecated. All CI actions are
pinned to verified commit SHAs. CI results remain separate from local validation.
The setup action's obsolete default `tools` package was also replaced with an
explicit `platform-tools` selection after Google no longer served that package.
