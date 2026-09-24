#!/usr/bin/env python3
"""Two separate RVVM processes must share actual ext4 data, not retained RAM."""
import gzip
from pathlib import Path
import selectors
import shutil
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
GUEST = ROOT / 'app/src/main/assets/vm'
OUT = ROOT / 'out/storage-test'
OUT.mkdir(parents=True, exist_ok=True)
DISK = OUT / 'disk.raw'
TOKEN = uuid.uuid4().hex


def boot(command, marker, name):
    proc = subprocess.Popen([str(ROOT/'out/host/flyby-host'), str(GUEST), str(DISK)],
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
                if not sent and b'FLYBY_ALPINE_READY' in buffer:
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
    with gzip.open(GUEST/'disk.raw.gz', 'rb') as source, DISK.open('wb') as dest:
        shutil.copyfileobj(source, dest)
    boot(f"echo {TOKEN} > /root/persist-test; echo {TOKEN} > /data/persist-test; sync; printf '\\nWRITE_OK\\n'",
         '\r\nWRITE_OK\r\n', 'write.log')
    boot(f"[ \"$(cat /root/persist-test)\" = {TOKEN} ] && [ \"$(cat /data/persist-test)\" = {TOKEN} ] && printf '\\nPERSISTENCE_OK\\n'",
         '\r\nPERSISTENCE_OK\r\n', 'read.log')
    print('PASS: /root and /data survive guest poweroff and a fresh RVVM process')
