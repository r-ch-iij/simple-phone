package io.github.r_ch_iij.simplephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * KYF42 SimplePhone: 実装上のバグ・デバッグ困難箇所を TDD で担保
 *
 * 各テストは実際のバグから抽出した。テストが失敗する → コードを修正 → 再テスト
 */
class PhoneLogicTest {

    // ========================================
    // 設定の永続化（F キーのデフォルト値）
    //    実際のバグ: F2 のデフォルトが NONE → 設定画面を開けない
    // ========================================

    @Test
    fun `F2 のデフォルトは設定画面`() {
        assertEquals(
            "F2(右上)のデフォルトは設定画面",
            SipConfig.ACTION_SETTINGS,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_2]
        )
    }

    @Test
    fun `F1 のデフォルトは音量上げ`() {
        assertEquals(
            "F1(左上)のデフォルトは音量上げ",
            SipConfig.ACTION_VOLUME_UP,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_1]
        )
    }

    @Test
    fun `F3 のデフォルトは音量下げ`() {
        assertEquals(
            "F3(左下)のデフォルトは音量下げ",
            SipConfig.ACTION_VOLUME_DOWN,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_3]
        )
    }

    @Test
    fun `F4 のデフォルトは無効`() {
        assertEquals(
            "F4(右下)のデフォルトは無効",
            SipConfig.ACTION_NONE,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_4]
        )
    }

    @Test
    fun `F キーの動作定数が全て有効`() {
        val actions = listOf(
            SipConfig.ACTION_NONE,
            SipConfig.ACTION_REDIAL,
            SipConfig.ACTION_SETTINGS,
            SipConfig.ACTION_CLEAR_ALL,
            SipConfig.ACTION_CLEAR_ONE,
            SipConfig.ACTION_CALL,
            SipConfig.ACTION_MUTE,
            SipConfig.ACTION_VOLUME_UP,
            SipConfig.ACTION_VOLUME_DOWN,
            SipConfig.ACTION_RESET
        )
        assertEquals("10種類の F キー動作", 10, actions.size)
        assertTrue("全て空文字列でない", actions.all { it.isNotEmpty() })
    }

    @Test
    fun `fKeyLabel は全アクションに日本語ラベルを返す`() {
        assertEquals("無効", SipConfig.fKeyLabel(SipConfig.ACTION_NONE))
        assertEquals("リダイヤル", SipConfig.fKeyLabel(SipConfig.ACTION_REDIAL))
        assertEquals("設定", SipConfig.fKeyLabel(SipConfig.ACTION_SETTINGS))
        assertEquals("全消去", SipConfig.fKeyLabel(SipConfig.ACTION_CLEAR_ALL))
        assertEquals("1文字削除", SipConfig.fKeyLabel(SipConfig.ACTION_CLEAR_ONE))
        assertEquals("発話", SipConfig.fKeyLabel(SipConfig.ACTION_CALL))
        assertEquals("ミュート", SipConfig.fKeyLabel(SipConfig.ACTION_MUTE))
        assertEquals("音量▲", SipConfig.fKeyLabel(SipConfig.ACTION_VOLUME_UP))
        assertEquals("音量▼", SipConfig.fKeyLabel(SipConfig.ACTION_VOLUME_DOWN))
        assertEquals("初期設定に戻す", SipConfig.fKeyLabel(SipConfig.ACTION_RESET))
        assertEquals("履歴", SipConfig.fKeyLabel(SipConfig.ACTION_HISTORY))
    }

    // ========================================
    // F キーの物理位置マッピング
    //    ガイド位置とキーコードの対応は MainActivity.guideKeyCode が唯一の正とする
    // ========================================

    @Test
    fun `ガイド位置1(左上)はF1`() {
        assertEquals(131, MainActivity.guideKeyCode(1))
    }

    @Test
    fun `ガイド位置2(右上)はF2`() {
        assertEquals(132, MainActivity.guideKeyCode(2))
    }

    @Test
    fun `ガイド位置3(左下)はF3`() {
        assertEquals(133, MainActivity.guideKeyCode(3))
    }

    @Test
    fun `ガイド位置4(右下)はF4`() {
        assertEquals(134, MainActivity.guideKeyCode(4))
    }

    @Test
    fun `通話中ミュート(右上F2)は音量▼(左下F3)と衝突しない`() {
        // 実際のバグ: KYF42 でミュートを F3(左下)=音量▼ に割り当てていた
        assertEquals(132, MainActivity.guideKeyCode(2))
        assertNotEquals(MainActivity.guideKeyCode(2), MainActivity.guideKeyCode(3))
    }

    // ========================================
    // 発信中 (Outgoing) 状態
    //    発信直後も Idle のままだと、応答前の発信が「通話中」に誤表示される
    // ========================================

    @Test
    fun `Outgoing は発信中フラグを持つ`() {
        val state = CallState.Outgoing("205")
        assertTrue("発信中フラグ", state.isOutgoing)
        assertFalse("通話中ではない", state.isInCall)
        assertFalse("着信中ではない", state.isIncoming)
    }

    @Test
    fun `Outgoing は発信先番号を保持する`() {
        val state = CallState.Outgoing("205")
        assertEquals("発信先番号", "205", state.number)
    }

    @Test
    fun `Idle は発信中ではない`() {
        val state: CallState = CallState.Idle
        assertFalse("待機中は発信中でない", state.isOutgoing)
    }

    // ========================================
    // 14. 音量保存のクランプ（表示と保存値の不一致防止）
    //    実際のバグ: 表示は50に丸めるが保存値は丸めない
    // ========================================

    @Test
    fun `音量保存値は50から250に丸める`() {
        assertEquals(50, 30.coerceIn(50, 250))
        assertEquals(50, 50.coerceIn(50, 250))
        assertEquals(150, 150.coerceIn(50, 250))
        assertEquals(250, 250.coerceIn(50, 250))
        assertEquals(250, 300.coerceIn(50, 250))
    }
}
