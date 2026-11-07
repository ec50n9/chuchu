package io.github.ec50n9.roost.ui.screens.ServerList

import io.github.ec50n9.roost.service.multiplexer.RemoteMultiplexerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplexerSessionRowsTest {
    @Test
    fun keepsTheRemoteOrder() {
        val rows =
            multiplexerSessionRows(
                remoteSessions =
                    listOf(
                        RemoteMultiplexerSession(name = "work", attached = false),
                        RemoteMultiplexerSession(name = "chuchu-2", attached = true),
                    ),
                localSessionIdsByRemoteName = emptyMap(),
            )

        assertEquals(listOf("work", "chuchu-2"), rows.map { it.key })
    }

    @Test
    fun unboundRemoteSessionIsNotMarkedAsOurs() {
        val rows =
            multiplexerSessionRows(
                remoteSessions = listOf(RemoteMultiplexerSession(name = "work", attached = true)),
                localSessionIdsByRemoteName = emptyMap(),
            )

        assertNull(rows.single().localSessionId)
        assertTrue(rows.single().attachedElsewhere)
    }

    @Test
    fun ourOwnAttachmentSupersedesAttachedElsewhere() {
        val rows =
            multiplexerSessionRows(
                remoteSessions = listOf(RemoteMultiplexerSession(name = "work", attached = true)),
                localSessionIdsByRemoteName = mapOf("work" to "tab-1"),
            )

        assertEquals("tab-1", rows.single().localSessionId)
        assertFalse(rows.single().attachedElsewhere)
    }

    @Test
    fun detachedSessionWithOurConnectionShowsNoMarker() {
        val rows =
            multiplexerSessionRows(
                remoteSessions = listOf(RemoteMultiplexerSession(name = "work", attached = false)),
                localSessionIdsByRemoteName = mapOf("work" to "tab-1"),
            )

        assertEquals("tab-1", rows.single().localSessionId)
        assertFalse(rows.single().attachedElsewhere)
    }

    @Test
    fun connectionToASessionTheRemoteNoLongerReportsGetsNoRow() {
        val rows =
            multiplexerSessionRows(
                remoteSessions = listOf(RemoteMultiplexerSession(name = "work", attached = false)),
                localSessionIdsByRemoteName = mapOf("gone" to "tab-9"),
            )

        assertEquals(listOf("work"), rows.map { it.key })
    }
}
