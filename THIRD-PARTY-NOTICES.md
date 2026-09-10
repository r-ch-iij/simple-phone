# Third-Party Notices

このリポジトリには、自社コード（`LICENSE` の MIT が適用される範囲）のほか、
以下の第三者ライブラリを `kyf42-phone/native/` 配下に同梱しています。
各ライブラリにはそれぞれのライセンスが適用され、MIT より優先します。

## re / rem / baresip — BSD-3-Clause

- 上流: https://github.com/baresip/re / https://github.com/baresip/rem / https://github.com/baresip/baresip
- 同梱: フォーク版（re `9c625b0` / rem `edbdc31` / baresip `9bee1e0`、いずれも baresip 3.10.0 系統）
- ライセンス本文: `native/re/LICENSE`、`native/rem/LICENSE`、`native/baresip/LICENSE`
- 著作物表示（バイナリ配布時の再掲を含む）:
  `Copyright (C) 2020 - 2024, Baresip Foundation` ほか各 `LICENSE` 記載の保持者。

## Mbed TLS 2.28.8 — Apache-2.0（選択）

- 上流: https://github.com/Mbed-TLS/mbedtls
- 同梱: フォーク版（`5a764e5`）
- Mbed TLS は Apache-2.0 **OR** GPL-2.0-or-later の選択式です。本リポジトリでは **Apache-2.0** を選択しています。
- ライセンス本文: `native/mbedtls/LICENSE`（両ライセンスの全文を含む）
- 実際にリンクするのは `libmbedcrypto`（暗号・ハッシュ）のみです。

## Opus 1.4 — BSD-3-Clause

- 上流: https://github.com/xiph/opus
- ライセンス本文: `native/opus-1.4/COPYING`
- 著作物表示: Xiph.Org Foundation ほか各ソースファイルのヘッダ記載の保持者。

## SpanDSP（G.722 サブセットのみ）— LGPL-2.1

- 上流: https://github.com/freeswitch/spandsp（原著作者 Steve Underwood）
- 同梱: G.722 に必要なファイルのみ抜粋（`native/spandsp/src/g722.c`、`alloc.c`、`vector_int.c` ほかヘッダ）
- ライセンス本文: `native/spandsp/COPYING.LIB`（LGPL-2.1 全文）
- `libspandsp.a` として静的リンクしています。LGPL-2.1 第6条に基づき、
  APK と本リポジトリのソース一式を合わせて提供することで再リンク手段を確保しています。
- 本ライブラリに対する改変を行った場合は、その旨をソース内に明示します。

## 配布していないもの

- Android NDK / SDK / OpenSL ES はビルド環境・端末側システムのものであり、本リポジトリには含みません。
