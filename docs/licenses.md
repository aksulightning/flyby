# Licenses and source provenance

Flyby's original source remains **Apache-2.0** (`LICENSE`). Third-party licenses
are identified before shipping these components. No external native/guest binaries
are checked into Git; the build provisions the exact archives from their official
sources. The debug APK contains the compiled RVVM/libvterm runtime and guest assets.

## Current dependencies

| Component | Pinned version / scope | License / primary source |
| --- | --- | --- |
| Android Gradle Plugin | 8.9.2, build tool | Apache-2.0 ([source](https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/NOTICE)) |
| Gradle and wrapper | 8.11.1, build tool | Apache-2.0 ([license](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE)) |
| Kotlin + Compose compiler plugin + stdlib | 2.1.20 | Apache-2.0 ([license](https://github.com/JetBrains/kotlin/blob/v2.1.20/license/LICENSE.txt)) |
| AndroidX Compose UI/Foundation/Material3 | BOM 2025.03.01 | Apache-2.0 ([source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)) |
| AndroidX activity-compose | 1.10.1 | Apache-2.0, same AndroidX license |
| AndroidX lifecycle-runtime-compose | 2.8.7 | Apache-2.0, same AndroidX license |
| kotlinx.coroutines Android/core/test | 1.10.1 | Apache-2.0 ([license](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.1/LICENSE.txt)) |
| JUnit | 4.13.2, test only | EPL-1.0 ([license](https://junit.org/junit4/license.html)) |
| Hamcrest | 1.3, JUnit test dependency | BSD-3-Clause ([license](https://github.com/hamcrest/JavaHamcrest/blob/v1.3/LICENSE.txt)) |
| JetBrains annotations | transitive dependency | Apache-2.0 ([license](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt)) |
| Guava listenablefuture | AndroidX transitive dependency | Apache-2.0 ([license](https://github.com/google/guava/blob/master/COPYING)) |
| libvterm | 0.3.3 | MIT; [full notice](licenses/libvterm-MIT.txt), verified source archive in prepare-native.py |
| RVVM library | ce8ca7c00ba4058e5f26811057573b3ff23e9316 | MPL-2.0; [full notice](licenses/RVVM-MPL-2.0.txt); GPL CLI excluded |
| GitHub Actions checkout / setup-java | v5, SHA-pinned, CI only | MIT ([checkout](https://github.com/actions/checkout/blob/v5/LICENSE), [setup-java](https://github.com/actions/setup-java/blob/v5/LICENSE)) |
| android-actions/setup-android | v3, SHA-pinned, CI only | MIT ([license](https://github.com/android-actions/setup-android/blob/v3/LICENSE)) |

Android SDK/NDK are external development tools with their own distribution terms
and component notices. They are not checked into this repository. Review all
resolved runtime artifacts and preserve notices when making a distributable APK;
build/test tools do not automatically become APK contents.

## Guest packages actually bundled

Read from the pinned minirootfs APK database and the kernel/firmware `.PKGINFO`,
not inferred from project names. Full immutable source recipe links, versions and
archive SHA-256 values are in [guest-provenance.json](guest-provenance.json), also
packaged as `assets/vm/provenance.json`. Kernel config is extracted to
`out/guest/kernel.config` by the preparation script.

| Package | Version | License expression | Exact Alpine source recipe |
| --- | --- | --- | --- |
| alpine-baselayout | 3.7.2-r1 | GPL-2.0-only | [60a7585b](https://gitlab.alpinelinux.org/alpine/aports/-/tree/60a7585bbab2fa0f762504eb617dbca90216e31f/main/alpine-baselayout) |
| alpine-baselayout-data | 3.7.2-r1 | GPL-2.0-only | [60a7585b](https://gitlab.alpinelinux.org/alpine/aports/-/tree/60a7585bbab2fa0f762504eb617dbca90216e31f/main/alpine-baselayout) |
| alpine-keys | 2.6-r0 | MIT | [b9f23bec](https://gitlab.alpinelinux.org/alpine/aports/-/tree/b9f23becced4d7b3ccc0fa0f28530243ccd314a0/main/alpine-keys) |
| alpine-release | 3.25.0_alpha20260805-r0 | MIT | [5aad27a9](https://gitlab.alpinelinux.org/alpine/aports/-/tree/5aad27a98146f1557e9e3408f615c59d44a41acb/main/alpine-base) |
| apk-tools | 3.0.7-r0 | GPL-2.0-only | [1397144f](https://gitlab.alpinelinux.org/alpine/aports/-/tree/1397144f807abac6f9df704d21a1f71827df13b6/main/apk-tools) |
| busybox | 1.38.0-r4 | GPL-2.0-only | [9ac9bf53](https://gitlab.alpinelinux.org/alpine/aports/-/tree/9ac9bf5318a9ccc8af34b1389eba3df432488673/main/busybox) |
| busybox-binsh | 1.38.0-r4 | GPL-2.0-only | [9ac9bf53](https://gitlab.alpinelinux.org/alpine/aports/-/tree/9ac9bf5318a9ccc8af34b1389eba3df432488673/main/busybox) |
| ca-certificates-bundle | 20260611-r0 | MPL-2.0 AND MIT | [5f8337af](https://gitlab.alpinelinux.org/alpine/aports/-/tree/5f8337afea8674524d360be5fdb513e7e57fcc29/main/ca-certificates) |
| libapk | 3.0.7-r0 | GPL-2.0-only | [1397144f](https://gitlab.alpinelinux.org/alpine/aports/-/tree/1397144f807abac6f9df704d21a1f71827df13b6/main/apk-tools) |
| libcrypto3 | 3.5.7-r0 | Apache-2.0 | [35c0d1f2](https://gitlab.alpinelinux.org/alpine/aports/-/tree/35c0d1f2b314f647008595f681786813760da191/main/openssl) |
| libssl3 | 3.5.7-r0 | Apache-2.0 | [35c0d1f2](https://gitlab.alpinelinux.org/alpine/aports/-/tree/35c0d1f2b314f647008595f681786813760da191/main/openssl) |
| musl | 1.2.6-r2 | MIT | [b0c8ea10](https://gitlab.alpinelinux.org/alpine/aports/-/tree/b0c8ea10e8f29cabe336b2e5d864124940e126ab/main/musl) |
| musl-utils | 1.2.6-r2 | MIT AND BSD-2-Clause AND GPL-2.0-or-later | [b0c8ea10](https://gitlab.alpinelinux.org/alpine/aports/-/tree/b0c8ea10e8f29cabe336b2e5d864124940e126ab/main/musl) |
| scanelf | 1.3.9-r1 | GPL-2.0-only | [c61801ee](https://gitlab.alpinelinux.org/alpine/aports/-/tree/c61801eeacb3ffcd9c2025b09e402153bb93fb39/main/pax-utils) |
| ssl_client | 1.38.0-r4 | GPL-2.0-only | [9ac9bf53](https://gitlab.alpinelinux.org/alpine/aports/-/tree/9ac9bf5318a9ccc8af34b1389eba3df432488673/main/busybox) |
| zlib | 1.3.2-r0 | Zlib | [f248b33b](https://gitlab.alpinelinux.org/alpine/aports/-/tree/f248b33b5943c7dc69bf691031d7612ab2e8ed93/main/zlib) |
| linux-lts | 6.18.53-r0 | GPL-2.0-only | [e4f5708a](https://gitlab.alpinelinux.org/alpine/aports/-/tree/e4f5708adc6a4e631b7dde47106bab095d5f1914/main/linux-lts) |
| opensbi | 1.9-r0 | BSD-2-Clause | [e3e95307](https://gitlab.alpinelinux.org/alpine/aports/-/tree/e3e95307fe4ab08caf28babdaac0b11e2cedb305/main/opensbi) |

## Native integration and distribution

The selected RVVM library revision is MPL-2.0, **not** the GPL-3.0-or-later v0.6
release. The full `src/main.c` and `src/rvvm_user_main.c` CLI programs are not built.
The CMake source set only includes the MPL library and selected devices. libvterm
is MIT. No upstream source modifications are made; CMake selects features and
routes RVVM diagnostics through a small Android adapter. Exact source archives
and hashes are in `scripts/prepare-native.py`. Preserve upstream notices and make
these pinned sources and build scripts available with a distributed binary.
MPL permits larger works under other terms while keeping covered files under MPL:
[license sections 3.1–3.3](https://www.mozilla.org/MPL/2.0/),
[Mozilla FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).

Linux/BusyBox/apk-tools and other guest packages are separate programs, but packaging
them in an APK still carries their licenses. For a public binary release, provide
**complete corresponding source alongside it**: every applicable source archive,
the exact aports recipes/patches/configs at the commits above, Flyby guest scripts,
and required build materials. Fetch the versions and checksummed distfiles specified
by those APKBUILD files; preserve their notices. A generic upstream URL, this
inventory, or just the kernel config is not a complete GPL source distribution.
`scripts/bundle-sources.py` assembles all SHA512-listed distfiles and the exact
aports directories, plus native archives, Flyby source and kernel config. The
nightly job fails closed if this source bundle cannot be built and uploads it
alongside the APK. Physical ARM64 acceptance remains required for a stable release;
the user-requested nightly is explicitly a development prerelease.

GPL obligations for the guest do not disappear because it is emulated. Conversely,
separate guest programs do not change Flyby's original Apache-2.0 license. There is
no linked QEMU/GLib/libfdt dependency in the implemented runtime. See the historical
Phase 1 QEMU document only for the superseded proposal.

Android runtime CI uses ReactiveCircus/android-emulator-runner at
`a421e43855164a8197daf9d8d40fe71c6996bb0d`, Apache-2.0 (Yang Chen).
Its upstream LICENSE was inspected before use. It is a CI tool, not packaged in Flyby.

The NVMe, PCI, Goldfish RTC, RTL8169 and user socket network modules come from
the same pinned MPL-2.0 RVVM source. Host disk provisioning uses e2fsprogs mke2fs/debugfs
(GPL-2.0), a build tool not packaged in the APK. Added kernel modules come from
the already documented GPL-2.0 Linux artifact; no new guest packages are added.

Nightly development-key caching uses actions/cache v4.2.4 at
`0400d5f644dc74513175e3cd8d07132dd4860809`, MIT
([license](https://github.com/actions/cache/blob/v4.2.4/LICENSE)).
No new Android runtime dependencies are introduced by settings or disk transfer.

The networked persistence smoke test installs `tree` from Edge (GPL-2.0-or-later,
[Alpine recipe](https://github.com/alpinelinux/aports/blob/master/main/tree/APKBUILD))
into a disposable test disk. It is not present in either seed or the released APK.
