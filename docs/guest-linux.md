# Alpine riscv64 guest

Run `python3 scripts/prepare-alpine-riscv64.py`. It produces the kernel, firmware,
initramfs, five image seeds and provenance in `app/src/main/assets/vm/`.
The Linux build host needs Docker, Python/PyYAML, the RISC-V helper compiler and
e2fsprogs. Guest installation still runs without root or target-architecture
install scripts.

## Package resolution

A clean build resolves current Alpine Edge packages using the official
`alpine:edge` container's apk and trust keys. Only requested package names are
maintained in `scripts/alpine_packages.py`; apk selects current versions and
their transitive dependencies from signed main/community indexes. No individual
APK filenames, versions or download URLs are hardcoded. Kernel configuration is
discovered inside the selected linux-lts package. The minirootfs filename and
SHA256 come from the official riscv64 `latest-releases.yaml`.

The resolved versions, recipe commits, repositories, URLs and SHA256 values are
saved atomically in `out/alpine/lock.json`. Downloads are content-addressed in
`out/downloads`. Image construction uses this snapshot offline and verifies
hashes and package signatures. Re-running preparation reuses the snapshot;
`python3 scripts/prepare-alpine-riscv64.py --refresh-packages` selects newer
packages. If a saved snapshot has been pruned upstream and is not cached, refresh
it explicitly and rerun the acceptance tests. Mismatching bytes are never accepted.

CI resolves once per workflow and passes the same lock to the host tests,
Android emulator and nightly build. The lock is retained as a workflow artifact
and included as `alpine-lock.json` in the corresponding-source archive.
Native runtime sources remain independently pinned in `prepare-native.py`.

The APK's generated `assets/vm/provenance.json` records the actual installed
packages for every image. `docs/guest-provenance.json` is a historical reference;
the generated metadata is authoritative for a new build. Source bundling reads
each exact APKBUILD in a network-disabled, read-only container, expands its shell
variables and handles `filename::URL` aliases. It tries Alpine's Edge distfiles
mirror, then release directories discovered from the official distfiles index
(newest first), then the recipe's upstream URL. Interrupted downloads are retried
up to four times, and every source must match the recipe's SHA512. A newer source release is never substituted for an older binary.

The script creates a root-owned newc archive with fixed timestamps and gzip mtime.
It uses the real minirootfs and installs a small `/init`: mount proc/sys/devtmpfs/
devpts, set hostname, exec BusyBox init. Its inittab starts a login shell on ttyS0
and a respawning private control daemon on ttyS1. The shell prints
`FLYBY_ALPINE_READY` and uses `TERM=xterm-256color`. The initramfs-only diagnostic environment retains its root shell. Fresh persistent
disks instead open the serial shell as `flybyuser` (UID/GID 1000), with a private
`/home/flybyuser`. The first serial session asks you to choose a password before
opening that shell. Desktop clients run as the same user. Root password login
is locked; `doas COMMAND` authenticates with your user password for administration.
No default password is shipped. Existing/imported disks retain their accounts.
Create a fresh disk after exporting your files to use this account arrangement. Alpine's default SSL CA
bundle is retained for HTTPS. The RTL8169 NIC uses RVVM user-mode sockets.
Kernel modules and their transitive dependencies come from the pinned linux-lts
APK. `scripts/guest_modules.py` declares the same module set for initramfs,
Minimal Alpine and Service Alpine. Selected aliases and soft dependencies are
included for modprobe and kernel-triggered module requests.

| Use | Included modules |
| --- | --- |
| Program formats | `binfmt_misc` |
| Filesystems and disk images | `ext4`, `overlay`, `fuse`, `loop`, `squashfs`, `vfat`, `exfat`, `nls_cp437`, `nls_utf8` |
| Virtual networks | `tun`, `veth`, `bridge`, `br_netfilter`, `8021q`, `dummy` |
| VPN | `wireguard` and its crypto/tunnel dependencies |
| Firewall and NAT | `nf_conntrack`, `nf_nat`, `nf_tables`, `nft_ct`, `nft_chain_nat`, `nft_nat`, `nft_masq`, `nft_redir`, `nft_reject`, `nft_reject_inet`, `nft_log`, `nf_log_syslog`, `nft_limit`, `nft_compat`, `xt_conntrack`, `xt_MASQUERADE`, `xt_addrtype`, `xt_comment`, `xt_tcpudp` |
| VM devices and shared folder | `realtek`, `r8169`, `af_packet`, `9p`, `9pnet`, `9pnet_fd` |

The additional modules are available on demand; they are not all loaded at boot.
For example, enable the binfmt_misc registration interface with:

```sh
modprobe binfmt_misc
mkdir -p /proc/sys/fs/binfmt_misc
mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc
```

Register the desired interpreter separately. This supplies kernel support, not
QEMU interpreters, a container engine, VPN configuration or FUSE daemons. Install
userspace tools such as `nftables`, `iproute2`, `wireguard-tools`, `fuse3` or
filesystem utilities with apk as needed. When an app update changes the kernel version, the boot initramfs installs its
matching module set if that version is missing from the persistent disk. Older
module versions and user files are preserved. New disks already contain the set.

Both image tests load every requested module, resolve TUN/veth aliases and execute
an extension handler through binfmt_misc. They also verify OverlayFS copy-up and
opening `/dev/fuse`. The Minimal image is exercised at 128 and 768 MiB, while the
Android test boots both variants at 128 MiB.

The firmware, Image, FDT and initrd layout is in `native/runtime/vm.h` and
[the runtime document](riscv-runtime.md). Serial console is NS16550 `ttyS0`, verified
by the running guest. The control daemon handles Stop via BusyBox poweroff and
resize via `stty -F /dev/ttyS0`; a native readiness gate prevents boot-time loss.

Run `python3 scripts/smoke-boot.py` after building the host target. It boots the
same board, waits for the real Alpine shell, sends commands, checks output and
powers off. `ctest` additionally tests the private resize/Stop path and Ctrl+C.
Failure keeps serial diagnostics in `out/smoke-boot.log`. The root filesystem is
in RAM except `/root` and `/data`, which use the private ext4/NVMe disk. See
[storage/network](storage-network.md) for seed generation, lifecycle and tests.

## Edge and existing installations

New installations and Disk Creator use the current official Edge minirootfs.
Both initramfs and SYSTEM seed configure exactly these repositories:

```text
https://dl-cdn.alpinelinux.org/alpine/edge/main
https://dl-cdn.alpinelinux.org/alpine/edge/community
```

`testing` is not enabled. Each workflow selects current Edge packages and locks them
for all of its jobs. Runtime `apk update` fetches fresh Edge package indexes.

## Image variants

Disk Creator offers **Minimal Alpine** (the default, existing BusyBox init) and
**Service Alpine** (OpenRC, including `openrc-init` as PID 1).
Both use the same resolved Edge base, kernel, networking, 1–100 GiB persistent
root and Android `/shared` support. Minimal's boot and package set stay unchanged.
Service adds the resolved OpenRC packages and dependencies listed in provenance.
The build uses a resolved x86_64 Linux `apk.static` with signature verification,
offline dependency resolution and no cross-architecture install scripts.
Runlevels are configured explicitly; no package metadata is fabricated.

Service enables `flyby-boot`, `flyby-control`, `flyby-console` and `flyby-network`.
The console and private control channel are supervised. The initramfs already
mounts the virtual filesystems; generic hardware discovery is not needed.
Use `rc-service NAME start|stop|restart|status` and `rc-update add NAME default`
to manage services. Service shutdown uses `openrc-shutdown`, runs stop hooks,
and remounts filesystems read-only; app Stop allows up to 30 seconds for this.

Updating the Android app retains the existing disk. To try either image, stop
Linux and create a disk in Settings, confirming replacement. Export first if
its contents are needed. There is no in-place Edge migration button in this
prototype. Imported disks retain their own init and packages; the creation
selector is not an assertion about the active disk.

`scripts/test-service.py` boots the real Service seed twice and checks PID 1,
package registration, service start/stop/restart, the shutdown hook and enabled
service persistence. Android instrumentation creates a Service disk through the
service intent, boots it at 128 MiB, verifies OpenRC and exercises export/import.
