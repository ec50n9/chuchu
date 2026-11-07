package io.github.ec50n9.roost.ui.screens.Terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayedSessionTest {
    @Test
    fun switchingAndEndingSessionsKeepsTheCorrectScreen() {
        val switched =
            displayedSessionAfterActiveChange(
                routeSessionId = "a",
                displayedSessionId = "a",
                activeTabId = "b",
                existingSessionIds = setOf("a", "b"),
            )
        assertEquals("b", switched)

        val backgroundSessionEnded =
            displayedSessionAfterActiveChange(
                routeSessionId = "a",
                displayedSessionId = switched,
                activeTabId = "b",
                existingSessionIds = setOf("b"),
            )
        assertEquals("b", backgroundSessionEnded)

        val displayedSessionEnded =
            displayedSessionAfterActiveChange(
                routeSessionId = "a",
                displayedSessionId = switched,
                activeTabId = "a",
                existingSessionIds = setOf("a"),
            )
        assertEquals("b", displayedSessionEnded)
    }
}
