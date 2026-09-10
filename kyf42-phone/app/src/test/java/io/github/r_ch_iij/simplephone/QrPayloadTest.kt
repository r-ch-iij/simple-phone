package io.github.r_ch_iij.simplephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * QrPayload.parse() の単体テスト。
 *
 * アプリが読み取る
 * sip:<内線>:<パスワード>@<ホスト>[:<ポート>][;realm=<value>] 形式の
 * ペイロードを正しくパースすることを担保する。
 *
 * テストケースはダミー値（RFC 5737 のドキュメント用アドレス）を使っている。
 */
class QrPayloadTest {

    // ===== 正常系 =====

    @Test
    fun `標準的なペイロードをパースできる`() {
        val result = QrPayload.parse("sip:203:example-pass-01@192.0.2.1:5060")
        assertNotNull(result)
        assertEquals("203", result!!.user)
        assertEquals("example-pass-01", result.password)
        assertEquals("192.0.2.1", result.host)
        assertEquals(5060, result.port)
        assertNull(result.realm)
    }

    @Test
    fun `ポート指定なしはデフォルト5060`() {
        val result = QrPayload.parse("sip:203:pass@192.0.2.1")
        assertNotNull(result)
        assertEquals(5060, result!!.port)
    }

    @Test
    fun `カスタムポートをパースできる`() {
        val result = QrPayload.parse("sip:203:pass@192.0.2.5:5080")
        assertNotNull(result)
        assertEquals(5080, result!!.port)
    }

    @Test
    fun `realm パラメータをパースできる`() {
        val result = QrPayload.parse("sip:203:pass@192.0.2.1:5060;realm=asterisk")
        assertNotNull(result)
        assertEquals("asterisk", result!!.realm)
    }

    @Test
    fun `realm 以外のパラメータは無視`() {
        val result = QrPayload.parse("sip:203:pass@192.0.2.1:5060;realm=asterisk;x=1;y=2")
        assertNotNull(result)
        assertEquals("asterisk", result!!.realm)
    }

    @Test
    fun `前後の空白をtrimしてパース`() {
        val result = QrPayload.parse("  sip:203:pass@192.0.2.1  ")
        assertNotNull(result)
        assertEquals("203", result!!.user)
    }

    @Test
    fun `別アドレスでもパースできる`() {
        val result = QrPayload.parse("sip:202:abc@198.51.100.10:5060")
        assertNotNull(result)
        assertEquals("202", result!!.user)
        assertEquals("198.51.100.10", result.host)
    }

    @Test
    fun `別サイトの内線情報をパースできる`() {
        val result = QrPayload.parse("sip:301:hexpw@203.0.113.10:5060")
        assertNotNull(result)
        assertEquals("301", result!!.user)
        assertEquals("203.0.113.10", result.host)
    }

    // ===== 異常系 =====

    @Test
    fun `sip スキーマ以外は null`() {
        assertNull(QrPayload.parse("https://example.com"))
    }

    @Test
    fun `空文字列は null`() {
        assertNull(QrPayload.parse(""))
    }

    @Test
    fun `@なしは null`() {
        assertNull(QrPayload.parse("sip:203:pass"))
    }

    @Test
    fun `パスワード空は null`() {
        assertNull(QrPayload.parse("sip:203:@192.0.2.1"))
    }

    @Test
    fun `ポートが範囲外（0）はデフォルトにフォールバック`() {
        val result = QrPayload.parse("sip:203:pass@192.0.2.1:0")
        assertNotNull(result)
        assertEquals(5060, result!!.port)
    }

    @Test
    fun `ポートが範囲外（99999）はデフォルトにフォールバック`() {
        val result = QrPayload.parse("sip:203:pass@192.0.2.1:99999")
        assertNotNull(result)
        assertEquals(5060, result!!.port)
    }

    @Test
    fun `ポートが数値でない場合はペイロード全体が無効`() {
        // :abc は正規表現の \d{1,5} にマッチせず、全体がパース失敗
        assertNull(QrPayload.parse("sip:203:pass@192.0.2.1:abc"))
    }

    // ===== realm の大文字小文字 =====

    @Test
    fun `realm は大文字小文字を無視`() {
        val result = QrPayload.parse("sip:203:pass@host:5060;Realm=Asterisk")
        assertNotNull(result)
        assertEquals("Asterisk", result!!.realm)
    }
}
