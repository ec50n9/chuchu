package io.github.ec50n9.roost.ui.screens.ServerList

import io.github.ec50n9.roost.model.AuthMethod
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.MultiplexerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRestorePolicyTest {
    private val host =
        HostProfile(
            name = "work",
            host = "example.com",
            username = "user",
            multiplexer = MultiplexerType.Tmux,
            linked = true,
        )

    @Test
    fun onlyAConnectionThatCanBeMadeSilentlyIsRestored() {
        assertTrue(shouldRestoreLink(host, requireAuthOnConnect = false))
        assertFalse(shouldRestoreLink(host, requireAuthOnConnect = true))
        assertFalse(
            shouldRestoreLink(
                host.copy(requireAuthOnConnect = true),
                requireAuthOnConnect = false,
            )
        )
        // Both kinds of host restore: a bare SSH link is its connectivity check, not a session.
        assertTrue(shouldRestoreLink(host.copy(multiplexer = null), requireAuthOnConnect = false))
        assertFalse(shouldRestoreLink(host.copy(linked = false), requireAuthOnConnect = false))

        val encryptedKey = host.copy(authMethod = AuthMethod.KeyWithPassphrase)
        assertFalse(shouldRestoreLink(encryptedKey, requireAuthOnConnect = false))
        assertTrue(
            shouldRestoreLink(
                encryptedKey.copy(keyPassphrase = "secret"),
                requireAuthOnConnect = false,
            )
        )
    }
}
