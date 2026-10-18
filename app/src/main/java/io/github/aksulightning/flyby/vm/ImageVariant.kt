package io.github.aksulightning.flyby.vm

/** Seed selection affects newly created disks only; the active disk always uses system.raw. */
enum class ImageVariant(val title: String, val seed: String, val description: String, val requiresGpu: Boolean = false) {
    MINIMAL("Minimal Alpine", "system", "Alpine Edge with the current lightweight BusyBox init."),
    SERVICE("Service Alpine", "service", "Alpine Edge with OpenRC init and persistent service management."),
    WAYLAND("Minimal Alpine Wayland", "wayland", "Service Alpine with an 800 × 600 Weston desktop. No Xorg or XWayland. 512–768 MiB RAM recommended."),
    X11("Minimal Alpine X11", "x11", "Service Alpine with Xorg, a lightweight window manager and xterm at 800 × 600. No Wayland session. 512–768 MiB RAM recommended."),
    X11_GPU("Alpine X11 GPU", "x11-gpu", "X11 with experimental VirGL 3D acceleration. Requires GPU enabled and an OpenGL ES 3 compatible device. Display updates are currently limited to 10 fps. 768 MiB RAM recommended.", true);

    fun available(gpuEnabled: Boolean) = !requiresGpu || gpuEnabled
}
