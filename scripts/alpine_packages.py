"""Resolve current Alpine Edge packages with apk; retain a per-build snapshot."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = 'https://dl-cdn.alpinelinux.org/alpine/edge/'
LOCK_PATH = ROOT/'out/alpine/lock.json'
# Only application requirements belong here. apk selects versions and the full
# dependency closure, including renamed/versioned dependencies such as LLVM.
WORLDS = {
    'service': ['openrc', 'openrc-init', 'openrc-user', 'busybox-ifupdown'],
    'wayland': ['weston', 'weston-backend-drm', 'weston-shell-desktop',
                'weston-terminal', 'seatd', 'seatd-openrc', 'eudev',
                'eudev-openrc', 'udev-init-scripts-openrc', 'font-dejavu'],
    'x11': ['xorg-server', 'xf86-input-libinput', 'twm', 'xterm', 'xauth',
            'xdpyinfo', 'eudev', 'eudev-openrc', 'udev-init-scripts-openrc',
            'font-dejavu', 'font-misc-misc', 'font-cursor-misc', 'font-alias'],
    'x11-gpu': ['mesa-dri-gallium', 'mesa-utils'],
}
PREFIXES = {'service': '', 'wayland': 'wayland-', 'x11': 'x11-', 'x11-gpu': 'x11-gpu-'}


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024*1024):
            digest.update(chunk)
    return digest.hexdigest()


def rootfs_release(records):
    matches = [r for r in records if r.get('flavor') == 'alpine-minirootfs'
               and r.get('arch') == 'riscv64']
    if len(matches) != 1:
        raise ValueError('Expected one current riscv64 minirootfs in latest-releases.yaml')
    record = matches[0]
    name = record['file']
    if pathlib.PurePosixPath(name).name != name or not name.endswith('.tar.gz'):
        raise ValueError('Unsafe minirootfs filename')
    digest = record['sha256']
    if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
        raise ValueError('Invalid minirootfs SHA256')
    return record


def package_record(path, url, arch):
    relative = url.removeprefix(BASE)
    parts = relative.split('/')
    if not url.startswith(BASE) or len(parts) != 3 or parts[0] not in ('main', 'community') or parts[1] != arch:
        raise ValueError(f'Unexpected package URL: {url}')
    with tarfile.open(path, ignore_zeros=True) as archive:
        fields = dict(line.split(' = ', 1) for line in
                      archive.extractfile('.PKGINFO').read().decode().splitlines()
                      if ' = ' in line)
    if parts[2] != f"{fields['pkgname']}-{fields['pkgver']}.apk":
        raise ValueError(f'Package metadata does not match URL: {url}')
    return dict(name=fields['pkgname'], version=fields['pkgver'],
                repository=parts[0], path=relative, sha256=sha256(path),
                license=fields['license'], origin=fields['origin'], commit=fields['commit'])


def cache_package(path, name, digest):
    target = ROOT/'out/downloads'/digest/name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)


def resolve(refresh=False):
    if LOCK_PATH.exists() and not refresh:
        return json.loads(LOCK_PATH.read_text())
    import yaml
    # Docker supplies the current official apk and Alpine trust keys without a
    # versioned bootstrap APK URL. Pin the pulled image ID for this resolution.
    subprocess.run(['docker', 'pull', '--platform=linux/amd64', 'alpine:edge'], check=True)
    image = subprocess.check_output(
        ['docker', 'image', 'inspect', '--format={{.Id}}', 'alpine:edge'], text=True).strip()
    LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=LOCK_PATH.parent) as temporary:
        stage = pathlib.Path(temporary).resolve()
        groups = {'tool': ['apk-tools-static'], 'boot': ['linux-lts', 'opensbi'], **WORLDS}
        lines = ['set -eu',
                 f"trap 'chown -R {os.getuid()}:{os.getgid()} /output' EXIT",
                 'printf "%s\\n" "$BASE/main" "$BASE/community" > /etc/apk/repositories']
        for group, names in groups.items():
            arch = 'x86_64' if group == 'tool' else 'riscv64'
            recursive = '' if group in ('tool', 'boot') else '--recursive'
            lines += [f'mkdir -p /output/{group}',
                      f'apk --no-cache --arch {arch} --keys-dir /usr/share/apk/keys/{arch} fetch {recursive} '
                      f'--url --output /output/{group} {" ".join(names)} > /output/{group}/urls.txt '
                      f'|| {{ cat /output/{group}/urls.txt; exit 1; }}']
        # The container only receives this disposable output directory.
        lines += [f'chown -R {os.getuid()}:{os.getgid()} /output']
        subprocess.run(['docker', 'run', '--rm', '--platform=linux/amd64',
                        '--cap-drop=ALL', '--cap-add=CHOWN', '--cap-add=DAC_OVERRIDE',
                        '-e', 'BASE='+BASE.rstrip('/'),
                        '-v', f'{stage}:/output', image, 'sh', '-c', '\n'.join(lines)], check=True)
        lock = dict(schema=1, branch='edge', resolver_image=image, groups={}, artifacts={})
        for group in groups:
            arch = 'x86_64' if group == 'tool' else 'riscv64'
            records = []
            for url in (stage/group/'urls.txt').read_text().splitlines():
                if not url.startswith('https://'):
                    continue  # apk progress/index messages
                record = package_record(stage/group/url.rsplit('/', 1)[1], url, arch)
                records.append(record)
                prefix = PREFIXES.get(group, '')
                name = prefix+record['name']+'.apk'
                cache_package(stage/group/url.rsplit('/', 1)[1], name, record['sha256'])
            if not records or not set(groups[group]) <= {p['name'] for p in records}:
                raise ValueError(f'Incomplete apk resolution for {group}')
            lock['groups'][group] = records
        with urllib.request.urlopen(BASE+'releases/riscv64/latest-releases.yaml', timeout=90) as response:
            release = rootfs_release(yaml.safe_load(response))
        lock['rootfs_snapshot'] = release['version']
        lock['artifacts']['alpine-minirootfs.tar.gz'] = [
            'releases/riscv64/'+release['file'], release['sha256']]
        for p in lock['groups']['boot']:
            lock['artifacts'][p['name']+'.apk'] = [p['path'], p['sha256']]
        tool, = lock['groups']['tool']
        lock['apk_tool'] = [tool['path'], tool['sha256']]
        # Atomic replacement: never leave a partially resolved snapshot.
        pending = LOCK_PATH.with_suffix('.partial')
        pending.write_text(json.dumps(lock, indent=2)+'\n')
        pending.replace(LOCK_PATH)
    return lock


def configure(lock):
    # Update existing dictionaries in place: image builders and their caller
    # share these references. No network work occurs merely by importing them.
    import service_image, wayland_image, x11_image, x11_gpu_image
    for group, module in [('service', service_image), ('wayland', wayland_image),
                          ('x11', x11_image), ('x11-gpu', x11_gpu_image)]:
        module.LOCK[:] = lock['groups'][group]
        module.PACKAGES.clear()
        module.PACKAGES.update({PREFIXES[group]+p['name']+'.apk':
                               (p['path'], p['sha256']) for p in module.LOCK})


if __name__ == '__main__':
    resolve(refresh=True)
    print(f'Resolved current Alpine packages: {LOCK_PATH}')
