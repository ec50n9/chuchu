package com.jossephus.chuchu.ui

import android.app.Application
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jossephus.chuchu.R
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.screens.AddServer.AddServerScreen
import com.jossephus.chuchu.ui.screens.AddServer.AddServerViewModel
import com.jossephus.chuchu.ui.screens.Keys.KeysViewModel
import com.jossephus.chuchu.ui.screens.ServerList.ServerListScreen
import com.jossephus.chuchu.ui.screens.ServerList.ServerListViewModel
import com.jossephus.chuchu.ui.screens.Settings.SettingsBackupViewModel
import com.jossephus.chuchu.ui.screens.Settings.SettingsScreen
import com.jossephus.chuchu.ui.screens.Terminal.TerminalScreen
import com.jossephus.chuchu.ui.screens.Terminal.TerminalViewModel
import com.jossephus.chuchu.ui.security.VerificationResult
import com.jossephus.chuchu.ui.security.requireUserVerification

@Composable
fun ApplicationNavController() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val lifecycleOwner = LocalLifecycleOwner.current
    var appUnlocked by rememberSaveable { mutableStateOf(false) }
    var unlockPromptRequested by rememberSaveable { mutableStateOf(false) }
    var appLockBlockedUntilToggle by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val isConfigChange =
                    (source as? android.app.Activity)?.isChangingConfigurations == true
                if (!isConfigChange) {
                    appUnlocked = false
                    unlockPromptRequested = false
                    appLockBlockedUntilToggle = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The library default is a 700ms crossfade. Between two screens that share a background it reads
    // as no animation at all, and it is over before the terminal has anything to show. A short slide
    // makes the screen change legible and finishes while the connection is still being made.
    NavHost(
        navController = navController,
        startDestination = "servers",
        enterTransition = {
            slideInHorizontally(animationSpec = tween(NAV_SLIDE_MS)) { it / 8 } +
                fadeIn(animationSpec = tween(NAV_SLIDE_MS))
        },
        exitTransition = { fadeOut(animationSpec = tween(NAV_FADE_MS)) },
        popEnterTransition = { fadeIn(animationSpec = tween(NAV_SLIDE_MS)) },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(NAV_SLIDE_MS)) { it / 8 } +
                fadeOut(animationSpec = tween(NAV_FADE_MS))
        },
    ) {
        composable("servers") {
            val vm: ServerListViewModel =
                viewModel(factory = ServerListViewModel.factory(application))
            val settingsRepo = SettingsRepository.getInstance(application)
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val hosts by vm.hosts.collectAsStateWithLifecycle()
            val hostRuntime by vm.hostRuntime.collectAsStateWithLifecycle()
            val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
            val searchQuery by vm.search.collectAsStateWithLifecycle()
            var pendingPassphraseAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
            var passphraseInput by remember { mutableStateOf("") }

            fun withConnectionVerification(hostId: Long, action: () -> Unit) {
                val hostRequiresAuth =
                    hosts.firstOrNull { it.id == hostId }?.requireAuthOnConnect == true
                if (!requireAuthOnConnect && !hostRequiresAuth) {
                    action()
                    return
                }
                requireUserVerification(
                    context = context,
                    title = context.getString(R.string.verification_connect_title),
                    subtitle = context.getString(R.string.verification_connect_subtitle),
                ) { result ->
                    if (result == VerificationResult.Success) action()
                }
            }

            fun withKeyPassphrase(hostId: Long, action: (String?) -> Unit) {
                val host = hosts.firstOrNull { it.id == hostId } ?: return
                if (host.authMethod == AuthMethod.KeyWithPassphrase && host.keyPassphrase.isBlank()) {
                    passphraseInput = ""
                    pendingPassphraseAction = { passphrase -> action(passphrase) }
                } else {
                    action(null)
                }
            }

            LaunchedEffect(vm) {
                // Back on the list, ask every linked host what it has now: sessions are created and
                // killed from the terminal, so the rows read on connect go stale.
                vm.refreshLinkedHosts()
                // Opening a session is a navigation the view model decides on (it may have to
                // attach first), so the graph just follows the requests.
                vm.openSession.collect { sessionId ->
                    navController.navigate("terminal/session/$sessionId")
                }
            }
            ServerListScreen(
                hosts = hosts,
                hostRuntime = hostRuntime,
                hostKeyPrompt = hostKeyPrompt,
                onHostKeyDecision = vm::onHostKeyDecision,
                searchQuery = searchQuery,
                onSearchChange = vm::updateSearchQuery,
                onAddServer = { navController.navigate("servers/add") },
                localShellEnabled = localShellEnabled,
                onOpenLocalShell = localShell@{
                        if (!localShellEnabled) return@localShell
                        if (!requireAuthOnConnect) {
                            navController.navigate("terminal/local")
                        } else {
                            requireUserVerification(
                                context = context,
                                title = context.getString(R.string.verification_local_shell_title),
                                subtitle = context.getString(R.string.verification_local_shell_subtitle),
                            ) { result ->
                                if (result == VerificationResult.Success) {
                                    navController.navigate("terminal/local")
                                }
                            }
                        }
                    },
                onEditServer = { id -> navController.navigate("servers/edit/$id") },
                onConnectServer = { id ->
                    withConnectionVerification(id) {
                        withKeyPassphrase(id) { passphrase -> vm.connect(id, passphrase) }
                    }
                },
                onOpenSession = { hostId, row ->
                    if (row.localSessionId != null) {
                        vm.openSession(hostId, row)
                    } else {
                        withConnectionVerification(hostId) {
                            withKeyPassphrase(hostId) { passphrase ->
                                vm.openSession(hostId, row, passphrase)
                            }
                        }
                    }
                },
                onNewSession = { id ->
                    withConnectionVerification(id) {
                        withKeyPassphrase(id) { passphrase -> vm.newSession(id, passphrase) }
                    }
                },
                onRefreshSessions = vm::refresh,
                onDisconnectServer = vm::disconnect,
                onDeleteServer = vm::deleteServer,
                onOpenSettings = { navController.navigate("settings") },
            )

            val passphraseAction = pendingPassphraseAction
            if (passphraseAction != null) {
                ChuDialog(
                    title = context.getString(R.string.terminal_key_passphrase),
                    confirmLabel = context.getString(R.string.dialog_confirm_connect),
                    onConfirm = {
                        pendingPassphraseAction = null
                        passphraseAction(passphraseInput)
                        passphraseInput = ""
                    },
                    onDismiss = {
                        pendingPassphraseAction = null
                        passphraseInput = ""
                    },
                ) {
                    ChuTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = context.getString(R.string.field_passphrase),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        composable("settings") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val backupViewModel: SettingsBackupViewModel =
                viewModel(factory = SettingsBackupViewModel.factory(application))
            val keysViewModel: KeysViewModel =
                viewModel(factory = KeysViewModel.factory(application))
            val themeName by settingsRepo.themeName.collectAsStateWithLifecycle()
            val fontName by settingsRepo.fontName.collectAsStateWithLifecycle()
            val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val accessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
            val accessoryBarSingleRow by
                settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
            val customKeyGroups by
                settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
            val showCustomActionsFab by
                settingsRepo.showCustomActionsFab.collectAsStateWithLifecycle()
            val builtinShortcuts by settingsRepo.builtinShortcuts.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val keepScreenAwake by settingsRepo.keepScreenAwake.collectAsStateWithLifecycle()
            val hideScreenContents by settingsRepo.hideScreenContents.collectAsStateWithLifecycle()
            val disableAutocorrect by settingsRepo.disableAutocorrect.collectAsStateWithLifecycle()
            val stableImeResize by settingsRepo.stableImeResize.collectAsStateWithLifecycle()
            val holdRepaintFrames by settingsRepo.holdRepaintFrames.collectAsStateWithLifecycle()
            val remoteClipboardWriteEnabled by
                settingsRepo.remoteClipboardWriteEnabled.collectAsStateWithLifecycle()
            val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle()
            val terminalFontSize by settingsRepo.terminalFontSize.collectAsStateWithLifecycle()
            val lightThemeName by settingsRepo.lightThemeName.collectAsStateWithLifecycle()
            SettingsScreen(
                currentTheme = themeName,
                currentFont = fontName,
                appLockEnabled = appLockEnabled,
                requireAuthOnConnect = requireAuthOnConnect,
                localShellEnabled = localShellEnabled,
                keepScreenAwake = keepScreenAwake,
                hideScreenContents = hideScreenContents,
                disableAutocorrect = disableAutocorrect,
                stableImeResize = stableImeResize,
                holdRepaintFrames = holdRepaintFrames,
                remoteClipboardWriteEnabled = remoteClipboardWriteEnabled,
                currentAccessoryLayoutIds = accessoryLayoutIds,
                accessoryBarSingleRow = accessoryBarSingleRow,
                currentTerminalCustomKeyGroups = customKeyGroups,
                showCustomActionsFab = showCustomActionsFab,
                onShowCustomActionsFabChanged = settingsRepo::setShowCustomActionsFab,
                builtinShortcuts = builtinShortcuts,
                onBuiltinShortcutsChanged = settingsRepo::setBuiltinShortcuts,
                themeMode = themeMode,
                lightThemeName = lightThemeName,
                onThemeSelected = settingsRepo::setTheme,
                onThemeModeChanged = settingsRepo::setThemeMode,
                onLightThemeSelected = settingsRepo::setLightTheme,
                onFontSelected = settingsRepo::setFont,
                onAppLockEnabledChanged = settingsRepo::setAppLockEnabled,
                onRequireAuthOnConnectChanged = settingsRepo::setRequireAuthOnConnect,
                onLocalShellEnabledChanged = settingsRepo::setLocalShellEnabled,
                onKeepScreenAwakeChanged = settingsRepo::setKeepScreenAwake,
                onHideScreenContentsChanged = settingsRepo::setHideScreenContents,
                onDisableAutocorrectChanged = settingsRepo::setDisableAutocorrect,
                onStableImeResizeChanged = settingsRepo::setStableImeResize,
                onHoldRepaintFramesChanged = settingsRepo::setHoldRepaintFrames,
                onRemoteClipboardWriteEnabledChanged = settingsRepo::setRemoteClipboardWriteEnabled,
                onAccessoryLayoutChanged = settingsRepo::setAccessoryLayoutIds,
                onAccessoryBarSingleRowChanged = settingsRepo::setAccessoryBarSingleRow,
                currentTerminalFontSize = terminalFontSize,
                onTerminalFontSizeChanged = settingsRepo::setTerminalFontSize,
                onTerminalCustomActionsChanged = settingsRepo::setTerminalCustomKeyGroups,
                backupViewModel = backupViewModel,
                keysViewModel = keysViewModel,
                onBack = {
                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentRoute == "settings") {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable("servers/add") {
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, null))
            AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(
            route = "servers/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, id))
            AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable("terminal/local") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            if (localShellEnabled) {
                val vm: TerminalViewModel =
                    viewModel(factory = TerminalViewModel.factory(application))
                TerminalScreen(
                    vm = vm,
                    sessionId = null,
                    openLocalShell = true,
                    onOpenSettings = { navController.navigate("settings") },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LaunchedEffect(Unit) {
                    val popped = navController.popBackStack("servers", inclusive = false)
                    if (!popped) {
                        navController.navigate("servers") { launchSingleTop = true }
                    }
                }
            }
        }
        composable(
            route = "terminal/session/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
            TerminalScreen(
                vm = vm,
                sessionId = sessionId,
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() },
            )
        }
    }

    val settingsRepo = SettingsRepository.getInstance(application)
    val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
    if (!appLockEnabled) {
        appUnlocked = false
        unlockPromptRequested = false
        appLockBlockedUntilToggle = false
    }
    LaunchedEffect(appLockEnabled, appUnlocked, unlockPromptRequested, appLockBlockedUntilToggle) {
        if (
            appLockEnabled && !appUnlocked && !unlockPromptRequested && !appLockBlockedUntilToggle
        ) {
            unlockPromptRequested = true
            requireUserVerification(
                context = context,
                title = context.getString(R.string.verification_unlock_title),
                subtitle = context.getString(R.string.verification_unlock_subtitle),
            ) { result ->
                appUnlocked = result == VerificationResult.Success
                if (result != VerificationResult.Success) {
                    appLockBlockedUntilToggle = true
                }
                unlockPromptRequested = false
            }
        }
    }
}

private const val NAV_SLIDE_MS = 220

/** The outgoing screen leaves faster than the incoming one arrives; matching durations smear. */
private const val NAV_FADE_MS = 140
