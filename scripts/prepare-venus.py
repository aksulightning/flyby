#!/usr/bin/env python3
"""Build the missing RISC-V Venus client from the locked Alpine Mesa source.

Requires Docker riscv64 binfmt on the build runner. This build tool does not
change Flyby's Android runtime: it still uses RVVM without QEMU.
"""
import importlib.util
import json
import pathlib
import shutil
import subprocess
import tarfile
from alpine_sources import recipe_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT/'out/venus'

def main():
    spec = importlib.util.spec_from_file_location('bundle', ROOT/'scripts/bundle-sources.py')
    bundle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bundle)
    lock = json.loads((ROOT/'out/alpine/lock.json').read_text())
    mesa = next(p for p in lock['groups']['x11-gpu'] if p['name'] == 'mesa')
    stage = ROOT/'out/venus-build'
    if stage.exists(): shutil.rmtree(stage)
    stage.mkdir(parents=True)
    recipe = stage/'recipe'; recipe.mkdir()
    commit = mesa['commit']; repository = mesa['repository']
    archive = bundle.download('https://codeload.github.com/alpinelinux/aports/tar.gz/'+commit,
                              bundle.CACHE/(commit+'.tar.gz'))
    prefix = f'aports-{commit}/{repository}/mesa/'
    with tarfile.open(archive) as tar:
        for item in tar:
            if not item.isfile() or not item.name.startswith(prefix): continue
            relative = pathlib.PurePosixPath(item.name[len(prefix):])
            if relative.is_absolute() or '..' in relative.parts: raise ValueError('Unsafe recipe path')
            target = recipe/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(tar.extractfile(item).read())
    sources = recipe_sources(recipe)
    expected, name, upstream = next(r for r in sources if r[1].startswith('mesa-') and r[1].endswith('.tar.xz'))
    source = bundle.source_archive(name, expected, upstream)
    source_root = stage/'source'; source_root.mkdir()
    with tarfile.open(source) as tar:
        tar.extractall(source_root, filter='data')
    directories = [p for p in source_root.iterdir() if p.is_dir()]
    if len(directories) != 1: raise ValueError('Expected one Mesa source directory')
    work = directories[0]
    patches = []
    for checksum, filename, uri in sources:
        if not filename.endswith('.patch'): continue
        path = recipe/filename
        if not path.exists(): shutil.copy2(bundle.source_archive(filename, checksum, uri), path)
        if bundle.digest(path, 'sha512') != checksum: raise ValueError('Mesa patch checksum mismatch')
        subprocess.run(['patch', '-p1', '-i', str(path.resolve())], cwd=work, check=True)
        patches.append(dict(name=filename, sha512=checksum))
    subprocess.run(['docker', 'pull', '--platform=linux/riscv64', 'alpine:edge'], check=True)
    image = subprocess.check_output(['docker', 'image', 'inspect', '--format={{.Id}}', 'alpine:edge'], text=True).strip()
    command = """set -eu
apk add --no-cache build-base pkgconf meson ninja python3 py3-cparser spirv-headers py3-mako py3-yaml py3-packaging py3-ply bison flex linux-headers libdrm-dev expat-dev zlib-dev zstd-dev libxshmfence-dev libx11-dev libxrandr-dev libxfixes-dev libxcb-dev wayland-dev wayland-protocols eudev-dev libdisplay-info-dev
apk info -v > /work/build-packages.txt
meson setup /work/build --buildtype=release --wrap-mode=nodownload --prefix=/usr -Dgallium-drivers= -Dvulkan-drivers=virtio -Dvulkan-layers= -Dplatforms=x11 -Dllvm=disabled -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Dgallium-rusticl=false -Dvideo-codecs= -Dbuild-tests=false
meson compile -C /work/build -j 2
DESTDIR=/work/install meson install -C /work/build --no-rebuild
"""
    subprocess.run(['docker', 'run', '--rm', '--platform=linux/riscv64',
                    '--cap-drop=ALL', '--cap-add=CHOWN', '--cap-add=DAC_OVERRIDE',
                    '--security-opt=no-new-privileges', '--pids-limit=512', '--memory=4g',
                    '-v', f'{stage.resolve()}:/work', '-w', '/work/source/'+work.name,
                    image, 'sh', '-ec', command], check=True)
    library, = (stage/'install/usr/lib').rglob('libvulkan_virtio.so')
    icd, = (stage/'install/usr/share/vulkan/icd.d').glob('*virtio*.json')
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, OUT/'libvulkan_virtio.so')
    shutil.copy2(icd, OUT/'virtio_icd.riscv64.json')
    metadata = dict(component='Flyby-built Mesa Venus client', architecture='riscv64',
                    mesa_version=mesa['version'], recipe_commit=commit,
                    source_archive=name, source_sha512=expected, patches=patches,
                    builder_image=image, build_packages=(stage/'build-packages.txt').read_text().splitlines(),
                    files={p.name:bundle.digest(p) for p in OUT.iterdir() if p.name != 'build.json'})
    (OUT/'build.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print(json.dumps(metadata, indent=2))

if __name__ == '__main__': main()
