# On-device transcription (whisper.cpp) and summaries (llama.cpp), each with
# a small JNI bridge of our own: whisperjr.cpp and llamajr.cpp, taken from
# CallVault (whispercv.cpp, llamacv.cpp at 5cfa2c60) with the symbols renamed.
# The engines are the pinned submodules in third_party/, not forked.
# arm64-v8a only (app/build.gradle.kts).
cmake_minimum_required(VERSION 3.22.1)
project(jemrec_native)

find_library(log-lib log)

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
set(LLAMA_ROOT   ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

# Optimise even in a debug build. AGP builds debug with -O0, and for ggml that
# is about 13x slower: CallVault measured a 47 s clip at 10 min 26 s at -O0
# against about 47 s at -O3 (OnePlus 12). A debug run is where timings are
# taken, so it has to run at speed. Before add_subdirectory so everything
# below inherits it.
add_compile_options($<$<CONFIG:Debug>:-O3>)

# OpenMP off: ggml would link the NDK's shared libomp.so, which is not in the
# APK, and the first model load on a phone would die with UnsatisfiedLinkError
# (CallVault's finding).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# The ARM kernels. Without these ggml is built with no -march, every
# dotprod / i8mm kernel is compiled out, and nothing warns. With them ggml
# builds one CPU backend per Android feature level and loads, at run time, the
# best one the phone's CPU reports: the A24 has dotprod and no i8mm, a newer
# flagship has both. CallVault, OnePlus 12, 6 threads, off -> on: summary
# prefill 97.5 -> 309.7 tokens/s, transcription encode 4848 -> 4073 ms.
# BACKEND_DL is required by ALL_VARIANTS. The backends are dlopen'd from the
# app's native library directory, so jniLibs { useLegacyPackaging = true } in
# app/build.gradle.kts must stay.
# Repack (ggml's default) keeps a second, repacked copy of the weights: most
# of the speed and about +50% of the model's size in memory. If a 6 GB phone
# refuses the summary model, GGML_CPU_REPACK OFF gives the memory back for
# about 1.5x the time (CallVault, OnePlus 9 Pro).
set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)

# Both vendor ggml behind `if (NOT TARGET ggml)`: the first added builds it and
# the second reuses it, so there is one ggml in the APK. The order decides
# whose ggml both compile against - check here first when either fails.
add_subdirectory(${WHISPER_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)
add_subdirectory(${LLAMA_ROOT}   ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(whisperjr SHARED whisperjr.cpp)
target_link_libraries(whisperjr whisper ${log-lib})

add_library(llamajr SHARED llamajr.cpp)
target_link_libraries(llamajr llama ${log-lib})

# Telling the two voices of a one-mix call apart: sherpa-onnx, pinned in
# third_party/ like the others, over onnxruntime (specs/003-voice-labels, V1).
#
# onnxruntime is the one prebuilt library: the build sherpa-onnx v2.0.1 itself
# downloads, fetched here with the same URL and hash. Checked 2026-11-03 with
# llvm-readelf: it NEEDS only libc, libm, libdl and liblog - its libc++ is
# linked in - and sherpa-onnx reaches it through onnxruntime's C API alone, so
# its NDK r27 libc++ and our NDK 28 one never meet. Handing sherpa-onnx the
# unpacked library through its pre-installed variables stops it downloading
# one of its own: with BUILD_SHARED_LIBS off it would fetch the static
# onnxruntime, a 1.5 GB archive.
set(SHERPA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/sherpa-onnx)
include(FetchContent)
FetchContent_Declare(onnxruntime_android
    URL      https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip
    URL_HASH SHA256=01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d)
FetchContent_MakeAvailable(onnxruntime_android)
set(ENV{SHERPA_ONNXRUNTIME_LIB_DIR}     ${onnxruntime_android_SOURCE_DIR}/jni/arm64-v8a)
set(ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR} ${onnxruntime_android_SOURCE_DIR}/headers)

# The C API and the speaker code only. Static, so sherpa-onnx and its own
# dependencies (kaldi-native-fbank, hclust-cpp, ...) end up inside
# libvoicejr.so, and what it does not call is dropped at link time; whisper
# turned BUILD_SHARED_LIBS on for ggml, so it is shadowed for this one
# directory. Its other dependencies are fetched by its CMake, each with a hash.
set(SHERPA_ONNX_ENABLE_C_API               ON  CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION ON  CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_JNI                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TTS                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_BINARY              OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_WEBSOCKET           OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO           OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PYTHON              OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS               OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_CHECK               OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_BUILD_C_API_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${SHERPA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/sherpa-onnx)
unset(BUILD_SHARED_LIBS)

add_library(voicejr SHARED voicejr.cpp)
target_include_directories(voicejr PRIVATE ${SHERPA_ROOT})
target_link_libraries(voicejr sherpa-onnx-c-api ${log-lib})
target_link_options(voicejr PRIVATE -Wl,--gc-sections)
