#!/bin/bash
# KYF42 デバイスセットアップスクリプト
# 使い方: ./test/setup-device.sh <serial> <extension> <password>
# 例: ./test/setup-device.sh <serial> 203 <password>
# 環境変数:
#   SERVER=...  SIP サーバ (デフォルト: 192.0.2.1。ご利用のPBXアドレスに置き換える)
#   SILENT=1    バイブなし・無音 (デフォルト、デバッグ用)
#   SILENT=0    バイブあり・デフォルト着信音 (リリース版の確認用)

set -euo pipefail

SERIAL="${1:?Usage: $0 <serial> <extension> <password>}"
EXT="${2:?Usage: $0 <serial> <extension> <password>}"
PASS="${3:?Usage: $0 <serial> <extension> <password>}"
SERVER="${SERVER:-192.0.2.1}"
# SILENT=1 (デフォルト): バイブなし・着信音なし（デバッグ用）。
# SILENT=0: バイブあり・デフォルト着信音（リリース版の動作確認用）。
SILENT="${SILENT:-1}"
if [ "$SILENT" = "0" ]; then
    VIBRATE="true"
    RINGTONE_LINE=""
else
    VIBRATE="false"
    RINGTONE_LINE='    <string name="ringtone_uri"></string>'
fi
APK_DIR="$(cd "$(dirname "$0")/../.." && pwd)/output"

ADB="adb -s $SERIAL"

echo "=== KYF42 セットアップ ==="
echo "  Serial: $SERIAL"
echo "  Extension: $EXT"
echo "  Server: $SERVER"
echo ""

# 1. アプリ存在確認
if ! $ADB shell pm list packages 2>/dev/null | grep -q io.github.r_ch_iij.simplephone; then
    echo "[1] アプリをインストール中..."
    $ADB install "$APK_DIR/kyf42-phone.apk"
else
    echo "[1] アプリはインストール済み"
fi

# 2. 権限付与
echo "[2] 権限を付与中..."
$ADB shell pm grant io.github.r_ch_iij.simplephone android.permission.RECORD_AUDIO 2>/dev/null || true
$ADB shell pm grant io.github.r_ch_iij.simplephone android.permission.CAMERA 2>/dev/null || true

# 3. 設定ファイル作成
echo "[3] 設定を投入中..."
TMPFILE=$(mktemp /tmp/sip_config_XXXXXX.xml)
cat > "$TMPFILE" << EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="server">$SERVER</string>
    <int name="port" value="5060" />
    <string name="username">$EXT</string>
    <string name="password">$PASS</string>
    <string name="realm">asterisk</string>
    <string name="action_key1">volume_up</string>
    <string name="action_key2">settings</string>
    <string name="action_key3">volume_down</string>
    <string name="action_key4">none</string>
    <boolean name="vibrate" value="$VIBRATE" />
    <int name="volume_pct" value="150" />
$RINGTONE_LINE
</map>
EOF

$ADB shell "run-as io.github.r_ch_iij.simplephone mkdir -p shared_prefs" 2>/dev/null || true
$ADB push "$TMPFILE" /sdcard/sip_config.xml >/dev/null
$ADB shell "run-as io.github.r_ch_iij.simplephone cp /sdcard/sip_config.xml shared_prefs/sip_config.xml"
rm -f "$TMPFILE"

# 4. アプリ起動
echo "[4] アプリを起動中..."
$ADB shell "am force-stop io.github.r_ch_iij.simplephone" 2>/dev/null || true
sleep 1
$ADB shell "am start -n io.github.r_ch_iij.simplephone/.MainActivity" >/dev/null

# 5. 登録確認
echo "[5] SIP 登録を確認中..."
for i in $(seq 1 10); do
    sleep 1
    if $ADB shell "logcat -d" 2>/dev/null | grep -q "REGISTER_OK"; then
        echo "  ✓ 登録成功"
        break
    fi
    if [ "$i" -eq 10 ]; then
        echo "  ✗ 登録タイムアウト"
        exit 1
    fi
done

echo ""
echo "=== セットアップ完了 ==="
