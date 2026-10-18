#!/usr/bin/env python3
"""Boot the real GPU image, require VirGL, render gears, exercise GUI input.

Host CI uses Mesa GLES software rendering to test the transport; actual Android
hardware performance remains device-specific. No guest software fallback passes.
"""
import gzip
import os
import pathlib
import shutil
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
out = root/'out/x11-gpu-test'
out.mkdir(parents=True, exist_ok=True)
build = pathlib.Path(os.environ.get('FLYBY_HOST_BUILD', root/'out/host'))
with gzip.open(root/'app/src/main/assets/vm/x11-gpu.seed', 'rb') as src, (out/'system.raw').open('wb') as dst:
    shutil.copyfileobj(src, dst)

# The native boundary must reject a GPU disk even if Android/UI is bypassed.
blocked = subprocess.run([str(build/'flyby-host'), str(root/'app/src/main/assets/vm'),
                          str(out/'system.raw'), '--system'], env=dict(os.environ, FLYBY_GPU='0'),
                         capture_output=True, text=True, timeout=15)
assert blocked.returncode != 0 and 'Enable GPU in Settings' in blocked.stderr, blocked.stderr
with (out/'boot.log').open('w') as log:
    try:
        result = subprocess.run([str(build/'flyby-wayland-tests'), str(root/'app/src/main/assets/vm'),
                                 str(out/'system.raw'), str(out/'desktop.ppm'), '--x11'],
                                env=dict(os.environ, FLYBY_GPU='1'), stdout=log,
                                stderr=subprocess.STDOUT, timeout=600)
    except subprocess.TimeoutExpired:
        log.flush()
        print((out/'boot.log').read_text(errors='replace')[-18000:])
        raise SystemExit('X11 GPU test timed out')
if result.returncode:
    print((out/'boot.log').read_text(errors='replace')[-18000:])
    raise SystemExit(result.returncode)
print('PASS: GPU-off rejection; Virtio-GPU/VirGL, GLX gears, X11 input and shutdown')
