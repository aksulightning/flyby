# Minimal Alpine Wayland

The third Disk Creator image derives from **Service Alpine**: Alpine Edge,
OpenRC PID 1, persistent services, the full SYSTEM disk, networking and `/shared`.
It adds Weston 16, its desktop shell and a Wayland terminal, started by OpenRC.
Minimal Alpine and Service Alpine remain available.

## Use

1. Stop the VM. Export the current disk first if you want to keep its contents.
2. In Settings → Disk Creator, select **Minimal Alpine Wayland**, choose the disk
   size, and confirm creation. This replaces the selected SYSTEM disk.
3. Start the VM, return from Terminal and open **Display**. Allow the desktop to
   finish starting. Terminal remains available for boot diagnostics and commands.

Start with 512–768 MiB RAM. The fixed guest resolution is **800 × 600**; the Android
view fits it with black margins, preserving its aspect ratio. Touch positions map
to an absolute pointer: tap to click and drag to move windows or select text.
A connected mouse supports buttons, hovering and its wheel. **Keyboard** opens the
Android IME; the bottom row supplies Ctrl, Esc, Tab, Enter and arrow keys.

The initial keyboard layout is US. IME text supports ASCII plus ä, ö, å and é
(including uppercase), through a right-Alt Compose key. Unsupported characters
produce a message instead of being silently substituted. Committed text is limited
to 2048 characters per operation. Physical keyboards use the guest layout; edit
`/etc/xdg/weston/weston.ini` if a different physical layout is required. The IME
mapping assumes the bundled US/Compose configuration.

Closing Display, backgrounding the Activity or opening Terminal releases held
keys/buttons and stops Android frame polling. The service continues to own the VM.
Stopping the VM clears the displayed frame. Imported Wayland disks work because
the display is detected through the guest input handshake, not a stored UI choice.
Existing disks are never converted or upgraded automatically by this feature.

## Implementation

* RVVM exposes a fixed XRGB8888 simple framebuffer at `0x18000000` (1,920,000 bytes).
  The pinned kernel's built-in `simpledrm` provides DRM/KMS. Weston uses Pixman;
  no guest GPU acceleration is claimed. Synchronized MMIO callbacks and snapshots
  avoid concurrent access to a raw framebuffer pointer.
* A private fourth UART at `0x10003000`, PLIC interrupt 9, carries bounded binary
  HID reports. `flyby-display-input` registers a keyboard and absolute pointer
  through UHID. Modules `evdev`, `uhid`, `hid-generic` and their dependencies are
  selected from the same pinned kernel. No Android input device is passed through.
  The third UART is always reserved so the fourth remains `ttyS3` with or without
  a shared folder.
* The display hardware exists on all VM boots; only the Wayland image starts the
  input and desktop services. Other images continue to use their serial terminal.
* Android copies at most ten frames per second while Display is resumed. This
  bounds host work; it is not a promise of ten guest-rendered frames per second.
* GUI programs run as the prototype's guest root user, like its existing terminal.
* No Xorg server, XWayland or Weston X11 backend is installed. Alpine's shared
  Cairo/Mesa dependencies do include X11 client libraries; they do not provide
  an X11 session. Native Wayland applications must also be available for riscv64.

`scripts/wayland-packages.json` pins the complete additional APK set by SHA256.
The cross-build uses apk signature checks and its installed database, with scripts
disabled. Required boot directories and the seatd group are created explicitly.
Build-only tools never enter the guest. Package licenses and source recipes,
including community packages, enter the provenance and corresponding-source bundle.
The compressed seed is approximately 97 MiB with the current package set.

## Validation

`python3 scripts/test-wayland.py` boots the real RV64 VM with 512 MiB RAM. It checks
OpenRC services, the Wayland socket, a nonblank 800 × 600 framebuffer, a command
typed into the graphical terminal through HID, pointer events and graceful shutdown.
It writes a framebuffer capture and boot log to `out/wayland-test/`.
Run after provisioning and building the native host targets, as in README.md.

For diagnosis, use Terminal: `rc-status`, `cat /var/log/weston.log`,
`cat /proc/bus/input/devices`, and `ls -l /dev/dri /dev/input`.
Physical Android touch/IME behavior and rendering performance remain device checks.
