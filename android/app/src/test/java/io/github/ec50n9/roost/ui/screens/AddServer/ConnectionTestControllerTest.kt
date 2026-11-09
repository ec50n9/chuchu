package io.github.ec50n9.roost.ui.screens.AddServer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTestControllerTest {
    @Test
    fun runningTestCannotBeStartedTwice() {
        val controller = ConnectionTestController()

        assertNotNull(controller.begin())
        assertNull(controller.begin())
        assertEquals(ConnectionTestStatus.Running, controller.state.value.status)
    }

    @Test
    fun parameterChangeRejectsTheOldResult() {
        val controller = ConnectionTestController()
        val token = controller.begin()!!

        controller.invalidate()
        controller.finish(token, ConnectionTestState(status = ConnectionTestStatus.Success))

        assertEquals(ConnectionTestStatus.Idle, controller.state.value.status)
    }

    @Test
    fun editingParametersDoesNotReopenTheRunningTest() {
        val controller = ConnectionTestController()
        val token = controller.begin()!!

        controller.invalidate()

        assertEquals(ConnectionTestStatus.Running, controller.state.value.status)
        assertNull(controller.begin())

        // The invalidated test still has to end the progress it started.
        controller.finish(token, ConnectionTestState(status = ConnectionTestStatus.Success))
        assertEquals(ConnectionTestStatus.Idle, controller.state.value.status)
    }

    @Test
    fun theNextTestAfterAnInvalidatedOneCanPublishItsResult() {
        val controller = ConnectionTestController()
        val abandoned = controller.begin()!!
        controller.invalidate()
        controller.finish(
            abandoned,
            ConnectionTestState(status = ConnectionTestStatus.Error, message = "stale"),
        )
        val latestToken = controller.begin()!!
        val result = ConnectionTestState(status = ConnectionTestStatus.Error, message = "failed")

        controller.finish(latestToken, result)

        assertEquals(result, controller.state.value)
    }
}
