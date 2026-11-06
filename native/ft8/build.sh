#!/usr/bin/env bash
# Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
# FT8 / FT4 decoder for the app: ft8_lib (Karlis Goba, MIT) built as a separate program, packaged as lib/<abi>/libft8dec.so.
# The app writes each 15 s (FT8) or 7.5 s (FT4) slot as a 12 kHz WAV and runs: libft8dec.so [-ft4] <file.wav>
#   native/ft8/build.sh <abi|host> <out-dir>      (ANDROID_NDK_HOME or ANDROID_NDK_LATEST_HOME for Android ABIs)
set -euo pipefail
ABI="${1:?abi}"; OUT="${2:?out dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${FT8_WORK:-$HERE/../../build/ft8}"
SHA=9fec6ca39886edbf96f4f5e71edc76da5074e871        # kgoba/ft8_lib, 2025-08-24
API=26
mkdir -p "$WORK" "$OUT"; WORK="$(cd "$WORK" && pwd)"; OUT="$(cd "$OUT" && pwd)"
if [ ! -d "$WORK/ft8_lib/.git" ]; then
  git init -q "$WORK/ft8_lib"
  git -C "$WORK/ft8_lib" fetch -q --depth 1 https://github.com/kgoba/ft8_lib "$SHA"
  git -C "$WORK/ft8_lib" checkout -q FETCH_HEAD
fi
S="$WORK/ft8_lib"
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
"$CC" -O3 -fPIE -pie -DHAVE_STPCPY -I"$S" -w \
  "$S"/ft8/*.c "$S"/common/wave.c "$S"/common/monitor.c "$S"/common/audio.c "$S"/fft/*.c "$S"/demo/decode_ft8.c \
  -lm -o "$OUT/libft8dec.so"
"$STRIP" "$OUT/libft8dec.so"
ls -la "$OUT/libft8dec.so"
