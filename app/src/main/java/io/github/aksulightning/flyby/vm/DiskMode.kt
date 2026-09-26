package io.github.aksulightning.flyby.vm

enum class DiskMode(val fileName: String, val seedName: String, val size: Long) {
    DATA("disk.raw", "disk.seed", 256L * 1024 * 1024),
    SYSTEM("system.raw", "system.seed", 1024L * 1024 * 1024);
    companion object { const val GUEST_ID = "riscv64-alpine-3.23.6-linux-6.18.53-v1" }
}
