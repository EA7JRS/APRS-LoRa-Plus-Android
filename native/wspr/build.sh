#!/usr/bin/env bash
# Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
# WSPR decoder for the app: wsprd (K1JT, K9AN; from Guenael/rtlsdr-wsprd, GPL-3.0) + wspr_main.c, FFTW replaced by KISS FFT
# (from ft8_lib). Packaged as lib/<abi>/libwsprdec.so.  native/wspr/build.sh <abi|host> <out-dir> [test]
set -euo pipefail
ABI="${1:?abi}"; OUT="${2:?out dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WSPR_WORK:-$HERE/../../build/wspr}"
SHA=1ca9b83dd2562ce9ef2453aacdd5bc3aab982c7d          # Guenael/rtlsdr-wsprd, 2026-03-05
FT8SHA=9fec6ca39886edbf96f4f5e71edc76da5074e871       # kgoba/ft8_lib (KISS FFT)
API=26
mkdir -p "$WORK" "$OUT"; WORK="$(cd "$WORK" && pwd)"; OUT="$(cd "$OUT" && pwd)"
fetch() { if [ ! -d "$WORK/$3/.git" ]; then git init -q "$WORK/$3"; git -C "$WORK/$3" fetch -q --depth 1 "https://github.com/$1" "$2"; git -C "$WORK/$3" checkout -q FETCH_HEAD; fi; }
fetch Guenael/rtlsdr-wsprd "$SHA" wsprd
fetch kgoba/ft8_lib "$FT8SHA" ft8_lib
W="$WORK/wsprd/wsprd"; K="$WORK/ft8_lib/fft"
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
SRC=("$W"/wsprd.c "$W"/wsprd_utils.c "$W"/wsprsim_utils.c "$W"/fano.c "$W"/tab.c "$W"/nhash.c "$K"/kiss_fft.c)
"$CC" -O2 -fPIE -pie -w -I"$HERE" -I"$W" -I"$K" "${SRC[@]}" "$HERE/wspr_main.c" -lm -o "$OUT/libwsprdec.so"
"$STRIP" "$OUT/libwsprdec.so"
if [ "${3:-}" = test ] && [ "$ABI" = host ]; then
  "$CC" -O2 -w -I"$HERE" -I"$W" -I"$K" "${SRC[@]}" "$HERE/test/wspr_gen.c" -lm -o "$OUT/wspr_gen"
fi
ls -la "$OUT/libwsprdec.so"
