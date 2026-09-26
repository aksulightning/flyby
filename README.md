# Flyby

> [!IMPORTANT]
> **An honest note about this project:** Flyby has been created largely with the
> help of AI. It may contain mistakes, unfinished ideas or bugs that have not been
> noticed yet. You do not need to use this app, and you should not trust it with
> important or irreplaceable data. If you try it, keep backups and treat it as an
> experimental project—not as a finished or security-audited product.

Flyby is an experimental Android app that runs a small Linux computer inside your
phone or tablet.

In everyday terms, the app gives you a Linux command line and, optionally, a very
simple Linux desktop. Linux runs locally on the Android device. Flyby does not need
Android root access, Termux, QEMU, a remote server or a permanent internet
connection just to start Linux.

Flyby is mainly for experimenting, learning and development. It is not intended to
replace Android or to be a polished desktop-computer experience.

## What can Flyby do?

- Start and stop an Alpine Linux system inside Android.
- Keep Linux files, settings and installed packages between restarts.
- Provide a terminal for typing Linux commands.
- Provide an optional basic graphical desktop.
- Let Linux use the Android device's outgoing internet connection.
- Share a folder chosen through Android with Linux at `/shared`.
- Create Linux disks from 1 to 100 GiB.
- Export and import a compressed `.flyby` backup of the Linux disk.
- Adjust the virtual machine's memory from 128 to 768 MiB.

The virtual Linux computer uses one virtual processor. It runs through a built-in
RISC-V interpreter, so performance depends heavily on the Android device and is
not expected to match a normal native Android app.

## The three disk images

A **disk image** is a file that acts like the virtual Linux computer's hard drive.
It contains the Linux system, installed programs, settings and files. Flyby's Disk
Creator offers three starting images:

| Image | What it provides | Best for |
| --- | --- | --- |
| **Minimal Alpine** | A small Alpine Linux system with a command line and a simple BusyBox startup system. This is the default. | Basic Linux commands, learning and the smallest setup. |
| **Service Alpine** | Alpine Linux with OpenRC, which can start and manage background services. It has no graphical desktop. | Server-like tools and programs that should run as services. |
| **Minimal Alpine Wayland** | Service Alpine plus a simple Weston/Wayland desktop and graphical terminal. | Trying a basic Linux desktop on Android. |

Important things to know about disks:

- Creating any of these images makes a **fresh Linux installation**. It does not
  upgrade or resize the contents of the current disk.
- Creating a disk replaces the current SYSTEM disk after confirmation. Export a
  backup first if you want to keep it.
- The selected image affects only the next disk you create. Existing disks are not
  automatically converted when the app is updated.
- A disk can be configured as 1–100 GiB, but the full amount is not necessarily
  reserved immediately. It consumes more real Android storage as Linux writes data.
  Running out of Android storage can damage or interrupt the Linux system.
- The whole Linux filesystem is persistent, including `/etc`, `/usr`, `/root`
  and installed packages. Temporary locations such as `/run` and `/tmp` are not.
- The Linux kernel and startup firmware come from the Android app. Installing a
  kernel package inside Linux does not replace them.
- The folder mounted at `/shared` points directly to the Android folder you chose.
  Changes and deletions there affect the real Android files. It is not included in
  Flyby disk backups.

More detail is available in
[Settings, Disk Creator and sharing](docs/settings-and-sharing.md),
[storage and networking](docs/storage-network.md) and
[Wayland notes](docs/wayland.md).

## How to try it

Flyby currently provides development builds rather than a stable release.

1. Open the repository's [Releases](https://github.com/aksulightning/flyby/releases)
   page.
2. Choose a release marked as a **prerelease** and download the ARM64 APK.
3. Install the APK on a compatible ARM64 Android device. Android may ask you to
   allow installation from that source.
4. Open Flyby and press **Start**.
5. Wait in **Terminal** until `root@flyby:~#` appears.
6. Type a simple command such as `ls` or `cat /etc/os-release`.

For the graphical image, first stop Linux, open **Settings → Disk Creator**, select
**Minimal Alpine Wayland**, create the disk and start Linux again. Then open
**Display**. Using 512–768 MiB of RAM is recommended for the desktop.

See [nightly downloads and disk backups](docs/nightly-and-backups.md) for detailed
download, update, export and import instructions.

## Backups

Stop Linux before exporting a disk. In **Settings**, choose **Export disk** and save
the resulting `.flyby` file somewhere safe. To restore it, choose **Import disk**
and confirm that the current disk may be replaced.

A backup contains the Linux disk and its files. It does **not** contain:

- live virtual-machine memory;
- terminal scrollback;
- Flyby app preferences; or
- files in the Android folder mounted at `/shared`.

Backups are compressed but **not encrypted**. Anyone who gets the backup may be able
to read its contents. Flyby accepts its own compatible backup format, not arbitrary
raw disk images or ZIP files.

## Known limitations and risks

- Flyby is experimental. Nightly builds are development prereleases, not stable
  releases.
- Automated tests run on an Android API 35 x86_64 emulator. Physical ARM64 phones
  and tablets, different keyboard apps, screen-off use and manufacturer-specific
  battery management still need broader testing.
- Android 8.0 (API 26) is the minimum configured Android version, but this does not
  mean every device has been tested.
- There is one virtual CPU and only 128–768 MiB of virtual RAM. Programs may be slow,
  and large or demanding Linux applications may not work.
- The graphical desktop is fixed at 800 × 600, has no GPU acceleration and is
  limited to native Wayland applications available for RISC-V. Xorg and XWayland
  are not included.
- The initial graphical keyboard layout is US. Android text input supports a limited
  set of extra characters. Physical keyboards use the Linux guest's configured
  layout.
- Networking is outgoing only. There is no port-forwarding, network settings screen,
  Android Private DNS integration, VPN bypass or automatic proxy configuration.
- Android may stop the app, especially because of battery or memory restrictions.
  If that happens, unsaved work in the running virtual machine is lost. Flyby does
  not automatically restart or save a RAM snapshot.
- Flyby keeps a partial wake lock while Linux is running. This can consume battery.
- Force-stopping the app or losing power during disk writes can leave the Linux
  filesystem needing recovery. Flyby does not automatically format or repair it.
- The terminal has bounded scrollback. Copy copies the visible screen rather than a
  freely selected character range, and resizing does not reflow old lines.
- The shared Android folder does not support every normal Linux filesystem feature.
  Symbolic links, hard links, special files and Unix ownership changes are not
  supported, and the Android storage provider may restrict other operations.
- A bug in the native interpreter, extreme memory use or a hostile guest kernel may
  crash the whole app. Flyby is **not a security-audited sandbox** for untrusted
  Linux software.
- Clearing Flyby's Android app data or uninstalling the app deletes its private
  Linux disk. Export important data before doing either.
- Development APKs may use different signing keys. Android can refuse an in-place
  update when the signer changes, which may require exporting the disk, uninstalling
  the old app and installing the new build.

## For developers

### Build the Android app

Requirements include JDK 17, Android SDK 35, Android NDK
`27.2.12479018`, CMake 3.22.1, Python 3 and `e2fsprogs`. Set
`ANDROID_HOME`. Local SDK paths and signing material must not be committed.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' \
  'ndk;27.2.12479018' 'cmake;3.22.1'
python3 scripts/prepare-native.py
python3 scripts/prepare-alpine-riscv64.py
./gradlew test assembleDebug lintDebug
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. The APK includes the native
interpreter, firmware, Linux kernel and initial Alpine resources, so the first boot
does not need to download them. The build fails if these guest resources have not
been prepared.

The default APK targets **arm64-v8a**. CI also builds an x86_64 test variant for the
Android emulator.

### Technical overview

Flyby embeds [RVVM](https://github.com/LekKit/RVVM) as an MPL-2.0 library and uses
its interpreter without a JIT. Linux boots through OpenSBI into a pinned Alpine Edge
RISC-V system. The Android app owns the virtual machine in a foreground service,
while libvterm renders the terminal.

The VM has a virtual NVMe system disk, user-mode outgoing networking and private
communication channels for lifecycle commands, Android folder sharing and display
input. The Wayland image uses a simple framebuffer and software rendering. The old
QEMU proposal in `docs/qemu-android.md` is retained only as historical
documentation; QEMU is not built or used.

More technical documentation:

- [Architecture](docs/architecture.md)
- [Native runtime and memory map](docs/riscv-runtime.md)
- [Guest Linux resources](docs/guest-linux.md)
- [Validation and manual acceptance](docs/validation.md)

### Host tests

With CMake 3.22 or newer and a C/C++ compiler:

```sh
cmake -S native -B out/host -DCMAKE_BUILD_TYPE=Release
cmake --build out/host -j2
ctest --test-dir out/host --output-on-failure
python3 scripts/smoke-boot.py
python3 scripts/test-wayland.py
```

For an Android device connected through ADB:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
python3 scripts/test-android.py --network
```

These tests exercise the real native interpreter and guest system. GitHub Actions
also checks booting, persistence, networking, Gradle tests, the debug APK and lint.
Passing automation does not replace testing on physical Android hardware.

## Licenses

Flyby source is licensed under **Apache-2.0**. Major included components use other
licenses: RVVM is **MPL-2.0**, libvterm is **MIT**, Linux and BusyBox are
**GPL-2.0-only**, and OpenSBI is **BSD-2-Clause**. Alpine packages have their own
licenses.

Exact versions, notices and source information are listed in
[docs/licenses.md](docs/licenses.md). Anyone distributing an APK that contains the
Linux guest must also follow the guest components' source-code and notice
requirements.
