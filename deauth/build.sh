#!/bin/bash
# Cross-compile deauth tool for ARM64 Android
# Requires: Android NDK r27+

NDK="${NDK:-$HOME/android-ndk-r27}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
$TOOLCHAIN/aarch64-linux-android34-clang -static -O2 -s -o deauth deauth.c
echo "Built: deauth (ARM64 static binary)"
