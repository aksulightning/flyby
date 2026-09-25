#!/usr/bin/env python3
"""Upgrade an actual Alpine 3.23.6 SYSTEM disk, keeping files and installed packages."""
import importlib.util
import os
from pathlib import Path
import selectors
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'out/edge-upgrade-test'
OUT.mkdir(parents=True, exist_ok=True)
DISK = OUT/'system.raw'
spec = importlib.util.spec_from_file_location('guest_builder', ROOT/'scripts/prepare-alpine-riscv64.py')
guest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guest)


def boot(label, command, upgrade=False, network=False):
    args = [str(ROOT/'out/host/flyby-host'), str(guest.DEST), str(DISK), '--system']
    if upgrade: args.append('--upgrade-edge')
    proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    buffer = b''
    sent = False
    try:
        with (OUT/(label+'.log')).open('wb') as log:
            deadline = time.monotonic() + (900 if upgrade else 180)
            while time.monotonic() < deadline:
                for key, _ in selector.select(1):
                    data = key.fileobj.read1(65536)
                    log.write(data); log.flush()
                    buffer = (buffer + data)[-512*1024:]
                for error in (b'FLYBY_STORAGE_ERROR', b'FLYBY_EDGE_UPGRADE_FAILED', b'Kernel panic', b'Initramfs unpacking failed'):
                    if error in buffer: raise RuntimeError(f'{label}: {error.decode()}')
                if not sent and b'FLYBY_ALPINE_READY' in buffer and (not network or b'FLYBY_NETWORK_READY' in buffer):
                    proc.stdin.write((command+" && printf '\\nEDGE_TEST_OK\\n'\n").encode()); proc.stdin.flush()
                    sent = True
                if sent and b'\r\nEDGE_TEST_OK\r\n' in buffer:
                    if upgrade: assert b'\r\nFLYBY_EDGE_UPGRADE_OK\r\n' in buffer
                    proc.stdin.write(b'poweroff\n'); proc.stdin.flush()
                    rest, _ = proc.communicate(timeout=30); log.write(rest)
                    assert proc.returncode == 0
                    print('PASS:', label, flush=True)
                    return
                if proc.poll() is not None: break
            raise RuntimeError(f'{label}: guest assertion timed out')
    except Exception:
        print(buffer.decode(errors='replace'), flush=True)
        raise
    finally:
        selector.close()
        if proc.poll() is None: proc.kill(); proc.wait()


legacy = guest.fetch('alpine-3.23.6.tar.gz',
    'https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/riscv64/alpine-minirootfs-3.23.6-riscv64.tar.gz',
    'e3fab77da4d4a1bb7784dc6800343e48ebd15f23ecb4008438d6ccef6acdecd9')
kernel_path, kernel_hash = guest.ARTIFACTS['linux-lts.apk']
kernel = guest.fetch('linux-lts.apk', guest.BASE+kernel_path, kernel_hash)
root = OUT/'legacy-root'
# Real stable packages, not a new Edge disk with a rewritten version string.
guest.initramfs(legacy, kernel, root, expected_release='Alpine Linux v3.23',
               repository_base='https://dl-cdn.alpinelinux.org/alpine/v3.23/')
# Older installations have neither helper: the APK's initramfs must supply both.
for name in ('flyby-upgrade-edge', 'flyby-upgrade-init'): (root/'etc'/name).unlink()
DISK.unlink(missing_ok=True)
with DISK.open('wb') as stream: stream.truncate(1024**3)
subprocess.run(['mke2fs', '-q', '-F', '-t', 'ext4', '-b', '4096', '-m', '0', '-d', str(root), str(DISK)], check=True)
boot('stock-3.23.6', "grep -qx 'VERSION_ID=3.23.6' /etc/os-release && apk add --no-cache tree && "
     "echo preserve-root > /root/edge-upgrade-test && chmod 600 /root/edge-upgrade-test && "
     "echo preserve-etc > /etc/edge-upgrade-test && sync", network=True)
verify = "grep -qx 'PRETTY_NAME=\"Alpine Linux edge\"' /etc/os-release && " \
    "grep -qx preserve-root /root/edge-upgrade-test && grep -qx preserve-etc /etc/edge-upgrade-test && " \
    "[ \"$(stat -c %a /root/edge-upgrade-test)\" = 600 ] && apk info -e tree && tree --version && " \
    "grep -q '/v3.23/main' /etc/apk/repositories.before-edge && " \
    "grep -qxF 'https://dl-cdn.alpinelinux.org/alpine/edge/main' /etc/apk/repositories && " \
    "grep -qxF 'https://dl-cdn.alpinelinux.org/alpine/edge/community' /etc/apk/repositories"
boot('upgrade-existing-disk-to-edge', verify, upgrade=True)
boot('edge-persists-after-restart', verify)
print('PASS: actual 3.23.6 to Edge migration preserves /root, /etc, file permissions and installed tree')
