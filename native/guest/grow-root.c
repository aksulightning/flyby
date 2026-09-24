/* SPDX-License-Identifier: Apache-2.0
 * Freestanding RV64 Linux helper: no libc or new runtime licensing dependency.
 * Expand ext4 to the backing disk size before switch_root. Fixed private paths.
 */
typedef unsigned long u64;
typedef unsigned int u32;
static long call(long number, long x, long y, long z) {
    register long a0 __asm__("a0") = x;
    register long a1 __asm__("a1") = y;
    register long a2 __asm__("a2") = z;
    register long a7 __asm__("a7") = number;
    __asm__ volatile("ecall" : "+r"(a0) : "r"(a1), "r"(a2), "r"(a7) : "memory");
    return a0;
}
static u32 le32(const unsigned char *b) { return (u32)b[0] | (u32)b[1]<<8 | (u32)b[2]<<16 | (u32)b[3]<<24; }
__attribute__((used)) int grow(void) {
    unsigned char sb[1024];
    u64 bytes;
    long device = call(56, -100, (long)"/dev/nvme0n1", 0); /* openat */
    long root = call(56, -100, (long)"/newroot", 0);
    if (device < 0 || root < 0 || call(29, device, 0x80081272UL, (long)&bytes) < 0 ||
        call(62, device, 1024, 0) < 0 || call(63, device, (long)sb, sizeof(sb)) != (long)sizeof(sb)) return 1;
    call(57, device, 0, 0);
    if (sb[56] != 0x53 || sb[57] != 0xef || le32(sb+24) > 6 || bytes < (1UL<<30) || bytes > (100UL<<30)) return 1;
    u64 blockSize = 1024UL << le32(sb+24);
    if (bytes % blockSize) return 1;
    u64 blocks = bytes / blockSize;
    u64 current = le32(sb+4);
    if (le32(sb+96) & 0x80) current |= (u64)le32(sb+336) << 32;
    if (blocks < current) return 1;
    if (blocks > current && call(29, root, 0x40086610UL, (long)&blocks) < 0) return 1; /* EXT4_IOC_RESIZE_FS */
    if (call(82, root, 0, 0) < 0) return 1; /* fsync */
    call(57, root, 0, 0);
    return 0;
}
__asm__(".global _start\n_start:\ncall grow\nli a7,93\necall\n");
