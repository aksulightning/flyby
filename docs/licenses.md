# Licenses and redistribution

Flyby's original Android frontend source is licensed **Apache-2.0**, see the repository
`LICENSE`. Third-party components retain their own licenses. No native QEMU,
Linux or BusyBox binary is currently included. The Gradle wrapper JAR is the
standard build bootstrap, generated from the verified Gradle 8.11.1 distribution.

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
| Terminal emulator library | **none** | Diagnostic preview is Flyby code, not a terminal emulator |
| GitHub Actions checkout / setup-java | v4, CI only | MIT ([checkout](https://github.com/actions/checkout/blob/v4/LICENSE), [setup-java](https://github.com/actions/setup-java/blob/v4/LICENSE)) |

Android SDK/NDK are external development tools with their own distribution terms
and component notices. They are not checked into this repository. Review all
resolved runtime artifacts and preserve notices when making a distributable APK;
build/test tools do not automatically become APK contents.

## Planned native components (not dependencies yet)

| Component | License and obligation |
| --- | --- |
| QEMU 9.2.4 | Emulator as a whole GPL-2.0; individual files include GPL-compatible licenses, TCG includes BSD/MIT. Firmware has separate licenses. [Exact upstream LICENSE](https://github.com/qemu/qemu/blob/v9.2.4/LICENSE) |
| Linux kernel | GPL-2.0-only overall, with syscall exception and file-specific license expressions. [Kernel rules](https://www.kernel.org/doc/html/latest/process/license-rules.html) |
| BusyBox | GPL-2.0-only. [BusyBox license](https://busybox.net/license.html) |
| GLib | LGPL-2.1-or-later; preserve license and notices, including relinking/replacement obligations. [GLib COPYING](https://gitlab.gnome.org/GNOME/glib/-/blob/main/COPYING) |
| libffi if required | MIT. [License](https://github.com/libffi/libffi/blob/master/LICENSE) |
| PCRE2 if required | BSD-3-Clause core, with additional notices per source distribution. [License](https://github.com/PCRE2Project/pcre2/blob/master/LICENCE.md) |
| zlib | Zlib license. [License](https://zlib.net/zlib_license.html) |
| libfdt | Dual GPL-2.0-or-later / BSD-2-Clause, use the BSD alternative for linked library. [Source](https://git.kernel.org/pub/scm/utils/dtc/dtc.git/tree/libfdt/libfdt.h) |

Pin versions and examine the actual source archives' per-file licenses before
adding those libraries. Choose and document the guest libc's license too; no
choice is silently made here. Networking dependencies are intentionally absent.

## Shipping QEMU in an APK

Running QEMU in a separate process does **not** remove GPL obligations for the
QEMU binary in the APK. A matching source archive must include the exact QEMU
source, changes/patches, build/install scripts, configs and dependency information
needed to reproduce it. Use GPL section 3(a) distribution with corresponding
source alongside each binary release rather than relying on a link to a moving
upstream branch or assuming that source can be provided later.

Preserve QEMU's license notices and the license information of all linked code.
If GLib or another LGPL component is linked statically, include sufficient
relinkable application objects/build materials and permit relinking with a
modified library; a stripped executable and upstream URL alone are insufficient.
If distributed dynamically, retain the applicable replacement/relinking rights
and source obligations. Audit the actual linkage before the first native APK.

Guest Linux and BusyBox are separate programs inside an initramfs; they have
their own corresponding-source obligations even though Android does not link
them. Provide exact kernel/BusyBox sources, configs, modifications and initramfs
construction scripts with a binary release. Do not package unused QEMU firmware.

The frontend uses Apache-2.0, consistent with its Kotlin/AndroidX dependencies.
Do not relicense the combined frontend as GPL-2.0-only: Apache-2.0 and GPLv2-only
have compatibility restrictions ([Apache guidance](https://www.apache.org/licenses/GPL-compatibility.html)).
The intended distribution is independent programs communicating through standard
argv/serial/QMP protocols, not a JNI-linked combination. QEMU keeps its GPL
license; packaging both in an APK does not waive corresponding-source duties.
Review the actual implemented boundary before a binary release; merely putting
code in another process is not by itself a universal license exemption
([GNU FAQ](https://www.gnu.org/licenses/old-licenses/gpl-2.0-faq.html#MereAggregation)).
