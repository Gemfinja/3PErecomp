cmake_minimum_required(VERSION 3.22.1)
project(third_birthday_recomp C CXX)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(RT_DIR "${REPO_ROOT}/runtime")
set(BUILD_OUT "${REPO_ROOT}/build/ULUS10567")
set(GEN_DIR "${BUILD_OUT}/gen")
set(SDL3_DIR "${REPO_ROOT}/android/third_party/SDL3")

# Build SDL3 from source (video/gamepad only; audio uses pure OpenSL ES in audio.c)
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_AUDIO OFF CACHE BOOL "" FORCE)
add_subdirectory("${REPO_ROOT}/android/third_party/libadrenotools" "${CMAKE_CURRENT_BINARY_DIR}/adrenotools_build")
add_subdirectory("${SDL3_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/SDL3_build")
target_include_directories(SDL3-shared PRIVATE "${REPO_ROOT}/android/third_party/libadrenotools/include")
target_link_libraries(SDL3-shared PRIVATE adrenotools)

# 1. libgame.so: recompiled MIPS->C game code + game_glue.c + mods_gen.c
file(GLOB RECOMP_CHUNKS "${GEN_DIR}/recomp_*.c")
add_library(game SHARED
    ${RECOMP_CHUNKS}
    "${RT_DIR}/src/rt/game_glue.c"
    "${BUILD_OUT}/mods_gen.c"
)
target_include_directories(game PRIVATE
    "${RT_DIR}/src/rt"
    "${GEN_DIR}"
    "${BUILD_OUT}"
)
target_compile_definitions(game PRIVATE SR_SDL3VK SR_GAME_DLL)
target_compile_options(game PRIVATE -O3 -fno-math-errno -mtune=cortex-x1 -fomit-frame-pointer -ffp-contract=fast -w)

# 2. libmain.so: native runtime + SDL3 + Vulkan + at3_standalone + xbrz
set(AT3_DIR "${RT_DIR}/third_party/at3_standalone")
set(AT3_SRCS
    "${AT3_DIR}/atrac.cpp"
    "${AT3_DIR}/atrac3.cpp"
    "${AT3_DIR}/atrac3plus.cpp"
    "${AT3_DIR}/atrac3plusdec.cpp"
    "${AT3_DIR}/atrac3plusdsp.cpp"
    "${AT3_DIR}/get_bits.cpp"
    "${AT3_DIR}/compat.cpp"
    "${AT3_DIR}/fft.cpp"
    "${AT3_DIR}/mem.cpp"
    "${RT_DIR}/src/rt/at3_bridge.cpp"
)
set_source_files_properties(${AT3_SRCS} PROPERTIES COMPILE_FLAGS "-O3 -fno-math-errno -mtune=cortex-x1 -fomit-frame-pointer -ffp-contract=fast -w")

set(XBRZ_SRCS
    "${RT_DIR}/third_party/xbrz/xbrz.cpp"
    "${RT_DIR}/third_party/xbrz/xbrz_c.cpp"
)
set_source_files_properties(${XBRZ_SRCS} PROPERTIES COMPILE_FLAGS "-O3 -fno-math-errno -mtune=cortex-x1 -fomit-frame-pointer -std=c++17 -w")

set(RT_SRCS
    "${RT_DIR}/src/rt/recomp.c"
    "${RT_DIR}/src/rt/vfpu_interp.c"
    "${RT_DIR}/src/rt/hle.c"
    "${RT_DIR}/src/rt/hle_ext.c"
    "${RT_DIR}/src/rt/sched.c"
    "${RT_DIR}/src/rt/iso.c"
    "${RT_DIR}/src/rt/mpeg.c"
    "${RT_DIR}/src/rt/pgf.c"
    "${RT_DIR}/src/rt/gui.c"
    "${RT_DIR}/src/rt/audio.c"
    "${RT_DIR}/src/rt/h264_mf.c"
    "${RT_DIR}/src/rt/savedata.c"
    "${RT_DIR}/src/rt/osk_win.c"
    "${RT_DIR}/src/rt/driver.c"
    "${RT_DIR}/src/rt/ctrl_server.c"
    "${RT_DIR}/src/rt/atrac_hle.c"
    "${RT_DIR}/src/rt/sas_hle.c"
    "${RT_DIR}/src/rt/twinstick.c"
    "${RT_DIR}/src/rt/mods.c"
    "${RT_DIR}/src/rt/cheats.c"
    "${RT_DIR}/src/rt/update.c"
    "${RT_DIR}/src/rt/ge.c"
    "${RT_DIR}/src/rt/gpu_sdl3vk/sdl3vk.c"
    "${RT_DIR}/src/rt/gpu_sdl3vk/ge_gpu.c"
    "${RT_DIR}/src/rt/gpu_sdl3vk/texpack_ppsspp.c"
)

add_library(main SHARED
    ${RT_SRCS}
    ${AT3_SRCS}
    ${XBRZ_SRCS}
)
target_include_directories(main PRIVATE
    "${RT_DIR}/src/rt"
    "${RT_DIR}/third_party/xxhash"
    "${RT_DIR}/third_party/at3_standalone"
    "${RT_DIR}/third_party/at3_shim"
    "${BUILD_OUT}"
)
target_compile_definitions(main PRIVATE SR_SDL3VK SR_VERSION="0.9.16")
target_compile_options(main PRIVATE -O3 -fno-math-errno -mtune=cortex-x1 -fomit-frame-pointer -ffp-contract=fast -flto=thin -w)
target_link_options(main PRIVATE -flto=thin)
target_link_libraries(main PRIVATE
    SDL3::SDL3
    vulkan
    mediandk
    aaudio
    dl
    log
    android
    m
)
