package io.github.ec50n9.roost.ui.screens.ServerList

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ec50n9.roost.data.db.AppDatabase
import io.github.ec50n9.roost.data.repository.HostRepository
import io.github.ec50n9.roost.data.repository.SettingsRepository
import io.github.ec50n9.roost.data.repository.SshKeyRepository
import io.github.ec50n9.roost.model.AuthMethod
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.service.terminal.HostLink
import io.github.ec50n9.roost.service.terminal.HostLinkStatus
import io.github.ec50n9.roost.service.terminal.HostKeyPrompt
import io.github.ec50n9.roost.service.terminal.TabSession
import io.github.ec50n9.roost.service.terminal.TabSpec
import io.github.ec50n9.roost.service.terminal.TerminalSessionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServerListViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val sessionRepository = TerminalSessionRepository.getInstance(application)

    private val searchQuery = MutableStateFlow("")
    private val actionErrors = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val creatingHostIds = MutableStateFlow<Set<Long>>(emptySet())
    private val autoConnectedHostIds = MutableStateFlow<Set<Long>>(emptySet())

    val hosts: StateFlow<List<HostProfile>> = hostRepository.observeAll()
        .combine(searchQuery) { hosts, query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@combine hosts
            hosts.filter { host ->
                host.name.contains(trimmed, ignoreCase = true) ||
                    host.host.contains(trimmed, ignoreCase = true) ||
                    host.username.contains(trimmed, ignoreCase = true)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    val hostRuntime: StateFlow<Map<Long, HostRuntime>> =
        combine(
            hostRepository.observeAll(),
            sessionRepository.hostLinks,
            sessionRepository.tabs,
            actionErrors,
            creatingHostIds,
        ) { hosts, links, tabs, errors, creating ->
            hosts.associate { host ->
                host.id to
                    runtimeFor(
                        host = host,
                        link = links[host.id],
                        hostTabs = tabs.filter { it.spec.hostId == host.id },
                        actionError = errors[host.id],
                        creating = creating.contains(host.id),
                    )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = sessionRepository.hostKeyPrompt

    /** Session the server list just opened; the nav graph turns this into a route. */
    private val _openSession = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openSession: SharedFlow<String> = _openSession.asSharedFlow()

    val search: StateFlow<String> = searchQuery.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "",
    )

    init {
        // Auto-connect is a per-host opt-in: link the host so its sessions show up without a tap.
        viewModelScope.launch {
            hostRepository.observeAll().collect { hosts ->
                hosts.filter {
                    shouldAutoConnect(it, settingsRepository.requireAuthOnConnect.value)
                }.forEach { host ->
                    if (autoConnectedHostIds.value.contains(host.id)) return@forEach
                    autoConnectedHostIds.update { it + host.id }
                    connect(host.id)
                }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun deleteServer(id: Long) {
        viewModelScope.launch {
            val host = hostRepository.getById(id) ?: return@launch
            hostRepository.delete(host)
        }
    }

    /** Links the host and, on a host without a multiplexer, opens its first session. */
    fun connect(hostId: Long, keyPassphrase: String? = null) {
        viewModelScope.launch {
            val spec = specForHost(hostId, keyPassphrase) ?: return@launch
            sessionRepository.connectHost(spec)?.let { _openSession.tryEmit(it.id) }
        }
    }

    fun disconnect(hostId: Long) {
        clearActionError(hostId)
        sessionRepository.disconnectHost(hostId)
    }

    fun refresh(hostId: Long) {
        clearActionError(hostId)
        sessionRepository.refreshHost(hostId)
    }

    /** Re-reads every linked host: sessions come and go on the remote while the terminal is up. */
    fun refreshLinkedHosts() {
        sessionRepository.hostLinks.value.keys.forEach(sessionRepository::refreshHost)
    }

    fun newSession(hostId: Long, keyPassphrase: String? = null) {
        viewModelScope.launch {
            val spec = specForHost(hostId, keyPassphrase) ?: return@launch
            clearActionError(hostId)
            if (!spec.usesRuntimeMultiplexer) {
                _openSession.tryEmit(sessionRepository.openTab(spec).id)
                return@launch
            }
            // Resolving a free name costs an SSH round trip; show that work on the card instead of
            // a button that looks like it did nothing until the terminal finally appears.
            if (hostId in creatingHostIds.value) return@launch
            creatingHostIds.update { it + hostId }
            try {
                runCatching {
                    val name =
                        sessionRepository.resolveMultiplexerSessionName(
                            spec = spec.copy(
                                multiplexerSessionName = null,
                                multiplexerCreateIfMissing = true,
                            ),
                            // "+ new session" always makes a new one: a detached chuchu-N is already a
                            // row on the card, and reusing it here made the button attach to it
                            // instead.
                            reuseDetachedChuchuSession = false,
                        )
                    sessionRepository.openTab(
                        spec.copy(multiplexerSessionName = name, multiplexerCreateIfMissing = true)
                    )
                }.onSuccess { tab ->
                    // The host's list is re-read once this session is actually attached (repository).
                    _openSession.tryEmit(tab.id)
                }.onFailure { error -> setActionError(hostId, error) }
            } finally {
                creatingHostIds.update { it - hostId }
            }
        }
    }

    /** Opens (attaching first when needed) the session behind a card row. */
    fun openSession(hostId: Long, row: SessionRow, keyPassphrase: String? = null) {
        row.localSessionId?.let { sessionId ->
            sessionRepository.selectTab(sessionId)
            _openSession.tryEmit(sessionId)
            return
        }
        val remoteName = row.remoteName ?: return
        viewModelScope.launch {
            val spec = specForHost(hostId, keyPassphrase) ?: return@launch
            clearActionError(hostId)
            val tab =
                sessionRepository.openTab(
                    spec.copy(
                        multiplexer = spec.multiplexer,
                        multiplexerSessionName = remoteName,
                        // Do not silently create a same-named session when it just died.
                        multiplexerCreateIfMissing = false,
                    )
                )
            _openSession.tryEmit(tab.id)
        }
    }

    fun onHostKeyDecision(accepted: Boolean) {
        sessionRepository.respondToHostKey(accepted)
    }

    private suspend fun specForHost(hostId: Long, keyPassphrase: String?): TabSpec? {
        val host = hostRepository.getById(hostId) ?: return null
        val key = host.keyId?.let { sshKeyRepository.getById(it) }
        return TabSpec.fromHostProfile(
            host = host,
            publicKeyOpenSsh = key?.publicKeyOpenSsh.orEmpty(),
            privateKeyPem = key?.privateKeyPem.orEmpty(),
            keyPassphrase = keyPassphrase ?: host.keyPassphrase,
        )
    }

    private fun runtimeFor(
        host: HostProfile,
        link: HostLink?,
        hostTabs: List<TabSession>,
        actionError: String?,
        creating: Boolean,
    ): HostRuntime {
        if (host.multiplexer == null) {
            return HostRuntime(
                linked = hostTabs.isNotEmpty(),
                error = actionError,
                rows =
                    hostTabs.map { tab ->
                        SessionRow(key = tab.id, localSessionId = tab.id)
                    },
            )
        }
        val status = link?.status ?: HostLinkStatus.Offline
        return HostRuntime(
            linked = status != HostLinkStatus.Offline,
            loading = status == HostLinkStatus.Connecting,
            creating = creating,
            error = actionError ?: link?.error.takeIf { status == HostLinkStatus.Error },
            rows =
                multiplexerSessionRows(
                    remoteSessions = link?.remoteSessions.orEmpty(),
                    localSessionIdsByRemoteName =
                        hostTabs
                            .mapNotNull { tab ->
                                tab.spec.multiplexerSessionName?.let { name -> name to tab.id }
                            }
                            .toMap(),
                ),
        )
    }

    private fun setActionError(hostId: Long, error: Throwable) {
        val message = error.message ?: "Failed to create the session"
        actionErrors.update { it + (hostId to message) }
    }

    private fun clearActionError(hostId: Long) {
        actionErrors.update { it - hostId }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ServerListViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ServerListViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

internal fun shouldAutoConnect(host: HostProfile, requireAuthOnConnect: Boolean): Boolean =
    host.autoConnect &&
        host.multiplexer != null &&
        !requireAuthOnConnect &&
        !host.requireAuthOnConnect &&
        (host.authMethod != AuthMethod.KeyWithPassphrase || host.keyPassphrase.isNotBlank())
