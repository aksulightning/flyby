#!/usr/bin/env python3
"""Pinned Alpine artifacts -> deterministic development initramfs. No root/tools needed."""
import gzip, hashlib, io, json, pathlib, stat, tarfile, urllib.request, os, subprocess, shutil
from service_image import PACKAGES as SERVICE_PACKAGES, APK_TOOL, build as build_service
ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / 'out/downloads'
DEST = ROOT / 'app/src/main/assets/vm'
BASE = 'https://dl-cdn.alpinelinux.org/alpine/edge/'
ARTIFACTS = {
 'linux-lts.apk': ('main/riscv64/linux-lts-6.18.53-r0.apk', 'eeadfce6e7a3740ffda666b1d694ef23ecc04007b4f989198208fe61e6b0fda3'),
 'opensbi.apk': ('main/riscv64/opensbi-1.9-r0.apk', 'a27f535aeb52580a7c2b531999fef249e8d402af25e276e6c269188c38889c3c'),
 'alpine-minirootfs.tar.gz': ('releases/riscv64/alpine-minirootfs-20260805-riscv64.tar.gz', 'fc8f2160b00fea310db5c2e49f8f57582ae5e3f870e7e6ef3859db6ed6682e5a'),
}
def fetch(name, url, digest):
    path = CACHE / digest / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        tmp = path.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=90) as source, tmp.open('wb') as target:
            import shutil
            shutil.copyfileobj(source, target)
        tmp.replace(path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise ValueError(f'SHA256 mismatch: {path}; remove it and retry')
    return path

def member(path, name):
    with tarfile.open(path, ignore_zeros=True) as tar:
        return tar.extractfile(name).read()

def initramfs(rootfs, kernel_package, system_root, expected_release='Alpine Linux edge', repository_base=BASE):
    entries = {}
    with tarfile.open(rootfs) as tar:
        # tarfile resolves the archive's /etc/os-release -> /usr/lib/os-release link.
        if f'PRETTY_NAME="{expected_release}"'.encode() not in tar.extractfile('./etc/os-release').read():
            raise ValueError(f'The bundled root filesystem must be {expected_release}')
        for item in tar:
            name = item.name.removeprefix('./').rstrip('/')
            if not name: continue
            if name.startswith('/') or '..' in pathlib.PurePosixPath(name).parts:
                raise ValueError('Unsafe archive name')
            if item.isdir(): data, mode = b'', stat.S_IFDIR | item.mode
            elif item.issym(): data, mode = item.linkname.encode(), stat.S_IFLNK | item.mode
            elif item.isfile() or item.islnk(): data, mode = tar.extractfile(item).read(), stat.S_IFREG | item.mode
            else: raise ValueError(f'Unexpected archive entry: {name}')
            entries[name] = (mode, data)
    # Only the required filesystem and network modules from the matching kernel.
    with tarfile.open(kernel_package, ignore_zeros=True) as tar:
        dep_name = next(n for n in tar.getnames() if n.endswith('/modules.dep'))
        prefix = dep_name.removesuffix('modules.dep')
        dependencies = dict(line.split(':', 1) for line in tar.extractfile(dep_name).read().decode().splitlines())
        selected = set()
        def include(name):
            if name in selected: return
            selected.add(name)
            for dep in dependencies[name].split(): include(dep)
        for module in ('ext4', 'realtek', 'r8169', 'af_packet', '9p', '9pnet', '9pnet_fd'):
            include(next(n for n in dependencies if n.endswith('/'+module+'.ko.gz')))
        for name in sorted(selected):
            path = prefix + name.removesuffix('.gz')
            for parent in pathlib.PurePosixPath(path).parents:
                if str(parent) != '.': entries[str(parent)] = (stat.S_IFDIR | 0o755, b'')
            entries[path] = (stat.S_IFREG | 0o644, gzip.decompress(tar.extractfile(prefix + name).read()))
        dep_data = ''.join(f"{n.removesuffix('.gz')}: {' '.join(d.removesuffix('.gz') for d in dependencies[n].split())}\n" for n in sorted(selected))
        entries[dep_name] = (stat.S_IFREG | 0o644, dep_data.encode())
    def file(name, value, mode=0o644):
        for parent in pathlib.PurePosixPath(name).parents:
            if str(parent) != '.': entries.setdefault(str(parent), (stat.S_IFDIR | 0o755, b''))
        entries[name] = (stat.S_IFREG | mode, value.encode())
    # Explicit Edge repositories in both initramfs and the full persistent seed.
    # Keep testing opt-in; never mix a stable branch into this installation.
    file('etc/apk/repositories', repository_base+'main\n'+repository_base+'community\n')
    helper = ROOT / 'out/guest/flyby-grow-root'
    helper.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([os.environ.get('RISCV_CC', 'riscv64-linux-gnu-gcc'), '-Os', '-static', '-s', '-nostdlib', '-ffreestanding', '-fno-builtin', '-fno-stack-protector', '-mno-relax', '-msmall-data-limit=0', '-Wl,-e,_start',
                    '-Wall', '-Wextra', '-Werror', str(ROOT/'native/guest/grow-root.c'), '-o', str(helper)], check=True)
    entries['sbin/flyby-grow-root'] = (stat.S_IFREG | 0o755, helper.read_bytes())
    file('etc/flyby-shared', '''#!/bin/sh
# The private third UART carries 9P2000, never terminal input or network traffic.
grep -q 'flyby.shared=1' /proc/cmdline || exit 0
modprobe 9pnet_fd && modprobe 9p || { echo FLYBY_SHARED_ERROR; exit 1; }
mkdir -p /shared
exec 4<> /dev/ttyS2
stty raw -echo <&4
mount -t 9p -o debug=0x1,trans=fd,rfdno=4,wfdno=4,version=9p2000,msize=8192,cache=none,access=any,nodev,nosuid shared /shared || { echo FLYBY_SHARED_ERROR; exit 1; }
echo FLYBY_SHARED_READY
''', 0o755)
    file('init', '''#!/bin/busybox sh
export PATH=/sbin:/bin:/usr/sbin:/usr/bin
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t devtmpfs devtmpfs /dev
mkdir -p /dev/pts /run /tmp
mount -t devpts devpts /dev/pts
hostname flyby
if grep -q 'flyby.root=1' /proc/cmdline; then
    modprobe ext4 || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    i=0
    while [ ! -b /dev/nvme0n1 ] && [ "$i" -lt 100 ]; do sleep 0.1; i=$((i+1)); done
    mkdir -p /newroot
    mount -t ext4 /dev/nvme0n1 /newroot || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    /sbin/flyby-grow-root || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    [ -x /newroot/sbin/init ] || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    mkdir -p /newroot/run /newroot/tmp /newroot/dev /newroot/proc /newroot/sys
    mount -t tmpfs tmpfs /newroot/run || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    mount -t tmpfs -o mode=1777 tmpfs /newroot/tmp || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    # Mount from the current initrd so older persistent roots also support sharing.
    /etc/flyby-shared || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    if grep -q 'flyby.shared=1' /proc/cmdline; then
        mkdir -p /newroot/shared
        mount -o move /shared /newroot/shared || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    fi
    mount -o move /dev /newroot/dev || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    mount -o move /sys /newroot/sys || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    mount -o move /proc /newroot/proc || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
    exec switch_root /newroot /sbin/init
fi
/etc/flyby-shared || { echo FLYBY_STORAGE_ERROR; exec /bin/sh; }
exec /bin/busybox init
''', 0o755)
    file('etc/inittab', '''::sysinit:/bin/sh /etc/flyby-boot
ttyS0::respawn:/bin/sh -l
::respawn:/bin/sh /etc/flyby-control
::shutdown:/bin/sync
::shutdown:/bin/umount -a -r
''')
    file('etc/flyby-boot', '''#!/bin/sh
printf '\\nHello from Linux\\n'
/etc/flyby-network &
cat /etc/os-release
if grep -q 'flyby.root=1' /proc/cmdline; then
    mkdir -p /data /root
    echo FLYBY_STORAGE_READY
    echo FLYBY_SYSTEM_READY
elif grep -q 'flyby.disk=1' /proc/cmdline; then
    modprobe ext4 || { echo FLYBY_STORAGE_ERROR; exit 1; }
    i=0
    while [ ! -b /dev/nvme0n1 ] && [ "$i" -lt 100 ]; do sleep 0.1; i=$((i+1)); done
    mkdir -p /data
    mount -t ext4 /dev/nvme0n1 /data || { echo FLYBY_STORAGE_ERROR; exit 1; }
    mkdir -p /data/root
    mount -o bind /data/root /root || { echo FLYBY_STORAGE_ERROR; exit 1; }
    touch /run/flyby-storage-ready
    echo FLYBY_STORAGE_READY
fi
''')
    file('etc/profile', '''export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TERM=xterm-256color
export PS1='root@flyby:\\w# '
printf '\\nFLYBY_ALPINE_READY\\n'
''')
    file('etc/flyby-network', """#!/bin/sh
modprobe af_packet || { echo FLYBY_NETWORK_ERROR; exit 1; }
modprobe realtek || { echo FLYBY_NETWORK_ERROR; exit 1; }
modprobe r8169 || { echo FLYBY_NETWORK_ERROR; exit 1; }
ip link set lo up
ip link set eth0 up
# Do not delay the interactive shell while offline; bounded DHCP runs in background.
udhcpc -i eth0 -n -q -t 5 -T 2 -s /etc/flyby-dhcp || echo FLYBY_NETWORK_OFFLINE
""", 0o755)
    file('etc/flyby-dhcp', """#!/bin/sh
case "$1" in
    deconfig) ip addr flush dev "$interface" ;;
    bound|renew)
        ifconfig "$interface" "$ip" netmask "$subnet"
        for gateway in $router; do ip route replace default via "$gateway" dev "$interface"; break; done
        : > /etc/resolv.conf
        for server in $dns; do echo "nameserver $server" >> /etc/resolv.conf; done
        echo FLYBY_NETWORK_READY
        ;;
esac
""", 0o755)
    file('usr/local/bin/flyby-network-check', """#!/bin/sh
set -e
# HTTPS keeps certificate verification enabled and uses the packaged Alpine CA bundle.
nslookup dl-cdn.alpinelinux.org
wget -T 20 -q -O /tmp/flyby-http http://dl-cdn.alpinelinux.org/alpine/MIRRORS.txt
test -s /tmp/flyby-http
wget -T 20 -q -O /tmp/flyby-https https://dl-cdn.alpinelinux.org/alpine/MIRRORS.txt
test -s /tmp/flyby-https
printf '\\nFLYBY_NETWORK_OK\\n'
""", 0o755)
    # Dedicated second UART. Commands never enter the user's shell or host shell.
    file('etc/flyby-control', '''#!/bin/sh
exec 3<> /dev/ttyS1
stty raw -echo <&3
printf 'FLYBY_CONTROL_READY\\n' >&3
while read -r op rows cols; do
    case "$op" in
        stop) /bin/busybox poweroff ;;
        resize)
            case "$rows:$cols" in *[!0-9:]*|'':*) continue ;; esac
            [ "$rows" -ge 2 ] && [ "$rows" -le 300 ] && \\
            [ "$cols" -ge 2 ] && [ "$cols" -le 500 ] && \\
            stty -F /dev/ttyS0 rows "$rows" cols "$cols"
            ;;
    esac
done <&3
''')
    # Populate the full root filesystem without privileged mounts or archive extraction.
    if system_root.exists(): shutil.rmtree(system_root)
    system_root.mkdir(parents=True)
    for name, (mode, data) in sorted(entries.items(), key=lambda e: (len(pathlib.PurePosixPath(e[0]).parts), e[0])):
        dest = system_root / name
        for parent in dest.parents:
            if parent == system_root: break
            if parent.is_symlink(): raise ValueError('Root seed path traverses a symlink')
        dest.parent.mkdir(parents=True, exist_ok=True)
        if stat.S_ISDIR(mode): dest.mkdir(exist_ok=True); dest.chmod(stat.S_IMODE(mode))
        elif stat.S_ISLNK(mode): dest.symlink_to(data.decode())
        else: dest.write_bytes(data); dest.chmod(stat.S_IMODE(mode))
    output = io.BytesIO()
    def record(ino, name, mode, data):
        encoded = name.encode() + b'\0'
        fields = [ino, mode, 0, 0, 2 if stat.S_ISDIR(mode) else 1, 0, len(data), 0, 0, 0, 0, len(encoded), 0]
        output.write(b'070701' + ''.join(f'{v:08x}' for v in fields).encode() + encoded)
        output.write(b'\0' * (-output.tell() % 4))
        output.write(data)
        output.write(b'\0' * (-output.tell() % 4))
    for ino, (name, (mode, data)) in enumerate(sorted(entries.items()), 1): record(ino, name, mode, data)
    record(len(entries)+1, 'TRAILER!!!', 0, b'')
    compressed = bytearray(gzip.compress(output.getvalue(), compresslevel=9, mtime=0))
    compressed[9] = 255  # Stable gzip OS byte across supported Python versions.
    return bytes(compressed)

def provenance(paths):
    packages = []
    with tarfile.open(paths['alpine-minirootfs.tar.gz']) as tar:
        database = tar.extractfile('./lib/apk/db/installed').read().decode()
    for block in database.strip().split('\n\n'):
        fields = dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
        packages.append(dict(name=fields['P'], version=fields['V'], license=fields['L'],
                             origin=fields['o'], commit=fields['c']))
    for name in ['linux-lts.apk', 'opensbi.apk', *SERVICE_PACKAGES]:
        fields = dict(line.split(' = ', 1) for line in member(paths[name], '.PKGINFO').decode().splitlines() if ' = ' in line)
        packages.append(dict(name=fields['pkgname'], version=fields['pkgver'], license=fields['license'],
                             origin=fields['origin'], commit=fields['commit'], images=['service'] if name in SERVICE_PACKAGES else ['minimal', 'service']))
    for package in packages:
        package.setdefault('images', ['minimal', 'service'])
        package['source_recipe'] = f"https://gitlab.alpinelinux.org/alpine/aports/-/tree/{package['commit']}/main/{package['origin']}"
    return dict(branch='edge', rootfs_snapshot='20260805',
                artifacts={name: dict(url=BASE+url, sha256=digest) for name, (url,digest) in (ARTIFACTS | SERVICE_PACKAGES).items()},
                build_tools={'apk-tools-static': dict(url=BASE+APK_TOOL[0], sha256=APK_TOOL[1])},
                images={'minimal': dict(seed='system.seed', init='BusyBox'), 'service': dict(seed='service.seed', init='OpenRC')},
                packages=packages)

def main():
    paths = {name: fetch(name, BASE+url, digest) for name, (url,digest) in (ARTIFACTS | SERVICE_PACKAGES).items()}
    apk_tool = fetch('apk-tools-static.apk', BASE+APK_TOOL[0], APK_TOOL[1])
    metadata = provenance(paths)
    DEST.mkdir(parents=True, exist_ok=True)
    # aapt transparently expands .gz assets and strips their suffix. Keep gzip bytes
    # under a neutral extension so Android and host consume the exact same seed.
    (DEST/'disk.raw.gz').unlink(missing_ok=True)
    (DEST/'provenance.json').write_text(json.dumps(metadata, indent=2)+'\n')
    kernel = member(paths['linux-lts.apk'], 'boot/vmlinuz-lts')
    if kernel[:2] == b'\x1f\x8b': kernel = gzip.decompress(kernel)
    if kernel[56:60] != b'RSC\x05': raise ValueError('Not a RISC-V Linux Image')
    (DEST/'kernel').write_bytes(kernel)
    config_dir = ROOT/'out/guest'
    config_dir.mkdir(parents=True, exist_ok=True)
    (config_dir/'kernel.config').write_bytes(member(paths['linux-lts.apk'], 'boot/config-6.18.53-0-lts'))
    (DEST/'firmware').write_bytes(member(paths['opensbi.apk'], 'usr/share/opensbi/generic/firmware/fw_jump.bin'))
    (DEST/'initrd').write_bytes(initramfs(paths['alpine-minirootfs.tar.gz'], paths['linux-lts.apk'], config_dir/'system-root'))
    build_service(config_dir/'system-root', config_dir/'service-root', paths, apk_tool)
    manifest = {name: hashlib.sha256((DEST/name).read_bytes()).hexdigest() for name in ['kernel','firmware','initrd']}
    for name, size, root in [('disk', 256, None), ('system', 1024, config_dir/'system-root'), ('service', 1024, config_dir/'service-root')]:
        disk = config_dir/(name+'-seed.raw')
        disk.unlink(missing_ok=True)
        with disk.open('wb') as stream: stream.truncate(size * 1024 * 1024)
        env = dict(os.environ, E2FSPROGS_FAKE_TIME='1700000000')
        command = ['mke2fs', '-q', '-t', 'ext4', '-b', '4096', '-F', '-L', 'flyby-'+name,
                   '-U', 'fedcba98-7654-4321-8123-123456789abc', '-m', '0',
                   '-E', 'lazy_itable_init=0,lazy_journal_init=0,hash_seed=fedcba98-7654-4321-8123-123456789abc']
        if root: command += ['-d', str(root)]
        subprocess.run(command+[str(disk)], check=True, env=env)
        if root:
            # mke2fs -d copies host ownership. Normalize every inode without root/fakeroot.
            commands = config_dir/'root-ownership.txt'
            names = ['/'] + ['/'+str(p.relative_to(root)) for p in root.rglob('*')]
            if any('"' in n or '\\' in n or '\n' in n for n in names): raise ValueError('Invalid root filename')
            commands.write_text(''.join(f'set_inode_field "{n}" uid 0\nset_inode_field "{n}" gid 0\n' for n in names))
            result = subprocess.run(['debugfs', '-w', '-f', str(commands), str(disk)], check=True,
                                    capture_output=True, text=True, env=env)
            if any(line and not line.startswith('debugfs ') for line in result.stderr.splitlines()):
                raise RuntimeError('debugfs failed: '+result.stderr)
        digest = hashlib.sha256()
        with disk.open('rb') as source, (DEST/(name+'.seed')).open('wb') as target:
            with gzip.GzipFile(filename='', mode='wb', fileobj=target, mtime=0) as compressed:
                while chunk := source.read(1024*1024):
                    digest.update(chunk); compressed.write(chunk)
        manifest[name+'.raw'] = digest.hexdigest()
    (DEST/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(json.dumps(manifest, indent=2))
if __name__ == '__main__': main()
