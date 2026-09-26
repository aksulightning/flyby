package io.github.aksulightning.flyby.vm

/** Seed selection affects newly created disks only; the active disk always uses system.raw. */
enum class ImageVariant(val title: String, val seed: String, val description: String) {
    MINIMAL("Minimal Alpine", "system", "Alpine Edge with the current lightweight BusyBox init."),
    SERVICE("Service Alpine", "service", "Alpine Edge with OpenRC init and persistent service management."),
    WAYLAND("Minimal Alpine Wayland", "wayland", "Service Alpine with an 800 × 600 Weston desktop. No Xorg or XWayland. 512–768 MiB RAM recommended.");
}
