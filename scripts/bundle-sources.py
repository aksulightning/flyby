#!/usr/bin/env python3
"""Collect pinned Alpine recipes, all SHA512-listed sources, native sources and Flyby.
Fails closed: a nightly must not publish if a required corresponding source is missing.
"""
import hashlib, http.client, io, json, pathlib, re, shutil, socket, ssl, subprocess, tarfile, tempfile, time, urllib.request, urllib.error
from functools import lru_cache
from html.parser import HTMLParser
from alpine_sources import recipe_sources
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT/'out/corresponding-source'
CACHE = ROOT/'out/source-downloads'

DOWNLOAD_ATTEMPTS = 4
RETRYABLE_HTTP_STATUS = {408, 429, 500, 502, 503, 504}


class DownloadUnavailable(RuntimeError):
    """A source endpoint remained unavailable after bounded retries."""


def transient_download_error(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code in RETRYABLE_HTTP_STATUS
    if isinstance(error, urllib.error.URLError):
        error = error.reason
    # Never turn certificate failures or local filesystem errors into retries.
    return isinstance(error, (ConnectionError, TimeoutError, socket.gaierror,
                              http.client.IncompleteRead, ssl.SSLEOFError))


def download(url, path):
    if path.exists():
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.partial')
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        print(f'Downloading ({attempt}/{DOWNLOAD_ATTEMPTS}): {url}', flush=True)
        try:
            with urllib.request.urlopen(url, timeout=120) as src, tmp.open('wb') as dest:
                shutil.copyfileobj(src, dest)
                # A fixed-length response can end early without read(size) raising.
                length = src.headers.get('Content-Length')
                if length is not None and dest.tell() < int(length):
                    raise http.client.IncompleteRead(b'', int(length) - dest.tell())
            tmp.replace(path)
            return path
        except (OSError, http.client.HTTPException) as error:
            if not transient_download_error(error):
                raise
            if attempt == DOWNLOAD_ATTEMPTS:
                raise DownloadUnavailable(
                    f'Download failed after {attempt} attempts: {url}: {error}') from error
            delay = 2 ** attempt
            print(f'Temporary download failure: {url}: {error}; retrying in {delay}s', flush=True)
        finally:
            # Never reuse an interrupted transfer, including on permanent failures.
            tmp.unlink(missing_ok=True)
        time.sleep(delay)

def digest(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        while chunk := stream.read(1024*1024): h.update(chunk)
    return h.hexdigest()


DISTFILES = 'https://distfiles.alpinelinux.org/distfiles/'


def parse_source_mirrors(index):
    class BranchIndex(HTMLParser):
        def __init__(self):
            super().__init__()
            self.branches = {}

        def handle_starttag(self, tag, attrs):
            if tag != 'a':
                return
            href = dict(attrs).get('href') or ''
            match = re.fullmatch(r'v([0-9]+)\.([0-9]+)/', href)
            if match:
                self.branches[tuple(map(int, match.groups()))] = href

    parser = BranchIndex()
    parser.feed(index)
    return [parser.branches[version] for version in sorted(parser.branches, reverse=True)]


@lru_cache(maxsize=1)
def discover_source_mirrors():
    # Discover available branches once per invocation, not from a stale disk cache.
    # Accept only version directories on the official host, never links to other hosts.
    try:
        with tempfile.TemporaryDirectory(prefix='flyby-source-index-') as tmp:
            path = download(DISTFILES, pathlib.Path(tmp)/'index.html')
            branches = parse_source_mirrors(path.read_text())
    except (urllib.error.HTTPError, DownloadUnavailable) as error:
        print(f'Cannot list Alpine source mirrors; using recipe URLs: {error}', flush=True)
        return []
    print('Discovered Alpine source mirrors: ' + ', '.join(branches), flush=True)
    return branches


def source_urls(name, upstream):
    yield DISTFILES + 'edge/' + name
    # Older identical sources may have moved from Edge into a release directory.
    # This changes only where bytes are fetched, not the package version or checksum.
    for branch in discover_source_mirrors():
        yield DISTFILES + branch + name
    if upstream:
        yield upstream

def source_archive(name, expected, upstream=None):
    # Search discovered distribution mirrors before the exact recipe's upstream.
    failures = []
    for url in source_urls(name, upstream):
        try:
            path = download(url, CACHE/expected/name)
        except (urllib.error.HTTPError, DownloadUnavailable) as failure:
            if isinstance(failure, urllib.error.HTTPError) and failure.code not in (404, 410):
                raise
            failures.append(f'{url}: {failure}')
            print(f'Source endpoint unavailable for {name}: {url}: {failure}', flush=True)
            continue
        if digest(path, 'sha512') != expected:
            path.unlink()
            raise ValueError(f'Source checksum mismatch: {name} from {url}')
        return path
    raise FileNotFoundError(f'Pinned source unavailable: {name}; ' + '; '.join(failures))

def main():
    if OUT.exists(): shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)
    metadata = json.loads((ROOT/'app/src/main/assets/vm/provenance.json').read_text())
    recipes = sorted({(p.get('repository', 'main'),p['origin'],p['commit']) for p in metadata['packages']})
    for repository, origin, commit in recipes:
        archive = download(f'https://codeload.github.com/alpinelinux/aports/tar.gz/{commit}', CACHE/(commit+'.tar.gz'))
        prefix = f'aports-{commit}/{repository}/{origin}/'
        dest = OUT/'aports'/commit/repository/origin
        dest.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            for item in tar:
                if not item.name.startswith(prefix) or not item.isfile(): continue
                relative = pathlib.PurePosixPath(item.name[len(prefix):])
                if '..' in relative.parts or relative.is_absolute(): raise ValueError('Unsafe recipe path')
                target = dest/relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(item).read()); target.chmod(item.mode & 0o777)
        sources = recipe_sources(dest)
        if not sources and origin != 'alpine-base': raise ValueError(f'No SHA512 source manifest for {origin}')
        # alpine-base generates release metadata entirely from this APKBUILD.
        for expected, name, upstream in sources:
            if '/' in name or name in ('.','..'): raise ValueError('Unsafe source filename')
            path = dest/name
            if not path.exists():
                path = source_archive(name, expected, upstream)
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
    shutil.copy2(ROOT/'out/alpine/lock.json',OUT/'alpine-lock.json')
    (OUT/'BUILDING.txt').write_text('Flyby '+revision+'\n\nExtract flyby-source.tar.gz and follow README.md. Native archives match scripts/prepare-native.py.\n'
        'Alpine recipe directories include all checksum-listed upstream distfiles, patches and configs.\n'
        'Use Alpine abuild for riscv64 with the exact APKBUILD in each directory; package versions/revisions\n'
        'are recorded in guest-provenance.json. Copy these distfiles into abuild SRCDEST for offline fetch.\n'
        'The packaged guest is produced by scripts/prepare-alpine-riscv64.py from official APKs resolved in out/alpine/lock.json.\n'
        'Sources for every package in that guest, including Linux, BusyBox, apk-tools and OpenSBI, are included.\n')
    hashes = {str(p.relative_to(OUT)):digest(p) for p in OUT.rglob('*') if p.is_file() and p.name!='SHA256SUMS.json'}
    (OUT/'SHA256SUMS.json').write_text(json.dumps(hashes,indent=2)+'\n')
    release = ROOT/'out/release'; release.mkdir(exist_ok=True)
    with tarfile.open(release/'flyby-corresponding-source.tar.gz','w:gz') as tar: tar.add(OUT,arcname='flyby-corresponding-source')
    print('Source bundle complete:',release/'flyby-corresponding-source.tar.gz')
if __name__=='__main__': main()
