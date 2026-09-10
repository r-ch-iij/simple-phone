# simple-phone

au GRATINA KYF42（Android 10 / API 29）向けの SIP 電話アプリと関連ドキュメントのリポジトリ。
物理キーのみで操作できる最小限の SIP クライアントを提供する。

## 構成

- `kyf42-phone/` — SIP クライアント本体（Kotlin + NDK/C）
- `kyf42-apks/` — KYF42 導入用の補助 APK 置き場
- `docs/` — KYF42 関連ドキュメント
- `Dockerfile` / `docker-build.sh` — APK ビルド用
- `KYF42_disabled_apps.md` — KYF42 の無効化済みアプリ記録
- `output/` — ビルド成果物（git 管理外）

## ビルド手順

```bash
cd kyf42-phone
./build-apk.sh
# または Docker で
./docker-build.sh [debug|release]
```

詳細は `docs/kyf42-phone-dev.md` を参照。

## ドキュメント

- KYF42 簡易 SIP クライアント開発計画: `docs/kyf42-simple-phone.md`
- 純正電話アプリでの SIP 接続手順: `docs/kyf42-garaho-sip.md`
- SIP 接続調査記録: `docs/kyf42-sip-investigation.md`
- 電話アプリの使い方（利用者向け）: `docs/kyf42-phone-manual.md`
- 低速モバイル回線での VoIP 可否: `docs/low-speed-mobile-voip.md`
- アプリ開発者ノート（ビルド・実機検証・キー配置）: `docs/kyf42-phone-dev.md`

SIP サーバのアドレス・内線番号・パスワード等の実運用値はリポジトリに保管しない。
設定はアプリの設定画面または QR 読み取りで行う。

## KYF42 電話アプリ（kyf42-phone）の技術スタック

`au GRATINA KYF42`（Android 10 / API 29）向けの SIP クライアント。Android 標準の
`android.net.sip` API は使用せず、C 言語の SIP スタックを NDK でビルドし、JNI 経由で
呼び出しています。音声入出力は Android OpenSL ES を直接制御します。

### 使用ライブラリ

| 区分 | ライブラリ | バージョン | 用途 |
|------|-----------|-----------|------|
| SIP スタック | **baresip** | 3.10.0（fork 9bee1e0） | SIP UA / 通話制御 / メディアネゴシエーション |
| ポータブル基盤 | **re**（baresip 付属） | fork 9c625b0 | イベントループ / ソケット / ハッシュ / SIP メッセージ等 |
| メディア基盤 | **rem**（baresip 付属） | fork edbdc31 | オーディオバッファ / リサンプル / コーデック補助 |
| TLS / 暗号 | **Mbed TLS** | 2.28.8（fork 5a764e5） | SIP over TLS 用の暗号・ハッシュ（mbedTLS v2.28 API に対応） |
| オーディオ | **Android OpenSL ES** | NDK 標準 API | 再生・録音（PCM デジタルゲインで音量増幅） |
| UI フレームワーク | **AndroidX** | core-ktx 1.10.1 / appcompat 1.6.1 / material 1.9.0 | 設定画面・互換 UI |
| 言語 | **Kotlin** | 1.9.0 | アプリ実装（JVM target 1.8） |

### ビルド環境

| ツール | バージョン |
|--------|-----------|
| Android Gradle Plugin | 8.1.0 |
| Gradle | 8.5 |
| CMake | 3.22.1 |
| Android NDK | 26.1.10909125 |
| compileSdk / targetSdk / minSdk | 34 / 29 / 28 |
| ABI | armeabi-v7a（32bit） |

### ネイティブライブラリの構成

`app/src/main/cpp/` の JNI ブリッジ（`native-sip-jni.c`）が上記 C ライブラリ群をまとめ、
`libnative-sip.so` としてビルドされます。ネイティブ側はあらかじめ静的ライブラリとして
ビルドされ `native/build_{re,rem,baresip}_final/lib*.a` に配置、CMake からリンクします。

* `libre.a` — re
* `librem.a` — rem
* `libbaresip.a` — baresip（opensles モジュール・ g711 コーデックを含むよう手動で統合）
* `libmbedtls.a` / `libmbedcrypto.a` / `libmbedx509.a` — Mbed TLS

> baresip / re / rem / mbedtls はいずれもフォーク版を `native/` 配下に vendoring しています
> （`.gitmodules` は未使用）。ビルド手順は `native/build_baresip_manual.sh` を参照。
