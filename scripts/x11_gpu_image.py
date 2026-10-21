"""GPU-required X11 image; a separate seed preserves the small CPU-only image."""
import json
import pathlib
import os
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
# Filled from the per-build resolution before creating any images.
LOCK = []
PACKAGES = {}


def build(x11, root, paths):
    if root.exists(): shutil.rmtree(root)
    shutil.copytree(x11, root, symlinks=True)
    subprocess.run([str(root.parent/'apk.static'), '--root', str(root), '--arch', 'riscv64',
                    '--no-scripts', '--no-network', 'add',
                    *[str(paths[name]) for name in PACKAGES]], check=True)
    if not (root/'usr/lib/dri/virtio_gpu_dri.so').exists():
        raise ValueError('GPU image requires the Mesa Virtio-GPU driver')
    # The RISC-V Venus client is built from this workflow's locked Mesa source.
    import hashlib
    venus = ROOT/'out/venus'
    metadata = json.loads((venus/'build.json').read_text())
    for name, expected in metadata['files'].items():
        if hashlib.sha256((venus/name).read_bytes()).hexdigest() != expected:
            raise ValueError('Venus build artifact checksum mismatch')
    if metadata['architecture'] != 'riscv64':
        raise ValueError('Venus build must target riscv64')
    shutil.copy2(venus/'libvulkan_virtio.so', root/'usr/lib/libvulkan_virtio.so')
    icds = root/'usr/share/vulkan/icd.d'
    icds.mkdir(parents=True, exist_ok=True)
    shutil.copy2(venus/'virtio_icd.riscv64.json', icds/'virtio_icd.riscv64.json')
    # Build a tiny target-ABI Vulkan acceptance client against the exact
    # installed guest loader/musl, without running target code on the host.
    helper = root/'usr/local/bin/flyby-vulkan-check'
    helper.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([os.environ.get('RISCV_CC', 'riscv64-linux-gnu-gcc'), '-Os', '-s',
                    '-nostdlib', '-ffreestanding', '-fno-builtin', '-fno-stack-protector',
                    '-mno-relax', '-msmall-data-limit=0', '-Wl,-e,_start',
                    '-Wl,--dynamic-linker,/lib/ld-musl-riscv64.so.1',
                    '-I'+str(ROOT/'native/vendor/virglrenderer/src'),
                    '-L'+str(root/'usr/lib'), '-L'+str(root/'lib'),
                    '-Wl,-rpath-link,'+str(root/'usr/lib'), '-Wl,-rpath-link,'+str(root/'lib'),
                    str(ROOT/'native/guest/vulkan-check.c'),
                    '-l:libvulkan.so.1', '-l:libc.musl-riscv64.so.1', '-o', str(helper)], check=True)
    (root/'etc/flyby-image').write_text('Alpine X11 GPU\n')
    (root/'etc/flyby-requires-gpu').write_text('1\n')
    config = root/'etc/X11/xorg.conf.d/10-flyby.conf'
    value = config.read_text().replace('Disable "glx"', 'Load "glx"')
    value = value.replace('Flyby framebuffer', 'Flyby Virtio GPU')
    value = value.replace('Option "AccelMethod" "none"', 'Option "AccelMethod" "glamor"')
    value = value.replace('Option "ShadowFB" "true"', 'Option "SWcursor" "true"')
    config.write_text(value)
    # Load the transport before Xorg starts. The runtime exposes a virtio MMIO
    # device only when this disk is booted with GPU enabled.
    prepare = root/'etc/init.d/flyby-display-prepare'
    prepare.write_text(prepare.read_text().replace('start() {', '''start() {
    grep -q 'flyby.gpu=1' /proc/cmdline || { eerror "Enable GPU in Flyby Settings"; return 1; }
    modprobe virtio_mmio && modprobe virtio_gpu || return 1'''))
    # Require the virgl driver: an initialization failure must not silently turn
    # this image into slow guest-side software rendering.
    environment = 'export MESA_LOADER_DRIVER_OVERRIDE=virtio_gpu\nexport GALLIUM_DRIVER=virgl\n'
    profile = root/'etc/profile.d/x11.sh'
    profile.write_text(profile.read_text()+environment+
        "if grep -q 'flyby.vulkan=1' /proc/cmdline; then export MESA_VK_WSI_DEBUG=sw; fi\n")
    for service in ('flyby-x11', 'flyby-x11-wm', 'flyby-x11-terminal'):
        path = root/'etc/init.d'/service
        path.write_text(path.read_text().replace('supervisor=', environment+'supervisor=', 1))
