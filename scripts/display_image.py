"""Common framebuffer input bridge and device services for graphical images."""
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]


def prepare(root):
    def write(name, value, mode=0o644):
        p = root/name
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.is_symlink(): p.unlink()
        p.write_text(value); p.chmod(mode)

    helper = root/'usr/local/sbin/flyby-display-input'
    helper.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([os.environ.get('RISCV_CC', 'riscv64-linux-gnu-gcc'), '-Os', '-static', '-s',
                    '-nostdlib', '-ffreestanding', '-fno-builtin', '-fno-stack-protector',
                    '-mno-relax', '-msmall-data-limit=0', '-Wl,-e,_start', '-Wall', '-Wextra', '-Werror',
                    str(ROOT/'native/guest/display-input.c'), '-o', str(helper)], check=True)
    write('etc/init.d/flyby-display-prepare', '''#!/sbin/openrc-run
description="Prepare Flyby display devices"
depend() { need flyby-boot; before udev; }
start() {
    modprobe evdev && modprobe hid-generic && modprobe uhid || return 1
    mkdir -p /dev/shm || return 1
    mountpoint -q /dev/shm || mount -t tmpfs -o mode=1777,nosuid,nodev,size=64m tmpfs /dev/shm || return 1
    stty -F /dev/ttyS3 raw -echo || return 1
}
''', 0o755)
    write('etc/init.d/flyby-display-input', '''#!/sbin/openrc-run
description="Flyby keyboard and absolute pointer"
supervisor="supervise-daemon"
command="/usr/local/sbin/flyby-display-input"
respawn_delay=1
respawn_max=5
retry="TERM/2/KILL/2"
depend() { need flyby-display-prepare udev; }
''', 0o755)
