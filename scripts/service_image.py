"""Build the optional OpenRC root from the unchanged Minimal root, offline."""
import pathlib
import shutil
import subprocess
import tarfile

PACKAGES = {
    'openrc.apk': ('main/riscv64/openrc-0.63.2-r1.apk', '4c0db28380ddd12ba945262fbe1d652200efa6cd2617f16782673ab02f996fc4'),
    'openrc-init.apk': ('main/riscv64/openrc-init-0.63.2-r1.apk', '7508f057a792e797b7835791ce284b49c281504d77c11a7723c51954faf2f9bd'),
    'openrc-user.apk': ('main/riscv64/openrc-user-0.63.2-r1.apk', 'd4cc2a5a276461259944363e12b346d8602fba38195e5bdc171d54d62619b663'),
    'libcap2.apk': ('main/riscv64/libcap2-2.78-r0.apk', '5b750d8ab948fedeb2c4b2eb94e04ef72c6025a5e68595c2401d6824b61f3483'),
    'busybox-ifupdown.apk': ('main/riscv64/busybox-ifupdown-1.38.0-r7.apk', '8e821df3c3694f4a0c325361837de1cbca5ac2e23752a91c928076da3767fb9d'),
}
# Build-only host tool; never copied into a guest or APK. Linux x86_64 build host.
APK_TOOL = ('main/x86_64/apk-tools-static-3.0.8-r0.apk', '673f1bfb22136fc42ca035321cf731a4ad0452c000928666cb39a138f0c277bf')


def build(minimal, service, paths, apk_archive):
    if service.exists(): shutil.rmtree(service)
    shutil.copytree(minimal, service, symlinks=True)
    apk = service.parent / 'apk.static'
    with tarfile.open(apk_archive, ignore_zeros=True) as archive:
        apk.write_bytes(archive.extractfile('sbin/apk.static').read())
    apk.chmod(0o755)
    # Use apk's real dependency solver, file tracking and installed database. No
    # cross-architecture scripts or network resolution. Signatures are verified
    # against the pinned minirootfs's Alpine keys, in addition to SHA256 pins.
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
            link.symlink_to('/etc/init.d/'+name)
    (service/'var/cache/rc').mkdir(parents=True, exist_ok=True)
    (service/'etc/hostname').write_text('flyby\n')
