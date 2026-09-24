#!/usr/bin/env python3
"""Two separate RVVM processes must share actual ext4 data, not retained RAM."""
import gzip
from pathlib import Path
import selectors
import shutil
import subprocess
import time
import uuid
import sys

ROOT = Path(__file__).resolve().parents[1]
GUEST = ROOT / 'app/src/main/assets/vm'
SYSTEM = '--system' in sys.argv
OUT = ROOT / ('out/system-test' if SYSTEM else 'out/storage-test')
OUT.mkdir(parents=True, exist_ok=True)
DISK = OUT / 'disk.raw'
TOKEN = uuid.uuid4().hex


def boot(command, marker, name):
    proc = subprocess.Popen([str(ROOT/'out/host/flyby-host'), str(GUEST), str(DISK)] + (['--system'] if SYSTEM else []),
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    buffer = b''
    sent = False
    try:
        with (OUT / name).open('wb') as log:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                for key, _ in selector.select(1):
                    data = key.fileobj.read1(65536)
                    log.write(data); log.flush(); buffer += data
                if b'FLYBY_STORAGE_ERROR' in buffer or b'Kernel panic' in buffer:
                    raise RuntimeError(f'Guest boot failed: {name}')
                network_ready = '--network' not in sys.argv or b'FLYBY_NETWORK_READY' in buffer
                if not sent and b'FLYBY_ALPINE_READY' in buffer and network_ready:
                    assert b'FLYBY_STORAGE_READY' in buffer
                    proc.stdin.write(command.encode() + b'\n'); proc.stdin.flush()
                    sent = True; buffer = b''
                if sent and marker.encode() in buffer:
                    proc.stdin.write(b'poweroff\n'); proc.stdin.flush()
                    # communicate drains output, avoiding pipe-fill deadlock during shutdown.
                    rest, _ = proc.communicate(timeout=30)
                    log.write(rest)
                    assert proc.returncode == 0
                    return
                if proc.poll() is not None: break
            raise RuntimeError(f'Storage assertion failed: {name}; inspect {OUT}')
    finally:
        selector.close()
        if proc.poll() is None: proc.kill(); proc.wait()


if __name__ == '__main__':
    with gzip.open(GUEST/('system.seed' if SYSTEM else 'disk.seed'), 'rb') as source, DISK.open('wb') as dest:
        shutil.copyfileobj(source, dest)
    if SYSTEM:
        install = "apk add --no-cache tree && " if '--network' in sys.argv else ""
        verify = "apk info -e tree && tree --version && " if '--network' in sys.argv else ""
        boot(install + f"echo {TOKEN} > /etc/flyby-persist-test; echo {TOKEN} > /usr/local/persist-test; sync; printf '\\nSYSTEM_WRITE_OK\\n'",
             '\r\nSYSTEM_WRITE_OK\r\n', 'write.log')
        boot(verify + f"[ \"$(cat /etc/flyby-persist-test)\" = {TOKEN} ] && [ \"$(cat /usr/local/persist-test)\" = {TOKEN} ] && grep '/dev/nvme0n1 / ext4' /proc/mounts && printf '\\nSYSTEM_PERSIST_OK\\n'",
             '\r\nSYSTEM_PERSIST_OK\r\n', 'read.log')
        print('PASS: full ext4 root survives fresh RVVM process; package test=' + str('--network' in sys.argv))
        sys.exit(0)
    boot(f"echo {TOKEN} > /root/persist-test; echo {TOKEN} > /data/persist-test; sync; printf '\\nWRITE_OK\\n'",
         '\r\nWRITE_OK\r\n', 'write.log')
    boot(f"[ \"$(cat /root/persist-test)\" = {TOKEN} ] && [ \"$(cat /data/persist-test)\" = {TOKEN} ] && printf '\\nPERSISTENCE_OK\\n'",
         '\r\nPERSISTENCE_OK\r\n', 'read.log')
    print('PASS: /root and /data survive guest poweroff and a fresh RVVM process')
