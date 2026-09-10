package io.github.r_ch_iij.simplephone

import java.net.URLDecoder

/**
 * QR コードのペイロード解析。
 *
 * アプリが読み取る SIP URI 形式のペイロードを [QrSipInfo] に変換する。
 *
 * 対応形式: `sip:<内線>:<パスワード>@<ホスト>[:<ポート>][;realm=<value>]`
 *
 * URL エンコードされた値（%40 → @ 等）も自動デコードする。
 */
data class QrSipInfo(
    val user: String,
    val password: String,
    val host: String,
    val port: Int,
    val realm: String?
)

object QrPayload {
    private const val DEFAULT_PORT = 5060

    private val regex = Regex(
        """^sip:([^:@/\s]+):([^@/\s;]+)@([^:@/;\s]+)(?::(\d{1,5}))?(?:;(.*))?$"""
    )

    fun parse(payload: String): QrSipInfo? {
        val m = regex.find(payload.trim()) ?: return null
        val user = decodeUrl(m.groupValues[1])
        val password = decodeUrl(m.groupValues[2])
        val host = decodeUrl(m.groupValues[3])
        if (user.isEmpty() || password.isEmpty() || host.isEmpty()) return null
        val port = m.groupValues[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        var realm: String? = null
        m.groupValues[5].takeIf { it.isNotEmpty() }?.split(';')?.forEach { param ->
            val kv = param.split('=', limit = 2)
            if (kv.size == 2 && kv[0].trim().equals("realm", ignoreCase = true)) {
                realm = decodeUrl(kv[1].trim())
            }
        }
        return QrSipInfo(user, password, host, port, realm)
    }

    private fun decodeUrl(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
