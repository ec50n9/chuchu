package io.github.ec50n9.roost.service.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import io.github.ec50n9.roost.data.db.AppDatabase
import io.github.ec50n9.roost.data.repository.HostRepository
import io.github.ec50n9.roost.data.repository.SettingsRepository
import io.github.ec50n9.roost.model.MultiplexerType
import io.github.ec50n9.roost.model.Transport
import io.github.ec50n9.roost.service.multiplexer.MultiplexerRegistry
import io.github.ec50n9.roost.service.multiplexer.RemoteMultiplexerSession
import io.github.ec50n9.roost.service.ssh.HostKeyStore
import io.github.ec50n9.roost.service.ssh.TailscaleStatusChecker
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionRepository private constructor(application: Application) {
    private sealed interface PreflightAttempt<out T> {
        data class Completed<T>(val value: T) : PreflightAttempt<T>

        data class HostKeyVerificationRequired(
            val verification: PreflightHostKeyVerification,
        ) : PreflightAttempt<Nothing>
    }

    private data class PreflightHostKeyVerification(
        val prompt: HostKeyPrompt,
        val keyBytes: ByteArray,
    )

    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val hostKeyStore =
        HostKeyStore(appContext.getSharedPreferences(HostKeyStore.PREFS_NAME, Application.MODE_PRIVATE))
    private val tailscaleStatusChecker = TailscaleStatusChecker(appContext)
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)
    private val settingsRepository = SettingsRepository.getInstance(appContext)
    private val hostRepository = HostRepository(AppDatabase.getInstance(appContext).hostProfileDao())

    // PATHs learned by probing. Shared by every engine so one probe serves them all, and backed by
    // the host row so it also survives the app.
    private val remotePaths = ConcurrentHashMap<Long, String>()

    private suspend fun loadRemotePath(hostId: Long): String? =
        remotePaths[hostId] ?: hostRepository.getById(hostId)?.remotePath?.also { remotePaths[hostId] = it }

    private suspend fun storeRemotePath(hostId: Long, path: String) {
        remotePaths[hostId] = path
        // The probe is only persisted when it produced a working PATH, so this write is a repair,
        // not a guess. Skipping the unchanged case keeps list refreshes from rewriting the row.
        if (hostRepository.getById(hostId)?.remotePath == path) return
        hostRepository.updateRemotePath(hostId, path)
    }

    private fun publishTerminalClipboard(tabId: String, text: String) {
        if (!canPublishTerminalClipboard(tabId)) return
        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal clipboard", text))
    }

    private fun canPublishTerminalClipboard(tabId: String): Boolean {
        if (!settingsRepository.remoteClipboardWriteEnabled.value) return false
        return attachedClients > 0 && _activeTabId.value == tabId
    }

    private val _tabs = MutableStateFlow<List<TabSession>>(emptyList())
    val tabs: StateFlow<List<TabSession>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<TabSession?> =
        combine(_tabs, _activeTabId) { tabs, id -> tabs.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val sessionState: StateFlow<SessionState> =
        activeTab
            .flatMapLatest { tab -> tab?.sessionState ?: flowOf(SessionState()) }
            .stateIn(scope, SharingStarted.Eagerly, SessionState())

    private val _preflightHostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    private val preflightMutex = Mutex()
    private var preflightEngine: TerminalSessionEngine? = null
    @Volatile private var preflightHostKeyDecision: CompletableDeferred<Boolean>? = null
    @Volatile private var preflightGeneration = 0L

    private val activeHostKeyPrompt: StateFlow<HostKeyPrompt?> =
        activeTab
            .flatMapLatest { tab -> tab?.hostKeyPrompt ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> =
        combine(_preflightHostKeyPrompt, activeHostKeyPrompt) { preflight, active -> preflight ?: active }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val connectedHostIds: StateFlow<Set<Long>> =
        _tabs
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(tabs.map { tab -> tab.sessionState.map { state -> tab to state } }) {
                        it.toList()
                    }
                }
            }
            .map { pairs ->
                pairs
                    .asSequence()
                    .filter { (_, state) ->
                        state.status == SessionStatus.Connecting ||
                            state.status == SessionStatus.Connected ||
                            state.status == SessionStatus.Reconnecting
                    }
                    .mapNotNull { (tab, _) -> tab.spec.hostId }
                    .toSet()
            }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Tabs that were resumed from parked: attaching them again is not a first attach.
    private val resumedTabIds = ConcurrentHashMap.newKeySet<String>()
    private var attachedClients = 0
    private var foregroundServiceRunning = false
    private var foregroundNotificationLabel: String? = null
    // Ids of multiplexer sessions this app currently has attached. A session that just became
    // attached exists on the remote only now, so the host's list has to be re-read then — a probe
    // fired when the connection was requested would still see the old list.
    private var attachedMultiplexerTabIds: Set<String> = emptySet()
    // Tabs that have been live at least once. A session that reaches Disconnected is over, but a
    // tab is born in that state (before its connect call lands), so only a tab that was live counts.
    private val liveTabIds = mutableSetOf<String>()

    init {
        scope.launch {
            combine(_tabs, _activeTabId) { tabs, _ -> tabs }
                .flatMapLatest { tabs ->
                    if (tabs.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(tabs.map { tab -> tab.sessionState.map { tab to it } }) {
                            it.toList()
                        }
                    }
                }
                .collect { pairs ->
                    val attachedNow =
                        pairs
                            .asSequence()
                            .filter { (tab, state) ->
                                tab.spec.usesRuntimeMultiplexer &&
                                    state.status == SessionStatus.Connected
                            }
                            .map { (tab, _) -> tab }
                            .toList()
                    val justAttachedHosts =
                        attachedNow
                            .filter { tab ->
                                tab.id !in attachedMultiplexerTabIds && resumedTabIds.remove(tab.id) == false
                            }
                            .mapNotNull { it.spec.hostId }
                            .toSet()
                    attachedMultiplexerTabIds = attachedNow.map { it.id }.toSet()
                    justAttachedHosts.forEach { hostId -> refreshHost(hostId) }

                    closeEndedSessions(pairs)

                    val anyAlive =
                        pairs.any { (_, state) ->
                            state.status == SessionStatus.Connecting ||
                                state.status == SessionStatus.Connected ||
                                state.status == SessionStatus.Reconnecting
                        }
                    val label = if (anyAlive) currentNotificationLabel() else null
                    if (anyAlive && (!foregroundServiceRunning || foregroundNotificationLabel != label)) {
                        SessionForegroundService.start(appContext, label ?: "Active session")
                        foregroundServiceRunning = true
                        foregroundNotificationLabel = label
                    } else if (!anyAlive && foregroundServiceRunning) {
                        SessionForegroundService.stop(appContext)
                        foregroundServiceRunning = false
                        foregroundNotificationLabel = null
                    }
                }
        }
    }

    /**
     * A session whose remote program exited (`exit`, a killed multiplexer session, a finished local
     * shell) has nothing left to show: drop it, so it does not linger as a connection to nothing.
     * Errors are kept — those still have a message and a retry.
     */
    private fun closeEndedSessions(pairs: List<Pair<TabSession, SessionState>>) {
        pairs.forEach { (tab, state) ->
            // A parked session is deliberately disconnected; that is not the session ending.
            if (tab.parked || state.status != SessionStatus.Disconnected) liveTabIds.add(tab.id)
        }
        val ended =
            pairs
                .filter { (tab, state) ->
                    !tab.parked && state.status == SessionStatus.Disconnected && liveTabIds.remove(tab.id)
                }
                .map { (tab, _) -> tab.id }
        liveTabIds.retainAll(pairs.map { (tab, _) -> tab.id }.toSet())
        ended.forEach { closeTab(it) }
    }

    fun attachClient() {
        attachedClients += 1
        activeTab.value?.let(::watchTab)
    }

    fun detachClient() {
        attachedClients = (attachedClients - 1).coerceAtLeast(0)
        if (attachedClients == 0) parkUnwatchedSessions()
    }

    /**
     * Nothing is rendering these sessions any more, and a multiplexer session does not need the app
     * to hold a connection to stay alive — the server keeps it running. So drop the local connection
     * instead of quietly reading a stream nobody sees. Bare SSH and local shells are the
     * process itself, so they stay connected.
     */
    private fun parkUnwatchedSessions() {
        _tabs.value
            .filter { tab -> tab.spec.usesRuntimeMultiplexer && !tab.parked }
            .forEach { tab ->
                tab.parked = true
                tab.engine.disconnect()
            }
    }

    /** Paint what is already there, or re-attach a parked session, when a screen starts watching. */
    private fun watchTab(tab: TabSession) {
        if (tab.parked) {
            tab.parked = false
            // A resumed session already exists remotely, so re-reading the host's session list (the
            // first-attach rule below) would only spend a round trip competing with this re-attach.
            resumedTabIds.add(tab.id)
            // The session lives on the server, so this only has to re-open the connection to it.
            reconnectTab(tab)
        } else {
            // Frames are dropped while nothing watches, so the screen needs one now.
            tab.engine.repaintNow()
        }
    }

    private fun currentNotificationLabel(): String {
        val tabs = _tabs.value
        if (tabs.isEmpty()) return "Active session"
        val active = tabs.firstOrNull { it.id == _activeTabId.value } ?: tabs.first()
        if (tabs.size == 1) return active.spec.notificationLabel
        return "${tabs.size} sessions  ·  ${active.spec.notificationLabel}"
    }

    fun tabsForHost(hostId: Long?): List<TabSession> =
        _tabs.value.filter { it.spec.hostId == hostId }

    // Multiplexer hosts the user linked, keyed by host id. A non-multiplexer host is never in
    // here: its sessions are the tabs themselves, so there is nothing to cache.
    private val _hostLinks = MutableStateFlow<Map<Long, HostLink>>(emptyMap())
    val hostLinks: StateFlow<Map<Long, HostLink>> = _hostLinks.asStateFlow()
    private val hostSpecs = ConcurrentHashMap<Long, TabSpec>()

    /**
     * Links a host so its multiplexer sessions can be listed, and returns the session tab when the
     * host has no multiplexer (there, connecting and having a session are the same act).
     */
    fun connectHost(spec: TabSpec): TabSession? {
        val hostId = spec.hostId ?: return null
        if (!spec.usesRuntimeMultiplexer) return openTab(spec)
        hostSpecs[hostId] = spec
        _hostLinks.update { links ->
            links + (hostId to HostLink(status = HostLinkStatus.Connecting))
        }
        launchHostSessionList(hostId, spec)
        return null
    }

    fun refreshHost(hostId: Long) {
        val spec = hostSpecs[hostId] ?: return
        if (!spec.usesRuntimeMultiplexer) return
        // A probe already in flight wins; concurrent listings would just race each other.
        if (_hostLinks.value[hostId]?.status == HostLinkStatus.Connecting) return
        _hostLinks.update { links ->
            // Keep the last known list visible while refreshing; only the probe result replaces it.
            val previous = links[hostId]
            links +
                (hostId to
                    HostLink(
                        status = HostLinkStatus.Connecting,
                        remoteSessions = previous?.remoteSessions.orEmpty(),
                    ))
        }
        launchHostSessionList(hostId, spec)
    }

    fun disconnectHost(hostId: Long) {
        tabsForHost(hostId).forEach { closeTab(it.id) }
        hostSpecs.remove(hostId)
        _hostLinks.update { links -> links - hostId }
    }

    private fun launchHostSessionList(hostId: Long, spec: TabSpec) {
        scope.launch {
            val listed = runCatching { listMultiplexerSessions(spec) }
            _hostLinks.update { links ->
                // A disconnect during the probe must win over its late answer.
                if (!hostSpecs.containsKey(hostId)) return@update links
                links + (
                    hostId to
                        listed.fold(
                            onSuccess = { HostLink(HostLinkStatus.Online, it) },
                            onFailure = { error ->
                                HostLink(
                                    status = HostLinkStatus.Error,
                                    error = error.message ?: "Failed to list sessions",
                                )
                            },
                        )
                )
            }
        }
    }

    fun openMultiplexerSessionNamesForHost(
        hostId: Long?,
        multiplexer: MultiplexerType,
    ): List<String> = tabsForHost(hostId)
        .filter { it.spec.multiplexer == multiplexer }
        .mapNotNull { it.spec.multiplexerSessionName }

    suspend fun resolveMultiplexerSessionName(
        spec: TabSpec,
        reuseDetachedChuchuSession: Boolean = false,
    ): String = withPreflightEngine { engine ->
        engine.resolveMultiplexerSessionName(
            spec = spec,
            localSessionNames = openMultiplexerSessionNamesForHost(
                hostId = spec.hostId,
                multiplexer = spec.multiplexer ?: MultiplexerRegistry.defaultType,
            ),
            reuseDetachedChuchuSession = reuseDetachedChuchuSession,
        )
    }

    suspend fun listMultiplexerSessions(spec: TabSpec): List<RemoteMultiplexerSession> =
        withPreflightEngine { engine -> engine.listMultiplexerSessions(spec) }

    private suspend fun <T> withPreflightEngine(block: suspend (TerminalSessionEngine) -> T): T {
        val generation = preflightGeneration
        return preflightMutex.withLock { runPreflightWithHostKeyRetries(generation, block) }
    }

    private suspend fun <T> runPreflightWithHostKeyRetries(
        generation: Long,
        block: suspend (TerminalSessionEngine) -> T,
    ): T {
        while (true) {
            when (val attempt = runPreflightAttempt(block)) {
                is PreflightAttempt.Completed -> return attempt.value
                is PreflightAttempt.HostKeyVerificationRequired -> {
                    if (!awaitPreflightHostKeyDecision(generation, attempt.verification)) {
                        throw IllegalStateException("Host key rejected")
                    }
                    ensurePreflightCurrent(generation)
                    val prompt = attempt.verification.prompt
                    hostKeyStore.saveKey(
                        prompt.host,
                        prompt.port,
                        prompt.algorithm,
                        attempt.verification.keyBytes,
                    )
                }
            }
        }
    }

    private fun ensurePreflightCurrent(generation: Long) {
        check(preflightGeneration == generation) { "Disconnected while preparing the connection" }
    }

    private suspend fun <T> runPreflightAttempt(
        block: suspend (TerminalSessionEngine) -> T,
    ): PreflightAttempt<T> {
        var verification: PreflightHostKeyVerification? = null
        val engine =
            TerminalSessionEngine(
                {},
                scope,
                newLocalShellService(),
                hostKeyStore,
                tailscaleStatusChecker,
                onHostKeyVerificationRequired = { prompt, keyBytes ->
                    verification = PreflightHostKeyVerification(prompt, keyBytes)
                },
                // Nothing renders a probe: listing sessions only needs the command output.
                hasAttachedClient = { false },
                loadRemotePath = ::loadRemotePath,
                storeRemotePath = ::storeRemotePath,
            )
        preflightEngine = engine
        return try {
            PreflightAttempt.Completed(block(engine))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            verification?.let { PreflightAttempt.HostKeyVerificationRequired(it) } ?: throw error
        } finally {
            // A preflight key check must not retain a live libssh2 session while the
            // user is deciding whether to trust the key.
            if (preflightEngine === engine) {
                preflightEngine = null
            }
            engine.dispose()
        }
    }

    private suspend fun awaitPreflightHostKeyDecision(
        generation: Long,
        verification: PreflightHostKeyVerification,
    ): Boolean {
        val decision = CompletableDeferred<Boolean>()
        preflightHostKeyDecision = decision
        return try {
            ensurePreflightCurrent(generation)
            _preflightHostKeyPrompt.value = verification.prompt
            decision.await()
        } finally {
            if (preflightHostKeyDecision === decision) {
                preflightHostKeyDecision = null
            }
            _preflightHostKeyPrompt.value = null
        }
    }

    fun openTab(spec: TabSpec): TabSession {
        val id = UUID.randomUUID().toString()
        val engine =
            TerminalSessionEngine(
                { text -> publishTerminalClipboard(id, text) },
                scope,
                newLocalShellService(),
                hostKeyStore,
                tailscaleStatusChecker,
                holdRepaintFrames = { settingsRepository.holdRepaintFrames.value },
                hasAttachedClient = { attachedClients > 0 },
                loadRemotePath = ::loadRemotePath,
                storeRemotePath = ::storeRemotePath,
            )
        val tab = TabSession(id, spec, engine)
        _tabs.value = _tabs.value + tab
        _activeTabId.value = id
        engine.connect(
            host = spec.host,
            port = spec.port,
            username = spec.username,
            password = spec.password,
            authMethod = spec.authMethod,
            publicKeyOpenSsh = spec.publicKeyOpenSsh,
            privateKeyPem = spec.privateKeyPem,
            keyPassphrase = spec.keyPassphrase,
            transport = spec.transport,
            sessionKey = sessionKeyFor(tab),
            postConnectCommand = spec.postConnectCommand,
            multiplexer = spec.multiplexer,
            multiplexerSessionName = spec.multiplexerSessionName,
            multiplexerCreateIfMissing = spec.multiplexerCreateIfMissing,
            remotePath = spec.remotePath,
            hostId = spec.hostId,
        )
        return tab
    }

    fun selectTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _activeTabId.value = id
        watchTab(tab)
    }

    fun closeTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            val nextSameHost = remaining.firstOrNull { it.spec.hostId == tab.spec.hostId }
            _activeTabId.value = nextSameHost?.id ?: remaining.firstOrNull()?.id
        }
        tab.engine.dispose()
    }

    fun reconnectActive() {
        val tab = activeTab.value ?: return
        reconnectTab(tab)
    }

    fun switchActiveMultiplexerSession(sessionName: String): Boolean {
        val tab = activeTab.value ?: return false
        val type = tab.spec.multiplexer ?: MultiplexerRegistry.defaultType
        MultiplexerRegistry.forType(type) ?: return false
        val updatedSpec = tab.spec.copy(
            multiplexer = type,
            multiplexerSessionName = sessionName,
            multiplexerCreateIfMissing = false,
        )
        tab.spec = updatedSpec
        reconnectTab(tab)
        return true
    }

    fun reconnectTab(tab: TabSession) {
        val spec = tab.spec
        tab.engine.connect(
            host = spec.host,
            port = spec.port,
            username = spec.username,
            password = spec.password,
            authMethod = spec.authMethod,
            publicKeyOpenSsh = spec.publicKeyOpenSsh,
            privateKeyPem = spec.privateKeyPem,
            keyPassphrase = spec.keyPassphrase,
            transport = spec.transport,
            sessionKey = sessionKeyFor(tab),
            postConnectCommand = spec.postConnectCommand,
            multiplexer = spec.multiplexer,
            multiplexerSessionName = spec.multiplexerSessionName,
            multiplexerCreateIfMissing = spec.multiplexerCreateIfMissing,
            remotePath = spec.remotePath,
            hostId = spec.hostId,
        )
    }

    fun disconnect() {
        preflightGeneration += 1
        preflightHostKeyDecision?.cancel()
        preflightHostKeyDecision = null
        preflightEngine?.dispose()
        preflightEngine = null
        _preflightHostKeyPrompt.value = null
        val tabs = _tabs.value
        _tabs.value = emptyList()
        _activeTabId.value = null
        tabs.forEach { it.engine.dispose() }
    }

    private fun activeEngine(): TerminalSessionEngine? = activeTab.value?.engine

    private fun sessionKeyFor(tab: TabSession): String =
        if (tab.spec.transport == Transport.LocalShell) {
            "local-shell:${tab.id}"
        } else {
            tab.spec.sessionKey
        }

    private fun newLocalShellService(): NativeLocalShellService =
        NativeLocalShellService(appContext.filesDir, appContext.cacheDir)

    private fun activeSftpEngine(): TerminalSessionEngine? =
        activeTab.value?.takeIf { it.spec.transport != Transport.LocalShell }?.engine

    private fun sftpTab(tabId: String): TabSession? =
        _tabs.value.firstOrNull { it.id == tabId && it.spec.transport != Transport.LocalShell }

    private fun sftpEngineForTab(tabId: String): TerminalSessionEngine? = sftpTab(tabId)?.engine

    private suspend fun <T> withSftpSession(
        tabId: String,
        operation: suspend (TerminalSessionEngine) -> T,
    ): T? {
        val tab = sftpTab(tabId) ?: return null
        awaitSessionReady(tab)
        return try {
            operation(tab.engine)
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            if (!awaitReconnectAfterFailure(tab)) throw err
            operation(tab.engine)
        }
    }

    private suspend fun awaitSessionReady(tab: TabSession) {
        val settled =
            withTimeoutOrNull(SFTP_RECONNECT_TIMEOUT_MS) {
                tab.sessionState.first {
                    it.status != SessionStatus.Connecting && it.status != SessionStatus.Reconnecting
                }
            } ?: throw IllegalStateException("Timed out waiting for the session to reconnect")
        if (settled.status != SessionStatus.Connected) {
            throw IllegalStateException(
                settled.error?.let { "Session is not connected: $it" } ?: "Session is not connected"
            )
        }
    }

    private suspend fun awaitReconnectAfterFailure(tab: TabSession): Boolean {
        withTimeoutOrNull(SFTP_DROP_DETECTION_MS) {
            tab.sessionState.first { it.status != SessionStatus.Connected }
        } ?: return false
        awaitSessionReady(tab)
        return true
    }

    fun resize(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        activeEngine()?.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    fun scroll(delta: Int, x: Float, y: Float) {
        activeEngine()?.scroll(delta, x, y)
    }

    fun scrollToActive() {
        activeEngine()?.scrollToActive()
    }

    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null) {
        activeEngine()?.writeKey(key, codepoint, mods, action, utf8)
    }

    fun writeText(text: String) {
        activeEngine()?.writeText(text)
    }

    fun writePaste(text: String) {
        activeEngine()?.writePaste(text)
    }

    fun sendFocusEvent(focused: Boolean) {
        activeEngine()?.sendFocusEvent(focused)
    }

    fun sendMouseEvent(
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ) {
        activeEngine()?.sendMouseEvent(action, button, mods, x, y, anyButtonPressed, trackLastCell)
    }

    fun setColorScheme(isDark: Boolean) {
        _tabs.value.forEach { it.engine.setColorScheme(isDark) }
    }

    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        _tabs.value.forEach { it.engine.setDefaultColors(fg, bg, cursor, palette) }
    }

    fun respondToHostKey(accepted: Boolean) {
        preflightHostKeyDecision?.let { decision ->
            decision.complete(accepted)
            return
        }
        activeEngine()?.respondToHostKey(accepted)
    }

    suspend fun sftpListDirectory(path: String): List<String> =
        activeSftpEngine()?.sftpListDirectory(path) ?: emptyList()

    suspend fun sftpListDirectory(tabId: String, path: String): List<String> =
        withSftpSession(tabId) { it.sftpListDirectory(path) } ?: emptyList()

    suspend fun sftpRealpath(path: String): String = activeSftpEngine()?.sftpRealpath(path) ?: "/"

    suspend fun sftpRealpath(tabId: String, path: String): String =
        withSftpSession(tabId) { it.sftpRealpath(path) } ?: "/"

    suspend fun sftpOpenWrite(path: String) {
        activeSftpEngine()?.sftpOpenWrite(path)
    }

    suspend fun sftpOpenWrite(tabId: String, path: String) {
        withSftpSession(tabId) { it.sftpOpenWrite(path) }
    }

    suspend fun sftpWriteChunk(data: ByteArray): Int = activeSftpEngine()?.sftpWriteChunk(data) ?: 0

    suspend fun sftpWriteChunk(tabId: String, data: ByteArray): Int =
        sftpEngineForTab(tabId)?.sftpWriteChunk(data) ?: 0

    suspend fun sftpCloseWrite() {
        activeSftpEngine()?.sftpCloseWrite()
    }

    suspend fun sftpCloseWrite(tabId: String) {
        sftpEngineForTab(tabId)?.sftpCloseWrite()
    }

    suspend fun sftpReadFile(tabId: String, path: String, maxBytes: Int): ByteArray =
        withSftpSession(tabId) { it.sftpReadFile(path, maxBytes) } ?: ByteArray(0)

    suspend fun sftpDelete(tabId: String, path: String, isDirectory: Boolean) {
        withSftpSession(tabId) { it.sftpDelete(path, isDirectory) }
    }

    companion object {
        private const val SFTP_RECONNECT_TIMEOUT_MS = 20_000L
        private const val SFTP_DROP_DETECTION_MS = 1_000L

        @Volatile private var instance: TerminalSessionRepository? = null

        fun getInstance(application: Application): TerminalSessionRepository {
            return instance
                ?: synchronized(this) {
                    instance
                        ?: TerminalSessionRepository(application).also { created ->
                            instance = created
                        }
                }
        }
    }
}
