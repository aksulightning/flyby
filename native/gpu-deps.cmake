# Embedded GLES renderer. Keep the upstream source lists/configuration scoped
# here: no DRM device access, display server, Vulkan, subprocess or video backend.
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(EPOXY "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libepoxy")
set(VIRGL "${CMAKE_CURRENT_SOURCE_DIR}/vendor/virglrenderer")
set(GPU_GEN "${CMAKE_CURRENT_BINARY_DIR}/gpu-generated")
file(MAKE_DIRECTORY "${GPU_GEN}/epoxy" "${GPU_GEN}/virgl")
set(VIRGL_MAJOR_VERSION 1)
set(VIRGL_MINOR_VERSION 1)
set(VIRGL_MICRO_VERSION 1)
configure_file("${VIRGL}/src/virgl-version.h.meson" "${GPU_GEN}/virgl/virgl-version.h" @ONLY)
file(WRITE "${GPU_GEN}/epoxy/config.h" "#define ENABLE_EGL 1\n#define ENABLE_GLX 0\n#define ENABLE_X11 0\n#define HAVE_KHRPLATFORM_H 1\n#define EPOXY_PUBLIC __attribute__((visibility(\"default\"))) extern\n")
foreach(api gl egl)
  execute_process(COMMAND "${Python3_EXECUTABLE}" "${EPOXY}/src/gen_dispatch.py"
    --header --no-source "--outputdir=${GPU_GEN}/epoxy" "${EPOXY}/registry/${api}.xml"
    COMMAND_ERROR_IS_FATAL ANY)
  execute_process(COMMAND "${Python3_EXECUTABLE}" "${EPOXY}/src/gen_dispatch.py"
    --source --no-header "--outputdir=${GPU_GEN}/epoxy" "${EPOXY}/registry/${api}.xml"
    COMMAND_ERROR_IS_FATAL ANY)
endforeach()
add_library(flyby_epoxy STATIC "${EPOXY}/src/dispatch_common.c" "${EPOXY}/src/dispatch_egl.c"
  "${GPU_GEN}/epoxy/gl_generated_dispatch.c" "${GPU_GEN}/epoxy/egl_generated_dispatch.c")
target_include_directories(flyby_epoxy PUBLIC "${EPOXY}/include" "${GPU_GEN}"
  PRIVATE "${GPU_GEN}/epoxy" "${EPOXY}/src")
target_compile_definitions(flyby_epoxy PRIVATE _GNU_SOURCE=1 EGL_NO_X11=1 MESA_EGL_NO_X11_HEADERS=1)
target_compile_options(flyby_epoxy PRIVATE -fvisibility=hidden)
target_link_libraries(flyby_epoxy PUBLIC dl)

file(WRITE "${GPU_GEN}/virgl/config.h" "#define VERSION \"1.1.1\"\n#define _GNU_SOURCE 1\n#define VIRGL_RENDERER_UNSTABLE_APIS 1\n#define UTIL_ARCH_LITTLE_ENDIAN 1\n#define UTIL_ARCH_BIG_ENDIAN 0\n#define HAVE_SYS_UIO_H 1\n#define HAVE_DLFCN_H 1\n#define HAVE_PTHREAD 1\n#define HAVE_EVENTFD_H 1\n#define HAVE_STRTOK_R 1\n")
if(NOT ANDROID)
  file(APPEND "${GPU_GEN}/virgl/config.h" "#define HAVE_TIMESPEC_GET 1\n")
endif()
foreach(b BSWAP32 BSWAP64 CLZ CLZLL EXPECT FFS FFSLL POPCOUNT POPCOUNTLL TYPES_COMPATIBLE_P UNREACHABLE)
  file(APPEND "${GPU_GEN}/virgl/config.h" "#define HAVE___BUILTIN_${b} 1\n")
endforeach()
foreach(a CONST FLATTEN FORMAT MALLOC NORETURN PACKED PURE RETURNS_NONNULL UNUSED WARN_UNUSED_RESULT WEAK ALIGNED)
  file(APPEND "${GPU_GEN}/virgl/config.h" "#define HAVE_FUNC_ATTRIBUTE_${a} 1\n")
endforeach()
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
  file(APPEND "${GPU_GEN}/virgl/config.h" "#define PIPE_ARCH_AARCH64 1\n")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
  file(APPEND "${GPU_GEN}/virgl/config.h" "#define PIPE_ARCH_X86_64 1\n")
else()
  message(FATAL_ERROR "GPU renderer supports ARM64 and x86_64 hosts")
endif()
execute_process(COMMAND "${Python3_EXECUTABLE}" -B "${VIRGL}/src/gallium/auxiliary/util/u_format_table.py"
  "${VIRGL}/src/gallium/auxiliary/util/u_format.yaml"
  OUTPUT_FILE "${GPU_GEN}/virgl/u_format_table.c" COMMAND_ERROR_IS_FATAL ANY)
# The pinned upstream Meson files are the authoritative lists. Extract only
# unconditional .c files, not optional DRM/Venus/video/platform backends.
set(VIRGL_SOURCES)
foreach(part mesa gallium)
  file(READ "${VIRGL}/src/${part}/meson.build" body)
  string(REGEX MATCHALL "'[a-zA-Z0-9_/]+\\.c'" entries "${body}")
  foreach(entry IN LISTS entries)
    string(REPLACE "'" "" entry "${entry}")
    if(EXISTS "${VIRGL}/src/${part}/${entry}")
      list(APPEND VIRGL_SOURCES "${VIRGL}/src/${part}/${entry}")
    endif()
  endforeach()
endforeach()
foreach(src iov virgl_context virgl_fence virgl_resource virgl_util virglrenderer
    vrend_blitter vrend_debug vrend_decode vrend_formats vrend_object vrend_renderer
    vrend_shader vrend_tweaks vrend_winsys)
  list(APPEND VIRGL_SOURCES "${VIRGL}/src/${src}.c")
endforeach()
add_library(flyby_virgl STATIC ${VIRGL_SOURCES} "${GPU_GEN}/virgl/u_format_table.c")
target_include_directories(flyby_virgl PUBLIC "${VIRGL}/src" "${GPU_GEN}/virgl"
  PRIVATE "${GPU_GEN}/virgl" "${VIRGL}/src/venus" "${VIRGL}/src/drm" "${VIRGL}/src/drm/drm-uapi"
  "${VIRGL}/src/gallium/include" "${VIRGL}/src/gallium/auxiliary" "${VIRGL}/src/gallium/auxiliary/util"
  "${VIRGL}/src/mesa" "${VIRGL}/src/mesa/compat" "${VIRGL}/src/mesa/pipe" "${VIRGL}/src/mesa/util")
target_compile_options(flyby_virgl PRIVATE -imacros "${GPU_GEN}/virgl/config.h" -fno-strict-aliasing -fvisibility=hidden)
target_link_libraries(flyby_virgl PUBLIC flyby_epoxy m)
if(ANDROID)
  target_include_directories(flyby_virgl PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/android")
  target_link_libraries(flyby_virgl PUBLIC log)
endif()
