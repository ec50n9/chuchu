package io.github.ec50n9.roost.ui.screens.ServerList

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import io.github.ec50n9.roost.R
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.Transport
import io.github.ec50n9.roost.service.terminal.HostKeyPrompt
import io.github.ec50n9.roost.service.terminal.TabSession
import io.github.ec50n9.roost.service.terminal.TerminalSessionRepository
import io.github.ec50n9.roost.ui.screens.Terminal.SessionIndicator
import io.github.ec50n9.roost.ui.screens.Terminal.sessionIndicator
import io.github.ec50n9.roost.ui.screens.Terminal.statusDotColor
import io.github.ec50n9.roost.ui.screens.Terminal.statusDotSymbol
import io.github.ec50n9.roost.ui.screens.Terminal.terminalTabDisplayLabel
import io.github.ec50n9.roost.ui.components.ChuButton
import io.github.ec50n9.roost.ui.components.ChuButtonVariant
import io.github.ec50n9.roost.ui.components.ChuCard
import io.github.ec50n9.roost.ui.components.ChuDialog
import io.github.ec50n9.roost.ui.components.HostKeyPromptDialog
import io.github.ec50n9.roost.ui.components.KeepImeClosed
import io.github.ec50n9.roost.ui.components.ChuText
import io.github.ec50n9.roost.ui.components.ChuTextField
import io.github.ec50n9.roost.ui.components.ChuSwitch
import io.github.ec50n9.roost.ui.components.TuiBadge
import io.github.ec50n9.roost.ui.theme.ChuColors
import io.github.ec50n9.roost.ui.theme.ChuTypography

/** Button height, bottom margin and a 12dp gap keep the last card clear of the FAB. */
private val FloatingActionInset = 72.dp

@Composable
fun ServerListScreen(
    hosts: List<HostProfile>,
    hostRuntime: Map<Long, HostRuntime>,
    hostKeyPrompt: HostKeyPrompt?,
    onHostKeyDecision: (Boolean) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAddServer: () -> Unit,
    localShellEnabled: Boolean,
    onOpenLocalShell: () -> Unit,
    onEditServer: (Long) -> Unit,
    onConnectServer: (Long) -> Unit,
    onOpenSession: (Long, SessionRow) -> Unit,
    onNewSession: (Long) -> Unit,
    onRefreshSessions: (Long) -> Unit,
    confirmBeforeDisconnect: Boolean,
    onConfirmBeforeDisconnectChanged: (Boolean) -> Unit,
    onDisconnectServer: (Long) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val sessionRepo = remember(application) { TerminalSessionRepository.getInstance(application) }
    val openTabs by sessionRepo.tabs.collectAsStateWithLifecycle()
    val hasOpenLocalShell = openTabs.any { it.spec.transport == Transport.LocalShell }

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var pendingDisconnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingConnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingLocalShell by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val hostId = pendingConnectHostId
        val openLocalShell = pendingLocalShell
        pendingConnectHostId = null
        pendingLocalShell = false
        when {
            openLocalShell -> onOpenLocalShell()
            hostId != null -> onConnectServer(hostId)
        }
    }
    // A session that ends leaves the terminal's keyboard behind; this screen has nothing to type into.
    KeepImeClosed()
    LaunchedEffect(hosts, selectedHostId) {
        val selected_id = selectedHostId
        if (selected_id != null && hosts.none { it.id == selected_id }) {
            selectedHostId = null
        }
    }

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    fun openLocalShellWithPermission() {
        if (needsNotificationPermission()) {
            pendingLocalShell = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onOpenLocalShell()
        }
    }

    fun requestDisconnect(hostId: Long) {
        if (confirmBeforeDisconnect) {
            pendingDisconnectHostId = hostId
        } else {
            selectedHostId = null
            onDisconnectServer(hostId)
        }
    }

    val colors = ChuColors.current
    val typography = ChuTypography.current
    // Two hosts with the same name are indistinguishable once the card hides the address, so the
    // address line comes back only for those.
    val duplicateNames = remember(hosts) {
        hosts.groupBy { it.name }.filterValues { it.size > 1 }.keys
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 12.dp, top = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChuText("$ ", style = typography.headline, color = colors.textMuted)
                    ChuText("roost", style = typography.headline)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSearchVisible && hosts.isNotEmpty()) {
                        ChuButton(
                            onClick = { isSearchVisible = true },
                            variant = ChuButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            ChuText(
                                stringResource(R.string.action_filter),
                                style = typography.label,
                                color = colors.textSecondary,
                            )
                        }
                    }
                    ChuButton(
                        onClick = onOpenSettings,
                        variant = ChuButtonVariant.Outlined,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        ChuText(
                            stringResource(R.string.settings_title),
                            style = typography.label,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            if (isSearchVisible) {
                ChuTextField(
                    value = searchQuery,
                    onValueChange = { value ->
                        onSearchChange(value)
                        if (value.isBlank()) isSearchVisible = false
                    },
                    label = "",
                    placeholder = stringResource(R.string.server_list_filter_placeholder),
                    singleLine = true,
                    showLabel = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader(stringResource(R.string.server_list_header_hosts))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = FloatingActionInset),
            ) {
                if (localShellEnabled) {
                    item(key = "local-shell") {
                        LocalShellCard(
                            isOpen = hasOpenLocalShell,
                            onOpen = { openLocalShellWithPermission() },
                        )
                    }
                }
                if (hosts.isEmpty()) {
                    item(key = "empty") {
                        if (searchQuery.isBlank()) {
                            EmptyState()
                        } else {
                            ChuText(
                                stringResource(R.string.server_list_no_hosts_match),
                                style = typography.body,
                                color = colors.textMuted,
                            )
                        }
                    }
                } else {
                    items(hosts, key = { it.id }) { host ->
                        val runtime = hostRuntime[host.id] ?: HostRuntime()
                        val disconnectBeforeDeleteMessage =
                            stringResource(R.string.server_list_disconnect_before_delete)
                        HostCard(
                            host = host,
                            showAddress = host.name in duplicateNames,
                            runtime = runtime,
                            openTabs = openTabs,
                            onOpenSession = { row -> onOpenSession(host.id, row) },
                            onNewSession = { onNewSession(host.id) },
                            onRefreshSessions = { onRefreshSessions(host.id) },
                            isSelected = selectedHostId == host.id,
                            onLongPress = { selectedHostId = host.id },
                            onCancelSelection = { selectedHostId = null },
                            onDeleteSelection = {
                                if (runtime.indicator == SessionIndicator.Connected) {
                                    Toast.makeText(
                                        context,
                                        disconnectBeforeDeleteMessage,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    onDeleteServer(host.id)
                                    selectedHostId = null
                                }
                            },
                            onEdit = {
                                selectedHostId = null
                                onEditServer(host.id)
                            },
                            onConnect = {
                                selectedHostId = null
                                if (needsNotificationPermission()) {
                                    pendingConnectHostId = host.id
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onConnectServer(host.id)
                                }
                            },
                            onDisconnect = { requestDisconnect(host.id) },
                        )
                    }
                }
            }
        }

        ChuButton(
            onClick = onAddServer,
            variant = ChuButtonVariant.Filled,
            bracketed = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .height(44.dp),
        ) {
            ChuText(stringResource(R.string.server_list_add_server), style = typography.label, color = colors.onAccent)
        }

        val pendingDisconnectHost = hosts.firstOrNull { it.id == pendingDisconnectHostId }
        if (pendingDisconnectHost != null) {
            var dontAskAgain by remember(pendingDisconnectHost.id) { mutableStateOf(false) }
            val sessionCount = openTabs.count { it.spec.hostId == pendingDisconnectHost.id }
            ChuDialog(
                title = stringResource(R.string.server_list_disconnect_title, pendingDisconnectHost.name),
                confirmLabel = stringResource(R.string.dialog_confirm_disconnect),
                dismissLabel = stringResource(R.string.dialog_dismiss),
                onConfirm = {
                    if (dontAskAgain) onConfirmBeforeDisconnectChanged(false)
                    selectedHostId = null
                    onDisconnectServer(pendingDisconnectHost.id)
                    pendingDisconnectHostId = null
                },
                onDismiss = { pendingDisconnectHostId = null },
            ) {
                ChuText(
                    when {
                        // Nothing attached: the link is the only thing going away.
                        sessionCount == 0 -> stringResource(R.string.server_list_disconnect_body_idle)
                        // A multiplexer session outlives its client, so this only detaches.
                        pendingDisconnectHost.multiplexer != null ->
                            pluralStringResource(
                                R.plurals.server_list_disconnect_body_detach,
                                sessionCount,
                                sessionCount,
                            )
                        // A bare SSH session *is* the connection: closing it ends the shell.
                        else ->
                            pluralStringResource(
                                R.plurals.server_list_disconnect_body_end,
                                sessionCount,
                                sessionCount,
                            )
                    },
                    style = typography.body,
                    color = colors.warning,
                )
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { dontAskAgain = !dontAskAgain }
                            .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChuText(
                        stringResource(R.string.server_list_disconnect_dont_ask_again),
                        style = typography.label,
                    )
                    ChuSwitch(
                        checked = dontAskAgain,
                        onCheckedChange = { dontAskAgain = it },
                    )
                }
            }
        }

        // A link probe verifies the host key just like a session does.
        HostKeyPromptDialog(
            prompt = hostKeyPrompt,
            onAccept = { onHostKeyDecision(true) },
            onReject = { onHostKeyDecision(false) },
        )
    }
}

/** What a screen reader hears for the host dot; the dot itself is colour only. */
@Composable
private fun hostIndicatorText(indicator: SessionIndicator): String =
    stringResource(
        when (indicator) {
            SessionIndicator.Connected -> R.string.session_status_connected
            SessionIndicator.Connecting -> R.string.session_status_connecting
            SessionIndicator.Error -> R.string.session_status_error
            SessionIndicator.Detached,
            SessionIndicator.Inactive -> R.string.session_status_disconnected
        }
    )

@Composable
private fun SectionHeader(label: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )
        ChuText(
            "── $label",
            style = typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.background(colors.background),
        )
    }
}

@Composable
private fun EmptyState() {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuCard(background = colors.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            ChuText(
                stringResource(R.string.server_list_empty_title),
                style = typography.body,
                color = colors.textMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ChuText(
                stringResource(R.string.server_list_empty_hint),
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalShellCard(
    isOpen: Boolean,
    onOpen: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    ChuCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(114.dp)
            .combinedClickable(onClick = onOpen),
        background = colors.surfaceVariant,
        border = if (isOpen) colors.success else colors.border,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChuText(
                        if (isOpen) "● " else "○ ",
                        style = typography.title,
                        color = if (isOpen) colors.success else colors.textMuted,
                    )
                    ChuText(stringResource(R.string.server_list_local_shell), style = typography.title)
                }
                TuiBadge(text = stringResource(R.string.badge_android), color = colors.accentSecondary)
            }
            Spacer(modifier = Modifier.height(2.dp))
            ChuText(
                stringResource(R.string.server_list_local_shell_subtitle),
                style = typography.body,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChuButton(
                    onClick = onOpen,
                    variant = ChuButtonVariant.Outlined,
                    bracketed = true,
                    borderColor = colors.accentSecondary,
                    contentDescription = stringResource(R.string.server_list_cd_open_local_shell),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ChuText(
                        stringResource(R.string.action_open),
                        style = typography.label,
                        color = colors.accentSecondary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: HostProfile,
    showAddress: Boolean,
    runtime: HostRuntime,
    openTabs: List<TabSession>,
    onOpenSession: (SessionRow) -> Unit,
    onNewSession: () -> Unit,
    onRefreshSessions: () -> Unit,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 132.dp.toPx() }
    val disconnectThresholdPx = with(density) { 72.dp.toPx() }
    val swipeOffsetX = remember(host.id) { Animatable(0f) }
    // Only a host that answered can be disconnected: connecting has nothing to tear down yet, and
    // a failed check left no link behind.
    val isConnected = runtime.indicator == SessionIndicator.Connected
    val isConnecting = runtime.indicator == SessionIndicator.Connecting
    val dotDescription = hostIndicatorText(runtime.indicator)
    // Tapping the card body folds the session list; it never connects and never enters a session.
    var sessionsExpanded by remember(host.id) { mutableStateOf(true) }

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            swipeOffsetX.snapTo(0f)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSelected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChuButton(
                        onClick = onCancelSelection,
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        ChuText(
                            stringResource(R.string.action_cancel),
                            style = typography.label,
                            color = colors.textSecondary,
                        )
                    }
                    ChuButton(
                        onClick = onDeleteSelection,
                        variant = ChuButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        ChuText(
                            stringResource(R.string.action_delete),
                            style = typography.label,
                            color = colors.error,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(colors.warning.copy(alpha = 0.82f))
                        .padding(end = 14.dp),
                ) {
                    ChuText(
                        stringResource(R.string.server_list_swipe_disconnect),
                        style = typography.label,
                        color = colors.background,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                    .fillMaxWidth()
                    .pointerInput(host.id, isConnected) {
                        if (!isConnected) {
                            return@pointerInput
                        }
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (swipeOffsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                                scope.launch { swipeOffsetX.snapTo(next) }
                            },
                            onDragEnd = {
                                if (swipeOffsetX.value <= -disconnectThresholdPx) {
                                    onDisconnect()
                                    scope.launch {
                                        swipeOffsetX.animateTo(0f, animationSpec = tween(140))
                                    }
                                } else {
                                    scope.launch {
                                        swipeOffsetX.animateTo(0f, animationSpec = tween(140))
                                    }
                                }
                            },
                        )
                    },
            ) {
                ChuCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { sessionsExpanded = !sessionsExpanded },
                            onLongClick = onLongPress,
                        ),
                    background = colors.surfaceVariant,
                    border = when {
                        isSelected -> colors.warning
                        isConnected -> colors.success
                        else -> colors.border
                    },
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChuText(
                                    statusDotSymbol(runtime.indicator) + " ",
                                    style = typography.title,
                                    color = statusDotColor(runtime.indicator, colors),
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription = dotDescription
                                        },
                                )
                                ChuText(
                                    host.name,
                                    style = typography.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChuButton(
                                    onClick = onEdit,
                                    variant = ChuButtonVariant.Ghost,
                                    bracketed = true,
                                    borderColor = colors.textMuted,
                                    testTag = "host_edit_${host.id}",
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    ChuText(
                                        stringResource(R.string.action_edit),
                                        style = typography.label,
                                        color = colors.textMuted,
                                    )
                                }
                                ChuButton(
                                    onClick = {
                                        if (isConnected) onDisconnect() else onConnect()
                                    },
                                    enabled = !isConnecting,
                                    variant = ChuButtonVariant.Outlined,
                                    bracketed = true,
                                    borderColor = if (isConnected) colors.warning else colors.accentSecondary,
                                    testTag = "host_connect_${host.id}",
                                    contentDescription =
                                        if (isConnected) {
                                            stringResource(R.string.server_list_cd_disconnect_from, host.name)
                                        } else {
                                            stringResource(R.string.server_list_cd_connect_to, host.name)
                                        },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    ChuText(
                                        if (isConnected) {
                                            stringResource(R.string.action_disconnect)
                                        } else {
                                            stringResource(R.string.action_connect)
                                        },
                                        style = typography.label,
                                        color = if (isConnected) colors.warning else colors.accentSecondary,
                                    )
                                }
                            }
                        }

                        if (showAddress) {
                            ChuText(
                                "> ${host.username}@${host.host}:${host.port}",
                                style = typography.body,
                                color = colors.textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (host.transport == Transport.TailscaleSSH || host.multiplexer != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (host.transport == Transport.TailscaleSSH) {
                                    TuiBadge(
                                        text = stringResource(R.string.badge_tailscale),
                                        color = colors.textMuted,
                                    )
                                }
                                host.multiplexer?.let { multiplexer ->
                                    TuiBadge(text = multiplexer.label, color = colors.textMuted)
                                }
                            }
                        }

                        // Sessions of this host: one tap opens (attaching first when the session
                        // lives in a remote multiplexer).
                        if (runtime.indicator != SessionIndicator.Inactive && sessionsExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            runtime.rows.forEach { row ->
                                HostSessionRow(
                                    row = row,
                                    tab = openTabs.firstOrNull { it.id == row.localSessionId },
                                    onClick = { onOpenSession(row) },
                                )
                            }
                            if (runtime.creating) {
                                HostSessionRowPlaceholder(stringResource(R.string.session_creating))
                            } else if (runtime.rows.isEmpty() && runtime.message == null && !isConnecting) {
                                // While connecting there is nothing to say yet; the dot carries it.
                                HostSessionRowPlaceholder(stringResource(R.string.session_none))
                            }
                            runtime.message?.let { message ->
                                ChuText(
                                    message,
                                    style = typography.labelSmall,
                                    color = if (runtime.messageIsError) colors.error else colors.textSecondary,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                )
                            }
                            // Both actions need a host that answered: while connecting there is
                            // nothing to list or create yet, and after a failed check the card's
                            // connect button is the retry.
                            if (isConnected) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ChuButton(
                                        onClick = onRefreshSessions,
                                        variant = ChuButtonVariant.Ghost,
                                        bracketed = true,
                                        borderColor = colors.textMuted,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        ChuText(
                                            stringResource(R.string.mux_refresh),
                                            style = typography.labelSmall,
                                            color = colors.textMuted,
                                        )
                                    }
                                    ChuButton(
                                        onClick = onNewSession,
                                        variant = ChuButtonVariant.Ghost,
                                        bracketed = true,
                                        borderColor = colors.accent,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        ChuText(
                                            stringResource(R.string.session_new),
                                            style = typography.labelSmall,
                                            color = colors.accent,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostSessionRowPlaceholder(label: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuText("└", style = typography.labelSmall, color = colors.textMuted)
        ChuText(label, style = typography.body, color = colors.textMuted)
    }
}

@Composable
private fun HostSessionRow(row: SessionRow, tab: TabSession?, onClick: () -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val state =
        if (tab == null) {
            null
        } else {
            val collected by tab.sessionState.collectAsStateWithLifecycle()
            collected
        }
    val label =
        row.remoteName
            ?: state?.title?.takeIf { it.isNotBlank() }
            ?: tab?.let { terminalTabDisplayLabel(it) }
            ?: row.key
    val indicator = sessionIndicator(state?.status, tab?.parked == true)
    val indicatorDescription =
        when (indicator) {
            SessionIndicator.Connected -> stringResource(R.string.session_status_connected)
            SessionIndicator.Connecting -> stringResource(R.string.session_status_connecting)
            SessionIndicator.Detached -> stringResource(R.string.session_status_detached)
            SessionIndicator.Inactive -> stringResource(R.string.session_status_unattached)
            SessionIndicator.Error -> stringResource(R.string.session_status_error)
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuText("└", style = typography.labelSmall, color = colors.textMuted)
        ChuText(
            statusDotSymbol(indicator),
            style = typography.labelSmall,
            color = statusDotColor(indicator, colors),
            modifier = Modifier.semantics { contentDescription = indicatorDescription },
        )
        ChuText(
            label,
            style = typography.body,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.attachedElsewhere) {
            ChuText(
                stringResource(R.string.mux_attached),
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
