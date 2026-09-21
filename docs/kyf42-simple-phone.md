# KYF42 簡易 SIP クライアント開発計画

## 概要

KYF42 向けに、物理キーのみで操作できる最小限の SIP クライアントを作成する。
ブート時の自動起動、着信 ringing、設定画面を含む。

## 端末仕様

| 項目 | 値 |
|------|-----|
| 機種 | 京セラ GRATINA KYF42 (au) |
| SoC | Qualcomm QM215 (32bit ARM) |
| Android | 10 (カーネル 4.9.227-perf) |
| RAM | 1GB |
| ROM | 8GB |
| 解像度 | 480x854 (FWVGA) |
| 物理キー | 0-9, *, #, 発話, 終話, クリア, 電源, サイドボタン |

## SIP 設定

| 項目 | 値 |
|------|-----|
| PBX | ご利用のPBXアドレス |
| 内線番号 | 203 |
| 認証ユーザ名 | 203 |
| パスワード | <内線のパスワード> |
| プロトコル | UDP |
| ポート | 5060 |
| コーデック | PCMU (G.711 μ-law), PCMA (G.711 A-law) |

## キーバインド設計（実測済み 2026-08-19）

### 実測したキーマッピング（getevent で確認済み）

| 物理キー | 物理位置 | Linux イベント | Android KeyEvent | アプリでの用途 |
|---------|---------|---------------|------------------|---------------|
| 0-9 | テンキー | KEY_NUMERIC_0〜9 | KEYCODE_0〜9 | 番号入力 / DTMF 送信 |
| * | テンキー | KEY_NUMERIC_STAR | KEYCODE_STAR | 未使用（予備） |
| # | テンキー | KEY_NUMERIC_POUND | KEYCODE_POUND | 未使用（予備） |
| 発話 | テンキー下部 | KEY_PHONE | KEYCODE_CALL | 発信 / 着信応答 |
| 終話 | テンキー下部 | KEY_POWER | KEYCODE_POWER | **使用不可**（システムが消費。画面オフ扱い） |
| クリア | テンキー下部 | KEY_CLEAR (355) → **KEYCODE_BACK (4)** | 待機中: 文字削除 / 通話中: 終話 |
| **左上** | 十字キー左上 | KEY_F1 | KEYCODE_F1 | 設定可能（設定画面で変更） |
| **左下** | 十字キー左下 | KEY_F3 | KEYCODE_F3 | 設定可能 |
| **右上** | 十字キー右上 | KEY_F2 | KEYCODE_F2 | 設定可能 |
| **右下** | 十字キー右下 | KEY_F4 | KEYCODE_F4 | 設定可能 |
| サイドボタン | 側面 | 0xfe (254) | .kl 次第 | 無視 |
| 決定 (OK) | 十字キー中央 | KEY_ENTER | KEYCODE_DPAD_CENTER | **通話中は明示的に無効化**（誤切断防止） |

### 重要な注意点
1. **終話ボタン = 電源ボタン**。KEYCODE_POWER は Android システムが必ず消費するため、
   アプリからは受け取れない。通話終了は**クリアボタン**で行う。
2. **決定 (OK) ボタン**を通話中に押すと切断される癖がある（実測）。
   アプリ側で KEYCODE_DPAD_CENTER を通話中に無視する処理を入れる。
3. サイドボタン（0xfe）は .kl ファイル次第で Android のどのキーになるか不明。
   アプリでは無視する。
4. **クリアボタンは KEYCODE_BACK (4)** を送信。matrix_keypad.kl: `key 355 BACK`。
5. **十字キー周囲の4キーの物理配置**:
   - 左上 = F1、左下 = F3、右上 = F2、右下 = F4
   - 設定画面では物理位置ラベル（「左上キー」「右上キー」等）で表示

## アーキテクチャ

```
┌─────────────────────────────────────┐
│  SimpleSipClient (Activity)         │
│  ├─ KeyHandler (物理キー処理)        │
│  ├─ DisplayManager (画面表示)        │
│  └─ SIP Manager                     │
│     ├─ Registration (登録)           │
│     ├─ OutgoingCall (発信)           │
│     ├─ IncomingCall (着信)           │
│     └─ AudioManager (音声管理)       │
└─────────────────────────────────────┘
```

## 必要なパーミッション

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

## 開発環境

- Android SDK (commandlinetools)
- Build Tools: 34.0.0
- Target SDK: 29 (Android 10)
- Min SDK: 28 (Android 9)
- Kotlin
- Gradle

## UI 設計

### メイン画面
```
┌──────────────────────────┐
│  [ステータス: 登録済み]    │
│                          │
│  [入力番号: 090-xxxx-xxxx] │
│                          │
│  [発話]  [終話]  [クリア]  │
└──────────────────────────┘
```

- 大きなフォント（祖母向け）
- 高コントラスト
- 最小限の情報のみ表示

## ファイル構成

```
app/
├── build.gradle
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/asterisk/simplephone/
│   │   ├── MainActivity.kt
│   │   ├── KeyHandler.kt
│   │   ├── SipManager.kt
│   │   ├── AudioManager.kt
│   │   └── BootReceiver.kt
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/strings.xml
│       └── values/styles.xml
```

## ビルド手順

```bash
cd kyf42-phone
./gradlew assembleDebug
adb install app/build/outputs/apk/kyf42/debug/app-kyf42-debug.apk
# KYF39 用は app/build/outputs/apk/kyf39/debug/app-kyf39-debug.apk
```

## 動作確認結果（2026-08-19）

### 成功した項目
| 項目 | 結果 |
|------|------|
| SIP 登録 (203) | ✅ PBX に Registered 登録 |
| 発信 (203→202) | ✅ 202 が着信表示、通話確立 |
| 着信（PBX→203） | ✅ アプリが「着信中」表示、発話で応答 |
| 通話終了 | ✅ クリアボタン（KEYCODE_CLEAR）で切断 |
| 単一登録 | ✅ 旧プロファイル除去後、1コンタクトのみ |
| 着信の取込み | ✅ takeAudioCall 成功（PendingIntent → onNewIntent） |
| 登録自己修復 | ✅ 「registration not running」時に5秒間隔で再オープン（最大5回） |

### バージョン履歴
| バージョン | 内容 |
|-----------|------|
| 1.0 | 初版（USE_SIP 権限不足で登録不可） |
| 2.0 | serverDomain を realm=asterisk に修正、フォアグラウンドサービス化、着信処理、バイブレーション |
| 2.1 | クリアボタン修正（KEYCODE_BACK=4 をハンドリング）、ANR 修正（SIP 操作をバックグラウンドスレッド化） |

### 重要な技術的発見（トラブルシュート）

#### 1. CROSS_DOMAIN_AUTHENTICATION エラー
- **症状**: REGISTER が `CROSS_DOMAIN_AUTHENTICATION: asterisk` で失敗
- **原因**: SipProfile の serverDomain（=<サーバアドレス>）と、Asterisk の challenge realm（=asterisk）が不一致
- **対策**: `SipProfile.Builder(user, "asterisk")` で realm に一致させ、実サーバは `setOutboundProxy("<サーバアドレス>")` で指定

#### 2. PBX に 203 の endpoint が存在しなかった
- PBX 側の設定ファイルを作成後、PBX への反映（reload 等）が必要
- 反映漏れだと「No matching endpoint found」で REGISTER が失敗

#### 3. mNetworkType == -1 による登録停滞（開発サイクル特有）
- **症状**: アプリ再起動時に `registration not running` エラー
- **原因**: Kyocera SipService の `mNetworkType` が -1 に固まる
  - 全 SIP プロファイルが閉じると `unregisterReceivers()` → mNetworkType=-1
  - CONNECTIVITY_CHANGE イベントが来るまで -1 のまま
- **回避**: WiFi を一度オフ→オンすると mNetworkType が 1 になり登録開始
- **対策**: アプリ v1.2 に自己修復ロジックを実装
  - 「registration not running」検出 → 5秒間隔で最大5回プロファイルを再オープン
  - ブート直後は WiFi 接続イベント（CONNECTIVITY_CHANGE）で mNetworkType が 1 になり自動登録
  - 開発中の再インストールサイクル特有の問題で、実運用（ブート）では発生しにくい

#### 4. Kyocera データ通信制限ダイアログ（jp.kyocera.restrictdata）
- **症状**: アプリインストール/更新のたびに「データ通信を行う可能性のあるアプリ」ダイアログ
- **対応**: 「制限を解除する」を選択 → `content://kc_restrictdata_settings/accept_mobile_data` に uid=10135 status=1 で記録
- 最終導入時は1回だけ表示される

### キー操作の実測結果
- 発話 = KEYCODE_CALL（発信/応答）✅
- クリア = KEYCODE_CLEAR（待機中: 削除 / 通話中: 終話）✅
- 終話 = KEY_POWER（システムが消費、アプリで使用不可）
- 決定 = KEYCODE_DPAD_CENTER（通話中は無効化で誤切断防止）
