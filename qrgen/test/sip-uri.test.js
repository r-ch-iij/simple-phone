import { expect, test } from 'bun:test'

import {
  buildSipPayload,
  maskSipPayload,
  normalizeSipSettings,
  SipSettingsError,
} from '../src/sip-uri.js'

function expectFieldError(callback, field) {
  let thrown
  try {
    callback()
  } catch (error) {
    thrown = error
  }
  expect(thrown).toBeInstanceOf(SipSettingsError)
  expect(thrown.field).toBe(field)
}

test('Simple Phoneが受け付ける標準ペイロードを生成する', () => {
  expect(buildSipPayload({
    server: '192.0.2.1',
    port: '5060',
    user: '203',
    password: 'example-pass-01',
    realm: '',
  })).toBe('sip:203:example-pass-01@192.0.2.1:5060')
})

test('realmを末尾パラメータとして追加する', () => {
  expect(buildSipPayload({
    server: 'pbx.example.com',
    port: 5080,
    user: '301',
    password: 'secret',
    realm: 'asterisk',
  })).toBe('sip:301:secret@pbx.example.com:5080;realm=asterisk')
})

test('区切り文字とUnicodeをURLエンコードする', () => {
  expect(buildSipPayload({
    server: 'pbx.example.com',
    port: 5060,
    user: '内線@1',
    password: 'p@ss/word;秘密',
    realm: '認証 realm',
  })).toBe('sip:%E5%86%85%E7%B7%9A%401:p%40ss%2Fword%3B%E7%A7%98%E5%AF%86@pbx.example.com:5060;realm=%E8%AA%8D%E8%A8%BC%20realm')
})

test('ホストとrealmだけ前後の空白を除去し認証情報は保持する', () => {
  expect(normalizeSipSettings({
    server: ' pbx.example.com ',
    port: '5060',
    user: ' 203 ',
    password: ' secret ',
    realm: ' asterisk ',
  })).toEqual({
    server: 'pbx.example.com',
    port: 5060,
    user: ' 203 ',
    password: ' secret ',
    realm: 'asterisk',
  })
})

test('範囲外ポートを拒否する', () => {
  expectFieldError(
    () => buildSipPayload({ server: 'host', port: 65536, user: '203', password: 'secret' }),
    'port',
  )
})

test('スキームやパスを含むサーバー入力を拒否する', () => {
  for (const server of ['sip://pbx.example.com', 'pbx.example.com/path', 'user@pbx.example.com']) {
    expectFieldError(
      () => buildSipPayload({ server, port: 5060, user: '203', password: 'secret' }),
      'server',
    )
  }
})

test('プレビューではパスワードだけを隠す', () => {
  expect(
    maskSipPayload('sip:203:example-pass-01@192.0.2.1:5060;realm=asterisk'),
  ).toBe('sip:203:••••••••••••@192.0.2.1:5060;realm=asterisk')
})
