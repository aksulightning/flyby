"""Pinned, offline Weston image derived from the Service root (no X server)."""
import json
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT/'scripts/wayland-packages.json').read_text())
PACKAGES = {p['name']+'.apk': (p['path'], p['sha256']) for p in LOCK}


def build(service, root, paths):
    if root.exists(): shutil.rmtree(root)
    shutil.copytree(service, root, symlinks=True)
    subprocess.run([str(root.parent/'apk.static'), '--root', str(root), '--arch', 'riscv64',
                    '--no-scripts', '--no-network', 'add',
                    *[str(paths[name]) for name in PACKAGES]], check=True)
    installed = (root/'lib/apk/db/installed').read_text()
    forbidden = ('xorg-server', 'xwayland', 'weston-xwayland', 'weston-backend-x11')
    if any('\nP:'+name+'\n' in '\n'+installed for name in forbidden):
        raise ValueError('Wayland image must not contain an X server or X11 backend')

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
    write('etc/flyby-image', 'Minimal Alpine Wayland\n')
    # apk scripts cannot execute on the cross-build host. Reproduce seatd's
    # pre-install group creation explicitly, without changing existing IDs.
    groups = (root/'etc/group').read_text()
    if not any(line.startswith('seat:') for line in groups.splitlines()):
        used = {int(line.split(':')[2]) for line in groups.splitlines() if ':' in line}
        gid = next(n for n in range(100, 1000) if n not in used)
        with (root/'etc/group').open('a') as f: f.write(f'seat:x:{gid}:\n')
    write('etc/xdg/weston/weston.ini', '''[core]
backend=drm
renderer=pixman
xwayland=false
idle-time=0
require-input=true

[shell]
locking=false
panel-position=top
background-color=0xff183044
animation=none

[keyboard]
keymap_layout=us
keymap_options=compose:ralt

[launcher]
icon=/usr/share/weston/terminal.png
path=/usr/bin/weston-terminal
''')
    write('etc/profile.d/wayland.sh', '''export XDG_RUNTIME_DIR=/run/flyby-wayland
export WAYLAND_DISPLAY=wayland-0
export XDG_SESSION_TYPE=wayland
''')
    # Flyby's minimal /etc/profile does not source Alpine's profile.d directory.
    # Make native Wayland clients launched from the serial login find Weston too.
    with (root/'etc/profile').open('a') as profile:
        profile.write('\n. /etc/profile.d/wayland.sh\n')
    # Weston executes --shell only after the terminal window is configured.
    # Expose that milestone separately from OpenRC's process-start status.
    write('usr/local/bin/flyby-wayland-shell', '''#!/bin/sh
printf '%s\\n' "$$" > /run/flyby-wayland/terminal-ready
exec /bin/sh -l
''', 0o755)
    write('etc/init.d/flyby-display-prepare', '''#!/sbin/openrc-run
description="Prepare Flyby display devices"
depend() { need flyby-boot; before udev; }
start() {
    modprobe evdev && modprobe hid-generic && modprobe uhid || return 1
    mkdir -p /run/flyby-wayland /dev/shm || return 1
    chmod 700 /run/flyby-wayland
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
    write('etc/init.d/flyby-wayland', '''#!/sbin/openrc-run
description="Flyby Wayland desktop (800x600)"
supervisor="supervise-daemon"
command="/usr/bin/weston"
command_args="--backend=drm --renderer=pixman --socket=wayland-0 --config=/etc/xdg/weston/weston.ini --log=/var/log/weston.log"
output_log="/var/log/weston-clients.log"
error_log="/var/log/weston-clients.log"
export XDG_RUNTIME_DIR=/run/flyby-wayland
export WAYLAND_DISPLAY=wayland-0
export XDG_SESSION_TYPE=wayland
export LIBSEAT_BACKEND=seatd
respawn_delay=3
respawn_max=5
retry="TERM/3/KILL/2"
depend() { need flyby-display-input seatd udev-trigger; }
start_pre() {
    local i=0
    while [ ! -e /dev/dri/card0 ] || [ ! -e /dev/input/event0 ]; do
        i=$((i+1))
        if [ "$i" -ge 60 ]; then
            eerror "Timed out waiting for Flyby DRM/input devices; check /dev/dri and /dev/input"
            return 1
        fi
        sleep 1
    done
    udevadm settle --timeout=60
}
''', 0o755)
    write('etc/init.d/flyby-wayland-terminal', '''#!/sbin/openrc-run
description="Flyby Wayland terminal"
supervisor="supervise-daemon"
command="/usr/bin/weston-terminal"
command_args="--shell=/usr/local/bin/flyby-wayland-shell"
output_log="/var/log/weston-terminal.log"
error_log="/var/log/weston-terminal.log"
export XDG_RUNTIME_DIR=/run/flyby-wayland
export WAYLAND_DISPLAY=wayland-0
export XDG_SESSION_TYPE=wayland
respawn_delay=3
respawn_max=5
retry="TERM/2/KILL/2"
depend() { need flyby-wayland; }
start_pre() {
    rm -f "$XDG_RUNTIME_DIR/terminal-ready"
    local i=0
    while [ ! -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]; do
        i=$((i+1))
        if [ "$i" -ge 60 ]; then
            eerror "Timed out waiting for Weston; see /var/log/weston.log and /var/log/weston-clients.log"
            return 1
        fi
        sleep 1
    done
}
''', 0o755)
    for level, names in {'boot': ['flyby-display-prepare', 'udev', 'udev-trigger', 'udev-settle'],
                         'default': ['seatd', 'flyby-display-input', 'flyby-wayland', 'flyby-wayland-terminal']}.items():
        for name in names:
            link = root/'etc/runlevels'/level/name
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to('/etc/init.d/'+name)
