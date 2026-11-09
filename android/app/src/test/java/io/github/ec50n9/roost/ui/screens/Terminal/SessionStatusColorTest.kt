package io.github.ec50n9.roost.ui.screens.Terminal

import io.github.ec50n9.roost.service.terminal.HostLinkStatus
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
        // A host with no link gets the same hollow dot, so "nothing there" is a shape and not only
        // a colour.
        assertEquals("○", statusDotSymbol(SessionIndicator.Inactive))
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

    @Test
    fun aHostDotFollowsTheLinkAndNeverBlanksAWorkingHost() {
        assertEquals(
            SessionIndicator.Connected,
            hostIndicator(HostLinkStatus.Online, attached = false),
        )
        assertEquals(
            SessionIndicator.Connecting,
            hostIndicator(HostLinkStatus.Connecting, attached = false),
        )
        assertEquals(
            SessionIndicator.Error,
            hostIndicator(HostLinkStatus.Error, attached = false),
        )
        assertEquals(SessionIndicator.Inactive, hostIndicator(null, attached = false))
        assertEquals(
            SessionIndicator.Inactive,
            hostIndicator(HostLinkStatus.Offline, attached = false),
        )
        // An attached session outranks a re-probe, otherwise every refresh blinks the card yellow.
        assertEquals(
            SessionIndicator.Connected,
            hostIndicator(HostLinkStatus.Connecting, attached = true),
        )
        assertEquals(
            SessionIndicator.Connected,
            hostIndicator(HostLinkStatus.Error, attached = true),
        )
    }
}
