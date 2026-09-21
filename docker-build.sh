#!/bin/bash
# docker-build.sh - Docker で APK をビルド
# 使い方: docker-build.sh [debug|release]  (デフォルト: debug)
# - debug:   着信音・バイブなし
# - release: 着信音・バイブあり（署名付き）
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
IMAGE_NAME="kyf42-phone-builder"
OUTPUT_DIR="$SCRIPT_DIR/output"
BUILD_TYPE="${1:-debug}"

echo "=== Docker イメージビルド ==="
docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"

echo ""
echo "=== APK ビルド ==="
mkdir -p "$OUTPUT_DIR"
docker run --rm \
    -v "$SCRIPT_DIR:/src" \
    -v "$OUTPUT_DIR:/output" \
    "$IMAGE_NAME" \
    /src/kyf42-phone/build-apk.sh "$BUILD_TYPE"

# Docker で作成されたファイルの所有者を現在のユーザーに変更
echo ""
echo "=== 権限修正 ==="
USER_ID=$(id -u)
GROUP_ID=$(id -g)
sudo chown -R "$USER_ID:$GROUP_ID" "$OUTPUT_DIR"
sudo chown -R "$USER_ID:$GROUP_ID" "$SCRIPT_DIR/kyf42-phone/native/build_"* 2>/dev/null || true

echo ""
echo "=== 完成 ($BUILD_TYPE) ==="
if [ "$BUILD_TYPE" = "release" ]; then
    ls -lh "$OUTPUT_DIR/kyf39-phone-release.apk" "$OUTPUT_DIR/kyf42-phone-release.apk"
else
    ls -lh "$OUTPUT_DIR/kyf39-phone.apk" "$OUTPUT_DIR/kyf42-phone.apk"
fi
