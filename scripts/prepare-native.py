#!/usr/bin/env python3
"""Fetch verified source archives; never downloads prebuilt native executables."""
import hashlib, pathlib, shutil, tarfile, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCES={
 'rvvm': ('https://codeload.github.com/LekKit/RVVM/tar.gz/ce8ca7c00ba4058e5f26811057573b3ff23e9316','e14f95d6b9fd03e35374297e66ea6c082f0b417bbc796e34e1c35bead2d17867'),
 'libvterm': ('https://www.leonerd.org.uk/code/libvterm/libvterm-0.3.3.tar.gz','09156f43dd2128bd347cbeebe50d9a571d32c64e0cf18d211197946aff7226e0'),
}
def main():
 for name,(url,digest) in SOURCES.items():
  dest=ROOT/'native/vendor'/name
  stamp=dest/'.flyby-sha256'
  if stamp.exists() and stamp.read_text()==digest: continue
  archive=ROOT/'out/downloads'/(name+'.tar.gz');archive.parent.mkdir(parents=True,exist_ok=True)
  if not archive.exists():
   partial=archive.with_suffix('.partial')
   with urllib.request.urlopen(url,timeout=90) as source,partial.open('wb') as target: shutil.copyfileobj(source,target)
   partial.replace(archive)
  if hashlib.sha256(archive.read_bytes()).hexdigest()!=digest: raise ValueError('SHA256 mismatch: '+str(archive))
  if dest.exists(): raise RuntimeError(f'{dest} exists without matching stamp; move aside before provisioning')
  dest.mkdir(parents=True)
  with tarfile.open(archive) as tar:
   for item in tar:
    parts=pathlib.PurePosixPath(item.name).parts[1:]
    if not parts: continue
    if '..' in parts or item.name.startswith('/') or not (item.isfile() or item.isdir()): raise ValueError('Unsafe source archive entry')
    path=dest.joinpath(*parts)
    if item.isdir(): path.mkdir(parents=True,exist_ok=True)
    else:
     path.parent.mkdir(parents=True,exist_ok=True)
     path.write_bytes(tar.extractfile(item).read());path.chmod(item.mode & 0o777)
  stamp.write_text(digest)
  print('Verified source:',name,digest)
if __name__=='__main__':main()
