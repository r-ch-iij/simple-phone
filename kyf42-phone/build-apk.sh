#!/bin/bash
# build-apk.sh - Docker 内で APK をビルドするスクリプト
# 使い方: build-apk.sh [debug|release]  (デフォルト: debug)
#
# - debug:   着信音・バイブなし（BuildConfig.ALERT_SILENT=true）
# - release: 着信音・バイブあり（BuildConfig.ALERT_SILENT=false）。
# 署名鍵は /output/<type>.keystore を使い回す（なければ自動生成）。
# コンテナ内の一時鍵を使わないことで、ビルド毎に署名が変わらない。
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"

BUILD_TYPE="${1:-debug}"
if [ "$BUILD_TYPE" != "debug" ] && [ "$BUILD_TYPE" != "release" ]; then
    echo "Usage: $0 [debug|release]" >&2
    exit 1
fi

echo "=== 環境確認 ==="
echo "BUILD_TYPE=$BUILD_TYPE"
java -version 2>&1 | head -1
gradle --version 2>&1 | head -3
echo "ANDROID_HOME=$ANDROID_HOME"
echo "NDK バージョン: $(ls $ANDROID_HOME/ndk/ 2>/dev/null | head -1)"

# CMake が見つからない場合の対処
if ! command -v cmake &>/dev/null; then
    export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
fi

echo ""
echo "=== ネイティブライブラリビルド ==="
bash native/build_all.sh

echo ""
echo "=== APK ビルド ($BUILD_TYPE) ==="
# .cxx キャッシュを消してクリーンビルド
rm -rf app/.cxx

EXTRA_PROPS=""
# フレーバー別 APK（assembleDebug/assembleRelease は全フレーバーをビルドする）
if [ "$BUILD_TYPE" = "release" ]; then
    declare -A APKS=(
        ["app/build/outputs/apk/kyf39/release/app-kyf39-release.apk"]="kyf39-phone-release.apk"
        ["app/build/outputs/apk/kyf42/release/app-kyf42-release.apk"]="kyf42-phone-release.apk"
    )
else
    declare -A APKS=(
        ["app/build/outputs/apk/kyf39/debug/app-kyf39-debug.apk"]="kyf39-phone.apk"
        ["app/build/outputs/apk/kyf42/debug/app-kyf42-debug.apk"]="kyf42-phone.apk"
    )
fi
# 署名鍵の準備（/output はホストにマウントされているため永続化される）
KEYSTORE="${KEYSTORE:-/output/${BUILD_TYPE}.keystore}"
if [ ! -f "$KEYSTORE" ]; then
    echo "署名鍵を新規生成: $KEYSTORE"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" -alias kyf42 \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=KYF42 $BUILD_TYPE, OU=Dev, O=Local, C=JP"
    chmod 600 "$KEYSTORE"
fi
EXTRA_PROPS="-Pandroid.injected.signing.store.file=$KEYSTORE \
    -Pandroid.injected.signing.store.password=android \
    -Pandroid.injected.signing.key.alias=kyf42 \
    -Pandroid.injected.signing.key.password=android"

# shellcheck disable=SC2086
gradle "assemble${BUILD_TYPE^}" --no-daemon $EXTRA_PROPS

echo ""
echo "=== ビルド完了 ==="
mkdir -p /output
for APK_SRC in "${!APKS[@]}"; do
    APK_DST="${APKS[$APK_SRC]}"
    ls -lh "$APK_SRC"
    # 出力先にコピー（所有者は Docker 実行後に docker-build.sh で修正）
    cp "$APK_SRC" "/output/$APK_DST"
    chmod 644 "/output/$APK_DST"
    echo "APK: /output/$APK_DST"
done
