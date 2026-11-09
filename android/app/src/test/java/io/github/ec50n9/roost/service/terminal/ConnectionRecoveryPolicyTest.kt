package io.github.ec50n9.roost.service.terminal

import io.github.ec50n9.roost.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun remoteShellTransportsKeepAutomaticReconnect() {
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.SSH),
        )
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.TailscaleSSH),
        )
    }

    @Test
    fun aSessionThatEndedIsNotReconnected() {
        // `exit` inside the session, or a killed multiplexer session: reconnecting would start a
        // new shell (or resurrect the killed session) under the old session's name.
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.SSH, endedCleanly = true),
        )
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.TailscaleSSH, endedCleanly = true),
        )
    }

    @Test
    fun aDroppedLinkStillReconnects() {
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.SSH, endedCleanly = false),
        )
    }

    @Test
    fun localShellDisconnectsWithoutRemoteReconnect() {
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.LocalShell),
        )
    }
}
