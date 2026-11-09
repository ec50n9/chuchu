package io.github.ec50n9.roost.ui.screens.ServerList

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.MultiplexerType
import io.github.ec50n9.roost.model.Transport
import io.github.ec50n9.roost.ui.screens.Terminal.SessionIndicator
import io.github.ec50n9.roost.ui.theme.ChuTheme
import io.github.ec50n9.roost.ui.theme.GhosttyThemeRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerListScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun hostCardsHideConnectionDetailsAndShowOnlyEnabledCapabilities() {
        render(
            listOf(
                HostProfile(
                    id = 1,
                    name = "private server",
                    host = "example.com",
                    username = "root",
                    transport = Transport.TailscaleSSH,
                    multiplexer = MultiplexerType.Tmux,
                ),
                HostProfile(
                    id = 2,
                    name = "plain server",
                    host = "192.0.2.10",
                    username = "admin",
                ),
            )
        )

        composeRule.onNodeWithText("root", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("example.com", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("192.0.2.10", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("tailscale").assertIsDisplayed()
        composeRule.onNodeWithText("tmux").assertIsDisplayed()
        composeRule.onNodeWithText("ssh").assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/server-list-screen.png")
    }

    @Test
    fun sameNamedHostsFallBackToTheirAddress() {
        render(
            listOf(
                HostProfile(id = 1, name = "pi", host = "192.0.2.10", username = "pi"),
                HostProfile(id = 2, name = "pi", host = "pi.tailnet.ts.net", username = "pi"),
                HostProfile(id = 3, name = "laptop", host = "192.0.2.11", username = "e9"),
            )
        )

        composeRule.onNodeWithText("pi@192.0.2.10:22", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("pi@pi.tailnet.ts.net:22", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("e9@192.0.2.11:22", substring = true).assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/server-list-duplicate-names.png")
    }

    @Test
    fun aBareHostOnlyOffersANewSessionOnceItsCheckCameBack() {
        val bare = HostProfile(id = 1, name = "plain server", host = "192.0.2.10", username = "admin")
        render(
            hosts = listOf(bare),
            runtime = mapOf(1L to HostRuntime(indicator = SessionIndicator.Connected)),
        )

        composeRule.onNodeWithText("+ new session").assertIsDisplayed()
        composeRule.onNodeWithText("disconnect").assertIsDisplayed()
    }

    @Test
    fun aFailedCheckShowsWhyAndOffersNoNewSession() {
        val bare = HostProfile(id = 1, name = "plain server", host = "192.0.2.10", username = "admin")
        render(
            hosts = listOf(bare),
            runtime =
                mapOf(
                    1L to
                        HostRuntime(
                            indicator = SessionIndicator.Error,
                            message = "Connection refused",
                            messageIsError = true,
                        )
                ),
        )

        composeRule.onNodeWithText("Connection refused").assertIsDisplayed()
        // Nothing answered, so there is nothing to list or create against. "connect" is the retry.
        composeRule.onNodeWithText("+ new session").assertDoesNotExist()
        composeRule.onNodeWithText("refresh").assertDoesNotExist()
        // A failed check leaves no link to tear down, so the button goes back to "connect".
        composeRule.onNodeWithText("disconnect").assertDoesNotExist()
        composeRule.onNodeWithText("connect").assertIsEnabled()
    }

    @Test
    fun aConnectingHostOffersNoSessionActions() {
        val bare = HostProfile(id = 1, name = "plain server", host = "192.0.2.10", username = "admin")
        render(
            hosts = listOf(bare),
            runtime = mapOf(1L to HostRuntime(indicator = SessionIndicator.Connecting)),
        )

        // A probe in flight is not a link yet: listing again would be queued behind it and creating
        // would start a second handshake. Both blink in when the dot turns green.
        composeRule.onNodeWithText("+ new session").assertDoesNotExist()
        composeRule.onNodeWithText("refresh").assertDoesNotExist()
    }

    @Test
    fun connectingDisablesTheButtonAndClaimsNoSessionCount() {
        val bare = HostProfile(id = 1, name = "plain server", host = "192.0.2.10", username = "admin")
        render(
            hosts = listOf(bare),
            runtime = mapOf(1L to HostRuntime(indicator = SessionIndicator.Connecting)),
        )

        // The dot carries the state, so the button must not pretend the link exists or can be
        // taken back: a blocking libssh2 connect ignores cancellation, so "cancel" would be a lie.
        composeRule.onNodeWithText("connect").assertIsNotEnabled()
        composeRule.onNodeWithText("disconnect").assertDoesNotExist()
        // Nothing is known yet, so the card must not claim there are no sessions.
        composeRule.onNodeWithText("no sessions yet").assertDoesNotExist()
    }

    @Test
    fun anUnlinkedHostHasNoSessionControlsAtAll() {
        val bare = HostProfile(id = 1, name = "plain server", host = "192.0.2.10", username = "admin")
        render(hosts = listOf(bare))

        composeRule.onNodeWithText("+ new session").assertDoesNotExist()
        composeRule.onNodeWithText("no sessions yet").assertDoesNotExist()
    }

    private fun render(hosts: List<HostProfile>, runtime: Map<Long, HostRuntime> = emptyMap()) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        GhosttyThemeRegistry.init(context)

        composeRule.setContent {
            ChuTheme(themeName = "Catppuccin Mocha") {
                ServerListScreen(
                    hosts = hosts,
                    hostRuntime = runtime,
                    hostKeyPrompt = null,
                    onHostKeyDecision = {},
                    searchQuery = "",
                    onSearchChange = {},
                    onAddServer = {},
                    localShellEnabled = false,
                    onOpenLocalShell = {},
                    onEditServer = {},
                    onConnectServer = {},
                    onOpenSession = { _, _ -> },
                    onNewSession = {},
                    onRefreshSessions = {},
                    confirmBeforeDisconnect = false,
                    onConfirmBeforeDisconnectChanged = {},
                    onDisconnectServer = {},
                    onDeleteServer = {},
                    onOpenSettings = {},
                )
            }
        }
    }
}
