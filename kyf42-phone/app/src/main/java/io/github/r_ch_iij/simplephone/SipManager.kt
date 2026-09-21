package io.github.r_ch_iij.simplephone

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * SIP マネージャー: baresip ネイティブライブラリ経由の SIP 通話を管理する。
 *
 * Android SIP API (android.net.sip) を使用せず、baresip の JNI ブリッジ
 * ([NativeSip]) を介して SIP 登録・発信・着信・DTMF を制御する。
 *
 * 音声ルーティング（[AudioManager.MODE_IN_COMMUNICATION] の切替）も
 * このクラスが担当し、通話開始時に ON、通話終了・失敗時に OFF する。
 *
 * ## ライフサイクル
 * 1. [start] — baresip 初期化 + SIP 登録
 * 2. [makeCall] / [answerCall] / [endCall] — 通話操作
 * 3. [applySettings] — 設定変更のライブ適用（再登録含む）
 * 4. [stop] — SIP 登録解除 + リソース解放
 *
 * ## スレッドモデル
 * baresip の操作はメインスレッドから呼び出すこと。
 * JNI コールバック（[NativeSip.Callback]）は baresip の re_main スレッド
 * から発火するため、[SipService] 内の [android.os.Handler] で
 * メインスレッドに転送してから [SipCallback] に通知する。
 *
 * @param context アプリケーションコンテキスト
 * @param callback SIP イベント通知先（[SipService] が実装）
 * @see NativeSip
 * @see SipService
 */
class SipManager(private val context: Context, private val callback: SipCallback) {

    companion object {
        private const val TAG = "SipManager"
    }

    // SipService が実装する通知先。NativeSip.Callback と同一型
    //（別 interface を持つと 1:1 コピーの二重管理になる）。
    typealias SipCallback = NativeSip.Callback

    private val nativeSip = NativeSip()
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private fun setAudioMode(inCall: Boolean) {
        audioManager.mode = if (inCall) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }
    }

    private fun volumeGain(): Float = SipConfig.getVolumePct(context) / 100.0f

    fun start() {
        val server = SipConfig.getServer(context)
        val port = SipConfig.getPort(context)
        val user = SipConfig.getUser(context)
        val password = SipConfig.getPassword(context)
        val realm = SipConfig.getRealm(context)

        Log.d(TAG, "start: server=$server:$port user=$user realm=$realm")

        // NativeSip コールバック設定
        nativeSip.nativeSetCallback(object : NativeSip.Callback {
            override fun onRegistered() {
                Log.d(TAG, "onRegistered")
                callback.onRegistered()
            }

            override fun onRegistrationFailed(reason: String) {
                Log.e(TAG, "onRegistrationFailed: $reason")
                callback.onRegistrationFailed(reason)
            }

            override fun onIncomingCall(callerNumber: String) {
                Log.d(TAG, "onIncomingCall: $callerNumber")
                callback.onIncomingCall(callerNumber)
            }

            override fun onCallStarted() {
                Log.d(TAG, "onCallStarted")
                setAudioMode(inCall = true)
                callback.onCallStarted()
            }

            override fun onCallEnded() {
                Log.d(TAG, "onCallEnded")
                setAudioMode(inCall = false)
                callback.onCallEnded()
            }

            override fun onCallFailed(reason: String) {
                Log.e(TAG, "onCallFailed: $reason")
                // 通話モードが残ると音声ルーティングが通話用のままになるため戻す
                setAudioMode(inCall = false)
                callback.onCallFailed(reason)
            }

            override fun onDebug(message: String) {
                Log.d(TAG, "onDebug: $message")
                callback.onDebug(message)
            }
        })

        // NativeSip 初期化・登録
        nativeSip.nativeInit(server, port, user, password, realm)
        // 音量（再生ゲイン）を適用
        Log.d(TAG, "applying volume gain=${volumeGain()}")
        nativeSip.nativeSetVolume(volumeGain())
        nativeSip.nativeRegister()
        callback.onDebug("SIP 登録中...")
    }

    fun makeCall(number: String) {
        if (number == SipConfig.getUser(context)) {
            callback.onCallFailed("自分自身には発信できません")
            return
        }
        nativeSip.nativeMakeCall(number)
    }

    // 設定をライブ適用（再初期化せず、baresip を崩さない）
    fun applySettings(server: String, port: Int, user: String,
                       password: String, realm: String, volumePct: Int,
                       accountChanged: Boolean) {
        // 音量ゲインは即時反映
        nativeSip.nativeSetVolume(volumePct / 100.0f)
        // アカウント変更時のみ UA を再作成して再登録（通話中の再登録は行わない）
        if (accountChanged) {
            nativeSip.nativeReregister(SipConfig.buildAor(context))
        }
        callback.onDebug("設定を適用しました" + if (accountChanged) "（再登録）" else "")
    }

    fun answerCall() {
        setAudioMode(inCall = true)
        nativeSip.nativeAnswerCall()
    }

    // ネットワーク復帰時などの再登録（UA は作り直さない）
    fun reregister() {
        Log.d(TAG, "reregister")
        nativeSip.nativeReregister(SipConfig.buildAor(context))
    }

    fun endCall() {
        nativeSip.nativeEndCall()
        setAudioMode(inCall = false)
    }

    fun sendDtmf(digit: Char) {
        nativeSip.nativeSendDtmf(digit)
    }

    // マイクのミュート切替（送信音声を無音化）
    fun setMute(mute: Boolean) {
        nativeSip.nativeSetMute(mute)
    }

    fun isRegistered(): Boolean = nativeSip.nativeIsRegistered()

    fun isInCall(): Boolean = nativeSip.nativeIsInCall()

    fun stop() {
        nativeSip.nativeUnregister()
        nativeSip.nativeDestroy()
        setAudioMode(inCall = false)
    }
}
