package io.github.ec50n9.roost.service.terminal

import io.github.ec50n9.roost.service.multiplexer.RemoteMultiplexerSession

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

/**
 * What a finished probe writes to `host_profiles.linked`, or `null` for "write nothing".
 *
 * Only an answered probe links a host. A failure writes nothing on purpose: refreshes run on every
 * return to the server list, so unlinking there would drop a link the user set on a passing network
 * blip. A host that never answered was never linked, so it is still not restored on the next launch.
 */
internal fun linkWriteAfterProbe(answered: Boolean): Boolean? = if (answered) true else null
