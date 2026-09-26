#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
ndk_dir=${ANDROID_NDK_HOME:-"$ANDROID_HOME/ndk/27.2.12479018"}
cmake_bin=${CMAKE_BIN:-"$ANDROID_HOME/cmake/3.22.1/bin/cmake"}
ninja_bin=${NINJA_BIN:-"$ANDROID_HOME/cmake/3.22.1/bin/ninja"}
python3 "$project_dir/scripts/prepare-native.py"
"$cmake_bin" -S "$project_dir/native" -B "$project_dir/out/android" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ninja_bin" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release
"$cmake_bin" --build "$project_dir/out/android" -j2
