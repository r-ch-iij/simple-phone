import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildSipPayload,
  maskSipPayload,
  normalizeSipSettings,
  SipSettingsError,
} from '../src/sip-uri.js'

test('Simple Phoneが受け付ける標準ペイロードを生成する', () => {
  assert.equal(buildSipPayload({
    server: '192.0.2.1',
    port: '5060',
    user: '203',
    password: 'example-pass-01',
    realm: '',
  }), 'sip:203:example-pass-01@192.0.2.1:5060')
})

test('realmを末尾パラメータとして追加する', () => {
  assert.equal(buildSipPayload({
    server: 'pbx.example.com',
    port: 5080,
    user: '301',
    password: 'secret',
    realm: 'asterisk',
  }), 'sip:301:secret@pbx.example.com:5080;realm=asterisk')
})

test('区切り文字とUnicodeをURLエンコードする', () => {
  assert.equal(buildSipPayload({
    server: 'pbx.example.com',
    port: 5060,
    user: '内線@1',
    password: 'p@ss/word;秘密',
    realm: '認証 realm',
  }), 'sip:%E5%86%85%E7%B7%9A%401:p%40ss%2Fword%3B%E7%A7%98%E5%AF%86@pbx.example.com:5060;realm=%E8%AA%8D%E8%A8%BC%20realm')
})

test('ホストとrealmだけ前後の空白を除去し認証情報は保持する', () => {
  assert.deepEqual(normalizeSipSettings({
    server: ' pbx.example.com ',
    port: '5060',
    user: ' 203 ',
    password: ' secret ',
    realm: ' asterisk ',
  }), {
    server: 'pbx.example.com',
    port: 5060,
    user: ' 203 ',
    password: ' secret ',
    realm: 'asterisk',
  })
})

test('範囲外ポートを拒否する', () => {
  assert.throws(
    () => buildSipPayload({ server: 'host', port: 65536, user: '203', password: 'secret' }),
    (error) => error instanceof SipSettingsError && error.field === 'port',
  )
})

test('スキームやパスを含むサーバー入力を拒否する', () => {
  for (const server of ['sip://pbx.example.com', 'pbx.example.com/path', 'user@pbx.example.com']) {
    assert.throws(
      () => buildSipPayload({ server, port: 5060, user: '203', password: 'secret' }),
      (error) => error instanceof SipSettingsError && error.field === 'server',
    )
  }
})

test('プレビューではパスワードだけを隠す', () => {
  assert.equal(
    maskSipPayload('sip:203:example-pass-01@192.0.2.1:5060;realm=asterisk'),
    'sip:203:••••••••••••@192.0.2.1:5060;realm=asterisk',
  )
})
