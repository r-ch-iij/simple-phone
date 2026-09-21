package io.github.r_ch_iij.simplephone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator

// SIP アカウントと F キー割り当ての設定画面
class SettingsActivity : Activity() {

    companion object {
        // adb での動作確認用: カメラなしで QR ペイロードを流し込む
        // adb shell am broadcast -a io.github.r_ch_iij.simplephone.TEST_QR --es payload 'sip:203:pass@192.0.2.1:5060'
        // release ビルドでは無視する（任意アプリからの設定投入防止）
        const val ACTION_TEST_QR = "io.github.r_ch_iij.simplephone.TEST_QR"
        private const val REQUEST_CAMERA = 101
        private const val REQUEST_RINGTONE = 102
    }

    private var sipService: SipService? = null
    private var bound = false

    private lateinit var serverEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var realmEdit: EditText
    private lateinit var volumeBar: SeekBar
    private lateinit var vibrateCheck: CheckBox

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sipService = (service as SipService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            sipService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // ソフトキーガイドに「初期設定に戻す」を設定（左上=SK1）
        setupSoftKeys()

        serverEdit = findViewById(R.id.setServer)
        portEdit = findViewById(R.id.setPort)
        userEdit = findViewById(R.id.setUser)
        passEdit = findViewById(R.id.setPassword)
        realmEdit = findViewById(R.id.setRealm)
        vibrateCheck = findViewById(R.id.setVibrate)
        volumeBar = findViewById(R.id.setVolume)
        val volumeLabel = findViewById<TextView>(R.id.setVolumeLabel)

        // 現在の設定を表示
        serverEdit.setText(SipConfig.getServer(this))
        portEdit.setText(SipConfig.getPort(this).toString())
        userEdit.setText(SipConfig.getUser(this))
        passEdit.setText(SipConfig.getPassword(this))
        realmEdit.setText(SipConfig.getRealm(this))
        vibrateCheck.isChecked = SipConfig.isVibrateEnabled(this)

        // 音量（再生ゲイン %）
        val volPct = SipConfig.getVolumePct(this)
        volumeBar.progress = volPct
        volumeLabel.text = "$volPct%"
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = progress.coerceAtLeast(50)
                volumeLabel.text = "$p%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val raw = seekBar?.progress ?: 150
                val p = raw.coerceAtLeast(50)
                volumeLabel.text = "$p%"
            }
        })

        // キー設定（4つのキーを統一管理）
        listOf(
            Triple(R.id.setKey1, SipConfig.KEY_ACTION_1, "左上"),
            Triple(R.id.setKey2, SipConfig.KEY_ACTION_2, "右上"),
            Triple(R.id.setKey3, SipConfig.KEY_ACTION_3, "左下"),
            Triple(R.id.setKey4, SipConfig.KEY_ACTION_4, "右下")
        ).forEach { (id, key, pos) ->
            val button = findViewById<Button>(id)
            refreshKeyButton(button, key, pos)
            button.setOnClickListener { cycleKey(key, button, pos) }
        }

        // QR コードからの設定取り込み
        findViewById<Button>(R.id.setQrImport).setOnClickListener { startQrScan() }

        // 着信音の選択
        refreshRingtoneButton()
        findViewById<Button>(R.id.setRingtone).setOnClickListener { openRingtonePicker() }

        // 保存
        findViewById<Button>(R.id.setSave).setOnClickListener {
            saveAndApply()
            finish()
        }
    }

    // 着信音選択ダイアログを開く
    private fun openRingtonePicker() {
        val current = SipConfig.getRingtoneUri(this)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "着信音の選択")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            if (!current.isNullOrEmpty()) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    Uri.parse(current))
            }
        }
        startActivityForResult(intent, REQUEST_RINGTONE)
    }

    // 着信音ボタンに現在の設定を表示する
    private fun refreshRingtoneButton() {
        val button = findViewById<Button>(R.id.setRingtone)
        val current = SipConfig.getRingtoneUri(this)
        button.text = when {
            current == null -> "着信音：デフォルト"
            current.isEmpty() -> "着信音：無音"
            else -> {
                val title = try {
                    RingtoneManager.getRingtone(
                        this, Uri.parse(current))?.getTitle(this)
                } catch (e: Exception) { null }
                "着信音：${title ?: current}"
            }
        }
    }

    private fun saveAndApply() {
        val server = serverEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull() ?: 5060
        val user = userEdit.text.toString().trim()
        val pass = passEdit.text.toString().trim()
        val realm = realmEdit.text.toString().trim()
        // 音量は SeekBar の範囲（50〜250%）に丸める（表示と保存値の不一致防止）
        val volPct = volumeBar.progress.coerceIn(50, 250)

        val accountChanged = server != SipConfig.getServer(this) ||
                port != SipConfig.getPort(this) ||
                user != SipConfig.getUser(this) ||
                pass != SipConfig.getPassword(this) ||
                realm != SipConfig.getRealm(this)

        SipConfig.set(this, SipConfig.KEY_SERVER, server)
        SipConfig.set(this, SipConfig.KEY_PORT, port)
        SipConfig.set(this, SipConfig.KEY_USER, user)
        SipConfig.set(this, SipConfig.KEY_PASSWORD, pass)
        SipConfig.set(this, SipConfig.KEY_REALM, realm)
        SipConfig.set(this, SipConfig.KEY_VIBRATE, vibrateCheck.isChecked)
        SipConfig.set(this, SipConfig.KEY_VOLUME, volPct)

        sipService?.applySettings(server, port, user, pass, realm, volPct, accountChanged)
    }

    private val fKeyOptions = listOf(
        SipConfig.ACTION_NONE,
        SipConfig.ACTION_REDIAL,
        SipConfig.ACTION_CLEAR_ALL,
        SipConfig.ACTION_CLEAR_ONE,
        SipConfig.ACTION_CALL,
        SipConfig.ACTION_MUTE,
        SipConfig.ACTION_VOLUME_UP,
        SipConfig.ACTION_VOLUME_DOWN,
        SipConfig.ACTION_SETTINGS,
        SipConfig.ACTION_RESET,
        SipConfig.ACTION_HISTORY
    )

    // ボタン表示を「位置：機能」形式で更新する
    private fun refreshKeyButton(button: Button, configKey: String, pos: String) {
        button.text = "$pos：${SipConfig.fKeyLabel(SipConfig.getAction(this, configKey))}"
    }

    // 4キー表示を一括更新する（初期表示・初期設定リセットで共用）
    private fun refreshAllKeys() {
        listOf(
            Triple(R.id.setKey1, SipConfig.KEY_ACTION_1, "左上"),
            Triple(R.id.setKey2, SipConfig.KEY_ACTION_2, "右上"),
            Triple(R.id.setKey3, SipConfig.KEY_ACTION_3, "左下"),
            Triple(R.id.setKey4, SipConfig.KEY_ACTION_4, "右下")
        ).forEach { (id, key, pos) ->
            refreshKeyButton(findViewById(id), key, pos)
        }
    }

    private fun cycleKey(configKey: String, button: Button, pos: String) {
        val current = SipConfig.getAction(this, configKey)
        val idx = fKeyOptions.indexOf(current)
        val next = fKeyOptions[(idx + 1) % fKeyOptions.size]
        SipConfig.set(this, configKey, next)
        button.text = "$pos：${SipConfig.fKeyLabel(next)}"
    }

    // ---- QR コードからの設定取り込み ----

    private val testQrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!BuildConfig.DEBUG) return
            intent?.getStringExtra("payload")?.let { applyQrPayload(it) }
        }
    }

    private fun startQrScan() {
        // QR スキャンにはカメラ権限が必要（初回はここで要求）
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA
            )
            return
        }
        launchQrScanner()
    }

    private fun launchQrScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setCaptureActivity(QrScanActivity::class.java)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("SIP 設定のQRコードをかざしてください")
        integrator.setBeepEnabled(false)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // 着信音選択の結果
        if (requestCode == REQUEST_RINGTONE) {
            if (resultCode == Activity.RESULT_OK) {
                val uri: Uri? =
                    data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                // null（サイレント選択）→ 空文字で保存し無音化。未設定（null取得）→ デフォルト音
                SipConfig.set(this, SipConfig.KEY_RINGTONE, uri?.toString() ?: "")
                refreshRingtoneButton()
            }
            return
        }
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            val contents = result.contents
            if (contents != null) applyQrPayload(contents)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                Toast.makeText(this, "QR読取にはカメラ権限が必要です", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ペイロードを解析して画面に反映し、保存・ライブ適用して閉じる
    private fun applyQrPayload(payload: String) {
        val info = QrPayload.parse(payload)
        if (info == null) {
            Toast.makeText(this, "SIP 設定のQRコードではありません", Toast.LENGTH_LONG).show()
            return
        }
        val realm = (info.realm ?: SipConfig.getRealm(this)).trim()

        // UI に反映
        serverEdit.setText(info.host)
        portEdit.setText(info.port.toString())
        userEdit.setText(info.user)
        passEdit.setText(info.password)
        realmEdit.setText(realm)

        // 保存＆適用
        saveAndApply()

        val msg = if (sipService != null) {
            "QRから設定を適用しました (${info.user}@${info.host})"
        } else {
            "設定を保存しました。次回起動時に適用されます (${info.user}@${info.host})"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    // 戻るキーで保存せず終了
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // SK1 (132) = 初期設定に戻す（キー割り当てのみ。SIPアカウントは変更しない）
            132 -> {
                SipConfig.resetToDefaults(this)
                refreshAllKeys()
                Toast.makeText(this, "初期設定に戻しました", Toast.LENGTH_SHORT).show()
                true
            }
            // SK2 (133) = 設定保存
            133 -> {
                saveAndApply()
                finish()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStart() {
        super.onStart()
        if (SipConfig.isConfigured(this)) {
            val intent = Intent(this, SipService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        registerReceiver(testQrReceiver, IntentFilter(ACTION_TEST_QR))
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(testQrReceiver)
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    // 設定画面のソフトキーガイド: 左上=「初期設定に戻す」, 中央=「設定保存」
    private fun setupSoftKeys() {
        try {
            val guideClass = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide")
            val getMethod = guideClass.getMethod("getSoftkeyGuide", Window::class.java)
            val guide = getMethod.invoke(null, window) ?: return
            val setText = guideClass.getMethod("setText", Int::class.java, CharSequence::class.java)
            val setEnabled = guideClass.getMethod("setEnabled", Int::class.java, Boolean::class.java)
            val invalidate = guideClass.getMethod("invalidate")
            setText.invoke(guide, 1, "初期設定に戻す")
            setEnabled.invoke(guide, 1, true)
            setText.invoke(guide, 2, "設定保存")
            setEnabled.invoke(guide, 2, true)
            invalidate.invoke(guide)
        } catch (e: Exception) {
            // KCfpSoftkeyGuide が利用不可の場合は無視
        }
    }
}
