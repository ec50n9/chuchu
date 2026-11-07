package io.github.ec50n9.roost.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionAllocatorTest {
    @Test
    fun reusesHighestDetachedManagedSessionOutsideLocalSessions() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("chuchu-1", attached = false),
                RemoteMultiplexerSession("roost-2", attached = false),
                RemoteMultiplexerSession("chuchu-3", attached = false),
                RemoteMultiplexerSession("chuchu-5", attached = true),
                RemoteMultiplexerSession("chuchu-7-extra", attached = false),
            ),
            localSessionNames = listOf("chuchu-3"),
        )

        assertEquals("roost-2", reusable)
    }

    @Test
    fun doesNotReuseAttachedOrLocalSessions() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("chuchu-1", attached = true),
                RemoteMultiplexerSession("chuchu-2", attached = false),
                RemoteMultiplexerSession("work", attached = false),
            ),
            localSessionNames = listOf("chuchu-2"),
        )

        assertEquals(null, reusable)
    }

    @Test
    fun skipsIndicesUsedByLegacyChuchuSessions() {
        val next = MultiplexerSessionAllocator.nextSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("chuchu-1", attached = false),
                RemoteMultiplexerSession("main", attached = true),
                RemoteMultiplexerSession("chuchu-3", attached = false),
            ),
            localSessionNames = listOf("chuchu-2"),
        )

        assertEquals("roost-4", next)
    }

    @Test
    fun ignoresNonManagedLocalNames() {
        val next = MultiplexerSessionAllocator.nextSessionName(
            remoteSessions = listOf(RemoteMultiplexerSession("chuchu-2", attached = false)),
            localSessionNames = listOf("chuchu-1-extra"),
        )

        assertEquals("roost-1", next)
    }

    @Test
    fun generatesRoostPrefixedNames() {
        val next = MultiplexerSessionAllocator.nextSessionName(
            remoteSessions = emptyList(),
            localSessionNames = emptyList(),
        )

        assertEquals("roost-1", next)
    }
}
