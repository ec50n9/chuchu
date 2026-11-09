package io.github.ec50n9.roost.ui.screens.Terminal

import io.github.ec50n9.roost.service.terminal.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatusColorTest {
    @Test
    fun parkedDisconnectionIsDetachedRatherThanAnError() {
        val indicator = sessionIndicator(SessionStatus.Disconnected, parked = true)

        assertEquals(SessionIndicator.Detached, indicator)
        assertEquals("○", statusDotSymbol(indicator))
    }

    @Test
    fun ordinaryDisconnectionAndUnknownSessionsAreInactive() {
        assertEquals(
            SessionIndicator.Inactive,
            sessionIndicator(SessionStatus.Disconnected, parked = false),
        )
        assertEquals(SessionIndicator.Inactive, sessionIndicator(null, parked = false))
    }

    @Test
    fun attachedAndTransitionalStatesKeepTheirMeaning() {
        assertEquals(
            SessionIndicator.Connected,
            sessionIndicator(SessionStatus.Connected, parked = false),
        )
        assertEquals(
            SessionIndicator.Connecting,
            sessionIndicator(SessionStatus.Connecting, parked = false),
        )
        assertEquals(
            SessionIndicator.Connecting,
            sessionIndicator(SessionStatus.Reconnecting, parked = false),
        )
    }

    @Test
    fun errorWinsOverParked() {
        assertEquals(SessionIndicator.Error, sessionIndicator(SessionStatus.Error, parked = true))
    }
}
