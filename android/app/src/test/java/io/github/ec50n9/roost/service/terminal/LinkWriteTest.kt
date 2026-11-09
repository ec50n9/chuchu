package io.github.ec50n9.roost.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkWriteTest {
    @Test
    fun anAnsweredProbeLinksTheHost() {
        assertEquals(true, linkWriteAfterProbe(answered = true))
    }

    @Test
    fun aFailedProbeWritesNothing() {
        // Refreshes run on every return to the server list; writing `false` here would silently
        // drop a link the user set as soon as one refresh hits a bad network.
        assertNull(linkWriteAfterProbe(answered = false))
    }
}
