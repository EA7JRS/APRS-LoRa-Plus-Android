#!/usr/bin/env bash
# Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
# Builds dsd-fme (digital voice decoder: DMR, D-STAR, YSF/C4FM, P25 phase 1 and 2, NXDN, dPMR, M17...) for the app, as a
# separate program packaged as lib/<abi>/libdsdfme.so (Android lets an app run programs from its native library folder).
# Static dependencies: mbelib (voice), libsndfile, ncurses; PulseAudio is a stub.
#   native/dsd/build.sh <abi|host> <out-dir>        abi: arm64-v8a, armeabi-v7a, x86_64
# Needs ANDROID_NDK_HOME (or ANDROID_NDK_LATEST_HOME) for Android ABIs.
set -euo pipefail
ABI="${1:?abi}"; OUT="${2:?out dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${DSD_WORK:-$HERE/../../build/dsd}"
API=26
DSD_SHA=587181f4dea29f1ce73758f7f0e6f940b5c5a028      # lwvmobile/dsd-fme
MBE_SHA=9a04ed5c78176a9965f3d43f7aa1b1f5330e771f      # szechyjs/mbelib 1.3.0
SF_SHA=396a715daeae7316f047ee220b364ab983b66df4       # libsndfile/libsndfile
NC_SHA=87c2c84cbd2332d6d94b12a1dcaf12ad1a51a938       # mirror/ncurses 6.4 20230311
mkdir -p "$WORK/src" "$OUT"
WORK="$(cd "$WORK" && pwd)"; OUT="$(cd "$OUT" && pwd)"

fetch() {  # repo sha dir
  if [ ! -d "$WORK/src/$3/.git" ]; then
    git init -q "$WORK/src/$3"
    git -C "$WORK/src/$3" fetch -q --depth 1 "https://github.com/$1" "$2"
    git -C "$WORK/src/$3" checkout -q FETCH_HEAD
  fi
}
fetch lwvmobile/dsd-fme "$DSD_SHA" dsd-fme
fetch szechyjs/mbelib "$MBE_SHA" mbelib
fetch libsndfile/libsndfile "$SF_SHA" libsndfile
fetch mirror/ncurses "$NC_SHA" ncurses

PREFIX="$WORK/$ABI/prefix"; B="$WORK/$ABI/build"; mkdir -p "$PREFIX" "$B"
CMAKE_ARGS=(-DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_POSITION_INDEPENDENT_CODE=ON)
if [ "$ABI" = host ]; then
  CC=gcc; HOSTARG=""
else
  NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"; [ -n "$NDK" ] || { echo "ANDROID_NDK_HOME not set"; exit 1; }
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  case "$ABI" in
    arm64-v8a) TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
    x86_64) TRIPLE=x86_64-linux-android ;;
    *) echo "unknown abi $ABI"; exit 1 ;;
  esac
  CC="$TC/$TRIPLE$API-clang"; HOSTARG="--host=${TRIPLE/armv7a/arm}"
  export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
  CMAKE_ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API")
fi

# ncurses (only to link: the app never starts the ncurses terminal)
if [ ! -f "$PREFIX/lib/libncursesw.a" ]; then
  mkdir -p "$B/ncurses" && cd "$B/ncurses"
  CC="$CC" CFLAGS="-O2 -fPIC" "$WORK/src/ncurses/configure" $HOSTARG --prefix="$PREFIX" --with-build-cc=gcc \
    --without-progs --without-tests --without-cxx --without-cxx-binding --without-ada --without-manpages --without-debug \
    --without-shared --with-normal --enable-widec --disable-database --without-fallbacks --disable-db-install \
    --disable-stripping --enable-pc-files=no > configure.log 2>&1 || { tail -40 configure.log; exit 1; }
  make -j"$(nproc)" libs > make.log 2>&1 || { tail -40 make.log; exit 1; }
  make install.libs install.includes > install.log 2>&1
fi
# libsndfile, without codecs that need other libraries
if [ ! -f "$PREFIX/lib/libsndfile.a" ]; then
  cmake -S "$WORK/src/libsndfile" -B "$B/sndfile" "${CMAKE_ARGS[@]}" -DBUILD_SHARED_LIBS=OFF -DBUILD_PROGRAMS=OFF -DBUILD_EXAMPLES=OFF \
    -DBUILD_TESTING=OFF -DENABLE_EXTERNAL_LIBS=OFF -DENABLE_MPEG=OFF -DENABLE_CPACK=OFF -DINSTALL_MANPAGES=OFF -DBUILD_REGTEST=OFF > /dev/null
  cmake --build "$B/sndfile" -j"$(nproc)" > /dev/null
  cmake --install "$B/sndfile" > /dev/null
fi
# mbelib (AMBE / IMBE voice)
if [ ! -f "$PREFIX/lib/libmbe.a" ]; then
  cmake -S "$WORK/src/mbelib" -B "$B/mbelib" "${CMAKE_ARGS[@]}" > /dev/null
  cmake --build "$B/mbelib" -j"$(nproc)" --target mbe-static > /dev/null
  cp "$B/mbelib/libmbe.a" "$PREFIX/lib/"; cp "$WORK/src/mbelib/mbelib.h" "$PREFIX/include/"
fi
# dsd-fme
cmake -S "$HERE" -B "$B/dsd" "${CMAKE_ARGS[@]}" -DDSD_SRC="$WORK/src/dsd-fme" -DDEPS="$PREFIX" > /dev/null
cmake --build "$B/dsd" -j"$(nproc)" > "$B/dsd.log" 2>&1 || { grep -E "error" "$B/dsd.log" | head -40; exit 1; }
cp "$B/dsd/dsd-fme" "$OUT/libdsdfme.so"
[ "$ABI" = host ] || "$STRIP" "$OUT/libdsdfme.so"
ls -la "$OUT/libdsdfme.so"
