#!/usr/bin/env python3
"""Exercise the actual interpreter, UART, Alpine userspace and guest shutdown."""
import pathlib, subprocess, selectors, sys, time
ROOT=pathlib.Path(__file__).resolve().parents[1]
exe=sys.argv[1] if len(sys.argv)>1 else str(ROOT/'out/host/flyby-host')
log=ROOT/'out/smoke-boot.log'
proc=subprocess.Popen([exe,str(ROOT/'app/src/main/assets/vm')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
selector=selectors.DefaultSelector();selector.register(proc.stdout,selectors.EVENT_READ)
buffer=b'';sent=False;checked=False;deadline=time.monotonic()+300
try:
 with log.open('wb') as output:
  while time.monotonic()<deadline:
   for key,_ in selector.select(1):
    data=key.fileobj.read1(16384)
    if not data: break
    output.write(data);output.flush();buffer+=data
    sys.stdout.buffer.write(data);sys.stdout.flush()
   if b'Initramfs unpacking failed' in buffer: raise RuntimeError('Corrupt initramfs; see '+str(log))
   if b'FLYBY_ALPINE_READY' in buffer and not sent:
    proc.stdin.write(b"uname -a; cat /etc/os-release; ls /; cd /; echo FLYBY_INPUT_OK; free; printf '\\033[31mRED\\033[0m\\n'\n")
    proc.stdin.flush();sent=True;buffer=b''
   if sent and b'\r\nFLYBY_INPUT_OK\r\n' in buffer and b'Mem:' in buffer and b'ID=alpine' in buffer and b'riscv64 Linux' in buffer and b'\x1b[31mRED\x1b[0m' in buffer:
    checked=True;proc.stdin.write(b'poweroff\n');proc.stdin.flush();break
   if proc.poll() is not None: break
  if not checked: raise RuntimeError('Alpine interactive shell did not pass smoke test; see '+str(log))
  proc.wait(timeout=30)
  # Drain the shutdown log as well.
  remaining=proc.stdout.read();output.write(remaining)
  if proc.returncode: raise RuntimeError('VM exited with '+str(proc.returncode))
  print('\nPASS: Linux, Alpine, shell input/output, guest poweroff')
finally:
 if proc.poll() is None: proc.kill();proc.wait()
