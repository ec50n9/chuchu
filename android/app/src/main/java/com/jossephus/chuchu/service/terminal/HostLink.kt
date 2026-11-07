package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession

/**
 * A host that the user linked, and the sessions the remote multiplexer reports for it.
 *
 * Only multiplexer hosts have a link: a bare SSH host has no "connected but idle" state, so its
 * sessions are simply the connections this app is holding open (see [TerminalSessionRepository.tabsForHost]).
 */
enum class HostLinkStatus {
    Offline,
    Connecting,
    Online,
    Error,
}

data class HostLink(
    val status: HostLinkStatus = HostLinkStatus.Offline,
    val remoteSessions: List<RemoteMultiplexerSession> = emptyList(),
    val error: String? = null,
) {
    val isLive: Boolean
        get() = status == HostLinkStatus.Connecting || status == HostLinkStatus.Online
}
