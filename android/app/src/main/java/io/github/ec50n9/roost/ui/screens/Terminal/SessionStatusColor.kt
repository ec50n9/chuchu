package io.github.ec50n9.roost.ui.screens.Terminal

import androidx.compose.ui.graphics.Color
import io.github.ec50n9.roost.service.terminal.HostLinkStatus
import io.github.ec50n9.roost.service.terminal.SessionStatus
import io.github.ec50n9.roost.ui.theme.ChuColorPalette

enum class SessionIndicator {
    Connected,
    Connecting,
    Detached,
    Inactive,
    Error,
}

/** Local attachment and remote lifetime are different axes: parked is detached, not an error. */
internal fun sessionIndicator(status: SessionStatus?, parked: Boolean): SessionIndicator =
    when {
        status == SessionStatus.Error -> SessionIndicator.Error
        parked -> SessionIndicator.Detached
        status == SessionStatus.Connected -> SessionIndicator.Connected
        status == SessionStatus.Connecting || status == SessionStatus.Reconnecting ->
            SessionIndicator.Connecting
        else -> SessionIndicator.Inactive
    }

/**
 * The host card speaks the same four states as the dots under it, so one colour means one thing on
 * the screen. An attached session outranks a probe: a refresh would otherwise blink a working host
 * yellow on every return to the list.
 */
internal fun hostIndicator(link: HostLinkStatus?, attached: Boolean): SessionIndicator =
    when {
        attached || link == HostLinkStatus.Online -> SessionIndicator.Connected
        link == HostLinkStatus.Connecting -> SessionIndicator.Connecting
        link == HostLinkStatus.Error -> SessionIndicator.Error
        else -> SessionIndicator.Inactive
    }

internal fun statusDotColor(indicator: SessionIndicator, colors: ChuColorPalette): Color =
    when (indicator) {
        SessionIndicator.Connected,
        SessionIndicator.Detached -> colors.success
        // Yellow, not accent: on a themed palette accent is derived from the foreground and is
        // nearly white, which reads as "no state" on a dot.
        SessionIndicator.Connecting -> colors.warning
        SessionIndicator.Inactive -> colors.textMuted
        SessionIndicator.Error -> colors.error
    }

/**
 * Hollow means "nothing is attached or linked": a parked session, or a host with no link. Shape
 * carries that, so the dot never depends on colour alone.
 */
internal fun statusDotSymbol(indicator: SessionIndicator): String =
    when (indicator) {
        SessionIndicator.Detached,
        SessionIndicator.Inactive -> "○"
        SessionIndicator.Connected,
        SessionIndicator.Connecting,
        SessionIndicator.Error -> "●"
    }