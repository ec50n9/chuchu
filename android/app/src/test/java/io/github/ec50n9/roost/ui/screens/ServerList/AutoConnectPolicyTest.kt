package io.github.ec50n9.roost.ui.screens.ServerList

import io.github.ec50n9.roost.model.AuthMethod
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.MultiplexerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {
    private val host =
        HostProfile(
            name = "work",
            host = "example.com",
            username = "user",
            multiplexer = MultiplexerType.Tmux,
            autoConnect = true,
        )

    @Test
    fun autoConnectOnlyUsesAnImmediatelyUsableConnection() {
        assertTrue(shouldAutoConnect(host, requireAuthOnConnect = false))
        assertFalse(shouldAutoConnect(host, requireAuthOnConnect = true))
        assertFalse(
            shouldAutoConnect(
                host.copy(requireAuthOnConnect = true),
                requireAuthOnConnect = false,
            )
        )
        assertFalse(shouldAutoConnect(host.copy(multiplexer = null), requireAuthOnConnect = false))

        val encryptedKey = host.copy(authMethod = AuthMethod.KeyWithPassphrase)
        assertFalse(shouldAutoConnect(encryptedKey, requireAuthOnConnect = false))
        assertTrue(
            shouldAutoConnect(
                encryptedKey.copy(keyPassphrase = "secret"),
                requireAuthOnConnect = false,
            )
        )
    }
}
