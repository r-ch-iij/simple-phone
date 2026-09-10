# KYF42 無効化済みアプリ一覧

## 無効化したアプリ（2026-08-18 時点）

| # | パッケージ名 | アプリ名 |
|---|---|---|
| 1 | `com.kddi.android.au_wifi_connect_f` | au Wi-Fi接続ツール |
| 2 | `com.mobisystems.office.kyocera` | OfficeSuite |
| 3 | `com.navitime.local.audrive` | au助手席ナビ |
| 4 | `com.navitime.local.naviwalk` | ナビwalk |
| 5 | `com.kddi.android.checker_android` | エリア通信品質レポート機能 |

## 元に戻すコマンド例

```bash
adb shell pm enable <パッケージ名>
```

## 全パッケージ名確認用

```bash
adb shell pm list packages -d
```

## インストールしたアプリ

| パッケージ名 | アプリ名 | 状態 |
|---|---|---|
| `eu.faircode.email` | FairEmail | インストール済（Mailボタンとの連携は未設定） |

## 注意事項

- 無効化は `pm disable-user` で行っている
- 過度な無効化はブートループの原因になる可能性あり（[XDA事例](https://xdaforums.com/t/kyf42-bootlooping-recovery-mode-no-command.4777387/)）
- 不安な場合は `pm hide` → `pm unhide` の方が復旧しやすい
- Mailボタン(KEY_F3)のキーリマップにはrootが必要（現状不可）
