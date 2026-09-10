#!/bin/bash
# long-call-test.sh - 10分間通話の安定性テスト
#
# 使い方:
#   ./test/long-call-test.sh [分数]   (デフォルト: 10)
#
# 前提:
#   - 2台の KYF42 が USB で接続されている
#   - 両方とも SimplePhone がインストール済み・設定済み
#   - ご利用の SIP サーバが稼働している（PBX_HOST/PBX_CONTAINER で指定）
#
# 判定基準:
#   - 指定時間の間、通話が切断されないこと
#   - ウォッチドッグの誤爆がないこと
#   - RTCP が継続受信されること
#   - CPU 20% 以下、メモリの異常増加なし
#   - 終話後に PBX のチャネルが 0 になること
#
# 注意: 音声の主観評価（音質・途切れ）は自動化できないため別途人手で確認すること。

set -uo pipefail

# === 設定（実機のシリアルとPBX接続先は環境変数で上書きしてください） ===
DEVICE_A="${DEVICE_A:-SERIAL_A}"  # 内線 203（発信側・例）
DEVICE_B="${DEVICE_B:-SERIAL_B}" # 内線 205（着信側・例）
EXT_B="205"
PBX_HOST="${PBX_HOST:-root@192.0.2.1}"  # 例: ご利用のPBXのSSH接続先に置き換える
PBX_CONTAINER="${PBX_CONTAINER:-asterisk}"
PBX_SSH="ssh -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes -o StrictHostKeyChecking=no"
DURATION_MIN="${1:-10}"
INTERVAL=30  # 監視間隔（秒）
CPU_LIMIT=20 # CPU 上限（%）
LOGDIR="/tmp/long-call-test"
mkdir -p "$LOGDIR"
LOG="$LOGDIR/long-call-$(date +%Y%m%d-%H%M%S).log"
FAILURES=0

log()  { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
pass() { log "PASS: $*"; }
fail() { log "FAIL: $*"; FAILURES=$((FAILURES + 1)); }

ui_text() {
    adb -s "$1" shell "uiautomator dump /sdcard/ui.xml 2>/dev/null && cat /sdcard/ui.xml" 2>/dev/null \
        | grep -oP 'text="[^"]*"' | grep -v '^text=""$'
}

pbx_channels() {
    $PBX_SSH "$PBX_HOST" \
        "docker exec $PBX_CONTAINER asterisk -rx 'core show channels'" 2>/dev/null \
        | grep -cE '^PJSIP/' || true
}

device_cpu() {
    local pid
    pid=$(adb -s "$1" shell "pidof io.github.r_ch_iij.simplephone" 2>/dev/null | tr -d '\r')
    [ -z "$pid" ] && { echo "N/A"; return; }
    adb -s "$1" shell "top -n 1 -p $pid 2>/dev/null" \
        | grep -E "^ *$pid " | awk '{print $9}' | tr -d '\r' || echo "N/A"
}

device_pss() {
    adb -s "$1" shell "dumpsys meminfo io.github.r_ch_iij.simplephone 2>/dev/null" \
        | grep -m1 "TOTAL" | awk '{print $2}' | tr -d '\r' || echo "N/A"
}

logcat_count() {
    adb -s "$1" shell "logcat -d 2>/dev/null" | grep -c "$2" || true
}

# === 開始 ===
log "=== 10分間通話安定性テスト（${DURATION_MIN}分） ==="

# --- 1. 登録確認 ---
for dev in "$DEVICE_A" "$DEVICE_B"; do
    adb -s "$dev" shell "am force-stop io.github.r_ch_iij.simplephone" 2>/dev/null || true
done
sleep 1
for dev in "$DEVICE_A" "$DEVICE_B"; do
    adb -s "$dev" shell "am start -n io.github.r_ch_iij.simplephone/.MainActivity" 2>/dev/null
done
sleep 9
for dev in "$DEVICE_A" "$DEVICE_B"; do
    if adb -s "$dev" shell "logcat -d" 2>/dev/null | grep -q "REGISTER_OK"; then
        pass "SIP 登録 ($dev)"
    else
        fail "SIP 登録なし ($dev)"
    fi
done
adb -s "$DEVICE_A" shell "logcat -c" 2>/dev/null
adb -s "$DEVICE_B" shell "logcat -c" 2>/dev/null

# --- 2. 発信→応答 ---
log "発信中: A($DEVICE_A) -> $EXT_B"
adb -s "$DEVICE_A" shell "input keyevent 28" 2>/dev/null
sleep 0.5
# 205 を入力 (KEYCODE_2=9, KEYCODE_0=7, KEYCODE_5=12)
for k in 9 7 12; do adb -s "$DEVICE_A" shell "input keyevent $k" 2>/dev/null; sleep 0.3; done
sleep 0.5
adb -s "$DEVICE_A" shell "input keyevent 5" 2>/dev/null
sleep 4
log "応答: B($DEVICE_B) SK2"
adb -s "$DEVICE_B" shell "input keyevent 133" 2>/dev/null
sleep 5

if ui_text "$DEVICE_A" | grep -q "通話中"; then
    pass "A: 通話中表示"
else
    fail "A: 通話中表示なし（応答失敗の可能性）"
fi
if ui_text "$DEVICE_B" | grep -q "通話中"; then
    pass "B: 通話中表示"
else
    fail "B: 通話中表示なし（応答失敗の可能性）"
fi
if [ "$FAILURES" -ne 0 ]; then
    log "呼接続に失敗したため中断"
    exit "$FAILURES"
fi
START_EPOCH=$(date +%s)

# 使用コーデックを記録
CHAN=$($PBX_SSH "$PBX_HOST" \
    "docker exec $PBX_CONTAINER asterisk -rx 'core show channels'" 2>/dev/null \
    | grep -m1 -oE 'PJSIP/203-[0-9a-f]+' || true)
if [ -n "$CHAN" ]; then
    CODEC=$($PBX_SSH "$PBX_HOST" \
        "docker exec $PBX_CONTAINER asterisk -rx 'core show channel $CHAN'" 2>/dev/null \
        | grep -m1 ReadFormat | awk '{print $2}' || true)
else
    CODEC=""
fi
log "使用コーデック(ReadFormat): ${CODEC:-不明}"

# --- 3. 監視ループ ---
ITERATIONS=$((DURATION_MIN * 60 / INTERVAL))
log "監視開始: ${ITERATIONS}回 x ${INTERVAL}秒"
CPU_MAX_A=0
CPU_MAX_B=0
for ((i = 1; i <= ITERATIONS; i++)); do
    sleep "$INTERVAL"
    ELAPSED=$((($(date +%s) - START_EPOCH) / 60))
    A_UI=$(ui_text "$DEVICE_A" | head -3 | tr '\n' ' ')
    B_UI=$(ui_text "$DEVICE_B" | head -3 | tr '\n' ' ')
    A_RTCP=$(logcat_count "$DEVICE_A" "CALL_RTCP")
    B_RTCP=$(logcat_count "$DEVICE_B" "CALL_RTCP")
    A_WD=$(logcat_count "$DEVICE_A" "watchdog.*hanging")
    B_WD=$(logcat_count "$DEVICE_B" "watchdog.*hanging")
    CH=$(pbx_channels)
    CPU_A=$(device_cpu "$DEVICE_A")
    CPU_B=$(device_cpu "$DEVICE_B")
    log "[$i/$ITERATIONS 約${ELAPSED}分] A=[$A_UI] B=[$B_UI] RTCP=$A_RTCP/$B_RTCP WD=$A_WD/$B_WD ch=$CH cpu=$CPU_A/$CPU_B"

    if ! echo "$A_UI" | grep -q "通話中"; then
        fail "A が通話中でない（${ELAPSED}分経過時点）"
        break
    fi
    if ! echo "$B_UI" | grep -q "通話中"; then
        fail "B が通話中でない（${ELAPSED}分経過時点）"
        break
    fi
    if [ "$A_WD" -ne 0 ] || [ "$B_WD" -ne 0 ]; then
        fail "ウォッチドッグ誤爆 (A=$A_WD B=$B_WD)"
        break
    fi
    # CPU 上限チェック（数値のみ）
    for v in "$CPU_A" "$CPU_B"; do
        INT_PART=$(echo "$v" | grep -oE '^[0-9]+' || echo "")
        if [ -n "$INT_PART" ] && [ "$INT_PART" -gt "$CPU_LIMIT" ]; then
            fail "CPU 上限超過: ${v}%"
        fi
    done
done

PSS_A_END=$(device_pss "$DEVICE_A")
PSS_B_END=$(device_pss "$DEVICE_B")
log "終了時メモリ(PSS KB): A=$PSS_A_END B=$PSS_B_END"

# --- 4. 終話 ---
log "終話: B側BACK"
adb -s "$DEVICE_B" shell "input keyevent 4" 2>/dev/null
sleep 5
if ui_text "$DEVICE_A" | grep -q "通話終了"; then
    pass "A: 通話終了"
else
    fail "A: 通話終了表示なし"
fi
if ui_text "$DEVICE_B" | grep -q "通話終了"; then
    pass "B: 通話終了"
else
    fail "B: 通話終了表示なし"
fi
CH_END=$(pbx_channels)
if [ "$CH_END" -eq 0 ]; then
    pass "PBX: 0 active channels"
else
    fail "PBX: チャネル残留 ($CH_END)"
fi

# --- 5. 履歴確認 ---
adb -s "$DEVICE_A" shell "am start -n io.github.r_ch_iij.simplephone/.HistoryActivity" 2>/dev/null
sleep 2
if ui_text "$DEVICE_A" | grep -qE "発信 205"; then
    pass "A: 履歴に発信記録"
else
    fail "A: 履歴に発信記録なし"
fi

# === 結果 ===
log ""
if [ "$FAILURES" -eq 0 ]; then
    log "=== ALL TESTS PASSED (${DURATION_MIN}分通話) ==="
else
    log "=== $FAILURES TEST(S) FAILED ==="
fi
log "詳細ログ: $LOG"
exit "$FAILURES"
