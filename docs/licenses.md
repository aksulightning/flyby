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
| alpine-baselayout | 3.7.2-r0 | GPL-2.0-only | [15b3b781](https://gitlab.alpinelinux.org/alpine/aports/-/tree/15b3b78187b3808104f98f765e9632e0b09281a7/main/alpine-baselayout) |
| alpine-baselayout-data | 3.7.2-r0 | GPL-2.0-only | [15b3b781](https://gitlab.alpinelinux.org/alpine/aports/-/tree/15b3b78187b3808104f98f765e9632e0b09281a7/main/alpine-baselayout) |
| alpine-keys | 2.6-r0 | MIT | [b9f23bec](https://gitlab.alpinelinux.org/alpine/aports/-/tree/b9f23becced4d7b3ccc0fa0f28530243ccd314a0/main/alpine-keys) |
| alpine-release | 3.23.6-r0 | MIT | [8608c797](https://gitlab.alpinelinux.org/alpine/aports/-/tree/8608c79733409c3aa716f7985d1cb45aedba55bd/main/alpine-base) |
| apk-tools | 3.0.8-r0 | GPL-2.0-only | [286502dc](https://gitlab.alpinelinux.org/alpine/aports/-/tree/286502dc74df00f907f93b0563867630dcc139e5/main/apk-tools) |
| busybox | 1.37.0-r30 | GPL-2.0-only | [1e823a60](https://gitlab.alpinelinux.org/alpine/aports/-/tree/1e823a60eb85606954b3a5af5f8e5bbd1ea680cf/main/busybox) |
| busybox-binsh | 1.37.0-r30 | GPL-2.0-only | [1e823a60](https://gitlab.alpinelinux.org/alpine/aports/-/tree/1e823a60eb85606954b3a5af5f8e5bbd1ea680cf/main/busybox) |
| ca-certificates-bundle | 20260909-r0 | MPL-2.0 AND MIT | [c733d58a](https://gitlab.alpinelinux.org/alpine/aports/-/tree/c733d58a9d16316e7fb6a49e65f28ecda365c56b/main/ca-certificates) |
| libapk | 3.0.8-r0 | GPL-2.0-only | [286502dc](https://gitlab.alpinelinux.org/alpine/aports/-/tree/286502dc74df00f907f93b0563867630dcc139e5/main/apk-tools) |
| libcrypto3 | 3.5.8-r0 | Apache-2.0 | [2b4b2590](https://gitlab.alpinelinux.org/alpine/aports/-/tree/2b4b2590f782b95276d31dcaaf41554b1a597a0b/main/openssl) |
| libssl3 | 3.5.8-r0 | Apache-2.0 | [2b4b2590](https://gitlab.alpinelinux.org/alpine/aports/-/tree/2b4b2590f782b95276d31dcaaf41554b1a597a0b/main/openssl) |
| musl | 1.2.5-r23 | MIT | [8aef0c37](https://gitlab.alpinelinux.org/alpine/aports/-/tree/8aef0c37b0ad23dc4137f0e4755b97a59dc698b8/main/musl) |
| musl-utils | 1.2.5-r23 | MIT AND BSD-2-Clause AND GPL-2.0-or-later | [8aef0c37](https://gitlab.alpinelinux.org/alpine/aports/-/tree/8aef0c37b0ad23dc4137f0e4755b97a59dc698b8/main/musl) |
| scanelf | 1.3.8-r2 | GPL-2.0-only | [3912b4fa](https://gitlab.alpinelinux.org/alpine/aports/-/tree/3912b4fa83437852312ad3c37a00229ff59fab1c/main/pax-utils) |
| ssl_client | 1.37.0-r30 | GPL-2.0-only | [1e823a60](https://gitlab.alpinelinux.org/alpine/aports/-/tree/1e823a60eb85606954b3a5af5f8e5bbd1ea680cf/main/busybox) |
| zlib | 1.3.2-r0 | Zlib | [f8c94d2e](https://gitlab.alpinelinux.org/alpine/aports/-/tree/f8c94d2e1d318ab29eb4ac5f00225341c877ed65/main/zlib) |
| linux-lts | 6.18.53-r0 | GPL-2.0-only | [954466b9](https://gitlab.alpinelinux.org/alpine/aports/-/tree/954466b9df0970b27514a32affccaac99d3c620e/main/linux-lts) |
| opensbi | 1.7-r0 | BSD-2-Clause | [eec9e283](https://gitlab.alpinelinux.org/alpine/aports/-/tree/eec9e28336c49b027111785d50fed1a04fe01b02/main/opensbi) |

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
This branch does not publish a GitHub binary release or claim that such a release
source bundle has been assembled. Do not publish a release until that bundle and
physical ARM64 acceptance tests are complete.

GPL obligations for the guest do not disappear because it is emulated. Conversely,
separate guest programs do not change Flyby's original Apache-2.0 license. There is
no linked QEMU/GLib/libfdt dependency in the implemented runtime. See the historical
Phase 1 QEMU document only for the superseded proposal.

Android runtime CI uses ReactiveCircus/android-emulator-runner at
`a421e43855164a8197daf9d8d40fe71c6996bb0d`, Apache-2.0 (Yang Chen).
Its upstream LICENSE was inspected before use. It is a CI tool, not packaged in Flyby.
