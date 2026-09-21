# SIP設定QRコード生成ツール

Simple Phoneが読み取れるSIP設定QRコードを生成する、GitHub Pages向けの静的Webアプリです。

入力内容とQRコード生成処理はすべてブラウザ内で完結します。SIPサーバー、ユーザー名、パスワード、realmを外部へ送信したり、Web StorageやURLへ保存したりしません。

## 対応するペイロード

```text
sip:<ユーザー名>:<パスワード>@<ホスト>:<ポート>[;realm=<realm>]
```

各値は必要に応じてパーセントエンコードされます。この形式はAndroidアプリの `QrPayload` が受け付ける形式と一致します。

## ローカル実行

Bun 1.4.2が必要です。

```bash
cd qrgen
bun ci
bun run dev
```

表示されたローカルURLをブラウザで開きます。

## テストとビルド

```bash
bun test
bun run build
```

静的ファイルは `dist/` に生成されます。`bun run preview` でビルド結果を確認できます。

## GitHub Pagesへの公開

`.github/workflows/pages.yml` は、`main` ブランチへのpush時にテストとビルドを実行し、生成物をGitHub Pagesへデプロイします。最初の公開前に、GitHubリポジトリの **Settings → Pages → Build and deployment → Source** を **GitHub Actions** に設定してください。

公開URLは通常、次の形式です。

```text
https://<owner>.github.io/<repository>/
```

プルリクエストではテストとビルドだけを行い、公開は行いません。

## 依存ライブラリ

- [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) 2.0.4（MIT License）
- [Vite](https://vite.dev/) 8.3.0（MIT License、開発・ビルド時のみ）
