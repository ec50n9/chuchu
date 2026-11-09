package io.github.ec50n9.roost.ui.screens.Terminal

import androidx.compose.ui.graphics.Color
import io.github.ec50n9.roost.service.terminal.SessionStatus
import io.github.ec50n9.roost.ui.theme.ChuColorPalette

internal enum class SessionIndicator {
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

internal fun statusDotColor(indicator: SessionIndicator, colors: ChuColorPalette): Color =
    when (indicator) {
        SessionIndicator.Connected,
        SessionIndicator.Detached -> colors.success
        SessionIndicator.Connecting -> colors.accent
        SessionIndicator.Inactive -> colors.textMuted
        SessionIndicator.Error -> colors.error
    }

internal fun statusDotSymbol(indicator: SessionIndicator): String =
    if (indicator == SessionIndicator.Detached) "○" else "●"
