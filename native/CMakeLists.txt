cmake_minimum_required(VERSION 3.22)
project(flyby LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(RVVM "${CMAKE_CURRENT_SOURCE_DIR}/vendor/rvvm")
if(NOT EXISTS "${RVVM}/include/rvvm/rvvm.h")
  message(FATAL_ERROR "Run python3 scripts/prepare-native.py first")
endif()
# Upstream CMake uses CMAKE_SOURCE_DIR and overwrites RVVM_SRC_DIR. Keep a
# small explicit board here; no CLI (GPL), GUI, JIT, host TAP, or host device passthrough.
file(GLOB RVVM_CORE "${RVVM}/src/core/*.c")
file(GLOB RVVM_UTIL "${RVVM}/src/util/*.c")
file(GLOB RVVM_CPU "${RVVM}/src/cpu/*.c")
list(FILTER RVVM_CPU EXCLUDE REGEX "/riscv32_interpreter.c$")
add_library(rvvm STATIC ${RVVM_CORE} ${RVVM_UTIL} ${RVVM_CPU}
  ${RVVM}/src/devices/riscv-aclint.c ${RVVM}/src/devices/riscv-plic.c
  ${RVVM}/src/devices/ns16550a.c ${RVVM}/src/devices/syscon.c
  ${RVVM}/src/devices/nvme.c ${RVVM}/src/devices/rtc-goldfish.c
  ${RVVM}/src/devices/rtl8169.c ${RVVM}/src/devices/tap_user.c)
target_include_directories(rvvm PUBLIC ${RVVM}/include ${RVVM}/src ${RVVM}/src/util)
target_compile_definitions(rvvm PRIVATE USE_RV64=1 USE_FPU=1 USE_FDT=1 USE_NET=1 RVVM_VERSION="flyby-ce8ca7c")
target_compile_options(rvvm PRIVATE -O2 -frounding-math -fno-math-errno)
target_link_libraries(rvvm PUBLIC m dl)
if(NOT ANDROID)
  target_link_libraries(rvvm PUBLIC pthread)
endif()
# Keep the real socket backend in the owner process and use an explicit
# Ethernet proxy inside the isolated service.
set_source_files_properties("${RVVM}/src/devices/tap_user.c" PROPERTIES
  COMPILE_DEFINITIONS "tap_open=user_tap_open;tap_attach=user_tap_attach;tap_send=user_tap_send;tap_get_mac=user_tap_get_mac;tap_set_mac=user_tap_set_mac;tap_portfwd=user_tap_portfwd;tap_ifaddr=user_tap_ifaddr;tap_close=user_tap_close")
# Replace the call expression after headers are processed. A macro on open
# is insufficient: Bionic's large-file aliases may redirect it to open64.
file(READ "${RVVM}/src/util/blk_io.c" FLYBY_BLK_IO)
string(REPLACE "int fd = open(filepath, open_flags, 0600);"
  "extern int flyby_open(const char*, int, ...);\n    int fd = flyby_open(filepath, open_flags, 0600);"
  FLYBY_BLK_IO "${FLYBY_BLK_IO}")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/flyby-generated")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/flyby-generated/blk_io.c" "${FLYBY_BLK_IO}")
set_source_files_properties("${RVVM}/src/util/blk_io.c" PROPERTIES HEADER_FILE_ONLY TRUE)
target_sources(rvvm PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/flyby-generated/blk_io.c")
target_sources(rvvm PRIVATE runtime/fd-open.c runtime/network.cpp)
include(gpu-deps.cmake)
add_library(flyby_runtime STATIC runtime/vm.cpp runtime/display.cpp runtime/gpu.cpp runtime/vulkan-probe.cpp)
target_link_libraries(flyby_runtime PUBLIC rvvm flyby_virgl)
target_include_directories(flyby_runtime PUBLIC runtime)
if(NOT ANDROID)
  add_executable(flyby-host runtime/host.cpp)
  target_link_libraries(flyby-host PRIVATE flyby_runtime)
  add_executable(flyby-wayland-tests runtime/wayland-tests.cpp)
  target_link_libraries(flyby-wayland-tests PRIVATE flyby_runtime)
endif()
file(GLOB VTERM_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libvterm/src/*.c")
add_library(vterm STATIC ${VTERM_SOURCES})
target_include_directories(vterm PUBLIC vendor/libvterm/include)
add_library(flyby_terminal STATIC runtime/terminal.cpp)
target_link_libraries(flyby_terminal PUBLIC vterm)
target_include_directories(flyby_terminal PUBLIC runtime)
# ART owns signal handling; do not install RVVM crash handlers in the app.
target_compile_definitions(rvvm PRIVATE USE_NO_STACKTRACE=1)
if(ANDROID)
  # Route upstream diagnostic lines to logcat without changing upstream source.
  set_source_files_properties(${RVVM}/src/util/utils.c PROPERTIES COMPILE_DEFINITIONS "fputs=flyby_rvvm_fputs")
  add_library(flyby SHARED runtime/jni.cpp runtime/rvvm_log.c)
  target_link_libraries(flyby PRIVATE flyby_runtime flyby_terminal log)
  target_link_options(flyby PRIVATE "-Wl,-z,max-page-size=16384")
endif()
if(NOT ANDROID)
  enable_testing()
  add_executable(flyby-native-tests runtime/tests.cpp)
  target_link_libraries(flyby-native-tests PRIVATE flyby_runtime flyby_terminal)
  add_test(NAME native-runtime COMMAND flyby-native-tests "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/vm")
  set_tests_properties(native-runtime PROPERTIES TIMEOUT 240)
endif()
