package io.github.r_ch_iij.simplephone

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
        // 再登録のレート制限。NetworkCallback と legacy 受信の二重発火や
        // 起動直後の重複投入を吸収する（UA 作り直しの連打防止）
        private const val REREG_MIN_INTERVAL_MS = 15_000L
        // 登録失敗時のリトライ間隔。指数バックオフの初期間隔と上限
        private const val REG_RETRY_BASE_MS = 15_000L
        private const val REG_RETRY_MAX_MS = 300_000L
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
    // ネットワーク復帰時に SIP 再登録するコールバック（切断→復帰の自動復旧と、
    // 未登録のまま放置された場合の回復）。起動直後の初回 onAvailable（sticky）や
    // 二重発火の重複は reregisterIfReady のレート制限で吸収する。
    // 初期登録との競合によるネイティブクラッシュ防止のため、起動直後の再登録は
    // ensureSipManager が記録した時刻で抑止する。
    @Volatile private var networkRearmNeeded = false
    // 最後に登録（再登録）を試みた時刻。二重発火の連打抑止用
    @Volatile private var lastRegAttemptMs = 0L
    // 登録失敗リトライのバックオフ段数。onRegistered で 0 に戻す
    @Volatile private var regRetryCount = 0
    private val regRetryRunnable = Runnable {
        Log.d(TAG, "registration retry attempt (backoff step $regRetryCount)")
        reregisterIfReady("registration retry")
    }
    // 設定済みなら登録を試みる。未登録のまま放置しないため、登録済みか否かは
    // 条件にしない（旧実装は登録済み時のみ再登録し、初回失敗が永久に残った）。
    // レート制限で起動直後の重複投入や二重発火を吸収する
    private fun reregisterIfReady(tag: String) {
        try {
            sipExecutor.execute {
                try {
                    if (!SipConfig.isConfigured(this@SipService)) return@execute
                    // 通話中の UA 作り直しは通話断になるため遅延し、終話後に再試行する
                    if (callActive || sipManager?.isInCall() == true) {
                        Log.d(TAG, "reregister on $tag deferred (call active)")
                        networkRearmNeeded = true
                        return@execute
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRegAttemptMs < REREG_MIN_INTERVAL_MS) {
                        Log.d(TAG, "reregister on $tag suppressed (rate limit)")
                        return@execute
                    }
                    lastRegAttemptMs = now
                    val mgr = sipManager
                    if (mgr == null) {
                        Log.d(TAG, "reregister on $tag: manager null, (re)creating")
                        ensureSipManager()
                    } else {
                        mgr.reregister()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "reregister on $tag failed", e)
                }
            }
        } catch (e: Exception) {
            // onDestroy 後の予約発火など、executor 終了後の投入は無視する
            Log.w(TAG, "reregister on $tag rejected", e)
        }
    }
    // 登録失敗時は指数バックオフで再登録を予約する（上限で頭打ちし継続する）。
    // 成功（onRegistered）で解除・リセットする
    private fun scheduleRegRetry() {
        mainHandler.removeCallbacks(regRetryRunnable)
        if (regRetryCount > 8) regRetryCount = 8
        val delay = minOf(REG_RETRY_BASE_MS * (1L shl regRetryCount), REG_RETRY_MAX_MS)
        regRetryCount++
        Log.d(TAG, "registration retry scheduled in ${delay}ms")
        mainHandler.postDelayed(regRetryRunnable, delay)
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 切断→復帰時は必ず、未登録のままなら初回 sticky も含めて再登録する。
            // 重複は reregisterIfReady のレート制限で吸収する
            if (!networkRearmNeeded && sipManager?.isRegistered() == true) return
            networkRearmNeeded = false
            Log.d(TAG, "network available -> reregister")
            reregisterIfReady("network available")
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "network lost")
            networkRearmNeeded = true
        }
    }
    private var connectivityManager: ConnectivityManager? = null
    // API 24 未満 (KYF39 / API 22) 用のフォールバック: CONNECTIVITY_ACTION で復帰検知。
    // registerDefaultNetworkCallback は API 24+ のため API 22 では使えない。
    // 確実のため NetworkCallback と併用する（重複はレート制限で吸収する）
    private var useLegacyNetworkReceiver = false
    private val legacyNetworkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                @Suppress("DEPRECATION")
                val info = connectivityManager?.activeNetworkInfo
                @Suppress("DEPRECATION")
                if (info != null && info.isConnected) {
                    if (!networkRearmNeeded && sipManager?.isRegistered() == true) return
                    networkRearmNeeded = false
                    Log.d(TAG, "legacy network connected -> reregister")
                    reregisterIfReady("legacy network")
                }
            }
        }
    }
    // Wi-Fi スリープによる登録断を抑止するロック。取得・解放はメインスレッドのみ
    private var wifiLock: WifiManager.WifiLock? = null

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
                    Log.w(TAG, "registerNetworkCallback failed", e)
                }
                // API 22 (KYF39) では復帰検知を確実にするため legacy 受信も併用する。
                // 初回 sticky 発火の重複は reregisterIfReady のレート制限で吸収する
                @Suppress("DEPRECATION")
                registerReceiver(
                    legacyNetworkReceiver,
                    IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                )
                useLegacyNetworkReceiver = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "register network callback failed", e)
        }
        // Wi-Fi スリープによる切断・未登録放置を抑止する（解放は onDestroy）
        try {
            val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SimplePhone:Sip"
            )?.also { it.acquire() }
            Log.d(TAG, "wifi lock acquired: ${wifiLock != null}")
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock acquire failed", e)
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
        mainHandler.removeCallbacks(regRetryRunnable)
        mainHandler.removeCallbacks(ringTimeoutRunnable)
        try {
            if (useLegacyNetworkReceiver) {
                unregisterReceiver(legacyNetworkReceiver)
            }
        } catch (e: Exception) {
            Log.w(TAG, "unregister legacy receiver failed", e)
        }
        // API24+ では NetworkCallback のみ、API22 系では両方登録しているため
        // こちらも外す（未登録の場合の例外は無視する）
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregister networkCallback failed", e)
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock release failed", e)
        }
        wifiLock = null
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
            // 起動直後の network sticky 発火との重複をレート制限で吸収する
            lastRegAttemptMs = SystemClock.elapsedRealtime()
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
        mainHandler.post { listeners.forEach(block) }
    }

    // ---- SipManager.SipCallback ----
    override fun onRegistered() {
        // 成功したら失敗リトライを解除・リセットする
        regRetryCount = 0
        mainHandler.removeCallbacks(regRetryRunnable)
        notifyListeners { it.onStatus("登録済み") }
    }

    override fun onRegistrationFailed(reason: String) {
        // 放置すると着信不能のままになるためバックオフで再登録を予約する
        scheduleRegRetry()
        notifyListeners { it.onStatus("登録失敗: $reason") }
    }

    // 通話画面（MainActivity）を前面に表示する。caller 指定時は着信通知つき
    private fun showMainActivity(callerNumber: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            if (callerNumber != null) {
                action = INCOMING_CALL_ACTION
                putExtra(EXTRA_CALLER, callerNumber)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    // 着信通知の後片付け＋通話終了の通知（開始/終了/失敗で共用）
    private fun endRingingAndNotify(block: (Listener) -> Unit) {
        notificationHelper.cancelIncomingCallNotification()
        mainHandler.post { notificationHelper.stopRinging() }
        notifyListeners(block)
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
        showMainActivity(callerNumber)

        notifyListeners { it.onIncomingCall(callerNumber) }
    }

    override fun onCallStarted() {
        callActive = true
        endRingingAndNotify { it.onCallStarted() }
        showMainActivity()
    }

    override fun onCallEnded() {
        callActive = false
        endRingingAndNotify { it.onCallEnded() }
        // 通話中に保留した再登録があれば実行する
        if (networkRearmNeeded || sipManager?.isRegistered() != true) {
            reregisterIfReady("call ended")
        }
    }

    override fun onCallFailed(reason: String) {
        callActive = false
        endRingingAndNotify { it.onCallFailed(reason) }
        // 通話中に保留した再登録があれば実行する
        if (networkRearmNeeded || sipManager?.isRegistered() != true) {
            reregisterIfReady("call failed")
        }
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
    private fun onSip(block: (SipManager) -> Unit) {
        sipExecutor.execute { sipManager?.let(block) }
    }

    fun makeCall(number: String) {
        onSip { it.makeCall(number) }
    }

    fun answerCall() {
        mainHandler.post { notificationHelper.stopRinging() }
        onSip { it.answerCall() }
    }

    fun endCall() {
        mainHandler.post { notificationHelper.stopRinging() }
        onSip { it.endCall() }
    }

    fun sendDtmf(digit: Char) {
        onSip { it.sendDtmf(digit) }
    }

    fun setMute(mute: Boolean) {
        onSip { it.setMute(mute) }
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
