package com.jossephus.chuchu.ui.screens.ServerList

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.jossephus.chuchu.R
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.screens.Terminal.statusDotColor
import com.jossephus.chuchu.ui.screens.Terminal.terminalTabDisplayLabel
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.HostKeyPromptDialog
import com.jossephus.chuchu.ui.components.KeepImeClosed
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.components.TuiBadge
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

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
    onDisconnectServer: (Long) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val sessionRepo = remember(application) { TerminalSessionRepository.getInstance(application) }
    val connectedHostIds by sessionRepo.connectedHostIds.collectAsStateWithLifecycle()
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

    val colors = ChuColors.current
    val typography = ChuTypography.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChuText("$ ", style = typography.headline, color = colors.textMuted)
                    ChuText("chuchu", style = typography.headline)
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

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        // A linked multiplexer host is connected even with no session attached:
                        // the link is what makes its session list visible.
                        val isConnected = host.id in connectedHostIds || runtime.linked
                        val disconnectBeforeDeleteMessage =
                            stringResource(R.string.server_list_disconnect_before_delete)
                        HostCard(
                            host = host,
                            isConnected = isConnected,
                            runtime = runtime,
                            openTabs = openTabs,
                            onOpenSession = { row -> onOpenSession(host.id, row) },
                            onNewSession = { onNewSession(host.id) },
                            onRefreshSessions = { onRefreshSessions(host.id) },
                            isSelected = selectedHostId == host.id,
                            onLongPress = { selectedHostId = host.id },
                            onCancelSelection = { selectedHostId = null },
                            onDeleteSelection = {
                                val is_selected_connected = host.id in connectedHostIds
                                if (is_selected_connected) {
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
                            onDisconnect = {
                                pendingDisconnectHostId = host.id
                            },
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
            val sessionCount = openTabs.count { it.spec.hostId == pendingDisconnectHost.id }
            ChuDialog(
                title = stringResource(R.string.server_list_disconnect_title, pendingDisconnectHost.name),
                confirmLabel = stringResource(R.string.dialog_confirm_disconnect),
                dismissLabel = stringResource(R.string.dialog_dismiss),
                onConfirm = {
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
    isConnected: Boolean,
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
    // Tapping the card body folds the session list; it never connects and never enters a session.
    var sessionsExpanded by remember(host.id) { mutableStateOf(true) }
    val hostLabel = "> ${host.username}@${host.host}:${host.port}"

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
                                    if (isConnected) "● " else "○ ",
                                    style = typography.title,
                                    color = if (isConnected) colors.success else colors.textMuted,
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

                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChuText(
                                hostLabel,
                                style = typography.body,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (host.transport == Transport.TailscaleSSH) {
                                TuiBadge(
                                    text = stringResource(R.string.badge_tailscale),
                                    color = colors.accent,
                                )
                            }
                        }

                        // Sessions of this host: one tap opens (attaching first when the session
                        // lives in a remote multiplexer).
                        if (runtime.linked && sessionsExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            runtime.rows.forEach { row ->
                                HostSessionRow(
                                    row = row,
                                    tab = openTabs.firstOrNull { it.id == row.localSessionId },
                                    onClick = { onOpenSession(row) },
                                )
                            }
                            if (runtime.loading) {
                                HostSessionRowPlaceholder(stringResource(R.string.session_listing))
                            } else if (runtime.rows.isEmpty() && runtime.error == null) {
                                HostSessionRowPlaceholder(stringResource(R.string.session_none))
                            }
                            runtime.error?.let { message ->
                                ChuText(
                                    message,
                                    style = typography.labelSmall,
                                    color = colors.error,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (host.multiplexer != null) {
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
            "●",
            style = typography.labelSmall,
            color = state?.let { statusDotColor(it.status, colors) } ?: colors.textMuted,
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
