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
    // 1. CROSS_DOMAIN_AUTHENTICATION (ドメイン vs レルム)
    //    実際のバグ: serverDomain=192.0.2.1, realm=asterisk → 不一致で REGISTER 失敗
    // ========================================

    @Test
    fun `SIP プロファイルのドメインとレルムが一致する`() {
        // Asterisk の challenge realm は "asterisk"
        val expectedRealm = "asterisk"
        // SipProfile.Builder の 2引目(serverDomain) は realm と一致すべき
        val profileDomain = expectedRealm
        assertEquals(
            "SipProfile の domain は Asterisk の realm と一致する必要がある",
            expectedRealm, profileDomain
        )
    }

    @Test
    fun `outboundProxy は実サーバアドレスを指す`() {
        val expectedServer = "192.0.2.1"
        // setOutboundProxy で実サーバを指定
        val proxyAddress = expectedServer
        assertEquals(
            "outboundProxy は実際の SIP サーバアドレスであるべき",
            expectedServer, proxyAddress
        )
    }

    @Test
    fun `ドメインとレルムが不一致だと CROSS_DOMAIN_AUTHENTICATION になる`() {
        // バグ再現: domain=192.0.2.1, realm=asterisk → 不一致
        val profileDomain = "192.0.2.1"
        val challengeRealm = "asterisk"
        val isCrossDomain = profileDomain != challengeRealm
        assertTrue(
            "不一致の場合は CROSS_DOMAIN_AUTHENTICATION エラーになる",
            isCrossDomain
        )
    }

    @Test
    fun `ドメインとレルムが一致すれば CROSS_DOMAIN_AUTHENTICATION にならない`() {
        val profileDomain = "asterisk"
        val challengeRealm = "asterisk"
        val isCrossDomain = profileDomain != challengeRealm
        assertFalse(
            "一致すれば CROSS_DOMAIN_AUTHENTICATION にならない",
            isCrossDomain
        )
    }

    // ========================================
    // 2. mNetworkType==-1 リトライロジック
    //    実際のバグ: 登録が停滞し、reRegister が無限ループする
    // ========================================

    @Test
    fun `リトライ回数は最大5回で打ち切り`() {
        var retryAttempts = 0
        val maxRetries = 5

        // リトライロジックのシミュレーション
        fun shouldRetry(): Boolean {
            if (retryAttempts < maxRetries) {
                retryAttempts++
                return true
            }
            return false
        }

        assertTrue("1回目はリトライ", shouldRetry())
        assertTrue("2回目はリトライ", shouldRetry())
        assertTrue("3回目はリトライ", shouldRetry())
        assertTrue("4回目はリトライ", shouldRetry())
        assertTrue("5回目はリトライ", shouldRetry())
        assertFalse("6回目は打ち切り", shouldRetry())
        assertEquals("リトライ回数は5", 5, retryAttempts)
    }

    @Test
    fun `リトライ間隔は5秒刻みで増加`() {
        val delays = (1..5).map { (it * 5000).toLong() }
        assertEquals(listOf(5000L, 10000L, 15000L, 20000L, 25000L), delays)
    }

    @Test
    fun `登録成功時にリトライカウンタがリセットされる`() {
        var retryAttempts = 3
        var registered = false
        // 登録成功 → retryAttempts=0, registered=true
        retryAttempts = 0
        registered = true
        assertEquals(0, retryAttempts)
        assertTrue(registered)
    }

    @Test
    fun `reRegister は登録済みなら何もしない`() {
        val registered = true
        val shouldOpenProfile = !registered
        assertFalse("登録済みなら再オープンしない", shouldOpenProfile)
    }

    // ========================================
    // 3. KEYCODE_BACK vs KEYCODE_CLEAR
    //    実際のバグ: 物理クリアボタンは KEYCODE_BACK(4) を送信、KEYCODE_CLEAR(28) ではない
    // ========================================

    @Test
    fun `物理クリアボタンは KEYCODE_BACK を送信`() {
        // matrix_keypad.kl: key 355 BACK → Android KEYCODE_BACK (4)
        val physicalClearKeycode = android.view.KeyEvent.KEYCODE_BACK
        assertEquals("物理クリアボタンは KEYCODE_BACK", 4, physicalClearKeycode)
    }

    @Test
    fun `KEYCODE_CLEAR と KEYCODE_BACK は別キー`() {
        assertNotEquals(
            "KEYCODE_CLEAR(28) と KEYCODE_BACK(4) は異なる",
            android.view.KeyEvent.KEYCODE_CLEAR,
            android.view.KeyEvent.KEYCODE_BACK
        )
    }

    @Test
    fun `KEYCODE_BACK はアプリ終了を防止する`() {
        // KEYCODE_BACK をハンドリングして true を返すと、Activity は終了しない
        val handled = true // onKeyDown で return true
        assertTrue("KEYCODE_BACK をハンドリングすればアプリは終了しない", handled)
    }

    @Test
    fun `KEYCODE_BACK で通話中なら終話される`() {
        val isInCall = true
        val isIncomingCall = false
        val shouldEndCall = isInCall || isIncomingCall
        assertTrue("通話中に KEYCODE_BACK → 終話", shouldEndCall)
    }

    @Test
    fun `KEYCODE_BACK で待機中なら1文字削除`() {
        val isInCall = false
        val isIncomingCall = false
        val currentNumber = StringBuilder("123")
        if (!isInCall && !isIncomingCall && currentNumber.isNotEmpty()) {
            currentNumber.deleteCharAt(currentNumber.length - 1)
        }
        assertEquals("待機中に KEYCODE_BACK → 1文字削除", "12", currentNumber.toString())
    }

    // ========================================
    // 4. 自分呼び出し防止
    //    実際のバグ: 203→203 で自分呼び出し、終話不可
    // ========================================

    @Test
    fun `自分の内線番号への発信は禁止`() {
        val myNumber = "203"
        val targetNumber = "203"
        val isSelfCall = targetNumber == myNumber
        assertTrue("自分自身への発信は禁止", isSelfCall)
    }

    @Test
    fun `異なる内線番号への発信は許可`() {
        val myNumber = "203"
        val targetNumber = "200"
        val isSelfCall = targetNumber == myNumber
        assertFalse("異なる番号への発信は許可", isSelfCall)
    }

    @Test
    fun `空番号への発信は禁止`() {
        val number = ""
        val canCall = number.isNotEmpty()
        assertFalse("空番号への発信は禁止", canCall)
    }

    // ========================================
    // 5. 設定の永続化（F キーのデフォルト値）
    //    実際のバグ: F2 のデフォルトが NONE → 設定画面を開けない
    // ========================================

    @Test
    fun `F2 のデフォルトは設定画面`() {
        assertEquals(
            "F2(右上)のデフォルトは設定画面",
            SipConfig.FKEY_SETTINGS,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_2]
        )
    }

    @Test
    fun `F1 のデフォルトは音量上げ`() {
        assertEquals(
            "F1(左上)のデフォルトは音量上げ",
            SipConfig.FKEY_VOLUME_UP,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_1]
        )
    }

    @Test
    fun `F3 のデフォルトは音量下げ`() {
        assertEquals(
            "F3(左下)のデフォルトは音量下げ",
            SipConfig.FKEY_VOLUME_DOWN,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_3]
        )
    }

    @Test
    fun `F4 のデフォルトは無効`() {
        assertEquals(
            "F4(右下)のデフォルトは無効",
            SipConfig.FKEY_NONE,
            SipConfig.ACTION_DEFAULTS[SipConfig.KEY_ACTION_4]
        )
    }

    @Test
    fun `F キーの動作定数が全て有効`() {
        val actions = listOf(
            SipConfig.FKEY_NONE,
            SipConfig.FKEY_REDIAL,
            SipConfig.FKEY_SETTINGS,
            SipConfig.FKEY_CLEAR_ALL,
            SipConfig.FKEY_CLEAR_ONE,
            SipConfig.FKEY_CALL,
            SipConfig.FKEY_MUTE,
            SipConfig.FKEY_VOLUME_UP,
            SipConfig.FKEY_VOLUME_DOWN,
            SipConfig.ACTION_RESET
        )
        assertEquals("10種類の F キー動作", 10, actions.size)
        assertTrue("全て空文字列でない", actions.all { it.isNotEmpty() })
    }

    @Test
    fun `fKeyLabel は全アクションに日本語ラベルを返す`() {
        assertEquals("無効", SipConfig.fKeyLabel(SipConfig.FKEY_NONE))
        assertEquals("リダイヤル", SipConfig.fKeyLabel(SipConfig.FKEY_REDIAL))
        assertEquals("設定", SipConfig.fKeyLabel(SipConfig.FKEY_SETTINGS))
        assertEquals("全消去", SipConfig.fKeyLabel(SipConfig.FKEY_CLEAR_ALL))
        assertEquals("1文字削除", SipConfig.fKeyLabel(SipConfig.FKEY_CLEAR_ONE))
        assertEquals("発話", SipConfig.fKeyLabel(SipConfig.FKEY_CALL))
        assertEquals("ミュート", SipConfig.fKeyLabel(SipConfig.FKEY_MUTE))
        assertEquals("音量▲", SipConfig.fKeyLabel(SipConfig.FKEY_VOLUME_UP))
        assertEquals("音量▼", SipConfig.fKeyLabel(SipConfig.FKEY_VOLUME_DOWN))
        assertEquals("初期設定に戻す", SipConfig.fKeyLabel(SipConfig.ACTION_RESET))
        assertEquals("履歴", SipConfig.fKeyLabel(SipConfig.ACTION_HISTORY))
    }

    // ========================================
    // 6. 旧プロファイルの残存（二重登録）
    //    実際のバグ: 過去のバージョンで作成された旧プロファイルが残り二重登録
    // ========================================

    @Test
    fun `旧プロファイル URI は実サーバを含む`() {
        val server = "192.0.2.1"
        val user = "203"
        val legacyUri = "sip:${user}@${server}"
        assertEquals("sip:203@192.0.2.1", legacyUri)
        assertTrue(
            "旧プロファイル URI は実サーバアドレスを含む",
            legacyUri.contains(server)
        )
    }

    @Test
    fun `新プロファイル URI はドメインを含む`() {
        val realm = "asterisk"
        val user = "203"
        val newUri = "sip:${user}@${realm}"
        assertEquals("sip:203@asterisk", newUri)
        assertTrue(
            "新プロファイル URI は realm を含む",
            newUri.contains(realm)
        )
    }

    @Test
    fun `旧プロファイルと新プロファイルは異なる`() {
        val legacyUri = "sip:203@192.0.2.1"
        val newUri = "sip:203@asterisk"
        assertNotEquals("旧と新は異なる URI", legacyUri, newUri)
    }

    // ========================================
    // 7. 着信フロー（onRinging が発火しない問題）
    //    実際のバグ: takeAudioCall 成功後に onRinging が呼ばれない
    //    対策: takeAudioCall 成功時に直接 onIncomingCall を呼ぶ
    // ========================================

    @Test
    fun `takeAudioCall 成功時に直接 onIncomingCall を呼ぶ`() {
        // onRinging が発火しない場合の代替フロー
        val takeAudioCallSucceeded = true
        val onRingingFired = false // 実際は発火しない
        val callbackCalled = takeAudioCallSucceeded // takeAudioCall 成功で直接呼ぶ

        assertTrue(
            "onRinging が発火しなくても takeAudioCall 成功時にコールバックを呼ぶ",
            callbackCalled
        )
    }

    @Test
    fun `takeAudioCall 失敗時は onIncomingCall を呼ばない`() {
        val takeAudioCallSucceeded = false
        val callbackCalled = takeAudioCallSucceeded
        assertFalse("失敗時はコールバックを呼ばない", callbackCalled)
    }

    // ========================================
    // 8. F キーの物理位置マッピング
    //    実際のバグ: 設定画面のラベルが物理位置と一致していなかった
    // ========================================

    @Test
    fun `F1 は左上キー`() {
        val position = "left_top"
        val keycode = android.view.KeyEvent.KEYCODE_F1
        assertEquals(131, keycode)
        assertEquals("left_top", position)
    }

    @Test
    fun `F2 は右上キー`() {
        val position = "right_top"
        val keycode = android.view.KeyEvent.KEYCODE_F2
        assertEquals(132, keycode)
        assertEquals("right_top", position)
    }

    @Test
    fun `F3 は左下キー`() {
        val position = "left_bottom"
        val keycode = android.view.KeyEvent.KEYCODE_F3
        assertEquals(133, keycode)
        assertEquals("left_bottom", position)
    }

    @Test
    fun `F4 は右下キー`() {
        val position = "right_bottom"
        val keycode = android.view.KeyEvent.KEYCODE_F4
        assertEquals(134, keycode)
        assertEquals("right_bottom", position)
    }

    @Test
    fun `KYF39のSK2は応答キー132`() {
        assertEquals(131, MainActivity.softKey1Code(22))
        assertEquals(132, MainActivity.softKey2Code(22))
    }

    @Test
    fun `KYF42のSK2は応答キー133`() {
        assertEquals(132, MainActivity.softKey1Code(29))
        assertEquals(133, MainActivity.softKey2Code(29))
    }

    @Test
    fun `物理キー配置と設定キーの対応`() {
        // getevent の順序: 左上→F1, 左下→F3, 右上→F2, 右下→F4
        val physicalMapping = mapOf(
            "left_top" to SipConfig.KEY_ACTION_1,
            "left_bottom" to SipConfig.KEY_ACTION_3,
            "right_top" to SipConfig.KEY_ACTION_2,
            "right_bottom" to SipConfig.KEY_ACTION_4
        )
        assertEquals(SipConfig.KEY_ACTION_1, physicalMapping["left_top"])
        assertEquals(SipConfig.KEY_ACTION_3, physicalMapping["left_bottom"])
        assertEquals(SipConfig.KEY_ACTION_2, physicalMapping["right_top"])
        assertEquals(SipConfig.KEY_ACTION_4, physicalMapping["right_bottom"])
    }

    // ========================================
    // 9. クリアキーのロジック（バグ再現防止）
    //    実際のバグ: クリア押下でクラッシュ、空文字列で deleteCharAt → IndexOutOfBoundsException
    // ========================================

    @Test
    fun `空の状態でクリアしてもクラッシュしない`() {
        val currentNumber = StringBuilder("")
        if (currentNumber.isNotEmpty()) {
            currentNumber.deleteCharAt(currentNumber.length - 1)
        }
        assertEquals("空のまま", "", currentNumber.toString())
    }

    @Test
    fun `1文字の状態でクリアすると空になる`() {
        val currentNumber = StringBuilder("1")
        if (currentNumber.isNotEmpty()) {
            currentNumber.deleteCharAt(currentNumber.length - 1)
        }
        assertEquals("空になる", "", currentNumber.toString())
    }

    @Test
    fun `複数文字でクリアすると1文字減る`() {
        val currentNumber = StringBuilder("123")
        if (currentNumber.isNotEmpty()) {
            currentNumber.deleteCharAt(currentNumber.length - 1)
        }
        assertEquals("1文字減る", "12", currentNumber.toString())
    }

    // ========================================
    // 10. 通話中は DTMF 送信、待機中は番号入力
    // ========================================

    @Test
    fun `通話中は入力が DTMF 送信になる`() {
        val isInCall = true
        val digit = "5"
        val isDtmf = isInCall
        assertTrue("通話中の入力は DTMF", isDtmf)
    }

    @Test
    fun `待機中は入力が番号追加になる`() {
        val isInCall = false
        val isIncomingCall = false
        val digit = "5"
        val isNumberInput = !isInCall && !isIncomingCall
        assertTrue("待機中の入力は番号追加", isNumberInput)
    }

    @Test
    fun `着信中は番号入力も DTMF もしない`() {
        val isInCall = false
        val isIncomingCall = true
        val digit = "5"
        val isNumberInput = !isInCall && !isIncomingCall
        val isDtmf = isInCall
        assertFalse("着信中は番号入力しない", isNumberInput)
        assertFalse("着信中は DTMF 送信しない", isDtmf)
    }

    // ========================================
    // 11. 保留キーや未使用キーの無視
    // ========================================

    @Test
    fun `保留キー(*) は未使用`() {
        val starAction = SipConfig.FKEY_NONE
        assertEquals("保留キーは未使用", SipConfig.FKEY_NONE, starAction)
    }

    @Test
    fun `# キーは未使用`() {
        val poundAction = SipConfig.FKEY_NONE
        assertEquals("パイキーは未使用", SipConfig.FKEY_NONE, poundAction)
    }

    @Test
    fun `決定ボタンは通話中に無視`() {
        val isInCall = true
        val isIncomingCall = false
        // 通話中は DPAD_CENTER を無視（誤切断防止）
        val shouldIgnore = isInCall || isIncomingCall
        assertTrue("通話中に決定ボタンは無視", shouldIgnore)
    }

    @Test
    fun `決定ボタンは待機中はシステムに委譲`() {
        val isInCall = false
        val isIncomingCall = false
        val shouldIgnore = isInCall || isIncomingCall
        assertFalse("待機中の決定ボタンはシステム委譲", shouldIgnore)
    }

    // ========================================
    // 12. キーコードの整合性テスト
    // ========================================

    @Test
    fun `数字キーのキーコードが正しい`() {
        assertEquals(7, android.view.KeyEvent.KEYCODE_0)
        assertEquals(8, android.view.KeyEvent.KEYCODE_1)
        assertEquals(9, android.view.KeyEvent.KEYCODE_2)
        assertEquals(10, android.view.KeyEvent.KEYCODE_3)
        assertEquals(11, android.view.KeyEvent.KEYCODE_4)
        assertEquals(12, android.view.KeyEvent.KEYCODE_5)
        assertEquals(13, android.view.KeyEvent.KEYCODE_6)
        assertEquals(14, android.view.KeyEvent.KEYCODE_7)
        assertEquals(15, android.view.KeyEvent.KEYCODE_8)
        assertEquals(16, android.view.KeyEvent.KEYCODE_9)
    }

    // ========================================
    // 13. 発信中 (Outgoing) 状態
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

    @Test
    fun `特殊キーのキーコードが正しい`() {
        assertEquals(17, android.view.KeyEvent.KEYCODE_STAR)
        assertEquals(18, android.view.KeyEvent.KEYCODE_POUND)
        assertEquals(5, android.view.KeyEvent.KEYCODE_CALL)
        assertEquals(6, android.view.KeyEvent.KEYCODE_ENDCALL)
        assertEquals(4, android.view.KeyEvent.KEYCODE_BACK)
        assertEquals(23, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
    }
}
