"""Read expanded APKBUILD source metadata in an isolated Alpine shell."""
import os
import pathlib
import re
import subprocess
import urllib.parse


def source_manifest(source, checksums):
    urls = {}
    for entry in source.split():
        alias, separator, uri = entry.partition('::')
        if not separator:
            uri = alias
            alias = pathlib.PurePosixPath(urllib.parse.urlsplit(uri).path).name
        if urllib.parse.urlsplit(uri).scheme in ('https', 'http', 'ftp'):
            if alias in urls and urls[alias] != uri:
                raise ValueError(f'Duplicate source filename: {alias}')
            urls[alias] = uri
    fields = checksums.split()
    if len(fields) % 2:
        raise ValueError('Malformed SHA512 source manifest')
    result = []
    seen = set()
    for expected, name in zip(fields[::2], fields[1::2]):
        if not re.fullmatch('[a-f0-9]{128}', expected):
            raise ValueError('Invalid source SHA512')
        if '/' in name or '\\' in name or name in ('.', '..') or name in seen:
            raise ValueError(f'Unsafe or duplicate source filename: {name}')
        seen.add(name)
        result.append((expected, name, urls.get(name)))
    return result


def recipe_sources(directory):
    # APKBUILD is shell, not a static URL list. Use its own variable expansion
    # and aliases (name::URL), but never execute a recipe on the build host or
    # expose credentials, the repository, the Docker socket or network to it.
    result = subprocess.run([
        'docker', 'run', '--rm', '--platform=linux/amd64',
        '--network=none', '--read-only', '--cap-drop=ALL',
        '--security-opt=no-new-privileges', '--pids-limit=64',
        '--memory=256m', '--user', f'{os.getuid()}:{os.getgid()}',
        '--tmpfs', '/tmp:rw,nosuid,nodev,size=16m',
        '-e', 'CARCH=riscv64', '-e', 'CHOST=riscv64-alpine-linux-musl',
        '-v', f'{directory.resolve()}:/recipe:ro', '-w', '/recipe',
        'alpine:edge', 'sh', '-ec',
        '. ./APKBUILD; printf "\\0FLYBY_SOURCE_MANIFEST\\0%s\\0%s" "$source" "$sha512sums"'
    ], capture_output=True, timeout=60, check=True)
    marker = b'\0FLYBY_SOURCE_MANIFEST\0'
    if marker not in result.stdout:
        raise ValueError('APKBUILD did not produce source metadata')
    source, checksums = result.stdout.rsplit(marker, 1)[1].decode().split('\0')
    return source_manifest(source, checksums)
