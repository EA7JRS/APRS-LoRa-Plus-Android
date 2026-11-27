#!/usr/bin/env bash
# Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
# SSDV decoder for the app: fsphil's ssdv (GPL-3, the classic 256-byte / RS(255,223) UKHAS format that the tracker and
# RadioLib use), built as a separate program packaged as lib/<abi>/libssdvdec.so. The app writes the received SSDV packets
# to a file and runs: libssdvdec.so <packets.bin> <out.jpg>
#   native/ssdv/build.sh <abi|host> <out-dir>      (ANDROID_NDK_HOME or ANDROID_NDK_LATEST_HOME for Android ABIs)
set -euo pipefail
ABI="${1:?abi}"; OUT="${2:?out dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${SSDV_WORK:-$HERE/../../build/ssdv}"
# bristol-seds/ssdv at a commit BEFORE the cm256/CBEC rework: pure upstream fsphil ssdv (256-byte packets, rs8 only).
SHA=b7128b16bebf38319ab6e9174b8dad5cbdbd19fc        # 2016-06-16, "Add a quality option to SSDV"
API=26
mkdir -p "$WORK" "$OUT"; WORK="$(cd "$WORK" && pwd)"; OUT="$(cd "$OUT" && pwd)"
if [ ! -d "$WORK/ssdv/.git" ]; then
  git init -q "$WORK/ssdv"
  git -C "$WORK/ssdv" fetch -q --depth 1 https://github.com/bristol-seds/ssdv "$SHA"
  git -C "$WORK/ssdv" checkout -q FETCH_HEAD
fi
S="$WORK/ssdv"
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
"$CC" -O2 -fPIE -pie -w -I"$S" \
  "$S/ssdv.c" "$S/rs8.c" "$HERE/ssdv_dec.c" \
  -o "$OUT/libssdvdec.so"
"$STRIP" "$OUT/libssdvdec.so"
ls -la "$OUT/libssdvdec.so"
