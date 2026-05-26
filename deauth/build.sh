#!/bin/bash
# Cross-compile deauth + CSA beacon flood for ARM64 Android
# Requires: Android NDK r27+
NDK="${NDK:-$HOME/android-ndk-r27}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN/aarch64-linux-android34-clang"

echo "=== Building deauth ==="
$CC -static -O2 -s -o deauth deauth.c && echo "OK" || echo "FAIL"

echo "=== Building beacon_flood (deauth + CSA) ==="
$CC -static -O2 -s -o beacon_flood beacon_flood.c && echo "OK" || echo "FAIL"

echo "Done. Files:"
ls -la deauth beacon_flood 2>/dev/null
