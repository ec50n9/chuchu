package io.github.ec50n9.roost.ui.screens.AddServer

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ec50n9.roost.R
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
class AddServerScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun fixedActionsAndSessionPersistenceAreVisible() = verifyScreen("add-server-screen.png")

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
    fun compactWidthKeepsActionsVisible() = verifyScreen("add-server-screen-compact.png")

    private fun verifyScreen(fileName: String) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        GhosttyThemeRegistry.init(context)
        val vm = AddServerViewModel(context, hostId = null)

        composeRule.setContent {
            ChuTheme(themeName = "Catppuccin Mocha") {
                AddServerScreen(vm = vm, onBack = {})
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.add_server_test_short))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.action_save))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.section_session_persistence))
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$fileName")
    }
}
