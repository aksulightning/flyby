#!/usr/bin/env python3
"""An existing persistent disk gets matching modules after an app/kernel update."""
import gzip
import importlib.util
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('storage_test', ROOT/'scripts/test-storage.py')
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
storage.SYSTEM = True
storage.OUT = ROOT/'out/kernel-update-test'
storage.OUT.mkdir(parents=True, exist_ok=True)
storage.DISK = storage.OUT/'disk.raw'
with gzip.open(storage.GUEST/'system.seed', 'rb') as source, storage.DISK.open('wb') as target:
    shutil.copyfileobj(source, target)

# Model a disk whose module directory belongs to the previous app kernel.
storage.boot("echo keep-me > /root/kernel-update-data; mv /lib/modules/$(uname -r) /lib/modules/previous-kernel; sync; printf '\\nKERNEL_UPDATE_PREPARED\\n'",
             '\r\nKERNEL_UPDATE_PREPARED\r\n', 'prepare.log')
storage.boot("test -d /lib/modules/previous-kernel && test -d /lib/modules/$(uname -r) && "
             "test \"$(cat /root/kernel-update-data)\" = keep-me && modprobe uhid && modprobe wireguard && printf '\\nKERNEL_UPDATE_OK\\n'",
             '\r\nKERNEL_UPDATE_OK\r\n', 'updated.log')
print('PASS: matching modules installed on existing disk; previous modules and user data preserved')
