#!/usr/bin/env python3
"""Real OpenRC PID1, service lifecycle and enabled-service persistence."""
import gzip
import importlib.util
from pathlib import Path
import shutil
import shlex
from module_checks import FILESYSTEM_MODULE_CHECKS

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('storage_test', ROOT/'scripts/test-storage.py')
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
storage.SYSTEM = True
storage.OUT = ROOT/'out/service-test'
storage.OUT.mkdir(parents=True, exist_ok=True)
storage.DISK = storage.OUT/'disk.raw'
with gzip.open(storage.GUEST/'service.seed', 'rb') as source, storage.DISK.open('wb') as target:
    shutil.copyfileobj(source, target)
def boot(command, marker, log):
    try:
        storage.boot(command, marker, log)
    except BaseException:
        print((storage.OUT/log).read_text(errors='replace')[-20000:], flush=True)
        raise


# The test service writes start/stop evidence so shutdown tests prove OpenRC ran
# stop hooks before powering off, rather than merely retaining flushed writes.
service = '''#!/sbin/openrc-run
start() { echo started >> /root/service-events; }
stop() { echo stopped >> /root/service-events; }
'''
checks = "[ \"$(readlink /proc/1/exe)\" = /sbin/openrc-init ] && apk info -e openrc openrc-init && rc-service flyby-control status && "
setup = f"printf %s {shlex.quote(service)} > /etc/init.d/flyby-test && chmod +x /etc/init.d/flyby-test && "
boot(FILESYSTEM_MODULE_CHECKS + checks + setup + "rc-service flyby-test start && rc-service flyby-test status && rc-service flyby-test stop && rc-service flyby-test restart && rc-update add flyby-test default && printf '\\nSERVICE_WRITE_OK\\n'",
             '\r\nSERVICE_WRITE_OK\r\n', 'create.log')
boot(checks + "i=0; until rc-service flyby-test status >/dev/null 2>&1; do i=$((i+1)); [ $i -lt 30 ] || break; sleep 1; done; " + checks + "rc-service flyby-test status && [ \"$(cat /root/service-events | tr '\\n' ,)\" = started,stopped,started,stopped,started, ] && printf '\\nSERVICE_PERSIST_OK\\n'",
             '\r\nSERVICE_PERSIST_OK\r\n', 'restart.log')
print('PASS: overlay copy-up, FUSE device, OpenRC PID1, service lifecycle and boot persistence')
