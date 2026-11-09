package io.github.ec50n9.roost.service.terminal

import io.github.ec50n9.roost.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
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
        else -> ReadLoopExitAction.AutomaticReconnect
    }
