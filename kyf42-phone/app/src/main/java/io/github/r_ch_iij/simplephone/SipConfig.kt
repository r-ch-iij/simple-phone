package io.github.r_ch_iij.simplephone

import android.content.Context
import android.content.SharedPreferences

/**
 * SIP アカウント設定の保持（SharedPreferences ベース）。
 *
 * サーバ・ポート・ユーザ名・パスワード・レルム・キー割り当て・
 * 音量ゲインを [android.content.SharedPreferences] に永続化する。
 *
 * ## キーの仕組み
 * KYF42 の物理キー配置（十字キー周囲 4 ボタン＋ソフトキーバー）に対応する:
 * - キー1 (KEYCODE_F1/SK1): 左上 / ソフトキー左
 * - キー2 (KEYCODE_F2/SK2): 右上 / ソフトキー中央
 * - キー3 (KEYCODE_F3/SK3): 左下 / ソフトキー右
 * - キー4 (KEYCODE_F4/SK4): 右下
 *
 * 各キーに [ACTION_NONE] / [ACTION_REDIAL] / [ACTION_SETTINGS] /
 * [ACTION_CLEAR_ALL] / [ACTION_CLEAR_ONE] / [ACTION_CALL] / [ACTION_MUTE] /
 * [ACTION_VOLUME_UP] / [ACTION_VOLUME_DOWN] を
 * 割り当て可能。
 *
 * ## 音量ゲイン
 * [KEY_VOLUME] は 50〜250 の整数（%）。100 = 等倍、150 = 1.5 倍。
 * baresip の audio_mixer に渡す前に 100 で割って浮動小数点に変換する。
 *
 * @see SettingsActivity
 * @see MainActivity.handleKeyAction
 */
object SipConfig {
    const val PREFS = "sip_config"
    const val KEY_LAST_NUMBER = "last_number"
    const val KEY_SERVER = "server"
    const val KEY_PORT = "port"
    const val KEY_USER = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_REALM = "realm"
    const val KEY_RINGTONE = "ringtone_uri"
    const val KEY_VIBRATE = "vibrate"
    const val KEY_VOLUME = "volume_pct"   // 音量（再生ゲイン %）。100=等倍、150=1.5倍
    const val KEY_HISTORY = "call_history" // 発着信履歴（"epoch|種別|番号" を改行区切り、最大30件）

    // 統一キー設定（十字キー・ソフトキーバー共通）
    const val KEY_ACTION_1 = "action_key1"
    const val KEY_ACTION_2 = "action_key2"
    const val KEY_ACTION_3 = "action_key3"
    const val KEY_ACTION_4 = "action_key4"

    // キーの機能
    const val ACTION_NONE = "none"
    const val ACTION_REDIAL = "redial"        // リダイヤル
    const val ACTION_SETTINGS = "settings"    // 設定画面
    const val ACTION_CLEAR_ALL = "clear_all"  // 入力を全て消去
    const val ACTION_CLEAR_ONE = "clear_one"  // 1文字消去
    const val ACTION_CALL = "call"            // 発話（現在の番号で発信）
    const val ACTION_MUTE = "mute"            // ミュート切替（通話中）
    const val ACTION_VOLUME_UP = "volume_up"  // 通話音量上げ
    const val ACTION_VOLUME_DOWN = "volume_down" // 通話音量下げ
    const val ACTION_RESET = "reset"          // 初期設定に戻す（キー割り当てのみ）
    const val ACTION_HISTORY = "history"     // 発着信履歴を開く

    // デファルト値
    internal val ACTION_DEFAULTS = mapOf(
        KEY_ACTION_1 to ACTION_VOLUME_UP,
        KEY_ACTION_2 to ACTION_SETTINGS,
        KEY_ACTION_3 to ACTION_VOLUME_DOWN,
        KEY_ACTION_4 to ACTION_NONE
    )

    // 後方互換: 旧 FKEY_* 定数を新しい ACTION_* にエイリアス
    const val FKEY_F1 = KEY_ACTION_1
    const val FKEY_F2 = KEY_ACTION_2
    const val FKEY_F3 = KEY_ACTION_3
    const val FKEY_F4 = KEY_ACTION_4
    const val FKEY_NONE = ACTION_NONE
    const val FKEY_REDIAL = ACTION_REDIAL
    const val FKEY_SETTINGS = ACTION_SETTINGS
    const val FKEY_CLEAR_ALL = ACTION_CLEAR_ALL
    const val FKEY_CLEAR_ONE = ACTION_CLEAR_ONE
    const val FKEY_CALL = ACTION_CALL
    const val FKEY_MUTE = ACTION_MUTE
    const val FKEY_VOLUME_UP = ACTION_VOLUME_UP
    const val FKEY_VOLUME_DOWN = ACTION_VOLUME_DOWN
    const val SK1 = KEY_ACTION_1
    const val SK2 = KEY_ACTION_2
    const val SK3 = KEY_ACTION_3

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getServer(context: Context): String = prefs(context).getString(KEY_SERVER, "")!!
    fun getPort(context: Context): Int = prefs(context).getInt(KEY_PORT, 5060)
    fun getUser(context: Context): String = prefs(context).getString(KEY_USER, "")!!
    fun getPassword(context: Context): String = prefs(context).getString(KEY_PASSWORD, "")!!
    fun getRealm(context: Context): String = prefs(context).getString(KEY_REALM, "asterisk")!!
    fun getRingtoneUri(context: Context): String? = prefs(context).getString(KEY_RINGTONE, null)
    fun isVibrateEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VIBRATE, true)
    fun getVolumePct(context: Context): Int = prefs(context).getInt(KEY_VOLUME, 150)

    fun getAction(context: Context, key: String): String =
        prefs(context).getString(key, ACTION_DEFAULTS[key] ?: ACTION_NONE)!!

    // 後方互換
    fun getFKey(context: Context, key: String): String = getAction(context, key)
    fun getSoftKey(context: Context, key: String): String = getAction(context, key)

    fun isConfigured(context: Context): Boolean =
        getServer(context).isNotEmpty() && getUser(context).isNotEmpty() && getPassword(context).isNotEmpty()

    fun buildAor(context: Context): String {
        val user = getUser(context)
        val server = getServer(context)
        val password = getPassword(context)
        return "<sip:$user@$server>;auth_user=$user;auth_pass=$password"
    }

    fun set(context: Context, key: String, value: Any) {
        prefs(context).edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
            }
        }.apply()
    }

    fun resetToDefaults(context: Context) {
        prefs(context).edit().apply {
            ACTION_DEFAULTS.forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    fun fKeyLabel(action: String): String = when (action) {
        ACTION_NONE -> "無効"
        ACTION_REDIAL -> "リダイヤル"
        ACTION_CLEAR_ALL -> "全消去"
        ACTION_CLEAR_ONE -> "1文字削除"
        ACTION_CALL -> "発話"
        ACTION_MUTE -> "ミュート"
        ACTION_VOLUME_UP -> "音量▲"
        ACTION_VOLUME_DOWN -> "音量▼"
        ACTION_SETTINGS -> "設定"
        ACTION_RESET -> "初期設定に戻す"
        ACTION_HISTORY -> "履歴"
        else -> action
    }

    /** 発着信履歴の1件 */
    data class HistoryEntry(val epochMillis: Long, val direction: String, val number: String)

    /** 履歴種別 */
    object HistoryType {
        const val OUTGOING = "発信"
        const val INCOMING = "着信"
        const val MISSED = "不在"
    }

    private const val HISTORY_MAX = 30

    /** 履歴を先頭に追加（最大件数で切り捨て） */
    fun addHistory(context: Context, direction: String, number: String) {
        val entries = getHistory(context).toMutableList()
        entries.add(0, HistoryEntry(System.currentTimeMillis(), direction, number))
        val trimmed = entries.take(HISTORY_MAX)
        prefs(context).edit()
            .putString(KEY_HISTORY, trimmed.joinToString("\n") {
                "${it.epochMillis}|${it.direction}|${it.number}"
            })
            .apply()
    }

    /** 履歴を新しい順で取得（壊れた行は無視） */
    fun getHistory(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val epoch = parts[0].toLongOrNull() ?: return@mapNotNull null
                HistoryEntry(epoch, parts[1], parts[2])
            }
            .toList()
    }
}
