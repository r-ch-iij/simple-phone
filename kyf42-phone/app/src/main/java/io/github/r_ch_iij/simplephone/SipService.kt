package io.github.r_ch_iij.simplephone

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// SIP の登録・通話を司るフォアグラウンドサービス
// ブート時は BootReceiver から起動され、着信もこのサービスが処理する
class SipService : Service(), SipManager.SipCallback {

    companion object {
        private const val TAG = "SipService"
        // 着信無応答のタイムアウト。これを過ぎたら自動拒否して
        // リングトーン・バイブを止める（鳴りっぱなしによるバッテリー消費防止）
        private const val RING_TIMEOUT_MS = 45_000L
        const val ACTION_START = "io.github.r_ch_iij.simplephone.ACTION_START"
        const val INCOMING_CALL_ACTION = "io.github.r_ch_iij.simplephone.INCOMING_CALL"
        const val EXTRA_CALLER = "io.github.r_ch_iij.simplephone.EXTRA_CALLER"

        fun start(context: Context) {
            if (!SipConfig.isConfigured(context)) return
            val intent = Intent(context, SipService::class.java).setAction(ACTION_START)
            // API 26+: バックグラウンドからのサービス起動には startForegroundService が必要。
            // API 22 (KYF39) では startForegroundService が存在しないため startService を使う。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    interface Listener {
        fun onStatus(status: String)
        fun onIncomingCall(callerNumber: String)
        fun onCallStarted()
        fun onCallEnded()
        fun onCallFailed(reason: String)
        fun onDebug(message: String)
    }

    private val binder = LocalBinder()
    private var sipManager: SipManager? = null
    private lateinit var notificationHelper: NotificationHelper
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    // SIP 操作は全てこのバックグラウンドスレッドで実行（メインスレッドをブロック防止）
    private val sipExecutor = Executors.newSingleThreadExecutor()
    // 通話中フラグ（折りたたみ検知で終話するため）
    @Volatile private var callActive = false
    // 着信音鳴動中フラグ（タイムアウト管理用）
    @Volatile private var ringing = false
    private var currentCaller: String? = null
    private val ringTimeoutRunnable = Runnable { onRingTimeout() }
    // 画面 OFF（＝本端末ではフリップを閉じた状態）で通話を終了するレシーバ
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && callActive) {
                Log.d(TAG, "screen off (flip closed) during call -> endCall")
                endCall()
            }
        }
    }
    // 端末終了時に SIP 登録を解除するレシーバ（PBX の stale contact 防止）
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                Log.d(TAG, "shutdown -> unregister")
                try {
                    sipManager?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "unregister on shutdown failed", e)
                }
                sipManager = null
            }
        }
    }
    // ネットワーク復帰時に SIP 再登録するコールバック（切断→復帰の自動復旧）。
    // 登録直後の初回 onAvailable（sticky）は無視し、onLost→onAvailable の
    // 遷移時のみ再登録する（初期登録との競合によるネイティブクラッシュ防止）。
    // また登録済みの場合のみ再登録する。
    @Volatile private var networkRearmNeeded = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!networkRearmNeeded) return
            networkRearmNeeded = false
            Log.d(TAG, "network available after loss -> reregister")
            sipExecutor.execute {
                try {
                    if (SipConfig.isConfigured(this@SipService) &&
                        sipManager?.isRegistered() == true
                    ) {
                        sipManager?.reregister()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "reregister on network available failed", e)
                }
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "network lost")
            networkRearmNeeded = true
        }
    }
    private var connectivityManager: ConnectivityManager? = null
    // API 24 未満 (KYF39 / API 22) 用のフォールバック: CONNECTIVITY_ACTION で復帰検知。
    // registerDefaultNetworkCallback は API 24+ のため API 22 では使えない。
    private var useLegacyNetworkReceiver = false
    private val legacyNetworkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                @Suppress("DEPRECATION")
                val info = connectivityManager?.activeNetworkInfo
                @Suppress("DEPRECATION")
                if (info != null && info.isConnected) {
                    Log.d(TAG, "legacy network connected -> reregister")
                    sipExecutor.execute {
                        try {
                            if (SipConfig.isConfigured(this@SipService) &&
                                sipManager?.isRegistered() == true
                            ) {
                                sipManager?.reregister()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "reregister on legacy network failed", e)
                        }
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SipService = this@SipService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()
        startForeground(NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildForegroundNotification("電話アプリ"))
        Log.d(TAG, "SipService created")
        // 画面 OFF（フリップ閉）で通話終了するレシーバを登録
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        // 端末終了時の登録解除レシーバを登録
        registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
        // ネットワーク変化の監視を開始
        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            } else {
                // API 22: NetworkRequest 版 (API 21+ で利用可能) でフォールバック
                try {
                    val request = NetworkRequest.Builder().build()
                    connectivityManager?.registerNetworkCallback(request, networkCallback)
                } catch (e: Exception) {
                    // さらに古い端末向け: CONNECTIVITY_ACTION ブロードキャスト
                    Log.w(TAG, "registerNetworkCallback failed, using legacy receiver", e)
                    @Suppress("DEPRECATION")
                    registerReceiver(
                        legacyNetworkReceiver,
                        IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                    )
                    useLegacyNetworkReceiver = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "register network callback failed", e)
        }
        // バックグラウンドで SIP 初期化
        sipExecutor.execute { ensureSipManager() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            INCOMING_CALL_ACTION -> {
                // NativeSip はコールバックで着信を通知するため、ここで takeCall は不要
                Log.d(TAG, "incoming call intent received")
            }
            "ANSWER_CALL" -> {
                Log.d(TAG, "answer call from notification")
                answerCall()
            }
            "REJECT_CALL" -> {
                Log.d(TAG, "reject call from notification")
                endCall()
            }
            else -> {
                if (!SipConfig.isConfigured(this)) {
                    Log.w(TAG, "SIP not configured, skipping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                sipExecutor.execute { ensureSipManager() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (useLegacyNetworkReceiver) {
                unregisterReceiver(legacyNetworkReceiver)
            } else {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "unregister networkCallback failed", e)
        }
        sipExecutor.shutdownNow()
        notificationHelper.stopRinging()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister screenOffReceiver failed", e)
        }
        try {
            unregisterReceiver(shutdownReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister shutdownReceiver failed", e)
        }
        sipManager?.stop()
        sipManager = null
        super.onDestroy()
    }

    private fun ensureSipManager() {
        if (sipManager == null) {
            val mgr = SipManager(this, this)
            mgr.start()
            sipManager = mgr
        }
    }

    // ---- Listener 管理 ----
    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyListeners(block: (Listener) -> Unit) {
        // リスナー通知はメインスレッドで実行（UI 更新用）
        mainHandler.post { listeners.toList().forEach(block) }
    }

    // ---- SipManager.SipCallback ----
    override fun onRegistered() {
        notifyListeners { it.onStatus("登録済み") }
    }

    override fun onRegistrationFailed(reason: String) {
        notifyListeners { it.onStatus("登録失敗: $reason") }
    }

    override fun onIncomingCall(callerNumber: String) {
        // 着信音・バイブレーションはメインスレッドで実行
        ringing = true
        currentCaller = callerNumber
        // 前回のタイムアウトがあれば取り消してから新しいものをセット（重複防止）
        mainHandler.removeCallbacks(ringTimeoutRunnable)
        mainHandler.postDelayed(ringTimeoutRunnable, RING_TIMEOUT_MS)
        mainHandler.post { notificationHelper.startRinging() }

        // 着信通知を表示（フルスクリーンインテントで Activity を前面に）
        notificationHelper.showIncomingCallNotification(callerNumber)

        // Activity を前面に持ってくる
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = INCOMING_CALL_ACTION
            putExtra(EXTRA_CALLER, callerNumber)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launchIntent)

        notifyListeners { it.onIncomingCall(callerNumber) }
    }

    override fun onCallStarted() {
        callActive = true
        notificationHelper.cancelIncomingCallNotification()
        mainHandler.post { notificationHelper.stopRinging() }
        showMainActivity()
        notifyListeners { it.onCallStarted() }
    }

    // 通話画面（MainActivity）を前面に表示する
    private fun showMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    override fun onCallEnded() {
        callActive = false
        notificationHelper.cancelIncomingCallNotification()
        mainHandler.post { notificationHelper.stopRinging() }
        notifyListeners { it.onCallEnded() }
    }

    override fun onCallFailed(reason: String) {
        callActive = false
        notificationHelper.cancelIncomingCallNotification()
        mainHandler.post { notificationHelper.stopRinging() }
        notifyListeners { it.onCallFailed(reason) }
    }

    override fun onDebug(message: String) {
        notifyListeners { it.onDebug(message) }
    }

    // 着信が RING_TIMEOUT_MS 内に応答されなかった場合の自動拒否
    private fun onRingTimeout() {
        if (!ringing || callActive) return
        val caller = currentCaller ?: "不明"
        Log.d(TAG, "ring timeout ($RING_TIMEOUT_MS ms) -> reject call from $caller")
        mainHandler.post { notificationHelper.stopRinging() }
        sipExecutor.execute { sipManager?.endCall() }
        notifyListeners { it.onStatus("不在着信: $caller") }
        notificationHelper.showMissedCallNotification(caller)
    }

    // ---- 通話操作（MainActivity から）: バックグラウンドで実行 ----
    fun makeCall(number: String) {
        sipExecutor.execute { sipManager?.makeCall(number) }
    }

    fun answerCall() {
        mainHandler.post { notificationHelper.stopRinging() }
        sipExecutor.execute { sipManager?.answerCall() }
    }

    fun endCall() {
        mainHandler.post { notificationHelper.stopRinging() }
        sipExecutor.execute { sipManager?.endCall() }
    }

    fun sendDtmf(digit: String) {
        sipExecutor.execute { sipManager?.sendDtmf(digit) }
    }

    fun setMute(mute: Boolean) {
        sipExecutor.execute { sipManager?.setMute(mute) }
    }

    /** 実際の通話状態をクエリ（ネイティブ層の状態が正） */
    fun isCallActive(): Boolean = sipManager?.isInCall() == true

    // 設定をライブ適用（サービス再起動・baresip 再初期化は行わない）
    fun applySettings(server: String, port: Int, user: String,
                      password: String, realm: String, volumePct: Int,
                      accountChanged: Boolean) {
        sipExecutor.execute {
            sipManager?.applySettings(server, port, user, password, realm,
                volumePct, accountChanged)
        }
    }
}
