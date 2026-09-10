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
import android.os.Vibrator
import android.util.Log

/**
 * 通知と着信音の管理を担当するヘルパークラス。
 *
 * SipService から通知ロジックを分離し、責務を明確にする。
 */
class NotificationHelper(private val context: Context) {

    companion object {
        private const val TAG = "NotificationHelper"
        const val CHANNEL_ID = "sip_call"
        const val INCOMING_CHANNEL_ID = "sip_incoming"
        const val NOTIFICATION_ID = 1
        private const val INCOMING_NOTIFICATION_ID = NOTIFICATION_ID + 1
        private const val MISSED_NOTIFICATION_ID = NOTIFICATION_ID + 2
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private var ringtone: Ringtone? = null

    fun createNotificationChannel() {
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

    fun buildForegroundNotification(text: String): Notification {
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("電話アプリ")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
    }

    fun showIncomingCallNotification(callerNumber: String) {
        // 応答 PendingIntent
        val answerIntent = Intent(context, SipService::class.java).apply {
            action = "ANSWER_CALL"
            putExtra("caller", callerNumber)
        }
        val answerPending = PendingIntent.getService(
            context, 2, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 拒否 PendingIntent
        val rejectIntent = Intent(context, SipService::class.java).apply {
            action = "REJECT_CALL"
        }
        val rejectPending = PendingIntent.getService(
            context, 3, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, INCOMING_CHANNEL_ID)
            .setContentTitle("着信中")
            .setContentText(callerNumber)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setFullScreenIntent(
                PendingIntent.getActivity(
                    context, 1,
                    Intent(context, MainActivity::class.java).apply {
                        action = SipService.INCOMING_CALL_ACTION
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ),
                true
            )
            .addAction(Notification.Action.Builder(
                null, "応答", answerPending
            ).build())
            .addAction(Notification.Action.Builder(
                null, "拒否", rejectPending
            ).build())
            .setAutoCancel(true)
            .build()
        notificationManager.notify(INCOMING_NOTIFICATION_ID, notification)
    }

    fun cancelIncomingCallNotification() {
        notificationManager.cancel(INCOMING_NOTIFICATION_ID)
    }

    fun showMissedCallNotification(caller: String) {
        cancelIncomingCallNotification()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("不在着信")
            .setContentText(caller)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
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
                ringtone?.isLooping = true
                ringtone?.play()
            }
            if (SipConfig.isVibrateEnabled(context)) {
                val pattern = longArrayOf(0, 500, 400, 500, 400)
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
