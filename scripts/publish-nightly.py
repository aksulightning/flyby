#!/usr/bin/env python3
"""Run only after both CI verification jobs; publish APK plus matching source bundle."""
import hashlib, json, os, pathlib, shutil, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT/'out/release'
def gh(*args):
    return subprocess.check_output(['gh', *args], cwd=ROOT, text=True).strip()
def main():
    repo = os.environ['GITHUB_REPOSITORY']
    revision = os.environ['GITHUB_SHA']
    run = os.environ['GITHUB_RUN_NUMBER']
    assert repo == 'aksulightning/flyby'
    tag = f'nightly-{run}-{revision[:7]}'
    apk = OUT/f'flyby-{tag}-arm64-v8a.apk'
    shutil.copy2(ROOT/'app/build/outputs/apk/debug/app-debug.apk', apk)
    source = OUT/'flyby-corresponding-source.tar.gz'
    assert source.is_file() and source.stat().st_size > 0
    signer = pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/apksigner'
    certificates = subprocess.check_output([str(signer),'verify','--print-certs',str(apk)],text=True)
    fingerprint = next(line for line in certificates.splitlines() if line.startswith('Signer #1 certificate SHA-256 digest:'))
    (OUT/'nightly-signing.sha256').write_text(fingerprint+'\n')
    # Cache eviction must not silently change the certificate between nightlies.
    releases = json.loads(gh('api',f'repos/{repo}/releases?per_page=100'))
    previous = next((r for r in releases if r['tag_name'].startswith('nightly-') and not r['draft']),None)
    if previous:
        old = OUT/'previous-signing'; old.mkdir(exist_ok=True)
        gh('release','download',previous['tag_name'],'--repo',repo,'--pattern','nightly-signing.sha256','--dir',str(old),'--clobber')
        if (old/'nightly-signing.sha256').read_text().strip() != fingerprint:
            raise RuntimeError('Nightly signing certificate changed. Restore the original development key before publishing.')
    assets = [apk,source,OUT/'nightly-signing.sha256']
    checksums = OUT/'SHA256SUMS'
    with checksums.open('w') as output:
        for file in assets:
            h = hashlib.sha256()
            with file.open('rb') as data:
                while chunk := data.read(1024*1024): h.update(chunk)
            output.write(f'{h.hexdigest()}  {file.name}\n')
    notes = OUT/'release-notes.md'
    notes.write_text(f'''Flyby 0.2.0-nightly.{run} · ARM64 Android · commit `{revision}`

Install the uniquely named **arm64-v8a.apk** asset below. Settings shows this version and commit.

- Night / Light / Follow device appearance.
- Optional whole-system persistent 1 GiB Alpine root disk; installed packages and /etc survive Stop/Start.
- Export/import the selected disk through Android's document picker. Stop Linux first; import replaces that disk after validation.
- Existing /root + /data storage remains available as a separate disk.

Verified by the required host/unit/build and API 35 Android emulator jobs:
https://github.com/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}

Physical ARM64 phone, OEM screen-off behavior and third-party document providers have not been tested.
This is a development prerelease with a development signing key, not a production release.
An older APK signed with another key cannot update in place. Do not uninstall it before preserving its data.

The accompanying source bundle includes the exact Flyby revision, RVVM/libvterm sources and Alpine package recipes,
upstream source archives, patches and configurations for the bundled Linux/userspace. See BUILDING.txt in the archive.
''')
    # gh uploads all assets into a draft before publishing the prerelease.
    print(gh('release','create',tag,*map(str,assets),str(checksums),'--repo',repo,'--target',revision,
             '--prerelease','--title',f'Flyby {tag}','--notes-file',str(notes)))
if __name__ == '__main__': main()
