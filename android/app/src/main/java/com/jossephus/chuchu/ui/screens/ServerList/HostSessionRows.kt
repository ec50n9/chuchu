package com.jossephus.chuchu.ui.screens.ServerList

import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession

/**
 * One row under a host card.
 *
 * [localSessionId] is the connection this app holds for the row, when it holds one: a session
 * attached to a multiplexer, or — on a host with no multiplexer — the session itself.
 */
data class SessionRow(
    val key: String,
    val remoteName: String? = null,
    val localSessionId: String? = null,
    val attachedElsewhere: Boolean = false,
)

data class HostRuntime(
    val linked: Boolean = false,
    val loading: Boolean = false,
    /** A "+ new session" for this host is still resolving its name over SSH. */
    val creating: Boolean = false,
    val error: String? = null,
    val rows: List<SessionRow> = emptyList(),
)

/**
 * Rows for a multiplexer host: the remote session list is the truth, so a connection this app holds
 * to a session the remote no longer reports does not get a row of its own. Attaching here supersedes
 * the "attached elsewhere" marker, because that marker only means "some client is attached, and it
 * is not us".
 */
internal fun multiplexerSessionRows(
    remoteSessions: List<RemoteMultiplexerSession>,
    localSessionIdsByRemoteName: Map<String, String>,
): List<SessionRow> = remoteSessions.map { remote ->
    val localSessionId = localSessionIdsByRemoteName[remote.name]
    SessionRow(
        key = remote.name,
        remoteName = remote.name,
        localSessionId = localSessionId,
        attachedElsewhere = remote.attached && localSessionId == null,
    )
}
