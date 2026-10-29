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
