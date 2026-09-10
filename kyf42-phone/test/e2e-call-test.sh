#!/bin/bash
# E2E 通話テストスクリプト
# 2台の KYF42 間で発信→着信→応答→通話→終話を検証する
#
# 使い方:
#   ./test/e2e-call-test.sh
#
# 前提:
#   - 2台の KYF42 が USB で接続されている
#   - 両方とも SimplePhone がインストール済み・設定済み
#   - ご利用の SIP サーバが稼働している（PBX_HOST/PBX_CONTAINER で指定）

set -euo pipefail

# === 設定（実機のシリアルとPBX接続先は環境変数で上書きしてください） ===
DEVICE_A="${DEVICE_A:-SERIAL_A}"  # 内線 203（例）
DEVICE_B="${DEVICE_B:-SERIAL_B}" # 内線 205（例）
EXT_A="203"
EXT_B="205"
PBX_HOST="${PBX_HOST:-root@192.0.2.1}"  # 例: ご利用のPBXのSSH接続先に置き換える
PBX_CONTAINER="${PBX_CONTAINER:-asterisk}"
PBX_SSH="ssh -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes -o StrictHostKeyChecking=no"
TIMEOUT=10

# === ユーティリティ ===
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${YELLOW}[TEST]${NC} $*"; }
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; FAILURES=$((FAILURES + 1)); }
FAILURES=0

adb_a() { adb -s "$DEVICE_A" "$@"; }
adb_b() { adb -s "$DEVICE_B" "$@"; }

wait_for_text() {
    local device=$1 text=$2 max_wait=${3:-$TIMEOUT}
    local elapsed=0
    while [ $elapsed -lt $max_wait ]; do
        local ui
        ui=$(adb -s "$device" shell "uiautomator dump /sdcard/ui.xml 2>/dev/null && cat /sdcard/ui.xml" 2>/dev/null)
        if echo "$ui" | grep -q "$text"; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

wait_for_log() {
    local device=$1 pattern=$2 max_wait=${3:-$TIMEOUT}
    local elapsed=0
    while [ $elapsed -lt $max_wait ]; do
        if adb -s "$device" shell "logcat -d" 2>/dev/null | grep -q "$pattern"; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

get_ui_text() {
    local device=$1
    adb -s "$device" shell "uiautomator dump /sdcard/ui.xml 2>/dev/null && cat /sdcard/ui.xml" 2>/dev/null \
        | grep -oP 'text="[^"]*"' | sed 's/text="//;s/"//' | grep -v '^$'
}

enter_number() {
    local device=$1 number=$2
    # KEYCODE_CLEAR で全消去
    adb -s "$device" shell "input keyevent 28"
    sleep 0.3
    # 数字を入力
    for (( i=0; i<${#number}; i++ )); do
        local digit="${number:$i:1}"
        local keycode=$((digit + 7))  # KEYCODE_0=7
        adb -s "$device" shell "input keyevent $keycode"
        sleep 0.3
    done
}

# === テスト開始 ===
echo "=========================================="
echo "  E2E 通話テスト"
echo "  Device A: $DEVICE_A (内線 $EXT_A)"
echo "  Device B: $DEVICE_B (内線 $EXT_B)"
echo "=========================================="
echo ""

# --- Test 1: 両端末の SIP 登録確認 ---
log "Test 1: SIP 登録確認"
for dev in "$DEVICE_A" "$DEVICE_B"; do
    adb -s "$dev" shell "am force-stop io.github.r_ch_iij.simplephone" 2>/dev/null || true
done
sleep 1
for dev in "$DEVICE_A" "$DEVICE_B"; do
    adb -s "$dev" shell "am start -n io.github.r_ch_iij.simplephone/.MainActivity" 2>/dev/null
done
sleep 5

for dev in "$DEVICE_A" "$DEVICE_B"; do
    if adb -s "$dev" shell "logcat -d" 2>/dev/null | grep -q "REGISTER_OK"; then
        pass "SIP 登録成功 ($dev)"
    else
        fail "SIP 登録失敗 ($dev)"
    fi
done

# --- Test 2: A→B 発信テスト ---
log "Test 2: $EXT_A → $EXT_B 発信テスト"
enter_number "$DEVICE_A" "$EXT_B"
sleep 0.5
adb_a shell "input keyevent 5"  # CALL
sleep 3

if wait_for_text "$DEVICE_A" "発信中" 5; then
    pass "A: 発信中表示"
else
    fail "A: 発信中表示なし"
fi

if wait_for_text "$DEVICE_B" "着信" 5; then
    pass "B: 着信表示"
else
    fail "B: 着信表示なし"
fi

# PBX で Ring 確認
if $PBX_SSH "$PBX_HOST" "docker exec $PBX_CONTAINER asterisk -rx 'core show channels'" 2>/dev/null | grep -q "Ring"; then
    pass "PBX: Ring 状態確認"
else
    fail "PBX: Ring 状態なし"
fi

# --- Test 3: B で応答 ---
log "Test 3: B で応答"
adb_b shell "input keyevent 5"  # CALL (応答)
sleep 3

if wait_for_text "$DEVICE_A" "通話中" 5; then
    pass "A: 通話中表示"
else
    fail "A: 通話中表示なし"
fi

if wait_for_text "$DEVICE_B" "通話中" 5; then
    pass "B: 通話中表示"
else
    fail "B: 通話中表示なし"
fi

# PBX で Up 確認
if $PBX_SSH "$PBX_HOST" "docker exec $PBX_CONTAINER asterisk -rx 'core show channels'" 2>/dev/null | grep -q "Up.*Dial\|Up.*AppDial"; then
    pass "PBX: Up 状態確認"
else
    fail "PBX: Up 状態なし"
fi

# --- Test 4: 終話 (BACKキー=4。ENDCALL=6はKYF42に到達しない。通話中のCALL=5は
# システムに奪われPair画面が開くため使用不可) ---
log "Test 4: 終話"
adb_a shell "input keyevent 4"  # BACK (通話中→終話)
sleep 3

if wait_for_text "$DEVICE_A" "発信中" 3 && ! wait_for_text "$DEVICE_A" "通話中" 1; then
    pass "A: 通話終了"
else
    # 通話中が消えていれば OK
    local_a_ui=$(get_ui_text "$DEVICE_A")
    if ! echo "$local_a_ui" | grep -q "通話中"; then
        pass "A: 通話終了"
    else
        fail "A: 通話終了失敗"
    fi
fi

# --- Test 5: B→A 発信テスト (逆方向) ---
log "Test 5: $EXT_B → $EXT_A 発信テスト (逆方向)"
enter_number "$DEVICE_B" "$EXT_A"
sleep 0.5
adb_b shell "input keyevent 5"  # CALL
sleep 3

if wait_for_text "$DEVICE_B" "発信中" 5; then
    pass "B: 発信中表示"
else
    fail "B: 発信中表示なし"
fi

if wait_for_text "$DEVICE_A" "着信" 5; then
    pass "A: 着信表示"
else
    fail "A: 着信表示なし"
fi

# A で応答
adb_a shell "input keyevent 5"
sleep 3

if wait_for_text "$DEVICE_B" "通話中" 5; then
    pass "B: 通話中表示"
else
    fail "B: 通話中表示なし"
fi

if wait_for_text "$DEVICE_A" "通話中" 5; then
    pass "A: 通話中表示"
else
    fail "A: 通話中表示なし"
fi

# 終話 (B側からBACKキー)
adb_b shell "input keyevent 4"
sleep 3

# === 結果 ===
echo ""
echo "=========================================="
if [ $FAILURES -eq 0 ]; then
    echo -e "${GREEN}  ALL TESTS PASSED${NC}"
else
    echo -e "${RED}  $FAILURES TEST(S) FAILED${NC}"
fi
echo "=========================================="

exit $FAILURES
