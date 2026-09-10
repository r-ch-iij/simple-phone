# au GRATINA KYF42 から Asterisk へ SIP 接続する

## この文書の目的

この文書は、京セラ製の Android 搭載ガラホ **au GRATINA KYF42**（以下 KYF42）を Asterisk の内線端末として使う手順をまとめたものです。
KYF42 の純正電話アプリは SIP クライアントを内蔵しており、Asterisk に登録して発着信できます。
この文書では、構成の全体像、必要な APK の入手先、KYF42 へのインストール手順、Asterisk 側の設定、VPN の方式選択を順に示します。

## KYF42 の仕様上の制約

KYF42 は Android 10 ベースのガラホで、CPU は 32bit（armeabi-v7a）です。
そのため、**32bit 対応の APK しか動作しません**。
また Google Play ストアが利用できないため、アプリは APK をサイドロードしてインストールします。
サイドロードには、KYF42 側で「提供元不明のアプリ」の許可が必要です。
ファイルの転送は Bluetooth か USB-C 経由が現実的です。

## 接続の全体像

構成は次のとおりです。

```
[KYF42 純正電話アプリ（SIP）] <-> [VPN トンネル] <-> [Asterisk]
```

純正電話アプリの SIP 設定は、Android 標準の「通話アカウント」画面に隠れています。
この画面は通常のメニューからは開けないため、**QuickShortcutMaker** で直接起動します。
VPN は必ずしも必要ではありません。
KYF42 が Asterisk と同じ LAN にいるなら、SIP サーバに LAN 内の IP アドレスを指定するだけで接続できます。
VPN が必要になるのは、外出先の LTE などから自宅の Asterisk に接続する場合です。

## Tailscale と WireGuard の選択

VPN には Tailscale と WireGuard のどちらも使えます。
両者とも 32bit 対応のアプリが存在し、KYF42 で動作します。
違いは NAT 越えの方式と、それに伴うサーバ側の要件です。

| 項目 | Tailscale | WireGuard |
|------|-----------|-----------|
| NAT 越え | 自動（リレーサーバ込み） | なし（素の UDP） |
| サーバ側の要件 | なし | グローバル IP か UDP のポート開放 |
| アカウント | Tailscale のアカウントが必要 | 不要（鍵の交換のみ） |
| 設定の手間 | 低い | 中程度 |

**Tailscale** は、制御サーバを介して NAT の向こう同士を接続し、直接通信できない場合にはリレーサーバを経由します。
そのため、自宅ルータでポートを開放しなくても外から Asterisk に届きます。
アカウントが必要で、かつ制御プレーンを外部（Tailscale 社、または自己管理のヘッドスケール）に依存します。
**WireGuard** は素の UDP のトンネルです。
NAT 越えの仕組みを持たないため、Asterisk サーバ側に到達可能なアドレスが必要です。
具体的には、グローバル IP を持っているか、自宅ルータでトンネル用の UDP ポートを開放するかのどちらかです。
ポートを開放できない環境では、VPN サーバとして使える VPS を中継に入れる方法もあります。
SIP を安定させるため、どちらの方式でも次の二点を守ります。
まず、Android の「常時接続 VPN」を有効にして、待受け中もトンネルを維持します。
次に WireGuard では、PersistentKeepalive を 25 秒に設定します。
これにより、NAT のマッピングが切れにくくなります。

## 取得した APK

次の APK を別途入手し、`kyf42-apks/` に置いて使います（再配布物のため git 管理外）。

| ファイル | アプリ | バージョン | 用途 | 入手元 |
|----------|--------|------------|------|--------|
| tailscale-android-universal.apk | Tailscale | 1.102.2 | VPN（Tailscale 方式） |  |
| quickshortcutmaker.apk | QuickShortcutMaker | 2.5.0 | 通話アカウント画面の起動 |  |
| wireguard-1.0.20260315-armv7.xapk | WireGuard | 1.0.20260315 | VPN（WireGuard 方式） | |
| sai-4.5.apk | SAI | 4.5 | XAPK のインストーラ | GitHub 公式リリース |

それぞれの SHA256 チェックサムは次のとおりです。

```
01c8ad2016155fcd61cbb9e02821ba831a71ad9495eb2154c2da4830181b71f0  tailscale-android-universal.apk
9163196fc421af91ed8cc94ba593e6cc21762e12993964373e8b2738895ff38d  quickshortcutmaker.apk
953688c3f6ccbad8f32bf3ab0bb27662bbc564d3a18d184933069c70099d027e  wireguard-1.0.20260315-armv7.xapk
632ce65cc6cde1fb5704375b18bccffd0bfa4d476385375ab8088bc21f5f8f11  sai-4.5.apk
```

## KYF42 へのインストール

1. 設定 → その他の設定 → セキュリティ → 提供元不明のアプリ を有効にします
2. APK を Bluetooth で KYF42 に転送します（送信側のスマホではファイルマネージャから共有 → Bluetooth を選びます）
3. KYF42 の通知から受信したファイルを開き、インストールを実行します
4. XAPK（WireGuard）は通常の操作ではインストールできないため、先に SAI をインストールし、SAI から XAPK を開いてインストールします

受信後のインストール画面は、すぐに操作しないと閉じる場合があります。

ファイル受信の通知が表示されているうちに「開く」を選んでください。

## PBX 側の設定例

内線番号を 6001 とする例です。お使いの PBX の形式に合わせて読み替えてください。

```ini
; config/pjsip/internal/kyf42.conf
[6001]
type=endpoint
transport=transport-v4udp
context=default
disallow=all
allow=ulaw,alaw
language=ja
auth=6001
aors=6001
direct_media=no
rtp_symmetric=yes
force_rport=yes
rewrite_contact=yes

[6001]
type=auth
auth_type=userpass
username=6001
password=<内線のパスワード>

[6001]
type=aor
max_contacts=1
qualify_frequency=60
```

ガラホや NAT 越えでは、**direct_media=no** にして音声を必ず Asterisk 経由にします。

ダイヤルプランは次の例を参考にします。

```ini
; config/extensions/internal/kyf42.conf
exten => 6001,1,Dial(PJSIP/6001)
```

設定の反映は、お使いの PBX の手順で行います（例: PJSIP とダイヤルプランの reload）。

## 純正電話アプリへの SIP アカウント登録

SIP 設定画面は adb から直接開けます（QuickShortcutMaker は不要でした）。

```
adb shell am start -n com.android.phone/com.android.services.telephony.sip.SipSettings
```

1. 一覧画面の右上「アカウントを追加」からアカウントを追加します
2. 編集画面（SIPアカウントの詳細）で次の項目を入力します
   * ユーザー名: 内線番号
   * パスワード: Asterisk 側で設定したもの
   * サーバー: VPN トンネル内の Asterisk のアドレス（WireGuard ならトンネル内 IP、Tailscale なら Tailscale アドレス）
3. 保存後、一覧のアカウント行のサマリが「通話着信ON」になることを確認します

### 最重要: 通話アカウント（PhoneAccount）の有効化

SIP アカウントの「着信を受ける」が ON でも、**Telecom の通話アカウントが無効だと着信を拒否**します。

この状態では Asterisk 側は 180（呼出中）を受信するのに端末は鳴らず、ログに次のエラーが出ます。

```
D SIP: [SipIncomingCallReceiver] takeCall, PhoneAccount is disabled. Not accepting incoming call...
```

有効化する手順は次のとおりです。

1. SIP 設定一覧 → アカウント行をタップ → 「SIPアカウントの詳細」を開きます
2. 「オプション設定 ▷タップしてすべて表示」をタップします
3. 通話アカウント一覧が開くので、SIP アカウント（例: 202）の**スイッチを ON** にします

状態確認は `adb shell dumpsys telecom` で行えます。

```
[[X] PhoneAccount: ...SipConnectionService...   ← X が付いていれば有効
[[ ] PhoneAccount: ...SipConnectionService...   ← 無効（着信拒否される）
```

## 実機での確認結果（2026-08-18）

ご利用のPBXに内線 202 を追加し（例）、次の手順で通話成立を確認しました。

- 開発者向けオプション: 設定 → その他の設定 → 端末情報 → ビルド番号を 7 回連打 → USB デバッグ ON
- APK は `adb install` で導入（QuickShortcutMaker、Tailscale）
- SIP 設定は上記の adb コマンドで直接開き、ユーザー名（例: 202）・パスワード・サーバー（ご利用のPBXアドレス）を登録
- 「通話アカウント」のスイッチ ON 後、Asterisk からの発信テストで着信・応答・通話（G.711）成立
- Linphone（内線 200）との相互発着信も成功

追加で判明した事項です。

- 着信の応答操作に癖があります。**受話器を持ち上げる方のボタン（通話・発信ボタン）で応答**します。決定（OK）ボタンで応答しようとすると終話になります
- 切断時の「ツーツー音」は失敗ではなく、切断トーン（Tone 17）です
- 通話終了時に SipConnectionService が died することがあり、直後の着信が失敗することがあります（時間をおくと復旧します）
- 200 の AOR には古いコンタクトが残ることがあり、`pjsip show aor` で Unavail が残る場合は無視して問題ありません（remove_unavailable は無効設定）

## 検証

Asterisk の CLI で登録状態を確認します。

```
pjsip show endpoints
pjsip show contacts
```

Contact に Registered と表示されれば登録成功です。

その後、別の内線から 6001 に掛けて着信し、通話と音声の確認を行います。

## トラブルシュート

- 着信しない
  - 「通話アカウント」の SIP アカウントのスイッチが ON か確認します（上記「最重要」節）
  - SIP 登録が切れている可能性があります。常時接続 VPN が有効か、トンネルが維持されているかを確認してください
- 発信側の電話機が発信音のまま
  - 発信側の SIP フォンのダイヤル方法の問題です。番号入力後に発信ボタンを押す、またはダイヤルプラン（桁数認識）の設定を確認します
- 音声が片方向
  - direct_media=no と rtp_symmetric=yes の設定を確認してください
- バッテリーの減りが早い
  - 32bit 端末では WireGuard がユーザー空間実装（wireguard-go）で動くため、通話中の負荷は高めです。待受け時は軽いです

## 参考

- 元記事: Asterisk×ガラケーで家の電話を純正アプリから送受信したい！！（Qiita）
  https://qiita.com/nagashima-shohei/items/8231c91b50061f75d62e
- KYF39/KYF42 へのアプリインストール方法（Bluetooth 転送）
  https://garaphone.toku-mo.com/2019/09/au/kyf39/113/
- KYF42 の 32bit 制約と APK の動作報告
  https://w.atwiki.jp/kapper1224/pages/194.html
- Tailscale の Android APK 配布ページ
  https://pkgs.tailscale.com/stable/
- WireGuard の Android アプリ
  https://www.wireguard.com/install/
