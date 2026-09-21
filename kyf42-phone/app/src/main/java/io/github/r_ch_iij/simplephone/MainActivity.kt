package io.github.r_ch_iij.simplephone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity(), SipService.Listener {

    companion object {
        private const val TAG = "SimplePhone"
        private const val REQUEST_RECORD_AUDIO = 100

        internal fun softKey1Code(sdkInt: Int): Int =
            if (sdkInt <= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                KeyEvent.KEYCODE_F1
            } else {
                KeyEvent.KEYCODE_F2
            }

        internal fun softKey2Code(sdkInt: Int): Int =
            if (sdkInt <= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                KeyEvent.KEYCODE_F2
            } else {
                KeyEvent.KEYCODE_F3
            }
    }

    private lateinit var statusText: TextView
    private lateinit var numberText: TextView
    private lateinit var debugText: TextView

    private var sipService: SipService? = null
    private var bound = false
    private var currentNumber = StringBuilder()
    private var callState: CallState = CallState.Idle
    private var hasSoftKeyGuide = false

    // 通話時間計測
    private var callStartTime = 0L
    private val callTimer = object : Runnable {
        override fun run() {
            if (callState is CallState.Active) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                val min = elapsed / 60
                val sec = elapsed % 60
                // ミュート中は表示で分かるようにする
                val prefix = if (callState.isMuted) "ミュート中" else "通話中"
                updateDisplay("$prefix %d:%02d".format(min, sec))
                mainHandler.postDelayed(this, 1000)
            }
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // デバッグ用ブロードキャストレシーバ（TEST_CALL / TEST_HANGUP）。
    // release ビルドでは無視する（任意アプリからの発信・切断防止）
    private val testReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            if (!BuildConfig.DEBUG) return
            when (i?.action) {
                "io.github.r_ch_iij.simplephone.TEST_CALL" -> {
                    val num = i.getStringExtra("number") ?: "200"
                    Log.d(TAG, "TEST_CALL broadcast received: $num")
                    sipService?.makeCall(num)
                }
                "io.github.r_ch_iij.simplephone.TEST_HANGUP" -> {
                    Log.d(TAG, "TEST_HANGUP broadcast received")
                    sipService?.endCall()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sipService = (service as SipService.LocalBinder).getService()
            bound = true
            sipService?.addListener(this@MainActivity)
            // 通話状態を先に同期してから表示を更新（上書き防止）
            syncCallState()
            if (callState is CallState.Idle) {
                updateDisplay("サービス接続済み")
            }
            Log.d(TAG, "service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            sipService?.removeListener(this@MainActivity)
            sipService = null
            updateDisplay("サービス切断")
            Log.d(TAG, "service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 全画面表示
        // KEEP_SCREEN_ON は通話中・着信中のみ付与する（放置時の画面点灯による
        // バッテリー消費を防ぐ。setKeepScreenOn() 参照）
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        statusText = findViewById(R.id.statusText)
        numberText = findViewById(R.id.numberText)
        debugText = findViewById(R.id.debugText)

        // バージョン情報を表示（デバッグ用）
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        val versionCode = packageManager.getPackageInfo(packageName, 0).versionCode
        debugText.text = "v$versionName ($versionCode) ユーザ:${SipConfig.getUser(this)}@${SipConfig.getServer(this)}"

        updateDisplay("起動中...")

        // 設定ボタンのクリックリスナー
        findViewById<TextView>(R.id.settingsButton).setOnClickListener {
            openSettings()
        }

        setupKeypadButtons()
        handleIncomingCallIntent(intent)

        // サービスを起動してバインド（未設定時はスキップ）
        if (SipConfig.isConfigured(this)) {
            SipService.start(this)
        } else {
            updateDisplay("設定が必要です")
            openSettings()
        }

        // マイク権限（通話音声の録音に必須）を実行時に要求
        requestRecordAudioPermission()

        // デバッグ用: 発信トリガ（adb broadcast で確実に呼び出すため）
        val testFilter = android.content.IntentFilter("io.github.r_ch_iij.simplephone.TEST_CALL")
        testFilter.addAction("io.github.r_ch_iij.simplephone.TEST_HANGUP")
        registerReceiver(testReceiver, testFilter)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent?.action != SipService.INCOMING_CALL_ACTION) return
        val callerNumber = intent.getStringExtra(SipService.EXTRA_CALLER)
        if (callerNumber.isNullOrEmpty()) {
            Log.w(TAG, "incoming call intent has no caller")
            return
        }
        onIncomingCall(callerNumber)
    }

    override fun onStart() {
        super.onStart()
        if (SipConfig.isConfigured(this)) {
            val intent = Intent(this, SipService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // リスナーは外さない（バックグラウンドで通話終了されてもコールバックを受けるため）
        // unbind は onDestroy で行う
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            sipService?.removeListener(this)
            unbindService(serviceConnection)
            bound = false
        }
        try {
            unregisterReceiver(testReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister testReceiver failed", e)
        }
    }

    private fun requestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        } else {
            Log.d(TAG, "RECORD_AUDIO already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO granted")
                updateDisplay("マイク許可済み")
            } else {
                Log.w(TAG, "RECORD_AUDIO denied")
                updateDisplay("⚠ マイク権限なし（音声通話不可）")
                AlertDialog.Builder(this)
                    .setTitle("マイク権限が必要です")
                    .setMessage("音声通話のためにマイク権限を許可してください。\n設定画面から許可できます。")
                    .setPositiveButton("設定を開く") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                        })
                    }
                    .setNegativeButton("後で") { d, _ -> d.dismiss() }
                    .show()
            }
        }
    }

    // ---- SipService.Listener ----
    // 画面点灯維持は通話中・着信中のみ（バッテリー節約）
    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onStatus(status: String) {
        runOnUiThread { updateDisplay(status) }
    }

    override fun onIncomingCall(callerNumber: String) {
        runOnUiThread {
            // SIP URI からユーザー名だけ抽出 (sip:203@... → 203)
            val displayNumber = callerNumber
                .removePrefix("sip:")
                .substringBefore("@")
            callState = CallState.Incoming(displayNumber)
            currentNumber.clear().append(displayNumber)
            updateNumberDisplay()
            setKeepScreenOn(true)
            updateDisplay("着信: $callerNumber")
        }
    }

    override fun onCallStarted() {
        runOnUiThread {
            // 応答前の状態で方向を判定（発信→発信、着信→着信）
            val wasIncoming = callState is CallState.Incoming
            val peer = when (val s = callState) {
                is CallState.Outgoing -> s.number
                is CallState.Incoming -> s.callerNumber
                else -> currentNumber.toString()
            }
            callState = CallState.Active()
            setKeepScreenOn(true)
            if (peer.isNotEmpty()) {
                SipConfig.addHistory(
                    this,
                    if (wasIncoming) SipConfig.HistoryType.INCOMING
                    else SipConfig.HistoryType.OUTGOING,
                    peer
                )
            }
            // 通話時間計測開始
            callStartTime = System.currentTimeMillis()
            mainHandler.post(callTimer)
            updateDisplay("通話中 0:00")
        }
    }

    override fun onCallEnded() {
        runOnUiThread {
            // 通話時間計測停止
            mainHandler.removeCallbacks(callTimer)
            val prev = callState
            val clearNumber = callState.isInCall
            callState = CallState.Idle
            when (prev) {
                // 応答せず終了（拒否・相手取消・タイムアウト）→ 不在着信として記録
                is CallState.Incoming ->
                    SipConfig.addHistory(this, SipConfig.HistoryType.MISSED, prev.callerNumber)
                else -> {}
            }
            val message = if (prev is CallState.Outgoing) "発信取消" else "通話終了"
            resetCallState(clearNumber, message)
        }
    }

    override fun onCallFailed(reason: String) {
        runOnUiThread {
            val clearNumber = callState.isInCall
            callState = CallState.Idle
            resetCallState(clearNumber, "通話失敗: $reason")
        }
    }

    override fun onDebug(message: String) {
        runOnUiThread { debugText.text = message }
    }

    // ---- キー処理 ----
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyDown: keyCode=$keyCode")

        // 状態別ソフトキー（設定の割り当てより優先）。
        // KYF39 (API 22) は SK1=F1(131), SK2=F2(132)。
        // KYF42 は SK1=F2(132), SK2=F3(133)。
        val softKey1 = softKey1Code(android.os.Build.VERSION.SDK_INT)
        val softKey2 = softKey2Code(android.os.Build.VERSION.SDK_INT)
        when (callState) {
            is CallState.Incoming -> when (keyCode) {
                softKey1 -> { endCall(); return true } // 拒否
                softKey2 -> { answerCall(); return true } // 応答
                else -> {}
            }
            is CallState.Active -> when (keyCode) {
                softKey2 -> { toggleMute(); return true } // ミュート切替
                else -> {}
            }
            is CallState.Outgoing -> when (keyCode) {
                softKey1 -> { endCall(); return true } // 発信取消
                else -> {}
            }
            else -> {}
        }

        return when (keyCode) {
            // 数字キー (KEYCODE_0=7 ～ KEYCODE_9=16)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                appendNumber((keyCode - KeyEvent.KEYCODE_0).toString()); true
            }

            // 発話ボタン（通話中は終話）
            KeyEvent.KEYCODE_CALL -> {
                when (callState) {
                    is CallState.Incoming -> answerCall()
                    is CallState.Active -> endCall()
                    is CallState.Outgoing -> endCall() // 発信取消
                    is CallState.Idle -> if (currentNumber.isNotEmpty()) makeCall()
                }
                true
            }

            // 終話ボタン
            KeyEvent.KEYCODE_ENDCALL -> {
                if (callState.isInCall || callState.isIncoming || callState.isOutgoing) endCall() else clearOneChar()
                true
            }

            // 物理クリアボタン → KEYCODE_BACK として送信される（matrix_keypad.kl: key 355 BACK）
            // 通話中・着信中・発信中は終話、待機中は1文字削除（デフォルトの戻る動作を防止）
            KeyEvent.KEYCODE_BACK -> {
                if (callState.isInCall || callState.isIncoming || callState.isOutgoing) endCall() else clearOneChar()
                true
            }

            // クリアボタン: 通話中は終話、待機中は文字削除（1文字ずつ）
            KeyEvent.KEYCODE_CLEAR -> {
                if (callState.isInCall || callState.isIncoming || callState.isOutgoing) endCall() else clearOneChar()
                true
            }

            // バックスペース: 同様に通話中は終話、待機中は1文字削除
            KeyEvent.KEYCODE_DEL -> {
                if (callState.isInCall || callState.isIncoming || callState.isOutgoing) endCall() else clearOneChar()
                true
            }

            // * キー
            KeyEvent.KEYCODE_STAR -> {
                appendNumber("*")
                true
            }

            // # キー
            KeyEvent.KEYCODE_POUND -> {
                appendNumber("#")
                true
            }

            // 決定 (OK) ボタン - 通話中・着信中・発信中は無効化（誤切断防止）
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                callState.isInCall || callState.isIncoming || callState.isOutgoing
            }

            // 十字キー左右 - 常に無視
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true

            // 十字キー周囲の4キー（keyCode 59-62）+ 機能キー F1-F4（131-134）
            // 表示（ガイド1-4＝キー1-4の設定）と一致させる。
            // 注意: KEYCODE_F2=132, F3=133, F4=134 のため、リテラル 132/133/134 を
            // 別アームに書くと重複して先勝ちで誤動作する。リテラルは書かないこと
            59, KeyEvent.KEYCODE_F1 -> { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_1)); true }
            60, KeyEvent.KEYCODE_F2 -> { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_2)); true }
            61, KeyEvent.KEYCODE_F3 -> { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_3)); true }
            62, KeyEvent.KEYCODE_F4 -> { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_4)); true }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun appendNumber(digit: String) {
        when (callState) {
            is CallState.Idle -> {
                currentNumber.append(digit)
                updateNumberDisplay()
            }
            is CallState.Active -> sipService?.sendDtmf(digit)
            is CallState.Incoming, is CallState.Outgoing -> { /* 着信中・発信中は無視 */ }
        }
    }

    private fun clearOneChar() {
        if (currentNumber.isNotEmpty()) {
            currentNumber.deleteCharAt(currentNumber.length - 1)
            updateNumberDisplay()
        }
    }

    private fun handleKeyAction(action: String) {
        when (action) {
            SipConfig.ACTION_REDIAL -> redial()
            SipConfig.ACTION_SETTINGS -> openSettings()
            SipConfig.ACTION_CLEAR_ALL -> clearAll()
            SipConfig.ACTION_CLEAR_ONE -> clearOneChar()
            SipConfig.ACTION_CALL -> if (!callState.isInCall && !callState.isIncoming && !callState.isOutgoing && currentNumber.isNotEmpty()) makeCall()
            SipConfig.ACTION_MUTE -> toggleMute()
            SipConfig.ACTION_VOLUME_UP -> adjustCallVolume(up = true)
            SipConfig.ACTION_VOLUME_DOWN -> adjustCallVolume(up = false)
            SipConfig.ACTION_RESET -> resetToDefaults()
            SipConfig.ACTION_HISTORY -> openHistory()
            else -> { /* 無効 */ }
        }
    }

    private fun openHistory() {
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    // マイクのミュート切替（通話中のみ）
    private fun toggleMute() {
        if (callState !is CallState.Active) {
            updateDisplay("通話中ではありません")
            return
        }
        val newMuted = !callState.isMuted
        callState = CallState.Active(muted = newMuted)
        sipService?.setMute(newMuted)
        // 表示は callTimer（1秒更新）に任せる。タイマーが止まっている場合の保険で即時更新
        mainHandler.removeCallbacks(callTimer)
        mainHandler.post(callTimer)
    }

    // 通話音量の増減（端末の STREAM_VOICE_CALL を調整し、音量 UI を表示）
    private fun adjustCallVolume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun redial() {
        val prefs = getSharedPreferences(SipConfig.PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(SipConfig.KEY_LAST_NUMBER, null)
        if (!last.isNullOrEmpty()) {
            currentNumber = StringBuilder(last)
            updateNumberDisplay()
            updateDisplay("リダイヤル: $last")
        } else {
            updateDisplay("履歴がありません")
        }
    }

    private fun clearAll() {
        currentNumber.clear()
        updateNumberDisplay()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun resetToDefaults() {
        SipConfig.resetToDefaults(this)
        setupKeypadButtons()
        setupSoftKeys()
        updateDisplay("初期設定に戻しました")
    }

    // 画面内 4ボタン（物理キーパッドを模倣）。
    // ソフトキーガイドが利用可能な場合は非表示、利用不可の場合は表示（フォールバック）
    private fun setupKeypadButtons() {
        val keypadArea = findViewById<android.widget.LinearLayout>(R.id.keypadArea)
        val tl = findViewById<Button>(R.id.btnKeypadTL)
        val tr = findViewById<Button>(R.id.btnKeypadTR)
        val bl = findViewById<Button>(R.id.btnKeypadBL)
        val br = findViewById<Button>(R.id.btnKeypadBR)

        // 統一キー設定を使用
        tl.text = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_1))
        tr.text = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_2))
        bl.text = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_3))
        br.text = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_4))

        tl.setOnClickListener { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_1)) }
        tr.setOnClickListener { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_2)) }
        bl.setOnClickListener { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_3)) }
        br.setOnClickListener { handleKeyAction(SipConfig.getAction(this, SipConfig.KEY_ACTION_4)) }

        // ソフトキーガイドが利用可能な場合はグリッドUIを非表示
        keypadArea.visibility = if (hasSoftKeyGuide) android.view.View.GONE else android.view.View.VISIBLE
    }

    // Kyocera ソフトキーバーに 4 ボタンを表示する。
    // KCfpSoftkeyGuide はシステムブートクラスパス上のためリフレクションでアクセス。
    // キーコード: 132=SK1, 133=SK2, 134=SK3, SK4 も利用可能
    // 戻り値: ソフトキーガイドが利用可能かどうか
    private fun setupSoftKeys(): Boolean {
        try {
            val guideClass = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide")
            val getMethod = guideClass.getMethod("getSoftkeyGuide", android.view.Window::class.java)
            val guide = getMethod.invoke(null, window) ?: return false
            val setText = guideClass.getMethod("setText", Int::class.java, CharSequence::class.java)
            val setEnabled = guideClass.getMethod("setEnabled", Int::class.java, Boolean::class.java)
            val invalidate = guideClass.getMethod("invalidate")
            // 設定されたキーの機能を表示（4つすべて）
            setText.invoke(guide, 1, SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_1)))
            setText.invoke(guide, 2, SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_2)))
            setText.invoke(guide, 3, SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_3)))
            setText.invoke(guide, 4, SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_4)))
            setEnabled.invoke(guide, 1, true)
            setEnabled.invoke(guide, 2, true)
            setEnabled.invoke(guide, 3, true)
            setEnabled.invoke(guide, 4, true)
            invalidate.invoke(guide)
            Log.d(TAG, "softkey guide initialized")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "KCfpSoftkeyGuide not available: ${e.message}")
            return false
        }
    }

    override fun onResume() {
        super.onResume()
        // Kyocera ソフトキー表示（利用可能かどうかを判定）
        hasSoftKeyGuide = setupSoftKeys()
        // 設定画面で変更した割り当てをボタン表示に反映する
        setupKeypadButtons()
        // 通話状態の同期（HOME→再開時など）
        if (bound) syncCallState()
        // 設定済みでサービス未起動なら起動
        if (!bound && SipConfig.isConfigured(this)) {
            SipService.start(this)
            bindService(Intent(this, SipService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun makeCall() {
        if (callState !is CallState.Idle) {
            updateDisplay("通話中は発信できません")
            return
        }
        val number = currentNumber.toString()
        if (number.isNotEmpty()) {
            callState = CallState.Outgoing(number)
            setKeepScreenOn(true)
            updateDisplay("発信中: $number")
            currentNumber.clear()
            updateNumberDisplay()
            sipService?.makeCall(number)
            // リダイヤル用に保存
            getSharedPreferences(SipConfig.PREFS, Context.MODE_PRIVATE)
                .edit().putString(SipConfig.KEY_LAST_NUMBER, number).apply()
        }
    }

    private fun answerCall() {
        updateDisplay("応答中...")
        sipService?.answerCall()
    }

    private fun endCall() {
        // 着信拒否も不在着信として記録（onCallEnded コールバック時は既に Idle のため）
        val prev = callState
        if (prev is CallState.Incoming) {
            SipConfig.addHistory(this, SipConfig.HistoryType.MISSED, prev.callerNumber)
        }
        sipService?.endCall()
        resetCallState(true, if (prev is CallState.Outgoing) "発信取消" else "通話終了")
    }

    // サービス再接続時にネイティブ層の実際の通話状態と UI を同期する。
    // HOME→再開時など、コールバックが届かないうちに状態が分岐するのを防ぐ。
    private fun syncCallState() {
        val actualInCall = sipService?.isCallActive() == true
        if (actualInCall && callState is CallState.Idle) {
            Log.d(TAG, "syncCallState: native call is active, syncing UI")
            callState = CallState.Active()
            setKeepScreenOn(true)
            updateDisplay("通話中")
        } else if (actualInCall && callState is CallState.Outgoing) {
            // 発信中はネイティブ層も通話扱いのため何もしない。
            // 応答は onCallStarted コールバックで Active に遷移する
            Log.d(TAG, "syncCallState: outgoing in progress, keeping UI")
        } else if (!actualInCall && callState.isInCall) {
            Log.d(TAG, "syncCallState: native call ended, clearing UI")
            callState = CallState.Idle
            resetCallState(true, "通話終了")
        } else if (!actualInCall && callState is CallState.Incoming) {
            // 着信中にネイティブ層が応答なし→タイムアウトで終了
            Log.d(TAG, "syncCallState: native call gone, clearing incoming state")
            callState = CallState.Idle
            resetCallState(true, "着信終了")
        } else if (!actualInCall && callState is CallState.Outgoing) {
            // 発信中にネイティブ層が終了（失敗・取消のコールバック漏れ対策）
            Log.d(TAG, "syncCallState: native call gone, clearing outgoing state")
            callState = CallState.Idle
            resetCallState(false, "発信取消")
        }
    }

    private fun updateDisplay(status: String) {
        statusText.text = status
        updateSoftKeysForState()
    }

    // 通話状態に応じてソフトキーラベルを切り替える。
    // ガイド番号とキーコードの対応: 1=SK1(132), 2=SK2(133), 3=SK3(134), 4=SK4
    // 状態別の上書き（onKeyDown の先頭で同じ対応付けで処理）:
    // - 着信中: SK1=拒否, SK2=応答
    // - 通話中: SK2=ミュート切替
    // - 発信中: SK1=発信取消
    private fun updateSoftKeysForState() {
        if (!hasSoftKeyGuide) return
        try {
            val guideClass = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide")
            val getMethod = guideClass.getMethod("getSoftkeyGuide", android.view.Window::class.java)
            val guide = getMethod.invoke(null, window) ?: return
            val setText = guideClass.getMethod("setText", Int::class.java, CharSequence::class.java)
            val setEnabled = guideClass.getMethod("setEnabled", Int::class.java, Boolean::class.java)
            val invalidate = guideClass.getMethod("invalidate")

            val label1: String
            val label2: String
            when (callState) {
                is CallState.Incoming -> {
                    label1 = "拒否"
                    label2 = "応答"
                }
                is CallState.Active -> {
                    label1 = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_1))
                    label2 = "ミュート切替"
                }
                is CallState.Outgoing -> {
                    label1 = "発信取消"
                    label2 = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_2))
                }
                else -> {
                    label1 = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_1))
                    label2 = SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_2))
                }
            }
            // 設定されたキーの機能を表示（4つすべて）
            setText.invoke(guide, 1, label1)
            setText.invoke(guide, 2, label2)
            setText.invoke(guide, 3, SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_3)))
            setText.invoke(guide, 4, SipConfig.fKeyLabel(SipConfig.getAction(this, SipConfig.KEY_ACTION_4)))
            setEnabled.invoke(guide, 1, true)
            setEnabled.invoke(guide, 2, true)
            setEnabled.invoke(guide, 3, true)
            setEnabled.invoke(guide, 4, true)
            invalidate.invoke(guide)
        } catch (e: Exception) {
            Log.w(TAG, "updateSoftKeysForState failed: ${e.message}")
        }
    }

    private fun updateNumberDisplay() {
        numberText.text = currentNumber.toString()
    }

    private fun resetCallState(clearNumber: Boolean, message: String) {
        callState = CallState.Idle
        setKeepScreenOn(false)
        if (clearNumber) currentNumber.clear()
        updateNumberDisplay()
        updateDisplay(message)
    }
}
