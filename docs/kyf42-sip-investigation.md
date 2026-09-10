# KYF42 SIP 接続調査記録

## 概要

au GRATINA KYF42（京セラ製 Android ガラホ）をご利用の Asterisk の内線端末として運用するための調査過程と発見事項を記録する。

2026-08-18 に実施。例として内線 202 を割り当てた。

## KYF42 の仕様要点

- Android 10 ベース、CPU は 32bit（armeabi-v7a）
- RAM 1GB / ROM 8GB
- Wi-Fi b/g/n（2.4GHz）、Bluetooth 5.1、USB Type-C
- Google Play ストアなし（APK サイドロード前提）
- QuickShortcutMaker / Tailscale / WireGuard を adb でインストール済み（`kyf42-apks/` 参照）

## adb を使った設定手順（確認済み）

### 開発者向けオプションの有効化
設定 → その他の設定 → 端末情報 → ビルド番号を 7 回連打 → USBデバッグ ON

### SIP 設定画面の起動（QuickShortcutMaker 不要）
```
adb shell am start -n com.android.phone/com.android.services.telephony.sip.SipSettings
```

### 電話アカウント設定画面（PhoneAccountSettingsActivity）
```
adb shell am start -n com.android.phone/.settings.PhoneAccountSettingsActivity
```
この画面には以下の項目がある:
- すべての通話アカウント →「通話できるアカウントを選択」
- SIM（NTT DOCOMO 等）
- SIP アカウント（202）
- 通話の発着と着信 → 発信先 / SIPの設定 / 着信を受ける

### 通話アカウント管理画面（EnableAccountPreferenceActivity）
```
adb shell am start -n com.android.server.telecom/.settings.EnableAccountPreferenceActivity
```
各 PhoneAccount の有効/無効を切替可能。

## 発見した重要な設定ポイント

### 1. 「通話アカウント」の SIP アカウントスイッチを ON にする
SipSettings で SIP アカウントを追加しただけでは着信しません。**Telecom の PhoneAccount が無効**だと `SipIncomingCallReceiver` が着信を拒否します。

```
D SIP: [SipIncomingCallReceiver] takeCall, PhoneAccount is disabled. Not accepting incoming call...
```

SipPhoneAccountSettingsActivity → オプション設定をタップ → 通話アカウント一覧 → SIP アカウントのスイッチを ON。

### 2. 「発信先」を SIP に固定する
PhoneAccountSettingsActivity に「通話の発着と着信」セクションがある。中の「発信先」を「最初に確認する」→「202」に変更。

**注意点**: SIM が挿入されている状態でしか「発信先」の変更ができない。SIM なしでは「データがありません」で開かない。設定は SIM 外し後も保存されるが、電話アプリが SIM 不在をチェックして「SIM エラー。カードを挿入してください。」と返す。

### 3. SIP 通話のみモード
SIM 外し状態で PhoneAccountSettingsActivity を見ると、「発信先」は「SIP通話の使用 → SIP通話のみ」に変わっている。設定自体は保存されている。

## KYF42 の制約（現行ファームウェア 1.080GC）

| 項目 | 結果 |
|------|------|
| SIP 着信 | SIM なしでも可能（PhoneAccount ON + 着信受ける ON で動作確認済み） |
| SIP 発信 | SIM 必須。SIM なしでは「SIMエラー。カードを挿入してください。」 |
| SIP 設定 | SipSettings でアカウント追加 → SipPhoneAccountSettingsActivity で着信 ON → PhoneAccountSettingsActivity で発信先=202 |

### SIP アカウント追加時の操作
SipSettings 画面 →「+」で追加 → ユーザー名/パスワード/サーバーを入力 → 保存。入力は `adb shell input text` で可能（数字/英字のみなら安全）。

## PBX 側の設定例（内線 202）

`config/pjsip/internal/202.conf`:
```ini
[202]
type=endpoint
transport=transport-v4udp
context=default
disallow=all
allow=ulaw,alaw,ilbc
dtmf_mode=auto
language=ja
callerid=<名前> <202>
auth=202
aors=202
[202]
type=auth
auth_type=userpass
username=202
password=<内線のパスワード>
[202]
type=aor
max_contacts=5
qualify_frequency=60
remove_unavailable=true
```

## 発見した問題と対処

### Stale contact 問題
KYF42 が再起動やネットワーク切替のたびに SIP ポートが変わる。Asterisk 側に古いコンタクトが残り、「Endpoint '200': Could not create dialog to invalid URI '200'」エラーが発生。

**対処**: AOR に `remove_unavailable=true` を追加。old contact は qualify 失敗後に自動削除される。

### SipConnectionService の不安定さ
通話終了時に `SipConnectionService` が died することがある。直後の着信が失敗するが、時間をおくと復旧する。

### KYF42 の電話アプリ（kcTeleService.apk）
```
package:/system/priv-app/kcTeleService/kcTeleService.apk
```
- アプリの呼び出し元: `com.android.server.telecom`
- 呼び出し先: `com.android.phone` の `TelephonyConnectionService` と `SipConnectionService`

## 「SIM エラー」問題の根本原因

### 経路
KYF42 電話アプリ → Telecom（TelephonyConnectionService）→ SIM 不存在チェック →「SIMエラー。カードを挿入してください。」

### 発見事項
- `defaultOutgoing` は SipConnectionService に設定済み（`dumpsys telecom` で確認）
- しかし、KYF42 の電話アプリは SIM の存在を Telecom とは別にチェックしている
- SIM なしでは TelephonyConnectionService 側で `isEmergencyOnly()` が true → エラー
- 「SIP通話のみ」設定は保存されるが、電話アプリの SIM チェックを迂回できない

### コード解析結果
- `com.android.phone` の実体は `/system/priv-app/kcTeleService/kcTeleService.apk`
- `TelephonyConnectionService.smali` に SIM 状態チェック（`isEmergencyOnly`）あり
- 「SIM エラー。カードを挿入してください。」の文字列リソースは `incall_error_emergency_only_ex`
- `PhoneAccountSettingsFragment.smali` に `default_outgoing_account` の設定キーを確認
- `AccountSelectionPreference`（ListPreference 継承）が発信先設定を管理

### 解決不可能な点
- SIM なしでの SIP 発信は、現行ファームウェア（1.080GC）では電話アプリの動作上、不可能
- PhoneAccount の無効化は TelephonyConnectionService の停止を意味し、着信も壊れる
- `pm disable` / `pm hide` による無効化はブートループのリスクあり（XDA に事例報告あり）

## 動作確認済み

| 項目 | SIM あり | SIM なし |
|------|---------|---------|
| SIP 着信 | ○ | ○ |
| SIP 発信 | ○ | ×（SIM エラー） |
| SIP 登録 | ○ | ○ |
| SIP PhoneAccount ON | ○ | ○ |
| 発信先=202 | ○ | 設定は保存されるが効かない |

## 飛行機モードでの検証

SIM なしでも「SIM エラー」が出ることを確認するため、KYF42 を飛行機モードにして 200（Linphone）に発信を試行した。結果は**同じ「SIMエラー。カードを挿入してください。」エラー**。

飛行機モード（全無線 OFF）でも、KYF42 の電話アプリは SIM の存在を直接チェックしていることが確定。フォールバックとして SIP に切り替わる挙動は実装されていない。

## 残作業・今後の課題

## Root 化の調査結果

### 誤: KYF42 は Qualcomm チップ（XDA 記載）
### 正: KYF42 は MediaTek チップ
- Garaho Wiki（garahowiki.com）の調査で判明
- 同ハードウェア: KY-42C / A202KC / A203KC / A204KC（DIGNO Keitai 4 系）
- KY-42C は KYF42 とほぼ同一（au 向けは KYF42、ドコモ向けは KY-42C）

### Firmware バージョンによる分岐

| バージョン | 方法 | 状況 |
|-----------|------|------|
| **1.090XX 未満**（1.080GC 等） | MTKClient + Magisk | **利用可能** |
| **1.090XX 以降** | 新 exploit（shomykohai/kyocera-ky-42c-unlock） | Preloader crash 経由で EL3 コード実行 |

**KYF42 の firmware は 1.080GC（1.090XX 未満）なので、標準の MTKClient メソッドが利用可能。**

### Root 化の手順（firmware 1.090XX 未満）

#### 前提条件
1. ADB 環境（既に構築済み）
2. Magisk を端末にインストール（APK サイドロード）
3. **NixOS Live USB**（推奨）または MTKClient を手動セットアップ

#### 手順（Garaho Wiki / MTKClient）
1. `sudo mtk r boot,vbmeta boot.img,vbmeta.img` — boot.img と vbmeta.img をダンプ
2. boot.img を端末にコピー → Magisk で patched ファイル生成
3. `sudo mtk e metadata,userdata,md_udc` — メタデータ/ユーザーデータ消去
4. `sudo mtk da seccfg unlock` — **bootloader unlock（全データ消去）**
5. `sudo mtk da vbmeta 3` — vbmeta を disable に設定
6. `sudo mtk w boot boot.patched` — patched boot イメージをフラッシュ
7. `sudo mtk reset` — 再起動

#### 重要な注意点
- **bootloader unlock は全データを消去する**
- NixOS Live USB が最も容易（MTKClient がプリインストール）
- Windows/Linux 両方で動作するが、Linux が推奨
- ブートロッカーの再ロックも可能（`mtk da seccfg lock`）

### 参考リンク
- Garaho Wiki: https://garahowiki.com/phones:kyocera_digno_keitai_4:rooting_the_kyocera_digno_keitai_4
- KY-42C unlock exploit: https://github.com/shomykohai/kyocera-ky-42c-unlock
- MTKClient: https://github.com/bkerler/mtkclient
- Magisk: https://github.com/topjohnwu/Magisk/releases

### Root 化による SIP 発信問題の解決可能性
Root 化された KYF42 では:
- `pm disable com.android.phone/com.android.server.telecom` で**安全に**電話サービスを無効化
- `pm hide`/`pm unhide` でブートループリスクを回避
- 電話アプリの SIM チェックをバイパスする修改が可能
- ただし、root 化は**すべてのデータを消去**するため、事前のバックアップが必須

- SIM なしでの SIP 発信には、Asterisk 側のカスタム SIP ダイヤラ（Zoiper 等）の導入、またはファームウェア変更が必要
- KY-42C（ドコモ DIGNO ケータイ）との比較（APK インストール可否、SIP アクティビティ有無）が未完了
- 低速回線（Povo 128kbps / IIJmio 300kbps）での通話品質評価は別文書（`low-speed-mobile-voip.md`）に記録
