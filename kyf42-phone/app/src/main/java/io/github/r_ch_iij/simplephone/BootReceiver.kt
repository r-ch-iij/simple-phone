package io.github.r_ch_iij.simplephone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // フォアグラウンドサービスで SIP を起動（Android 10 では Activity 直接起動は制限される）
            SipService.start(context)
        }
    }
}
