package io.github.r_ch_iij.simplephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Vibrator
import android.util.Log

/**
 * 通知と着信音の管理を担当するヘルパークラス。
 *
 * SipService から通知ロジックを分離し、責務を明確にする。
 *
 * KYF39 (API 22) 互換:
 * - NotificationChannel は API 26+ のみ。API 22 では作成しない
 * - Notification.Builder(context, channelId) は API 26+ のみ。API 22 では 1 引数版
 * - getSystemService(Class) は API 23+ のみ。API 22 では文字列版
 * - PendingIntent.FLAG_IMMUTABLE は API 23+ のみ。API 22 では付けない
 * - Notification.Action.Builder(Icon, ...) は API 23+ のみ。API 22 では int 版 addAction
 * - Ringtone.setLooping は API 28+ のみ。API 22 ではループなし
 */
class NotificationHelper(private val context: Context) {

    companion object {
        private const val TAG = "NotificationHelper"
        const val CHANNEL_ID = "sip_call"
        const val INCOMING_CHANNEL_ID = "sip_incoming"
        const val NOTIFICATION_ID = 1
        private const val INCOMING_NOTIFICATION_ID = NOTIFICATION_ID + 1
        private const val MISSED_NOTIFICATION_ID = NOTIFICATION_ID + 2

        // API 22 では FLAG_IMMUTABLE が存在しない（定数は inline されるため
        // 参照自体はビルドできるが、古い platform では無視されるビットになる）。
        // 明示的に分岐して意図を明確にする。
        fun pendingFlags(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        }
    }

    @Suppress("DEPRECATION")
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private var ringtone: Ringtone? = null

    fun createNotificationChannel() {
        // API 26 未満ではチャンネル概念がないため何もしない
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 着信通知用（HIGH: フルスクリーン Intent が必要）
        val incomingChannel = NotificationChannel(
            INCOMING_CHANNEL_ID, "着信通知", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "着信時にフルスクリーンで表示"
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(incomingChannel)

        // フォアグラウンドサービス用（LOW: 常時表示）
        val channel = NotificationChannel(
            CHANNEL_ID, "SIP 電話", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SIP 電話の通知"
        }
        notificationManager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    fun buildForegroundNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("電話アプリ")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .build()
        } else {
            Notification.Builder(context)
                .setContentTitle("電話アプリ")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .build()
        }
    }

    @Suppress("DEPRECATION")
    fun showIncomingCallNotification(callerNumber: String) {
        // 応答 PendingIntent
        val answerIntent = Intent(context, SipService::class.java).apply {
            action = "ANSWER_CALL"
            putExtra("caller", callerNumber)
        }
        val answerPending = PendingIntent.getService(
            context, 2, answerIntent, pendingFlags()
        )

        // 拒否 PendingIntent
        val rejectIntent = Intent(context, SipService::class.java).apply {
            action = "REJECT_CALL"
        }
        val rejectPending = PendingIntent.getService(
            context, 3, rejectIntent, pendingFlags()
        )

        val fullScreenPending = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply {
                action = SipService.INCOMING_CALL_ACTION
                putExtra(SipService.EXTRA_CALLER, callerNumber)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags()
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, INCOMING_CHANNEL_ID)
                .setContentTitle("着信中")
                .setContentText(callerNumber)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setFullScreenIntent(fullScreenPending, true)
                .addAction(android.R.drawable.ic_menu_call, "応答", answerPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒否", rejectPending)
                .setOngoing(true)
                .setAutoCancel(true)
                .build()
        } else {
            Notification.Builder(context)
                .setContentTitle("着信中")
                .setContentText(callerNumber)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setFullScreenIntent(fullScreenPending, true)
                .addAction(android.R.drawable.ic_menu_call, "応答", answerPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒否", rejectPending)
                .setOngoing(true)
                .setAutoCancel(true)
                .build()
        }
        notificationManager.notify(INCOMING_NOTIFICATION_ID, notification)
    }

    fun cancelIncomingCallNotification() {
        notificationManager.cancel(INCOMING_NOTIFICATION_ID)
    }

    @Suppress("DEPRECATION")
    fun showMissedCallNotification(caller: String) {
        cancelIncomingCallNotification()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("不在着信")
                .setContentText(caller)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build()
        } else {
            Notification.Builder(context)
                .setContentTitle("不在着信")
                .setContentText(caller)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build()
        }
        notificationManager.notify(MISSED_NOTIFICATION_ID, notification)
    }

    fun startRinging() {
        // デバッグビルドは無音（BuildConfig.ALERT_SILENT は buildType ごとに変数化）。
        // debug=true（無音）、release=false（着信音・バイブあり）
        if (BuildConfig.ALERT_SILENT) {
            Log.d(TAG, "silent mode (debug build): skip ringing")
            return
        }
        try {
            // 着信音の解決: 未設定(null)→システムデフォルト、
            // 空文字→無音（鳴らさない）、URI→指定音
            val uriString = SipConfig.getRingtoneUri(context)
            val uri = when {
                uriString == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                uriString.isEmpty() -> null
                else -> Uri.parse(uriString)
            }
            if (uri != null && (ringtone == null || ringtone!!.isPlaying.not())) {
                ringtone = RingtoneManager.getRingtone(context, uri)
                // setLooping は API 28+ のみ。API 22 では単発再生になる
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = true
                }
                ringtone?.play()
            }
            if (SipConfig.isVibrateEnabled(context)) {
                val pattern = longArrayOf(0, 500, 400, 500, 400)
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRinging failed", e)
        }
    }

    fun stopRinging() {
        try {
            ringtone?.stop()
            ringtone = null
            vibrator.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "stopRinging failed", e)
        }
    }
}
