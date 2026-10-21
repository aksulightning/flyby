# Alpine X11 GPU

This separate image adds experimental OpenGL acceleration to the X11/twm/xterm
image. The guest's Mesa VirGL driver talks to a Virtio 1.0 MMIO GPU implemented
in Flyby. A dedicated native thread translates commands using virglrenderer and
Android EGL/OpenGL ES. No Android root access or host DRM nodes are required. Vulkan is an optional,
experimental Venus path described below.
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
  guest-side software rendering. Vulkan remains disabled unless Experimental Vulkan is enabled.
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
video or an external render server. Venus dynamically loads the system Vulkan
loader when requested. The small Android adapters use public
NDK APIs for the embedded Mesa utilities. Source archives and notices are included
in the corresponding-source release and APK license viewer.

Guest packages and their dependencies are resolved by apk and recorded alongside
X11 in the shared `out/alpine/lock.json` snapshot. The image loads `virtio_mmio` and `virtio_gpu`, enables GLX and glamor,
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

## Experimental Vulkan

Stop Linux, enable GPU and Experimental Vulkan, and use a newly created X11 GPU
disk. New GPU images include Mesa's RISC-V Venus ICD, the Vulkan loader,
`vulkaninfo`, `vkcube`, and `flyby-vulkan-check`. Ordinary applications and
these checks run as `flybyuser`; only the virtual GPU render nodes are granted
to that user.

The startup probe runs inside the isolated worker. Android needs Vulkan 1.1,
VK_EXT_external_memory_dma_buf, VK_EXT_image_drm_format_modifier and
VK_EXT_queue_family_foreign. Meeting these requirements is a compatibility
candidate, not a promise: external memory mapping depends on the phone driver.
Initialization/mapping errors are reported; turn Vulkan off to retain OpenGL.

The guest's Vulkan calls use Venus through Virtio-GPU context initialization,
blob resources, a bounded 128 MiB host-visible window, and per-context timeline
fences. Host-visible memory is exposed through interpreted MMIO accesses;
the guest never supplies a host pointer. Mappings must fit the window and cannot
overlap. Existing GPU resource and context limits remain in effect. The blob limit bounds
shared transport memory; driver-internal Vulkan allocations are not fully accounted.

Presentation uses Mesa's `MESA_VK_WSI_DEBUG=sw` path to copy Vulkan results into
the X11 display. Vulkan commands still execute through the host driver, while
presentation retains CPU copying and the existing 800x600/10fps limit.

Try `vulkaninfo --summary`, `flyby-vulkan-check`, then `vkcube --c 60`.
The check fills a Vulkan buffer on a queue, waits for its fence and verifies
mapped memory. CI runs these through the real RISC-V guest with a software host
Vulkan driver; physical Android GPU behavior still needs device testing.

Alpine does not publish its Venus package for riscv64. GitHub Actions builds
the client from the exact locked Mesa recipe/source and verified patches.
`custom_components.venus` in guest provenance records source hashes, build
image, tool packages and output hashes. It is not represented as an official
Alpine binary package. Existing GPU disks are not modified; create a new GPU
disk after exporting files to obtain the driver and acceptance helper.
