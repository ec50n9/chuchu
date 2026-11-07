package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.ui.graphics.Color
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.ui.theme.ChuColorPalette

/** One dot colour for a session everywhere: the terminal, the switcher, and the server list. */
internal fun statusDotColor(status: SessionStatus, colors: ChuColorPalette): Color = when (status) {
    SessionStatus.Connected -> colors.success
    SessionStatus.Connecting,
    SessionStatus.Reconnecting -> colors.accent
    SessionStatus.Disconnected,
    SessionStatus.Error -> colors.error
}
