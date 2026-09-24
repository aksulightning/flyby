#!/usr/bin/env python3
"""Real outbound DNS/HTTP/HTTPS. Requires network access without a host-only proxy."""
import pathlib, subprocess, selectors, sys, time
ROOT=pathlib.Path(__file__).resolve().parents[1]
exe=sys.argv[1] if len(sys.argv)>1 else str(ROOT/'out/host/flyby-host')
log=ROOT/'out/network-test.log'
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
   if b'FLYBY_ALPINE_READY' in buffer and b'FLYBY_NETWORK_READY' in buffer and not sent:
    proc.stdin.write(b"flyby-network-check || echo FLYBY_NETWORK_FAILED\n")
    proc.stdin.flush();sent=True;buffer=b''
   if sent and b'\r\nFLYBY_NETWORK_OK\r\n' in buffer:
    checked=True;proc.stdin.write(b'poweroff\n');proc.stdin.flush();break
   if b'\r\nFLYBY_NETWORK_FAILED\r\n' in buffer: break
   if b'FLYBY_NETWORK_OFFLINE' in buffer or b'FLYBY_NETWORK_ERROR' in buffer: break
   if proc.poll() is not None: break
  if not checked: raise RuntimeError('Guest DNS/HTTP/HTTPS failed; see '+str(log))
  proc.wait(timeout=30)
  # Drain the shutdown log as well.
  remaining=proc.stdout.read();output.write(remaining)
  if proc.returncode: raise RuntimeError('VM exited with '+str(proc.returncode))
  print('\nPASS: guest DNS, HTTP, HTTPS with CA verification and poweroff')
finally:
 if proc.poll() is None: proc.kill();proc.wait()
