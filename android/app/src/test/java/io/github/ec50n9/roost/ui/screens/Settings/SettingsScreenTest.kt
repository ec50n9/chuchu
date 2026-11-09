package io.github.ec50n9.roost.ui.screens.Settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ec50n9.roost.R
import io.github.ec50n9.roost.data.repository.SettingsRepository
import io.github.ec50n9.roost.ui.screens.Keys.KeysViewModel
import io.github.ec50n9.roost.ui.theme.ChuTheme
import io.github.ec50n9.roost.ui.theme.GhosttyThemeRegistry
import io.github.ec50n9.roost.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun terminalContentScrollsUnderFixedHeaderAndTabs() {
        val context = setScreen()

        composeRule.onNodeWithText(context.getString(R.string.settings_category_terminal))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.settings_hold_repaint_frames))
            .performScrollTo()
        assertFixedHeaderVisible(context)
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings-terminal-scrolled.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
    fun compactWidthKeepsHeaderAndTabsVisible() {
        val context = setScreen()

        assertFixedHeaderVisible(context)
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings-compact.png")
    }

    private fun assertFixedHeaderVisible(context: Application) {
        composeRule.onNodeWithText(context.getString(R.string.settings_title)).assertIsDisplayed()
        composeRule.onNodeWithText("x").assertIsDisplayed()
        SettingsCategory.entries.forEach { category ->
            composeRule.onNodeWithText(context.getString(category.labelRes)).assertIsDisplayed()
        }
    }

    private fun setScreen(): Application {
        val context = ApplicationProvider.getApplicationContext<Application>()
        GhosttyThemeRegistry.init(context)
        composeRule.setContent {
            ChuTheme(themeName = SettingsRepository.DEFAULT_THEME) {
                SettingsScreen(
                    currentTheme = SettingsRepository.DEFAULT_THEME,
                    currentFont = SettingsRepository.DEFAULT_FONT,
                    appLockEnabled = false,
                    requireAuthOnConnect = false,
                    confirmBeforeDisconnect = true,
                    localShellEnabled = true,
                    keepScreenAwake = false,
                    hideScreenContents = false,
                    disableAutocorrect = false,
                    stableImeResize = false,
                    holdRepaintFrames = true,
                    remoteClipboardWriteEnabled = false,
                    currentAccessoryLayoutIds = emptyList(),
                    accessoryBarSingleRow = false,
                    currentTerminalCustomKeyGroups = emptyList(),
                    showCustomActionsFab = true,
                    onShowCustomActionsFabChanged = {},
                    themeMode = ThemeMode.Dark,
                    lightThemeName = SettingsRepository.DEFAULT_LIGHT_THEME,
                    onThemeSelected = {},
                    onThemeModeChanged = {},
                    onLightThemeSelected = {},
                    onFontSelected = {},
                    onAppLockEnabledChanged = {},
                    onRequireAuthOnConnectChanged = {},
                    onConfirmBeforeDisconnectChanged = {},
                    onLocalShellEnabledChanged = {},
                    onKeepScreenAwakeChanged = {},
                    onHideScreenContentsChanged = {},
                    onDisableAutocorrectChanged = {},
                    onStableImeResizeChanged = {},
                    onHoldRepaintFramesChanged = {},
                    onRemoteClipboardWriteEnabledChanged = {},
                    onAccessoryLayoutChanged = {},
                    onAccessoryBarSingleRowChanged = {},
                    onTerminalCustomActionsChanged = {},
                    keysViewModel = KeysViewModel(context),
                    onBack = {},
                )
            }
        }
        return context
    }
}
