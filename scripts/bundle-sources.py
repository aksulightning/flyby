#!/usr/bin/env python3
"""Collect pinned Alpine recipes, all SHA512-listed sources, native sources and Flyby.
Fails closed: a nightly must not publish if a required corresponding source is missing.
"""
import hashlib, io, json, pathlib, re, shutil, subprocess, tarfile, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT/'out/corresponding-source'
CACHE = ROOT/'out/source-downloads'

def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        tmp = path.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=120) as src, tmp.open('wb') as dest: shutil.copyfileobj(src, dest)
        tmp.replace(path)
    return path

def digest(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        while chunk := stream.read(1024*1024): h.update(chunk)
    return h.hexdigest()

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    metadata = json.loads((ROOT/'app/src/main/assets/vm/provenance.json').read_text())
    recipes = sorted({(p['origin'],p['commit']) for p in metadata['packages']})
    for origin, commit in recipes:
        archive = download(f'https://codeload.github.com/alpinelinux/aports/tar.gz/{commit}', CACHE/(commit+'.tar.gz'))
        prefix = f'aports-{commit}/main/{origin}/'
        dest = OUT/'aports'/commit/'main'/origin
        dest.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            for item in tar:
                if not item.name.startswith(prefix) or not item.isfile(): continue
                relative = pathlib.PurePosixPath(item.name[len(prefix):])
                if '..' in relative.parts or relative.is_absolute(): raise ValueError('Unsafe recipe path')
                target = dest/relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(item).read()); target.chmod(item.mode & 0o777)
        recipe = (dest/'APKBUILD').read_text()
        sources = re.findall(r'([a-f0-9]{128})\s+([^\s"\'\\]+)', recipe)
        if not sources and origin != 'alpine-base': raise ValueError(f'No SHA512 source manifest for {origin}')
        # alpine-base generates release metadata entirely from this APKBUILD.
        for expected, name in sources:
            if '/' in name or name in ('.','..'): raise ValueError('Unsafe source filename')
            path = dest/name
            if not path.exists():
                path = download('https://distfiles.alpinelinux.org/distfiles/edge/'+name, CACHE/name)
                shutil.copy2(path, dest/name)
                path = dest/name
            if digest(path, 'sha512') != expected: raise ValueError(f'Source checksum mismatch: {origin}/{name}')
        print('Verified corresponding sources:',origin,commit,flush=True)
    # Full pinned native source archives (including notices) already verified by prepare-native.
    from importlib.machinery import SourceFileLoader
    native = SourceFileLoader('native_sources',str(ROOT/'scripts/prepare-native.py')).load_module()
    native.main()
    (OUT/'native').mkdir(exist_ok=True)
    for name, (_, expected) in native.SOURCES.items():
        source = ROOT/'out/downloads'/(name+'.tar.gz')
        if digest(source) != expected: raise ValueError('Native source checksum mismatch')
        shutil.copy2(source, OUT/'native'/source.name)
    revision = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    subprocess.run(['git','archive','--format=tar.gz','--prefix=flyby/','-o',str(OUT/'flyby-source.tar.gz'),revision],cwd=ROOT,check=True)
    (OUT/'guest-provenance.json').write_text(json.dumps(metadata,indent=2)+'\n')
    shutil.copy2(ROOT/'out/guest/kernel.config',OUT/'kernel.config')
    (OUT/'BUILDING.txt').write_text('Flyby '+revision+'\n\nExtract flyby-source.tar.gz and follow README.md. Native archives match scripts/prepare-native.py.\n'
        'Alpine recipe directories include all checksum-listed upstream distfiles, patches and configs.\n'
        'Use Alpine abuild for riscv64 with the exact APKBUILD in each directory; package versions/revisions\n'
        'are recorded in guest-provenance.json. Copy these distfiles into abuild SRCDEST for offline fetch.\n'
        'The packaged guest is produced by scripts/prepare-alpine-riscv64.py from official pinned APKs.\n'
        'Sources for every package in that guest, including Linux, BusyBox, apk-tools and OpenSBI, are included.\n')
    hashes = {str(p.relative_to(OUT)):digest(p) for p in OUT.rglob('*') if p.is_file() and p.name!='SHA256SUMS.json'}
    (OUT/'SHA256SUMS.json').write_text(json.dumps(hashes,indent=2)+'\n')
    release = ROOT/'out/release'; release.mkdir(exist_ok=True)
    with tarfile.open(release/'flyby-corresponding-source.tar.gz','w:gz') as tar: tar.add(OUT,arcname='flyby-corresponding-source')
    print('Source bundle complete:',release/'flyby-corresponding-source.tar.gz')
if __name__=='__main__': main()
