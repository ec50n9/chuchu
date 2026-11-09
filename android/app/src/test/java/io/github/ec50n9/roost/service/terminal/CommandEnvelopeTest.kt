package io.github.ec50n9.roost.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandEnvelopeTest {
    @Test
    fun keepsStderrThatArrivesAfterExitMarker() {
        val result = parseCommandEnvelope("0 main\nCHUCHU_EXIT:0\ntmux warning\n")

        assertEquals(0, result.exitCode)
        assertEquals("0 main\n\ntmux warning", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun usesLastMarkerWhenCommandOutputContainsMarkerText() {
        val result = parseCommandEnvelope("0 CHUCHU_EXIT:7\nCHUCHU_EXIT:0\n")

        assertEquals(0, result.exitCode)
        assertEquals("0 CHUCHU_EXIT:7", result.stdout)
        assertEquals("", result.stderr)
    }
}
