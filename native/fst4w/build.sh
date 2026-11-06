#!/usr/bin/env bash
# Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
# FST4W decoder for the app: fst4w_dec.c (C port of the FST4W path of WSJT-X 3.3.0-beta5, GPL-3.0) with KISS FFT (from
# ft8_lib). Packaged as lib/<abi>/libfst4wdec.so.  native/fst4w/build.sh <abi|host> <out-dir> [test]
set -euo pipefail
ABI="${1:?abi}"; OUT="${2:?out dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${FST4W_WORK:-$HERE/../../build/fst4w}"
FT8SHA=9fec6ca39886edbf96f4f5e71edc76da5074e871       # kgoba/ft8_lib (KISS FFT)
API=26
mkdir -p "$WORK" "$OUT"; WORK="$(cd "$WORK" && pwd)"; OUT="$(cd "$OUT" && pwd)"
if [ ! -d "$WORK/ft8_lib/.git" ]; then
  git init -q "$WORK/ft8_lib"; git -C "$WORK/ft8_lib" fetch -q --depth 1 https://github.com/kgoba/ft8_lib "$FT8SHA"; git -C "$WORK/ft8_lib" checkout -q FETCH_HEAD
fi
K="$WORK/ft8_lib/fft"
if [ "$ABI" = host ]; then CC=gcc; STRIP=strip
else
  NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"; [ -n "$NDK" ] || { echo "ANDROID_NDK_HOME not set"; exit 1; }
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  case "$ABI" in
    arm64-v8a) CC="$TC/aarch64-linux-android$API-clang" ;;
    armeabi-v7a) CC="$TC/armv7a-linux-androideabi$API-clang" ;;
    x86_64) CC="$TC/x86_64-linux-android$API-clang" ;;
    *) echo "unknown abi $ABI"; exit 1 ;;
  esac
  STRIP="$TC/llvm-strip"
fi
"$CC" -O2 -fPIE -pie -Wall -Wno-unused-function -I"$HERE" -I"$K" "$HERE/fst4w_dec.c" "$K/kiss_fft.c" "$K/kiss_fftr.c" -lm -o "$OUT/libfst4wdec.so"
"$STRIP" "$OUT/libfst4wdec.so"
if [ "${3:-}" = test ] && [ "$ABI" = host ]; then
  "$CC" -O2 -Wall -Wno-unused-function -I"$HERE" "$HERE/test/fst4w_gen.c" -lm -o "$OUT/fst4w_gen"
fi
ls -la "$OUT/libfst4wdec.so"
