package io.github.r_ch_iij.simplephone

/**
 * 通話状態の有限状態マシン。
 *
 * - [Idle]: 待機中（番号入力可能）
 * - [Outgoing]: 発信中（相手応答待ち。取消可能）
 * - [Incoming]: 着信中（応答/拒否可能）
 * - [Active]: 通話中（DTMF 入力、ミュート、終話可能）
 */
sealed class CallState {
    /** 待機中 */
    data object Idle : CallState()

    /** 発信中 @property number 発信先番号 */
    data class Outgoing(val number: String) : CallState()

    /** 着信中 @property callerNumber 相手番号 */
    data class Incoming(val callerNumber: String) : CallState()

    /** 通話中 @property muted ミュート中かどうか */
    data class Active(val muted: Boolean = false) : CallState()

    val isInCall: Boolean get() = this is Active
    val isIncoming: Boolean get() = this is Incoming
    val isOutgoing: Boolean get() = this is Outgoing
    val isMuted: Boolean get() = this is Active && muted
}
