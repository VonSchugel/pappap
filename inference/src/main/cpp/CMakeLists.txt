cmake_minimum_required(VERSION 3.22)
project(pappap LANGUAGES C CXX)

# Keep the manual workflow editor installable on legacy 32-bit devices.
if(NOT ANDROID_ABI MATCHES "^(arm64-v8a|x86_64)$")
    add_library(pappap SHARED legacy.cpp)
    return()
endif()

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
foreach(ext AVX AVX2 BMI2 FMA F16C)
    set(GGML_${ext} OFF CACHE BOOL "" FORCE)
endforeach()
# Android Studio's debug build otherwise spends most of its time in unoptimized kernels.
add_compile_options(-O2 -g0)
FetchContent_Declare(llama_src URL
    https://codeload.github.com/ggml-org/llama.cpp/tar.gz/171527522610996f5f0acf12334e185156ca8f44)
FetchContent_MakeAvailable(llama_src)
add_library(pappap SHARED inference.cpp)
target_compile_features(pappap PRIVATE cxx_std_17)
target_link_libraries(pappap PRIVATE llama android log)
