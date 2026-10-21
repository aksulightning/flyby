"""Normal guest account and password-authenticated administration for fresh disks."""
import pathlib

USER = 'flybyuser'
UID = 1000
LOCK = []
PACKAGES = {}

def configure(root):
    def write(name, text, mode=0o644):
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.is_symlink():
            path.unlink()
        path.write_text(text)
        path.chmod(mode)

    # apk installation scripts are deliberately disabled during cross builds.
    for filename, record in [
        ('etc/passwd', 'flybyuser:x:1000:1000:Flyby user:/home/flybyuser:/bin/ash'),
        ('etc/group', 'flybyuser:x:1000:'),
        ('etc/shadow', 'flybyuser:!:0:0:99999:7:::'),
    ]:
        path = root/filename
        lines = path.read_text().splitlines()
        if any(line.startswith('flybyuser:') for line in lines):
            raise ValueError('Unexpected pre-existing flybyuser account in seed')
        if filename == 'etc/passwd' and any(line.split(':')[2] == '1000' for line in lines):
            raise ValueError('UID 1000 is already allocated')
        if filename == 'etc/group' and any(line.split(':')[2] == '1000' for line in lines):
            raise ValueError('GID 1000 is already allocated')
        if filename == 'etc/shadow':
            lines = [(':'.join([parts[0], '!', *parts[2:]]) if parts[0] == 'root' else line)
                     for line in lines for parts in [line.split(':')]]
        write(filename, '\n'.join(lines + [record])+'\n', 0o600 if filename.endswith('shadow') else 0o644)
    home = root/'home/flybyuser'
    home.mkdir(parents=True, exist_ok=True)
    home.chmod(0o700)
    # Weston obtains DRM/input devices through seatd; the desktop user needs
    # only membership in seat, never wheel, disk, input or unrestricted devices.
    group = root/'etc/group'
    lines = group.read_text().splitlines()
    lines = [line + (',' if line.split(':')[3] else '') + USER if line.startswith('seat:') else line
             for line in lines]
    group.write_text('\n'.join(lines)+'\n')
    write('etc/doas.conf', 'permit flybyuser as root\n', 0o600)
    doas = root/'usr/bin/doas'
    if not doas.is_file():
        raise ValueError('The base image must install doas')
    doas.chmod(0o4755)
    write('etc/flyby-login', """#!/bin/sh
# This fixed root-owned console entry point is never callable through doas.
# No password, password hash or reusable credential ships in an image.
if grep -Eq '^flybyuser:(!|\\*|:)' /etc/shadow; then
    original_tty=$(stty -g) || exit 1
    trap 'stty "$original_tty"' 0 1 2 15
    stty -echo || exit 1
    printf '\\nFLYBY_PASSWORD_SETUP\\nSet a password for flybyuser to enable doas.\\n'
    until passwd flybyuser; do
        echo 'Password setup failed; please try again.'
    done
    stty "$original_tty" || exit 1
    trap - 0 1 2 15
fi
exec su - flybyuser
""", 0o755)
    inittab = root/'etc/inittab'
    if inittab.exists():
        inittab.write_text(inittab.read_text().replace('ttyS0::respawn:/bin/sh -l',
                                                     'ttyS0::respawn:/etc/flyby-login'))
    profile = root/'etc/profile'
    prompt = """if [ "$(id -u)" -eq 0 ]; then
    export PS1='root@flyby:\\w# '
else
    export PS1='flybyuser@flyby:\\w$ '
fi"""
    profile.write_text('\n'.join(prompt if line.startswith('export PS1=') else line
                                 for line in profile.read_text().splitlines())+'\n')
    if (root/'etc/init.d/flyby-x11').exists():
        write('etc/udev/rules.d/70-flyby-render.rules',
              'SUBSYSTEM=="drm", KERNEL=="renderD*", GROUP="flybyuser", MODE="0660"\n')
    # Keep privileged device setup separate from ordinary desktop clients.
    for service in ('flyby-x11-wm', 'flyby-x11-terminal', 'flyby-wayland', 'flyby-wayland-terminal'):
        path = root/'etc/init.d'/service
        if path.exists():
            value = path.read_text().replace('supervisor="supervise-daemon"',
                'supervisor="supervise-daemon"\ncommand_user="flybyuser:flybyuser"\ndirectory=/home/flybyuser\nexport HOME=/home/flybyuser\nexport USER=flybyuser\nexport LOGNAME=flybyuser')
            # supervise-daemon opens output files after dropping privileges.
            # Create fixed log paths as root, then allow the selected user to write.
            value = value.replace('start_pre() {', 'start_pre() {\n'
                '    touch "$output_log" "$error_log" || return 1\n'
                '    chown flybyuser:flybyuser "$output_log" "$error_log" || return 1\n')
            if service == 'flyby-wayland':
                value = value.replace('chmod 700 "$XDG_RUNTIME_DIR"',
                    'chmod 700 "$XDG_RUNTIME_DIR"\n    chown flybyuser:flybyuser "$XDG_RUNTIME_DIR"\n'
                    '    touch /var/log/weston.log || return 1\n    chown flybyuser:flybyuser /var/log/weston.log || return 1')
            path.write_text(value)
    x11 = root/'etc/init.d/flyby-x11'
    if x11.exists():
        value = x11.read_text().replace('chmod 700 /run/flyby-x11',
            'chmod 700 /run/flyby-x11\n    chown flybyuser:flybyuser /run/flyby-x11')
        value = value.replace('chmod 600 /run/flyby-x11/Xauthority',
            'chmod 600 /run/flyby-x11/Xauthority\n    chown flybyuser:flybyuser /run/flyby-x11/Xauthority')
        value = value.replace('xauth -f /run/flyby-x11/Xauthority add :0 MIT-MAGIC-COOKIE-1 "$cookie" || return 1',
            'xauth -f /run/flyby-x11/Xauthority add :0 MIT-MAGIC-COOKIE-1 "$cookie" || return 1\n'
            '    chown flybyuser:flybyuser /run/flyby-x11/Xauthority')
        x11.write_text(value)


def owner(name):
    """Ownership for image serialization, independent of the build runner UID."""
    return (UID, UID) if name == 'home/flybyuser' or name.startswith('home/flybyuser/') else (0, 0)
