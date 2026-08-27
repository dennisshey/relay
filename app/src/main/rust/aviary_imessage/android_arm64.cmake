# CMake toolchain used to cross-compile unicorn-engine-sys (bundled QEMU) for
# Android arm64. cmake-rs's built-in Android support is incomplete for this, so we
# force the ABI + make program, pull in the real NDK toolchain, and force native
# __int128 (the cross-compile config test can't run, so QEMU misdetects it).
set(ANDROID_ABI arm64-v8a CACHE STRING "" FORCE)
set(ANDROID_PLATFORM android-21 CACHE STRING "" FORCE)
set(CMAKE_MAKE_PROGRAM "/opt/homebrew/bin/ninja" CACHE FILEPATH "" FORCE)
include("/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264/build/cmake/android.toolchain.cmake")
string(APPEND CMAKE_C_FLAGS " -DCONFIG_INT128=1")
string(APPEND CMAKE_CXX_FLAGS " -DCONFIG_INT128=1")
