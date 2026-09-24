#!/usr/bin/env python3
"""Pinned Alpine artifacts -> deterministic development initramfs. No root/tools needed."""
import gzip, hashlib, io, json, pathlib, stat, tarfile, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / 'out/downloads'
DEST = ROOT / 'app/src/main/assets/vm'
BASE = 'https://dl-cdn.alpinelinux.org/alpine/v3.23/'
ARTIFACTS = {
 'linux-lts.apk': ('main/riscv64/linux-lts-6.18.53-r0.apk', 'fd8a989452f0e9979b14315409c98f2192d7889236af9a922ce19c092c6a86d5'),
 'opensbi.apk': ('main/riscv64/opensbi-1.7-r0.apk', 'd0794002fd39d2fe3637e4448d0829e961ccfc2b50dd55280bcfb87536cad5b8'),
 'alpine-minirootfs.tar.gz': ('releases/riscv64/alpine-minirootfs-3.23.6-riscv64.tar.gz', 'e3fab77da4d4a1bb7784dc6800343e48ebd15f23ecb4008438d6ccef6acdecd9'),
}
def fetch(name, url, digest):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
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

def initramfs(rootfs):
    entries = {}
    with tarfile.open(rootfs) as tar:
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
    def file(name, value, mode=0o644): entries[name] = (stat.S_IFREG | mode, value.encode())
    file('init', '''#!/bin/busybox sh
export PATH=/sbin:/bin:/usr/sbin:/usr/bin
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t devtmpfs devtmpfs /dev
mkdir -p /dev/pts /run /tmp
mount -t devpts devpts /dev/pts
hostname flyby
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
cat /etc/os-release
''')
    file('etc/profile', '''export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TERM=xterm-256color
export PS1='root@flyby:\\w# '
printf '\\nFLYBY_ALPINE_READY\\n'
''')
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
    for name in ['linux-lts.apk', 'opensbi.apk']:
        fields = dict(line.split(' = ', 1) for line in member(paths[name], '.PKGINFO').decode().splitlines() if ' = ' in line)
        packages.append(dict(name=fields['pkgname'], version=fields['pkgver'], license=fields['license'],
                             origin=fields['origin'], commit=fields['commit']))
    for package in packages:
        package['source_recipe'] = f"https://gitlab.alpinelinux.org/alpine/aports/-/tree/{package['commit']}/main/{package['origin']}"
    return dict(artifacts={name: dict(url=BASE+url, sha256=digest) for name, (url,digest) in ARTIFACTS.items()}, packages=packages)

def main():
    paths = {name: fetch(name, BASE+url, digest) for name, (url,digest) in ARTIFACTS.items()}
    metadata = provenance(paths)
    DEST.mkdir(parents=True, exist_ok=True)
    (DEST/'provenance.json').write_text(json.dumps(metadata, indent=2)+'\n')
    kernel = member(paths['linux-lts.apk'], 'boot/vmlinuz-lts')
    if kernel[:2] == b'\x1f\x8b': kernel = gzip.decompress(kernel)
    if kernel[56:60] != b'RSC\x05': raise ValueError('Not a RISC-V Linux Image')
    (DEST/'kernel').write_bytes(kernel)
    config_dir = ROOT/'out/guest'
    config_dir.mkdir(parents=True, exist_ok=True)
    (config_dir/'kernel.config').write_bytes(member(paths['linux-lts.apk'], 'boot/config-6.18.53-0-lts'))
    (DEST/'firmware').write_bytes(member(paths['opensbi.apk'], 'usr/share/opensbi/generic/firmware/fw_jump.bin'))
    (DEST/'initrd').write_bytes(initramfs(paths['alpine-minirootfs.tar.gz']))
    manifest = {name: hashlib.sha256((DEST/name).read_bytes()).hexdigest() for name in ['kernel','firmware','initrd']}
    (DEST/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(json.dumps(manifest, indent=2))
if __name__ == '__main__': main()
