#!/bin/bash
# Build baresip for Android ARM
set -e

NDK=/usr/lib/android-sdk/ndk/26.1.10909125
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi28-clang
AR=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar

NATIVE_DIR=$(pwd)
RE_INC=$NATIVE_DIR/re/include
REM_INC=$NATIVE_DIR/rem/include
MBED_INC=$NATIVE_DIR/mbedtls/include
BARE_INC=$NATIVE_DIR/baresip/include
BARE_SRC=$NATIVE_DIR/baresip/src

CFLAGS="-DUSE_MBEDTLS -DMBEDTLS_MD_C -DARRAY_SIZE=RE_ARRAY_SIZE \
  -I$RE_INC -I$REM_INC -I$MBED_INC -I$BARE_INC -I$BARE_SRC \
  -O2 -fPIC -Wall -Wno-unused-parameter -Wno-missing-field-initializers"

OUTDIR=$NATIVE_DIR/build_baresip_final
mkdir -p $OUTDIR

echo "=== Compiling baresip core ==="
SOURCES=(
  account.c audio.c aufilt.c auplay.c ausrc.c
  baresip.c call.c cmd.c conf.c config.c contact.c
  event.c log.c main.c mem.c module.c play.c
  registration.c rtp.c sip.c ua.c aucodec.c
  breq.c dial_number.c descr.c custom_hdrs.c http.c
)

for src in "${SOURCES[@]}"; do
  if [ -f "$BARE_SRC/$src" ]; then
    echo "  Compiling $src..."
    $CC $CFLAGS -c "$BARE_SRC/$src" -o "$OUTDIR/${src%.c}.o" 2>&1 | head -3
  fi
done

echo "=== Creating static library ==="
$AR rcs $OUTDIR/libbaresip.a $OUTDIR/*.o
echo "=== Done: $(ls -lh $OUTDIR/libbaresip.a) ==="
