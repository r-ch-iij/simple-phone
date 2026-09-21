# kyf42-phone 開発者ノート

SIP 電話アプリ本体（`kyf42-phone/`）のビルド・実機検証・実装メモ。
技術スタックの一覧は `README.md` を参照。

## ビルド

Docker でのビルドを推奨（NDK・SDK・Gradle をイメージ内で用意）。

```bash
# debug（着信音・バイブなし）
./docker-build.sh debug

# release（着信音・バイブあり、署名付き）
./docker-build.sh release
```

成果物は `output/` に出る（`kyf42-phone.apk` / `kyf42-phone-release.apk`）。
署名鍵は `output/<type>.keystore` を使い回す（なければ自動生成）。
`output/` と `*.keystore` / `*.jks` は git 管理外。

コンテナ内で直接ビルドする場合：

```bash
cd kyf42-phone
./build-apk.sh [debug|release]
```

ネイティブライブラリは `native/build_all.sh` で再ビルドできる。
`native/build_*` はビルド成果物のため git 管理外。

## ネイティブライブラリの構成

| ライブラリ | ソース | 出力先 |
|---|---|---|
| libre.a | `native/re/` | `native/build_re/` |
| librem.a | `native/rem/` | `native/build_rem/` |
| libmbedcrypto.a | `native/mbedtls/` | `native/build_mbedtls/` |
| libbaresip.a | `native/baresip/`（opensles・g711・opus モジュール統合） | `native/build_baresip_final/` |
| libopus.a | `native/opus-1.4/` | `native/build_opus/` |
| spandsp（G.722 サブセット） | `native/spandsp/` | `native/build_spandsp*/` |

- `re` / `rem` / `baresip` / `mbedtls` はフォーク版を `native/` 配下に vendoring（`.gitmodules` 未使用）。
- `baresip` の `static.c` は `opensles`・`g711`・`opus` モジュールを静的登録。
- TLS は `ua_init()` で無効化済み（`libmbedtls.a`・`libmbedx509.a` はビルド対象外）。
- 過去の不具合：`build_all.sh` の `CFLAGS_BASE` にプラットフォーム判定マクロ（`HAVE_EPOLL` 等）が欠落し、`poll_setup` が EINVAL で失敗。マクロ追加と API レベル修正（28）で解決。実機で `poll_setup: method set to 2 (epoll)` を確認。

## 対応コーデック

| コーデック | 状態 | 帯域目安 | 備考 |
|---|---|---|---|
| G.722 | ○（優先） | ~87 kbps | 広帯域 16kHz。端末 8kHz との変換は auresamp |
| Opus | ○ | ~24 kbps | mono / 20kbps / VOIP / FEC。DTX 無効 |
| G.711 PCMU (ulaw) | ○ | ~87 kbps | フォールバック |
| G.711 PCMA (alaw) | ○ | ~87 kbps | フォールバック |
| iLBC / G.729 | × | — | baresip にモジュールなし。G.722＋Opus で足りているため見送り |

オファー順は G.722 → Opus → PCMU → PCMA。PBX 側の `allow=` 設定と合わせること。
PBX に Opus トランスコーダがない構成では、opus⇔ulaw 混在は切断されるため両端を統一する。

## キー配置

### ソフトキー

画面下部の3キー。状態により表示・機能が変わる。

| 画面 | SK1（左） | SK2（中央） | SK3（右） |
|---|---|---|---|
| メイン | 音量▲ | 設定 | 音量▼ |
| 通話中 | 音量▲ | ミュート切替 | 音量▼ |
| 着信中 | 拒否 | 応答 | — |
| 発信中 | 発信取消 | — | — |
| 設定 | 初期設定に戻す | 保存 | 保存 |

### 物理キー

| キー | keyCode | メイン画面 | 通話中 |
|---|---|---|---|
| 左上 (F1) | 59 | 音量▲ | 設定通り |
| 右上 (F2) | 60 | 設定 | 設定通り |
| 左下 (F3) | 61 | 音量▼ | 設定通り |
| 右下 (F4) | 62 | 無効 | 設定通り |
| 赤い電話ボタン | 5 (CALL) | 発信 / 応答 | 終話 |
| 数字キー (0-9) | 7-16 | 番号入力 | DTMF |
| クリアボタン | 4 (BACK) | 1文字削除 | 終話 |

※終話ボタンはシステムが消費するためアプリで使用不可。通話終了はクリアボタンまたは赤電話ボタン。
※決定キー（DPAD_CENTER）は通話中の誤切断防止のため明示的に無視。
※ソフトキーイベントは機種差があり、KYF39（API 22）は SK1=131 / SK2=132、KYF42 は SK1=132 / SK2=133。

### ソフトキー実装ガイド

京セラの `KCfpSoftkeyGuide` API をリフレクションで制御する
（ライブラリ未インストールでもクラッシュしないため）。

```kotlin
// 1. ソフトキーガイドを取得
val guideClass = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide")
val getMethod = guideClass.getMethod("getSoftkeyGuide", Window::class.java)
val guide = getMethod.invoke(null, window) ?: return

// 2. テキスト設定
val setText = guideClass.getMethod("setText", Int::class.java, CharSequence::class.java)
setText.invoke(guide, 1, "左のテキスト")  // SK1
setText.invoke(guide, 2, "中央のテキスト") // SK2
setText.invoke(guide, 3, "右のテキスト")   // SK3

// 3. 有効/無効設定
val setEnabled = guideClass.getMethod("setEnabled", Int::class.java, Boolean::class.java)
setEnabled.invoke(guide, 1, true)
setEnabled.invoke(guide, 2, true)
setEnabled.invoke(guide, 3, false) // 無効

// 4. 再描画
val invalidate = guideClass.getMethod("invalidate")
invalidate.invoke(guide)
```

注意事項：

- 利用不可の場合は `return` して無視。
- テキストは画面遷移ごとに再設定が必要。
- 通話中は状態別にキー表示を切り替える。

## 実機テスト

2台の KYF42 を USB 接続し、`kyf42-phone/test/` のスクリプトを使う。
SIP サーバのアドレス・内線番号・パスワードは実運用値を引数・環境変数で渡す
（リポジトリに保管しない）。

```bash
# デバイスのセットアップ（アプリ導入＋設定投入＋登録確認）
SERVER=<sip-server> ./kyf42-phone/test/setup-device.sh <serial> <extension> <password>

# E2E 通話テスト（発信→着信→応答→通話→終話、逆方向も）
DEVICE_A=<serial-a> DEVICE_B=<serial-b> \
  PBX_HOST=<ssh-target> PBX_CONTAINER=<container> \
  ./kyf42-phone/test/e2e-call-test.sh

# 長時間通話の安定性テスト（既定10分）
DEVICE_A=<serial-a> DEVICE_B=<serial-b> \
  PBX_HOST=<ssh-target> PBX_CONTAINER=<container> \
  ./kyf42-phone/test/long-call-test.sh [分数]
```

手動確認の要点：

| テスト | 確認項目 |
|---|---|
| SIP 登録 | 「登録済み」表示、`REGISTER_OK` ログ |
| 発信 | 発信側「発信中」、着信側「着信」表示 |
| 応答 | 両方「通話中」、タイマ計数、RTP 疎通 |
| 終話 | 両方「通話終了」、PBX のチャネル残留なし |
| DTMF | 通話中の番号押下が相手に届く |

ログ確認：`adb logcat -s NativeSip:V` 等。
メモリ確認：`adb shell dumpsys meminfo io.github.r_ch_iij.simplephone`（目安 ~8MB）。

## トラブルシュート

- SIP 登録失敗：VPN 未接続または SIP サーバ停止を疑う。VPN アプリの接続状態とサーバの稼働を確認。
- 音声が聞こえない：RTP ポートの到達性、`RECORD_AUDIO` 権限、オーディオ設定を確認。
- アプリがクラッシュ：`logcat` で JNI エラー・メモリ状況を確認。不要モジュールの無効化を検討。
- 通話終了後の着信失敗：端末側 SIP サービスの一時的な不調。時間をおくと復旧する。

## 残タスク

- G.722 対応：完了（spandsp サブセットをベンダリング）。
- iLBC / G.729 対応：見送り（G.722＋Opus で要求を満たす。128kbps 回線の実測で不足が出たら着手）。
- 状態管理の共通化・長い関数の分割・ViewModel 導入等のコード品質改善：長期課題。
- targetSdk 更新：Android 10 実機での動作確認が必須のため要検討（更新しない場合、Google Play 公開不可）。

## 作業履歴（完了分・抜粋）

- baresip / re / rem / mbedtls / opus / spandsp の NDK クロスコンパイルと JNI ブリッジ実装。
- ネイティブビルド最適化（TLS/X.509 除外、CMake 簡素化）。
- Opus・G.722 コーデック追加と実機での登録・通話確認。
- 10分間通話の安定性テスト合格。
- デフォルトのサーバ IP・パスワード・ユーザー名のハードコード除去、不要権限の削除、重複コードの解消。
