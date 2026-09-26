/* SPDX-License-Identifier: Apache-2.0
 * Freestanding RV64 Linux UHID transport over Flyby's private fourth UART.
 * No shell commands, network listener or host-device passthrough.
 * Wire: 16 bytes: 'F','I',1,type (1 keyboard / 2 absolute pointer), report, padding.
 */
#include <linux/uhid.h>

static long syscall4(long nr, long x, long y, long z, long w) {
    register long a0 __asm__("a0") = x;
    register long a1 __asm__("a1") = y;
    register long a2 __asm__("a2") = z;
    register long a3 __asm__("a3") = w;
    register long a4 __asm__("a4") = 0;
    register long a7 __asm__("a7") = nr;
    __asm__ volatile("ecall" : "+r"(a0) : "r"(a1), "r"(a2), "r"(a3), "r"(a4), "r"(a7) : "memory");
    return a0;
}
static void zero(void *p, unsigned long n) { unsigned char *b = p; while (n--) *b++ = 0; }
static void copy(void *d, const void *s, unsigned long n) {
    unsigned char *to = d; const unsigned char *from = s; while (n--) *to++ = *from++;
}
static long readfd(int fd, void *p, unsigned long n) { return syscall4(63, fd, (long)p, n, 0); }
static long writefd(int fd, const void *p, unsigned long n) { return syscall4(64, fd, (long)p, n, 0); }
static const unsigned char keyboard[] = {
    0x05,1, 0x09,6, 0xa1,1, 0x05,7, 0x19,0xe0, 0x29,0xe7,
    0x15,0, 0x25,1, 0x75,1, 0x95,8, 0x81,2,
    0x75,8, 0x95,1, 0x81,1,
    0x19,0, 0x29,0x65, 0x15,0, 0x25,0x65, 0x75,8, 0x95,6, 0x81,0, 0xc0
};
static const unsigned char pointer[] = {
    0x05,1, 0x09,2, 0xa1,1, 0x09,1, 0xa1,0,
    0x05,9, 0x19,1, 0x29,3, 0x15,0, 0x25,1, 0x75,1, 0x95,3, 0x81,2,
    0x75,5, 0x95,1, 0x81,1,
    0x05,1, 0x09,0x30, 0x09,0x31, 0x15,0, 0x26,0xff,0x7f,
    0x75,16, 0x95,2, 0x81,2,
    0x09,0x38, 0x15,0x81, 0x25,0x7f, 0x75,8, 0x95,1, 0x81,6, 0xc0,0xc0
};
static int create(const char *name, unsigned namesize, const unsigned char *desc, unsigned size) {
    int fd = syscall4(56, -100, (long)"/dev/uhid", 2 | 0x800, 0);
    if (fd < 0) return -1;
    struct uhid_event e;
    zero(&e, sizeof(e)); e.type = UHID_CREATE2;
    copy(e.u.create2.name, name, namesize);
    e.u.create2.bus = BUS_VIRTUAL;
    e.u.create2.vendor = 0x1209; e.u.create2.product = 0xf1b0;
    e.u.create2.rd_size = size; copy(e.u.create2.rd_data, desc, size);
    if (writefd(fd, &e, sizeof(e)) != sizeof(e)) return -1;
    return fd;
}
static int report(int fd, const unsigned char *data, unsigned size) {
    struct uhid_event e;
    zero(&e, sizeof(e)); e.type = UHID_INPUT2;
    e.u.input2.size = size; copy(e.u.input2.data, data, size);
    return writefd(fd, &e, sizeof(e)) == sizeof(e);
}
static void reply(int fd, struct uhid_event *e) {
    unsigned id;
    if (e->type == UHID_GET_REPORT) {
        id = e->u.get_report.id; zero(e, sizeof(*e));
        e->type = UHID_GET_REPORT_REPLY; e->u.get_report_reply.id = id;
        e->u.get_report_reply.err = 5; /* EIO: no feature reports */
        writefd(fd, e, sizeof(*e));
    } else if (e->type == UHID_SET_REPORT) {
        id = e->u.set_report.id; zero(e, sizeof(*e));
        e->type = UHID_SET_REPORT_REPLY; e->u.set_report_reply.id = id;
        e->u.set_report_reply.err = 5; writefd(fd, e, sizeof(*e));
    }
}
__attribute__((used)) int run(void) {
    int serial = syscall4(56, -100, (long)"/dev/ttyS3", 2 | 0x800 | 0x100, 0);
    int kb = create("Flyby keyboard", 15, keyboard, sizeof(keyboard));
    int mouse = create("Flyby pointer", 14, pointer, sizeof(pointer));
    if (serial < 0 || kb < 0 || mouse < 0) return 1;
    struct { int fd; short events, revents; } fds[3] = {{serial,1,0},{kb,1,0},{mouse,1,0}};
    unsigned char packet[16]; unsigned used = 0, started = 0;
    for (;;) {
        long ret = syscall4(73, (long)fds, 3, 0, 0); /* ppoll, infinite timeout */
        if (ret == -4) continue;
        if (ret < 0) return 1;
        for (unsigned i = 1; i < 3; ++i) if (fds[i].revents & 1) {
            struct uhid_event e; zero(&e, sizeof(e));
            if (readfd(fds[i].fd, &e, sizeof(e)) > 0) {
                if (e.type == UHID_START) {
                    started |= 1u << i;
                    if (started == 6) {
                        static const char ready[] = "FLYBY_DISPLAY_READY\n";
                        writefd(serial, ready, sizeof(ready)-1);
                    }
                }
                reply(fds[i].fd, &e);
            }
        }
        if (fds[0].revents & 1) {
            long n = readfd(serial, packet + used, sizeof(packet) - used);
            if (n <= 0) continue;
            used += n;
            if (used == sizeof(packet)) {
                if (packet[0] == 'F' && packet[1] == 'I' && packet[2] == 1) {
                    if (packet[3] == 1 && !report(kb, packet+4, 8)) return 1;
                    if (packet[3] == 2 && !report(mouse, packet+4, 6)) return 1;
                    used = 0;
                } else {
                    for (unsigned i = 1; i < sizeof(packet); ++i) packet[i-1] = packet[i];
                    used = sizeof(packet)-1;
                }
            }
        }
    }
}
__asm__(".global _start\n_start:\ncall run\nli a7,93\necall\n");
