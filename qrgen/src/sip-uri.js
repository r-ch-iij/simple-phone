const DEFAULT_PORT = 5060

export class SipSettingsError extends Error {
  constructor(field, message) {
    super(message)
    this.name = 'SipSettingsError'
    this.field = field
  }
}

function required(value, field, message, trim = true) {
  const original = String(value ?? '')
  if (!original.trim()) throw new SipSettingsError(field, message)
  return trim ? original.trim() : original
}

function encode(value, field) {
  try {
    return encodeURIComponent(value)
  } catch {
    throw new SipSettingsError(field, 'この文字列はQRコードに変換できません')
  }
}

export function normalizeSipSettings(input) {
  const server = required(input.server, 'server', 'SIPサーバーを入力してください')
  const user = required(input.user, 'user', '内線番号またはユーザー名を入力してください', false)
  const password = required(input.password, 'password', 'SIPパスワードを入力してください', false)
  const portText = required(input.port ?? DEFAULT_PORT, 'port', 'ポートを入力してください')
  const port = Number(portText)

  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new SipSettingsError('port', '1〜65535の整数を入力してください')
  }
  if (/\s/.test(server)) {
    throw new SipSettingsError('server', '空白を含まないホスト名またはIPアドレスを入力してください')
  }
  if (/^(?:sips?|https?):\/\//i.test(server)) {
    throw new SipSettingsError('server', 'sip: などを付けず、ホスト名だけを入力してください')
  }
  if (server.includes('@') || server.includes('/') || server.includes(';')) {
    throw new SipSettingsError('server', 'ホスト名またはIPアドレスだけを入力してください')
  }

  return {
    server,
    port,
    user,
    password,
    realm: String(input.realm ?? '').trim(),
  }
}

export function buildSipPayload(input) {
  const settings = normalizeSipSettings(input)
  const base = `sip:${encode(settings.user, 'user')}:${encode(settings.password, 'password')}@${encode(settings.server, 'server')}:${settings.port}`
  return settings.realm ? `${base};realm=${encode(settings.realm, 'realm')}` : base
}

export function maskSipPayload(payload) {
  const match = payload.match(/^(sip:[^:]+:)([^@]+)(@.*)$/)
  return match ? `${match[1]}${'•'.repeat(Math.min(match[2].length, 12))}${match[3]}` : payload
}
