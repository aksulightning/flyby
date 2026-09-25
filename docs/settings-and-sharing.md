# Settings, Disk Creator and Android folder sharing

The app now starts a persistent Alpine SYSTEM disk by default. Settings removes
Home/Data mode selection. Existing `disk.raw` (the former Home/Data disk) is left
untouched; it is not automatically migrated, deleted or included in a SYSTEM
backup. Users upgrading with important files on that disk should export them with
the previous version before switching. `system.raw` is also retained on upgrade.

## RAM and Disk Creator

RAM accepts every integer from 128 to 768 MiB. It is saved only with **Save RAM**
and applies at the next start. The Kotlin and native runtime enforce the same
bounds. The initrd is placed near the top of the selected RAM with an overlap
check against the kernel, instead of a fixed address outside a 128 MiB machine.

Disk Creator accepts an integer from 1 to 100 GiB (binary units, matching the RAM
units). **Create Alpine disk** opens a replacement confirmation. While stopped,
a foreground service decompresses and verifies the complete Alpine ext4 seed in a
private staging directory, sparsely extends the image, fsyncs it and atomically
replaces `system.raw`. Failure before replacement retains the previous disk.
Creation is a fresh installation, not resizing the existing user filesystem.

On first boot the initramfs grows ext4 to the image capacity before switch_root.
The freestanding RV64 helper in `native/guest/grow-root.c` uses Linux's
`EXT4_IOC_RESIZE_FS` ioctl. It does not link libc. A failed growth or mount stops
normal boot and prints `FLYBY_STORAGE_ERROR`; no formatting or automatic repair
is attempted. Existing matching filesystems are left alone.

Image capacity is sparse, not a reservation of Android free space. Real storage
is needed for the Alpine files, ext4 metadata and later writes. Running out of
Android space can cause guest I/O errors. Export/import uses the actual image
length, validates 1–100 GiB SYSTEM sizes and retains legacy 256 MiB DATA archive
support internally. Large backups may take substantial time even when compressed.

Guest preparation requires `gcc-riscv64-linux-gnu` in addition to e2fsprogs.
CI installs the compiler, builds the helper from source and packages its bytes in
the verified initrd/system seed.

## /shared

**Choose Android folder** uses `OpenDocumentTree` and takes persistent read/write
URI permission. Selection and disconnect are permitted only when Linux and disk
operations are stopped. Cancellation leaves the old selection unchanged.
Picker results wait for the Activity to reconnect to the VM service before its
state is checked. A temporarily disconnected service is not a running VM; the
pending URI survives Activity recreation and its permission is retained on return.
Disconnect releases the previous URI permission; it does not delete files.
Revoked/moved/unavailable folders produce a start or file-operation error; select
a valid folder again in Settings. Android's system picker controls which folders
and providers may be selected.

A private third UART at `0x10002000`, IRQ 8, carries bounded 9P2000 messages.
The matching Alpine `9p`, `9pnet` and `9pnet_fd` modules mount it at `/shared`
using `trans=fd`, `cache=none`, `nodev` and `nosuid`. There is no listening network
port and the share does not depend on outbound networking. The initramfs mounts
the share before switch_root, including for an existing older SYSTEM disk.

The Kotlin server resolves only document IDs obtained from the selected tree.
It supports directory listing, read/write, creation, rename, truncation and
deletion through `DocumentsContract`/`ContentResolver`. `..` is confined to the
share root. Special files, hardlinks, symlinks and Unix ownership changes are not
supported. SAF providers can additionally restrict seek, write, rename or delete;
those failures return a filesystem error, not apparent success. Permissions and
timestamps are provider metadata, not a POSIX permissions database. File changes
and deletions affect Android files directly. Backups never include `/shared`.

The test APK provides a disposable DocumentsProvider; instrumentation uses it to
exercise real Android document calls from Linux (read, create, write, mkdir,
rename, delete and rmdir). It is not packaged in the production APK.

## Appearance and licenses

Theme, terminal font size (8–32 sp), line spacing (100–160%), underline/block/bar
cursor, extra key row and keeping the screen on in Terminal are persistent.
Terminal appearance changes do not restart Linux; font metrics resize its TTY.
**Licenses** opens a separate screen with selectable offline license texts,
notices and packaged Alpine package/source metadata.

Validation commands:

```sh
./gradlew test assembleDebug assembleDebugAndroidTest lintDebug
FLYBY_RAM_MIB=128 FLYBY_DISK_GIB=2 python3 scripts/test-storage.py --system
FLYBY_RAM_MIB=768 FLYBY_DISK_GIB=100 python3 scripts/test-storage.py --system
python3 scripts/test-android.py --network
```
