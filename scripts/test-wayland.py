#!/usr/bin/env python3
"""Exercise the actual framebuffer and graphical input inside the RV64 guest."""
import gzip
import pathlib
import shutil
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
out = root/'out/wayland-test'
out.mkdir(parents=True, exist_ok=True)
with gzip.open(root/'app/src/main/assets/vm/wayland.seed', 'rb') as src, (out/'system.raw').open('wb') as dst:
    shutil.copyfileobj(src, dst)
with (out/'boot.log').open('w') as log:
    try:
        result = subprocess.run([str(root/'out/host/flyby-wayland-tests'), str(root/'app/src/main/assets/vm'),
                                 str(out/'system.raw'), str(out/'desktop.ppm')], stdout=log,
                                stderr=subprocess.STDOUT, timeout=420)
    except subprocess.TimeoutExpired:
        log.flush()
        print((out/'boot.log').read_text(errors='replace')[-20000:])
        raise SystemExit('Wayland test timed out; see out/wayland-test/boot.log')
if result.returncode:
    print((out/'boot.log').read_text(errors='replace')[-20000:])
    raise SystemExit(result.returncode)
print('PASS: Wayland display, graphical terminal input, pointer and shutdown (512 MiB)')
