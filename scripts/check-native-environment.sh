#!/usr/bin/env bash
# Prerequisite check only. This does not claim to build QEMU for Android.
set -euo pipefail
if [[ $# -ne 1 || ${1:-} == --help ]]; then
  echo "Usage: ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018 $0 /path/to/qemu-9.2.4"
  echo "Checks host tools, NDK and QEMU source. Does not build or download dependencies."
  [[ ${1:-} == --help ]] && exit 0
  exit 2
fi
failed=0
check_file() {
  if [[ ! -f $1 ]]; then printf 'MISSING: %s\n' "$1"; failed=1; fi
}
for tool in python3 ninja meson pkg-config cc; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'MISSING host tool: %s\n' "$tool"; failed=1
  fi
done
source_dir=$1
check_file "$source_dir/VERSION"
check_file "$source_dir/configure"
check_file "$source_dir/meson_options.txt"
if [[ -f $source_dir/VERSION && $(tr -d '\r\n' < "$source_dir/VERSION") != 9.2.4 ]]; then
  echo 'ERROR: source must be QEMU 9.2.4; re-check options before changing versions.'
  failed=1
fi
if [[ -z ${ANDROID_NDK_HOME:-} ]]; then
  echo 'MISSING: ANDROID_NDK_HOME (NDK 27.2.12479018)'; failed=1
else
  check_file "$ANDROID_NDK_HOME/source.properties"
  if [[ -f $ANDROID_NDK_HOME/source.properties ]] &&
     ! grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$ANDROID_NDK_HOME/source.properties"; then
    echo 'ERROR: expected NDK 27.2.12479018'; failed=1
  fi
  toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
  for tool in clang clang++ llvm-ar llvm-ranlib llvm-readelf; do
    if [[ ! -x $toolchain/$tool ]]; then printf 'MISSING NDK tool: %s\n' "$tool"; failed=1; fi
  done
fi
if [[ $failed -ne 0 ]]; then
  echo 'Native prerequisites incomplete. See docs/qemu-android.md.'
  exit 1
fi
echo 'Source/tool prerequisites found. Android GLib/libfdt dependency builds and QEMU port remain unverified.'
