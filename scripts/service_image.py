"""Build the optional OpenRC root from the unchanged Minimal root, offline."""
import json
import pathlib
import shutil
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
# Filled from the per-build resolution before creating any images.
LOCK = []
PACKAGES = {}


def build(minimal, service, paths, apk_archive):
    if service.exists(): shutil.rmtree(service)
    shutil.copytree(minimal, service, symlinks=True)
    apk = service.parent / 'apk.static'
    with tarfile.open(apk_archive, ignore_zeros=True) as archive:
        apk.write_bytes(archive.extractfile('sbin/apk.static').read())
    apk.chmod(0o755)
    # Use apk's real dependency solver, file tracking and installed database. No
    # cross-architecture scripts or network resolution. Signatures are verified
    # against the minirootfs's Alpine keys, in addition to the snapshot SHA256.
    subprocess.run([str(apk), '--root', str(service), '--arch', 'riscv64',
                    '--no-scripts', '--no-network', 'add',
                    *[str(paths[name]) for name in PACKAGES]], check=True)

    def write(name, text, mode=0o755):
        path = service / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.is_symlink(): path.unlink()
        path.write_text(text)
        path.chmod(mode)

    # The image's PID 1 is OpenRC. Its package deliberately leaves /sbin/init
    # selection to the integrator. The wrapper gives PID 1 the executable
    # name openrc-init and leaves Minimal's BusyBox init untouched.
    write('sbin/init', '#!/bin/sh\nexec /sbin/openrc-init "$@"\n')
    (service/'etc/inittab').unlink()  # openrc-init does not use BusyBox inittab.
    write('etc/flyby-image', 'Service Alpine\n', 0o644)
    with (service/'etc/rc.conf').open('a') as config:
        config.write('\n# Flyby is a full guest; initramfs already mounts proc/sys/dev/run.\n'
                     'rc_sys=""\nrc_nocolor=YES\nrc_interactive=NO\nrc_logger=NO\n')
    boot = (service/'etc/flyby-boot').read_text().replace('/etc/flyby-network &\n', '')
    write('etc/flyby-boot', boot)
    write('etc/init.d/flyby-boot', '''#!/sbin/openrc-run
description="Flyby persistent system readiness"
start() { /bin/sh /etc/flyby-boot; }
''')
    write('etc/init.d/flyby-network', '''#!/sbin/openrc-run
description="Flyby virtual Ethernet"
depend() { need flyby-boot; provide net; }
start() { /etc/flyby-network; }
stop() { ip addr flush dev eth0; ip link set eth0 down; }
''')
    # Supervised daemons keep working after a shell exit or daemon crash.
    for name, args in [('control', '/bin/sh /etc/flyby-control'),
                       ('console', '/bin/busybox getty -L -n -l /etc/flyby-login 115200 ttyS0 xterm-256color')]:
        command, arguments = args.split(' ', 1)
        write('etc/init.d/flyby-'+name, f'''#!/sbin/openrc-run
description="Flyby {name}"
supervisor="supervise-daemon"
command="{command}"
command_args="{arguments}"
respawn_delay=1
respawn_max=0
retry="TERM/2/KILL/2"
depend() {{ need flyby-boot{' flyby-control' if name == 'console' else ''}; }}
''')
    write('etc/flyby-login', '''#!/bin/sh
exec /bin/sh -l
''')
    control = (service/'etc/flyby-control').read_text().replace('/bin/busybox poweroff', '/sbin/openrc-shutdown -p now')
    write('etc/flyby-control', control)
    for name, option in [('poweroff', '-p'), ('halt', '-H'), ('reboot', '-r')]:
        write('usr/local/sbin/'+name, f'#!/bin/sh\nexec /sbin/openrc-shutdown {option} now "$@"\n')
    # Only enable hardware services appropriate to this already-mounted VM.
    # Other packaged service scripts remain available for rc-update/rc-service.
    for level, services in {
        'boot': ['flyby-boot'],
        'default': ['flyby-control', 'flyby-console', 'flyby-network'],
        'shutdown': ['killprocs', 'savecache', 'mount-ro'],
    }.items():
        for name in services:
            link = service / 'etc/runlevels' / level / name
            link.parent.mkdir(parents=True, exist_ok=True)
            link.unlink(missing_ok=True)
            link.symlink_to('/etc/init.d/'+name)
    (service/'var/cache/rc').mkdir(parents=True, exist_ok=True)
    (service/'etc/hostname').write_text('flyby\n')
