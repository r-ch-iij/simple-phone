package io.github.r_ch_iij.simplephone

import android.util.Log

/**
 * baresip ネイティブ SIP ライブラリへの JNI ブリッジ。
 *
 * Android SIP API (android.net.sip) を使用せず、baresip の C ライブラリ
 * (libbaresip.a + libre/librem) を JNI 経由で呼び出す。
 *
 * ## 初期化フロー
 * 1. [nativeInit] — baresip の libre/re ライブラリ初期化 + SIP アカウント設定
 * 2. [nativeSetCallback] — JNI コールバック（登録・通話イベント）を設定
 * 3. [nativeRegister] — SIP REGISTER 送信
 *
 * ## スレッドモデル
 * - baresip のイベントループ（re_main）は別スレッドで動作
 * - JNI コールバック（[Callback]）は re_main スレッドから呼ばれる
 * - [SipService] 内で [android.os.Handler] を使ってメインスレッドに転送する
 *
 * ## ミュート実装
 * [nativeSetMute] は baresip の audio モジュールで送信フレームをゼロフィルし、
 * 受信音声はそのまま出力する（片方向ミュート）。
 * audio.c の tx->muted フラグで制御する。
 *
 * @see SipManager
 * @see SipService
 */
class NativeSip {

    companion object {
        private const val TAG = "NativeSip"

        init {
            try {
                System.loadLibrary("native-sip")
                Log.i(TAG, "native-sip library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native-sip: ${e.message}")
            }
        }
    }

    interface Callback {
        fun onRegistered()
        fun onRegistrationFailed(reason: String)
        fun onIncomingCall(callerNumber: String)
        fun onCallStarted()
        fun onCallEnded()
        fun onCallFailed(reason: String)
        fun onDebug(message: String)
    }

    // JNI ネイティブ関数
    external fun nativeInit(
        server: String,
        port: Int,
        user: String,
        pass: String,
        realm: String
    )
    external fun nativeDestroy()
    external fun nativeRegister()
    external fun nativeUnregister()
    external fun nativeReregister(aor: String)
    external fun nativeMakeCall(number: String)
    external fun nativeAnswerCall()
    external fun nativeEndCall()
    external fun nativeSendDtmf(digit: Char)
    external fun nativeSetMute(mute: Boolean)
    external fun nativeIsRegistered(): Boolean
    external fun nativeIsInCall(): Boolean
    external fun nativeSetVolume(gain: Float)
    external fun nativeSetCallback(callback: Callback)
}
