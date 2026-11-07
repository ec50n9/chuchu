package io.github.ec50n9.roost.ui.screens.Terminal

import androidx.compose.ui.graphics.Color
import io.github.ec50n9.roost.service.terminal.SessionStatus
import io.github.ec50n9.roost.ui.theme.ChuColorPalette

/** One dot colour for a session everywhere: the terminal, the switcher, and the server list. */
internal fun statusDotColor(status: SessionStatus, colors: ChuColorPalette): Color = when (status) {
    SessionStatus.Connected -> colors.success
    SessionStatus.Connecting,
    SessionStatus.Reconnecting -> colors.accent
    SessionStatus.Disconnected,
    SessionStatus.Error -> colors.error
}
