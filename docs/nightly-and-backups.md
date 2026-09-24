# Nightly builds, appearance and disk backups

## Download and identify the new build

Open https://github.com/aksulightning/flyby/releases and choose a `nightly-*`
**prerelease**, then download its uniquely named `flyby-nightly-…-arm64-v8a.apk`.
Settings shows version `0.2.0-nightly.RUN+COMMIT`, version code and full commit SHA.
Local builds show `0.2.0-dev / local-unpublished` unless build properties are supplied.
The APK, SHA256SUMS, development signing fingerprint and matching corresponding
sources are assets of the same release. Do not use GitHub's generic source ZIP as
an APK or as a substitute for the complete corresponding-source bundle.

The existing Android workflow publishes only after **both** host/build/unit and
Android API 35 runtime jobs pass. Pushes to `codex/riscv-phase2` and manual runs on
that branch produce nightlies; PRs cannot publish. This is a tested-build channel,
not a daily timer (scheduled workflows would need to live on the default branch).

Nightlies use an explicit `out/signing/debug.keystore` selected with
`-PflybyNightly=true`, retained in Actions cache; it is never committed. Before publication the job saves the key, deletes the local copy, restores it
from cache and compares its public certificate to the APK signer. A cache miss or
fingerprint mismatch stops publication. The password
used by keytool is Android's public default debug password, not a production secret. This is not production signing and must not be used for trusted
production distribution. Before publishing a later nightly, the script compares
its signer against the previous nightly; missing/changed keys stop publication.
Cache eviction needs restoration of the original key or an explicit channel
migration, not a silent certificate change. An older local debug APK may have a
different signer: Android then rejects an in-place update. **Preserve/export data
before uninstalling anything**; uninstall removes Android private files.

## Appearance

Settings → Appearance → **Night**, **Light**, or **Follow device**. The preference
survives Activity recreation and process restarts. The terminal retains its black
ANSI canvas; the app chrome and settings follow the selected Material color scheme.

## Whole-system persistence

Whole-system persistence is now the default. Stop Linux, then use Settings →
**Disk Creator** to create a fresh 1–100 GiB Alpine disk (replacement is confirmed).
Start boots the packaged kernel/initramfs, loads ext4, mounts the NVMe disk and
`switch_root`s into Alpine on that disk. `/etc`, `/usr`, `/root`, the APK package
database and installed packages persist across Stop/Start. `/run` and `/tmp` are
RAM-backed; `/proc`, `/sys` and `/dev` are virtual. Kernel and firmware are bundled
app resources, not updated by installing a kernel inside the guest.

Legacy DATA (`disk.raw`, 256 MiB) and SYSTEM (`system.raw`, 1–100 GiB) are **separate disks**
in `filesDir/vm/default`. DATA is retained but no longer offered as a mode in Settings;
there is no automatic migration. Export important legacy files with the previous app version.
Initial SYSTEM content is the same pinned Alpine minirootfs/modules/control tools
as the initramfs. Provisioning uses mke2fs/debugfs without root, mounts or fakeroot.
Seeds are verified and installed once; updates never silently reformat user disks.
Raw images are sparse on Android filesystems that support holes, but need space
as guest writes fill them. New SYSTEM disks expand ext4 to their configured capacity at first boot.

## Export and import

Stop Linux. In Settings, choose **Export disk** and choose a
file with Android's document picker. This creates a compressed `.flyby` backup.
To restore a SYSTEM backup: **Import disk** → choose the backup →
confirm **Replace disk**. The foreground service owns the transfer, holds a wake
lock only during work and reports progress. VM Start and storage changes are blocked
while copying; no broad storage permission is requested.

A backup contains a versioned header, disk type, guest compatibility ID, exact raw
image length, image bytes and SHA-256 digest, wrapped in gzip. Import checks the
metadata, bounded length, hash and filesystem signature while writing a separate
sparse staging file. It fsyncs and atomically renames only after validation; an
invalid/truncated/wrong-mode backup leaves the existing disk untouched. This is
not a filesystem repair tool. Only Flyby backups for the same guest/kernel family
are accepted, not arbitrary raw images, ZIPs or future incompatible versions.

Backups contain your guest files and are **not encrypted**. They do not contain
live VM RAM, terminal scrollback, app preferences or the other disk mode. Use Stop
rather than killing the process before export for a clean filesystem. If an export
fails, discard its incomplete destination file. Android process death can interrupt
a transfer; a fresh import safely replaces its staging file. A provider can refuse
access, be offline, or run out of space; the app reports that failure.

## Validation

`test-storage.py --system` verifies /etc and /usr changes across two native VM
processes and confirms ext4 is mounted at `/`. Add `--network` to install `tree`
with apk and verify the installed package after reboot (CI uses this).
JVM tests cover backup roundtrip, truncation and wrong disk type. The Android
runner retains prior boot/IME/network/lifecycle coverage and adds Night/storage
UI selection, full-system boot, service export/import and restored /etc content
after another boot. Its file URI fixture exercises service I/O without pretending
to test every Storage Access Framework provider. Physical ARM64 and real external
provider testing remain required before a stable release.

## Initial signing correction

`nightly-18-1cb501e` booted and passed acceptance but AGP's implicit key path did not
match the post-job cache path; its ephemeral key was not retained. It is superseded
by the first nightly with explicit key provisioning and verified pre-publication
cache save. This one known tag/fingerprint migration is recorded in the publisher;
all subsequent signer mismatches still stop publication. An installation of nightly
18 cannot update in place to the corrected signer. Export its disk before removing
it; this limitation does not affect disk backup compatibility.
