"""Pinned Xorg-only image derived from Service Alpine; no Wayland compositor."""
import json
import pathlib
import shutil
import subprocess
from display_image import prepare as prepare_display

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT/'scripts/x11-packages.json').read_text())
# Keep each image's versions separate when a shared dependency has different pins.
PACKAGES = {'x11-'+p['name']+'.apk': (p['path'], p['sha256']) for p in LOCK}


def build(service, root, paths):
    if root.exists(): shutil.rmtree(root)
    shutil.copytree(service, root, symlinks=True)
    subprocess.run([str(root.parent/'apk.static'), '--root', str(root), '--arch', 'riscv64',
                    '--no-scripts', '--no-network', 'add',
                    *[str(paths[name]) for name in PACKAGES]], check=True)
    installed = '\n'+(root/'lib/apk/db/installed').read_text()
    if any('\nP:'+name+'\n' in installed for name in ('weston', 'xwayland', 'weston-xwayland')):
        raise ValueError('X11 image must not contain a Wayland compositor or XWayland')
    prepare_display(root)

    def write(name, value, mode=0o644):
        p = root/name
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.is_symlink(): p.unlink()
        p.write_text(value); p.chmod(mode)

    write('etc/flyby-image', 'Minimal Alpine X11\n')
    write('etc/X11/xorg.conf.d/10-flyby.conf', '''Section "Module"
    Disable "glx"
EndSection

Section "Device"
    Identifier "Flyby framebuffer"
    Driver "modesetting"
    Option "kmsdev" "/dev/dri/card0"
    Option "AccelMethod" "none"
    Option "ShadowFB" "true"
EndSection

Section "InputClass"
    Identifier "Flyby input"
    MatchIsKeyboard "on"
    Driver "libinput"
    Option "XkbLayout" "us"
    Option "XkbOptions" "compose:ralt"
EndSection

Section "InputClass"
    Identifier "Flyby pointer"
    MatchIsPointer "on"
    Driver "libinput"
EndSection
''')
    write('etc/profile.d/x11.sh', '''export DISPLAY=:0
export XAUTHORITY=/run/flyby-x11/Xauthority
export XDG_SESSION_TYPE=x11
''')
    with (root/'etc/profile').open('a') as profile:
        profile.write('\n. /etc/profile.d/x11.sh\n')
    write('etc/X11/flyby.twmrc', '''RandomPlacement
NoGrabServer
NoTitle
BorderWidth 2
''')
    write('usr/local/bin/flyby-x11-shell', '''#!/bin/sh
printf '%s\\n' "$$" > /run/flyby-x11/terminal-ready
exec /bin/sh -l
''', 0o755)
    write('etc/init.d/flyby-x11', '''#!/sbin/openrc-run
description="Flyby X11 server (800x600)"
supervisor="supervise-daemon"
command="/usr/bin/Xorg"
command_args=":0 vt1 -noreset -nolisten tcp -novtswitch -auth /run/flyby-x11/Xauthority -logfile /var/log/Xorg.0.log"
output_log="/var/log/flyby-x11.log"
error_log="/var/log/flyby-x11.log"
respawn_delay=3
respawn_max=5
retry="TERM/3/KILL/2"
depend() { need flyby-display-input udev-trigger; }
start_pre() {
    mkdir -p /run/flyby-x11 /tmp/.X11-unix || return 1
    chmod 700 /run/flyby-x11
    chmod 1777 /tmp/.X11-unix
    local cookie i=0
    cookie=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \\n')
    [ "${#cookie}" -eq 32 ] || return 1
    touch /run/flyby-x11/Xauthority
    chmod 600 /run/flyby-x11/Xauthority
    xauth -f /run/flyby-x11/Xauthority add :0 MIT-MAGIC-COOKIE-1 "$cookie" || return 1
    while [ ! -e /dev/dri/card0 ] || [ ! -e /dev/input/event0 ]; do
        i=$((i+1))
        [ "$i" -lt 60 ] || { eerror "Timed out waiting for Flyby display/input devices"; return 1; }
        sleep 1
    done
    udevadm settle --timeout=60 || return 1
    # Package triggers cannot run on the cross-build host.
    for fonts in /usr/share/fonts/misc /usr/share/fonts/100dpi /usr/share/fonts/75dpi; do
        [ ! -d "$fonts" ] || mkfontdir "$fonts" || return 1
    done
}
''', 0o755)
    write('etc/init.d/flyby-x11-wm', '''#!/sbin/openrc-run
description="Flyby X11 window manager"
supervisor="supervise-daemon"
command="/usr/bin/twm"
command_args="-f /etc/X11/flyby.twmrc"
export DISPLAY=:0
export XAUTHORITY=/run/flyby-x11/Xauthority
output_log="/var/log/flyby-x11-wm.log"
error_log="/var/log/flyby-x11-wm.log"
respawn_delay=3
respawn_max=5
retry="TERM/2/KILL/2"
depend() { need flyby-x11; }
start_pre() {
    local i=0
    until xdpyinfo >/dev/null 2>&1; do
        i=$((i+1))
        [ "$i" -lt 60 ] || { eerror "X11 server unavailable; see /var/log/Xorg.0.log"; return 1; }
        sleep 1
    done
}
''', 0o755)
    write('etc/init.d/flyby-x11-terminal', '''#!/sbin/openrc-run
description="Flyby X11 terminal"
supervisor="supervise-daemon"
command="/usr/bin/xterm"
command_args="-geometry 96x30+8+8 -fa monospace -fs 10 -e /usr/local/bin/flyby-x11-shell"
export DISPLAY=:0
export XAUTHORITY=/run/flyby-x11/Xauthority
export XDG_SESSION_TYPE=x11
output_log="/var/log/flyby-x11-terminal.log"
error_log="/var/log/flyby-x11-terminal.log"
respawn_delay=3
respawn_max=5
retry="TERM/2/KILL/2"
depend() { need flyby-x11-wm; }
start_pre() { rm -f /run/flyby-x11/terminal-ready; }
''', 0o755)
    for level, names in {'boot': ['flyby-display-prepare', 'udev', 'udev-trigger', 'udev-settle'],
                         'default': ['flyby-display-input', 'flyby-x11', 'flyby-x11-wm', 'flyby-x11-terminal']}.items():
        for name in names:
            link = root/'etc/runlevels'/level/name
            link.parent.mkdir(parents=True, exist_ok=True)
            link.unlink(missing_ok=True)
            link.symlink_to('/etc/init.d/'+name)
