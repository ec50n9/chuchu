package io.github.ec50n9.roost.service.terminal

import io.github.ec50n9.roost.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
    RequireManualReconnect,
    AutomaticReconnect,
}

/**
 * What to do when a read loop stops.
 *
 * [endedCleanly] means the session itself is over: the remote program exited (`exit`, a
 * `tmux kill-session`, ...) and the channel reported EOF. Reconnecting there would start a *new*
 * shell, or resurrect the multiplexer session the user just killed, under the old session's name.
 * A transport that dies mid-session leaves no EOF behind, and that is the case reconnect is for.
 */
internal fun readLoopExitAction(
    transport: Transport?,
    endedCleanly: Boolean = false,
): ReadLoopExitAction =
    when {
        endedCleanly -> ReadLoopExitAction.Disconnect
        transport == Transport.LocalShell -> ReadLoopExitAction.Disconnect
        transport == Transport.Mosh -> ReadLoopExitAction.RequireManualReconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }

internal class MoshContactTracker {
    private companion object {
        // Mosh starts warning about lost contact after roughly three seconds; matching that keeps the
        // terminal responsive without waiting for its intentionally long failure timeout.
        const val LOST_CONTACT_THRESHOLD_MS = 3_000L
    }

    private var lastPendingOutbound = 0
    private var lastAckProgressAtMs: Long? = null

    fun reset() {
        lastPendingOutbound = 0
        lastAckProgressAtMs = null
    }

    fun isContactLost(pendingOutbound: Int, nowMs: Long): Boolean {
        val previousPending = lastPendingOutbound
        lastPendingOutbound = pendingOutbound
        val lastProgress = lastAckProgressAtMs
        if (
            lastProgress == null ||
                previousPending == 0 ||
                pendingOutbound == 0 ||
                pendingOutbound < previousPending
        ) {
            lastAckProgressAtMs = nowMs
            return false
        }
        return nowMs - lastProgress >= LOST_CONTACT_THRESHOLD_MS
    }
}
