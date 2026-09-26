> Historical Phase 1 proposal. Superseded by the embedded RV64 runtime.
> QEMU is not built, linked or used by Phase 2. See [docs/riscv-runtime.md](../../docs/riscv-runtime.md).

# Native QEMU workspace

The Phase 1 app contains no native executable and no JNI library. NDK r27c and
`arm64-v8a` packaging are pinned in `app/build.gradle.kts`; CMake is deliberately
not added because QEMU uses configure/Meson/Ninja.

See [the Android build investigation](../../docs/qemu-android.md) for the selected
QEMU 9.2.4 source baseline, verified configure flags, dependencies, unresolved
Android porting work and the next build procedure.

`work/` and `prefix/` are ignored scratch locations for source/builds and target
dependencies. Future verified patches belong under `patches/` with upstream
source commit, rationale and license. Do not put downloaded binaries in Git.
