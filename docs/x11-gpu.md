# Alpine X11 GPU

This separate image adds experimental OpenGL acceleration to the X11/twm/xterm
image. The guest's Mesa VirGL driver talks to a Virtio 1.0 MMIO GPU implemented
in Flyby. A dedicated native thread translates commands using virglrenderer and
Android EGL/OpenGL ES. No root access, host DRM nodes or Vulkan are required.
Supported OpenGL features depend on the device's GLES driver and VirGL.

1. Stop Linux and export the current disk if you need its contents.
2. Enable **Settings → Enable GPU**, then select **Alpine X11 GPU** in Disk Creator.
3. Select 768 MiB RAM, create the disk, start Linux and open Display.
4. Run `glxinfo -B` to see the renderer or `glxgears` to try a small 3D application.

Changing the setting requires Linux and disk transfers to be stopped. With GPU
off, creation and startup of GPU disks are blocked. Re-enabling GPU allows the
same disk to start without replacement. The ext4 label `flyby-x11-gpu` identifies
the requirement and survives the existing export/import format. Import and export
remain available with GPU off. Do not manually relabel a GPU disk.

Ordinary X11, Wayland, Service and Minimal images retain their existing devices
and do not allocate a GPU renderer, even if the setting is enabled. An app update
never converts an existing disk to the GPU image.

## Current limits

- OpenGL ES 3 support is required on the host. EGL/VirGL initialization failure
  is reported as a startup error and leaves the disk intact.
- The GPU image explicitly selects VirGL; it does not silently fall back to
  guest-side software rendering. It does not provide Vulkan.
- Display is fixed to 800 × 600, with readback into Flyby's existing Android
  Bitmap view. Presentation is still limited to 10 fps. 3D commands are accelerated,
  but readback and interpreted RISC-V execution limit performance.
- Resource accounting is bounded to 128 MiB, 1,024 resources and 64 contexts.
  Large textures, workloads and applications may exceed these limits.
- One VM can own the process-wide VirGL renderer. All rendering runs on one
  thread, and teardown joins it before RVVM releases guest RAM.
- Physical Android GPU compatibility and performance require device testing.

The native source archives and hashes are pinned in `scripts/prepare-native.py`.
`native/gpu-deps.cmake` builds a GLES-only renderer, without GBM/DRM, GLX,
Vulkan, video or an external render server. The small Android adapters use public
NDK APIs for the embedded Mesa utilities. Source archives and notices are included
in the corresponding-source release and APK license viewer.

Guest packages are pinned in `scripts/x11-gpu-packages.json`, on top of the X11
lockfile. The image loads `virtio_mmio` and `virtio_gpu`, enables GLX and glamor,
and uses a software cursor. Xorg remains local-only and cookie authenticated.

## Verification

`EGL_PLATFORM=surfaceless python3 scripts/test-x11-gpu.py` checks that native
startup rejects the disk with GPU disabled, then boots the real RISC-V guest,
requires a VirGL GLX renderer, runs gears and checks display, input and shutdown.
On CI the host EGL driver uses software rendering; this exercises the virtual
GPU protocol and guest graphics stack, not physical Android GPU speed.

Linux host builds need EGL headers and GLES runtime libraries (`libegl1-mesa-dev`
and `libgles2` on Ubuntu), plus Python 3/PyYAML for upstream source generation.
Android builds use the pinned NDK's EGL headers. GPU disk gating and backup
roundtrips also have JVM tests in `GpuImageTest`.
