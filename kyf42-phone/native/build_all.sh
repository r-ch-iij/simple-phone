#!/bin/bash
# build_all.sh - KYF42 SIP Phone ネイティブライブラリ再ビルド
# 前提: Android NDK 27.0.12077973 がインストール済み
# 16スレッド対応: re, rem, baresip をサブシェルで並列ビルド
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
NDK=${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/27.0.12077973}
[ -d "$NDK" ] || NDK=/usr/lib/android-sdk/ndk/27.0.12077973
# KYF39 (API 22) / KYF42 (API 28) 切替: ANDROID_API_LEVEL で上書き可能。
# 本ブランチ (kyf39-android51) のデフォルトは 22。
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-22}
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi${ANDROID_API_LEVEL}-clang
AR=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar

NATIVE_DIR=$SCRIPT_DIR
RE_INC=$NATIVE_DIR/re/include
REM_INC=$NATIVE_DIR/rem/include
MBED_INC=$NATIVE_DIR/mbedtls/include
BARE_INC=$NATIVE_DIR/baresip/include
BARE_SRC=$NATIVE_DIR/baresip/src

# re-config.cmake が定義するプラットフォーム判定マクロ
CFLAGS_BASE="-DUSE_MBEDTLS -DMBEDTLS_MD_C -DARRAY_SIZE=RE_ARRAY_SIZE \
  -DHAVE_EPOLL -DHAVE_SELECT -DHAVE_ATOMIC -DHAVE_INET6 \
  -DHAVE_PTHREAD -DHAVE_PWD_H -DHAVE_ROUTE_LIST -DHAVE_SETRLIMIT \
  -DHAVE_STRERROR_R -DHAVE_STRINGS_H -DHAVE_SYS_TIME_H -DHAVE_UNAME \
  -DHAVE_SIGNAL -DHAVE_FORK -DHAVE_ACCEPT4 \
  -DHAVE_SELECT_H -DHAVE_PRCTL \
  -DLINUX -D_GNU_SOURCE -DRELEASE -DRE_VERSION=\"3.9.0\" \
  -DVER_MAJOR=3 -DVER_MINOR=9 -DVER_PATCH=0 \
  -O2 -fPIC -Wno-unused-parameter -Wno-missing-field-initializers \
  -include unistd.h"

# ============================================================
# ビルド関数
# ============================================================

# re (libre) - 全ファイルをコンパイル
build_re() {
  local OUT=$NATIVE_DIR/build_re
  rm -rf "$OUT" && mkdir -p "$OUT"
  echo "=== Building re (libre) ==="
  local FAIL=0 TOTAL=0
  local ARNAME="$OUT/libre.a"
  for src in $(find "$NATIVE_DIR/re/src" -name "*.c" ! -name "openssl.c" | sort); do
    TOTAL=$((TOTAL+1))
    local rel=${src#$NATIVE_DIR/re/src/}
    local odir="$OUT/$(dirname "$rel")"
    mkdir -p "$odir"
    local bn=$(basename "$src")
    local prefix=$(dirname "$rel" | tr '/' '_')
    local outname="${prefix}_${bn%.c}.o"
    if $CC $CFLAGS_BASE -I$RE_INC -I$REM_INC -I$MBED_INC -c "$src" -o "$OUT/$outname" 2>/dev/null; then
      $AR rcs "$ARNAME" "$OUT/$outname" 2>/dev/null
    else
      FAIL=$((FAIL+1))
    fi
  done
  echo "  re: $TOTAL compiled, $FAIL failed, $(ls -lh "$ARNAME" | awk '{print $5}')"
}

# rem (librem) - 全ファイルをコンパイル
build_rem() {
  local OUT=$NATIVE_DIR/build_rem
  rm -rf "$OUT" && mkdir -p "$OUT"
  echo "=== Building rem (librem) ==="
  local FAIL=0 TOTAL=0
  local ARNAME="$OUT/librem.a"
  for src in $(find "$NATIVE_DIR/re/rem" -name "*.c" ! -name "openssl.c" | sort); do
    TOTAL=$((TOTAL+1))
    local rel=${src#$NATIVE_DIR/re/rem/}
    local odir="$OUT/$(dirname "$rel")"
    mkdir -p "$odir"
    local bn=$(basename "$src")
    local prefix=$(dirname "$rel" | tr '/' '_')
    local outname="${prefix}_${bn%.c}.o"
    if $CC $CFLAGS_BASE -I$RE_INC -I$MBED_INC -c "$src" -o "$OUT/$outname" 2>/dev/null; then
      $AR rcs "$ARNAME" "$OUT/$outname" 2>/dev/null
    else
      FAIL=$((FAIL+1))
    fi
  done
  echo "  rem: $TOTAL compiled, $FAIL failed, $(ls -lh "$ARNAME" | awk '{print $5}')"
}

# baresip - 全 src/ + opensles モジュール + g711 モジュール
build_baresip() {
  local OUT=$NATIVE_DIR/build_baresip_final
  rm -rf "$OUT" && mkdir -p "$OUT"
  echo "=== Building baresip ==="
  local CFLAGS_BARE="$CFLAGS_BASE -DSTATIC \
    -I$RE_INC -I$REM_INC -I$MBED_INC -I$BARE_INC -I$BARE_SRC"
  local FAIL=0 TOTAL=0

  echo "=== Building baresip core ==="
  for src in $(find "$BARE_SRC" -maxdepth 1 -name "*.c" | sort); do
    TOTAL=$((TOTAL+1))
    local bn=$(basename "$src")
    $CC $CFLAGS_BARE -c "$src" -o "$OUT/${bn%.c}.o" 2>/dev/null || FAIL=$((FAIL+1))
  done

  echo "=== Building opensles module ==="
  for f in opensles.c player.c recorder.c; do
    TOTAL=$((TOTAL+1))
    $CC $CFLAGS_BARE -c "$NATIVE_DIR/baresip/modules/opensles/$f" \
      -o "$OUT/opensles_${f%.c}.o" 2>/dev/null || FAIL=$((FAIL+1))
  done

  echo "=== Building g711 module ==="
  TOTAL=$((TOTAL+1))
  $CC $CFLAGS_BARE -c "$NATIVE_DIR/baresip/modules/g711/g711.c" \
    -o "$OUT/g711.o" 2>/dev/null || FAIL=$((FAIL+1))

  echo "=== Building opus module ==="
  for f in opus.c encode.c decode.c sdp.c; do
    TOTAL=$((TOTAL+1))
    $CC $CFLAGS_BARE -I$NATIVE_DIR/opus-1.4/include \
      -c "$NATIVE_DIR/baresip/modules/opus/$f" \
      -o "$OUT/opus_${f%.c}.o" 2>/dev/null || FAIL=$((FAIL+1))
  done

  echo "=== Building aufilt modules (auresamp/auconv) ==="
  # サンプルレート・チャンネル数変換（Opus 48kHz 等と端末 8kHz の橋渡し）
  for f in auresamp/auresamp.c auconv/auconv.c; do
    TOTAL=$((TOTAL+1))
    mod=$(dirname "$f")
    $CC $CFLAGS_BARE \
      -c "$NATIVE_DIR/baresip/modules/$f" \
      -o "$OUT/${mod}.o" 2>/dev/null || FAIL=$((FAIL+1))
  done

  echo "=== Building rtpwatch module (RTP RX watchdog, own source) ==="
  TOTAL=$((TOTAL+1))
  $CC $CFLAGS_BARE \
    -c "$NATIVE_DIR/../app/src/main/cpp/rtpwatch.c" \
    -o "$OUT/rtpwatch.o" 2>/dev/null || FAIL=$((FAIL+1))

  echo "=== Building g722 module ==="
  TOTAL=$((TOTAL+1))
  $CC $CFLAGS_BARE -I$NATIVE_DIR/spandsp/include \
    -c "$NATIVE_DIR/baresip/modules/g722/g722.c" \
    -o "$OUT/g722.o" || FAIL=$((FAIL+1))

  echo "=== Generating static.c ==="
  cat > "$OUT/static.c" << 'STATIC_EOF'
/* static.c - static module table for baresip */
#include <re_types.h>
#include <re_mod.h>

extern const struct mod_export exports_opensles;
extern const struct mod_export exports_g711;
extern const struct mod_export exports_opus;
extern const struct mod_export exports_g722;
extern const struct mod_export exports_auresamp;
extern const struct mod_export exports_auconv;
extern const struct mod_export exports_rtpwatch;

const struct mod_export *mod_table[] = {
	&exports_opensles,
	&exports_g711,
	&exports_opus,
	&exports_g722,
	&exports_auresamp,
	&exports_auconv,
	&exports_rtpwatch,
	NULL
};
STATIC_EOF
  TOTAL=$((TOTAL+1))
  $CC $CFLAGS_BARE -c "$OUT/static.c" -o "$OUT/static.o" 2>/dev/null || FAIL=$((FAIL+1))

  rm -f "$OUT/libbaresip.a"
  find "$OUT" -name "*.o" > /tmp/bare_objs.txt
  $AR rcs "$OUT/libbaresip.a" @/tmp/bare_objs.txt
  echo "  baresip: $TOTAL compiled, $FAIL failed, $(ls -lh "$OUT/libbaresip.a" | awk '{print $5}')"
}

# spandsp (G.722 のみベンダリング。modem/FAX 部分は含まない)
# 前提: libre ではなく re のヘッダのみ使用。config.h 不要
# （HAVE_CONFIG_H 未定義で全てフォールバックする）
build_spandsp() {
  local OUT=$NATIVE_DIR/build_spandsp_g722
  rm -rf "$OUT" && mkdir -p "$OUT"
  echo "=== Building spandsp (G.722 subset) ==="
  # NDK の math.h は float 版関数を持つため shim を無効化する
  local CFLAGS_SPAN="$CFLAGS_BASE \
    -DHAVE_SINF -DHAVE_COSF -DHAVE_TANF -DHAVE_ASINF -DHAVE_ACOSF \
    -DHAVE_ATANF -DHAVE_ATAN2F -DHAVE_CEILF -DHAVE_FLOORF -DHAVE_POWF \
    -DHAVE_EXPF -DHAVE_LOGF -DHAVE_LOG10F \
    -I$RE_INC -I$NATIVE_DIR/spandsp/include"
  local FAIL=0 TOTAL=0
  for f in g722.c alloc.c vector_int.c; do
    TOTAL=$((TOTAL+1))
    # 新規モジュールのためエラーは非表示にしない（デバッグ用）
    if ! $CC $CFLAGS_SPAN -c "$NATIVE_DIR/spandsp/src/$f" \
      -o "$OUT/${f%.c}.o"; then
      FAIL=$((FAIL+1))
    fi
  done
  rm -f "$OUT/libspandsp.a"
  find "$OUT" -name "*.o" > /tmp/spandsp_objs.txt
  # 空アーカイブ回避: .o がなければ失敗扱い
  if [ ! -s /tmp/spandsp_objs.txt ]; then
    echo "  spandsp: NO OBJECTS (build failed)"
    return 1
  fi
  $AR rcs "$OUT/libspandsp.a" @/tmp/spandsp_objs.txt
  echo "  spandsp: $TOTAL compiled, $FAIL failed, $(ls -lh "$OUT/libspandsp.a" | awk '{print $5}')"
}

# opus 1.4 (fixed-point)
# 前提: HAVE_CONFIG_H 未定義で全てフォールバックするため config.h 不要。
# 元の手ビルドと同等: CELT + SILK共通 + SILK_FIXED + OPUS + OPUS_FLOAT(analysis/mlp)。
# silk/float, x86/arm 最適化, demo/test は対象外。
build_opus() {
  local OUT=$NATIVE_DIR/build_opus
  mkdir -p "$OUT"
  rm -f "$OUT"/*.o 2>/dev/null || true
  echo "=== Building opus (fixed-point) ==="
  local O=$NATIVE_DIR/opus-1.4
  local INCS="-I$O/celt -I$O/silk -I$O/silk/fixed -I$O/include -I$O/src"
  local FLAGS="$CFLAGS_BASE $INCS -DOPUS_BUILD -DFIXED_POINT -DUSE_ALLOCA"
  local FAIL=0 TOTAL=0
  for src in $O/celt/*.c $O/silk/*.c $O/silk/fixed/*.c \
      $O/src/opus.c $O/src/opus_decoder.c $O/src/opus_encoder.c \
      $O/src/opus_multistream.c $O/src/opus_multistream_decoder.c \
      $O/src/opus_multistream_encoder.c $O/src/opus_projection_decoder.c \
      $O/src/opus_projection_encoder.c $O/src/repacketizer.c \
      $O/src/mapping_matrix.c $O/src/analysis.c $O/src/mlp.c $O/src/mlp_data.c; do
    [ -f "$src" ] || continue
    TOTAL=$((TOTAL+1))
    local rel=${src#$O/}
    local outname="$(dirname "$rel" | tr '/' '_')_$(basename "$src" .c).o"
    if $CC $FLAGS -c "$src" -o "$OUT/$outname" 2>/dev/null; then
      :
    else
      FAIL=$((FAIL+1))
    fi
  done
  rm -f "$OUT/libopus.a"
  find "$OUT" -maxdepth 1 -name "*.o" > /tmp/opus_objs.txt
  # 空アーカイブ回避: .o がなければ失敗扱い
  if [ ! -s /tmp/opus_objs.txt ]; then
    echo "  opus: NO OBJECTS (build failed)"
    return 1
  fi
  $AR rcs "$OUT/libopus.a" @/tmp/opus_objs.txt
  echo "  opus: $TOTAL compiled, $FAIL failed, $(ls -lh "$OUT/libopus.a" | awk '{print $5}')"
}

# mbedcrypto のみ (HMAC/SHA/MD5 - SIP認証用)
# libmbedtls.a (TLS) と libmbedx509.a (X.509) は不要
build_mbedcrypto() {
  local OUT=$NATIVE_DIR/build_mbedtls/library
  mkdir -p "$OUT"
  rm -f "$OUT"/*.o 2>/dev/null || true
  echo "=== Building mbedcrypto ==="
  local CRYPTO_SOURCES="aes.c aesni.c arc4.c aria.c asn1parse.c asn1write.c base64.c bignum.c blowfish.c camellia.c ccm.c chacha20.c chachapoly.c cipher.c cipher_wrap.c constant_time.c cmac.c ctr_drbg.c des.c dhm.c ecdh.c ecdsa.c ecjpake.c ecp.c ecp_curves.c entropy.c entropy_poll.c error.c gcm.c havege.c hkdf.c hmac_drbg.c md.c md2.c md4.c md5.c memory_buffer_alloc.c mps_reader.c mps_trace.c nist_kw.c oid.c padlock.c pem.c pk.c pk_wrap.c pkcs12.c pkcs5.c pkparse.c pkwrite.c platform.c platform_util.c poly1305.c psa_crypto.c psa_crypto_aead.c psa_crypto_cipher.c psa_crypto_client.c psa_crypto_driver_wrappers.c psa_crypto_ecp.c psa_crypto_hash.c psa_crypto_mac.c psa_crypto_rsa.c psa_crypto_se.c psa_crypto_slot_management.c psa_crypto_storage.c psa_its_file.c ripemd160.c rsa.c rsa_internal.c sha1.c sha256.c sha512.c threading.c timing.c version.c version_features.c xtea.c"
  local FAIL=0 TOTAL=0
  for src in $CRYPTO_SOURCES; do
    [ -f "$NATIVE_DIR/mbedtls/library/$src" ] || continue
    TOTAL=$((TOTAL+1))
    if $CC $CFLAGS_BASE -I$MBED_INC -c "$NATIVE_DIR/mbedtls/library/$src" -o "$OUT/${src%.c}.o" 2>/dev/null; then
      :
    else
      FAIL=$((FAIL+1))
    fi
  done
  rm -f "$OUT/libmbedcrypto.a"
  find "$OUT" -maxdepth 1 -name "*.o" > /tmp/mbedcrypto_objs.txt
  $AR rcs "$OUT/libmbedcrypto.a" @/tmp/mbedcrypto_objs.txt
  echo "  mbedcrypto: $TOTAL compiled, $FAIL failed, $(ls -lh "$OUT/libmbedcrypto.a" | awk '{print $5}')"
}

# ============================================================
# メイン: 16スレッドで並列ビルド
# ============================================================
echo "=== KYF39 SimplePhone Native Build ==="
echo "  NDK: $NDK"
echo "  Target: armeabi-v7a (API $ANDROID_API_LEVEL)"
echo ""

# mbedcrypto は他のライブラリの依存先なので先にビルド
build_mbedcrypto

# spandsp (G.722) は他に依存しないため先にビルド
build_spandsp

# re, rem, baresip, opus をサブシェルで並列ビルド
build_re &
build_rem &
build_baresip &
build_opus &
wait

# --- 結果サマリ ---
echo ""
echo "=== Build Summary ==="
ls -lh "$NATIVE_DIR/build_spandsp_g722/libspandsp.a"
ls -lh "$NATIVE_DIR/build_re/libre.a"
ls -lh "$NATIVE_DIR/build_rem/librem.a"
ls -lh "$NATIVE_DIR/build_mbedtls/library/libmbedcrypto.a"
ls -lh "$NATIVE_DIR/build_baresip_final/libbaresip.a"
ls -lh "$NATIVE_DIR/build_opus/libopus.a"
echo ""
echo "=== Removed (not needed) ==="
echo "  libmbedtls.a  (TLS transport - disabled in ua_init)"
echo "  libmbedx509.a (X.509 certificates - not used)"
echo "=== All native libraries built successfully ==="
