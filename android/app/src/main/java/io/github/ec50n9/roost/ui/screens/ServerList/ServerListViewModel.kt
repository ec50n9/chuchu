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
import io.github.ec50n9.roost.ui.screens.Terminal.hostIndicator
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
    private val hostNotices = MutableStateFlow<Map<Long, HostNotice>>(emptyMap())
    private val creatingHostIds = MutableStateFlow<Set<Long>>(emptySet())
    private val restoredHostIds = MutableStateFlow<Set<Long>>(emptySet())

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
            // Links, attachments and tabs change independently but are read together; folding them
            // into one value keeps the combine under the 5-argument typed overload.
            combine(sessionRepository.hostLinks, sessionRepository.connectedHostIds, sessionRepository.tabs) {
                    links,
                    attached,
                    tabs,
                ->
                LinkSnapshot(links, attached, tabs)
            },
            hostNotices,
            creatingHostIds,
        ) { hosts, linkSnapshot, notices, creating ->
            hosts.associate { host ->
                host.id to
                    runtimeFor(
                        host = host,
                        link = linkSnapshot.links[host.id],
                        attached = host.id in linkSnapshot.attached,
                        hostTabs = linkSnapshot.tabs.filter { it.spec.hostId == host.id },
                        notice = notices[host.id],
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
        // A host is linked because the user linked it, and `linked` is where that survives the app.
        // Restoring only re-lists the multiplexer sessions, so a restart lands on the same hub the
        // user left. Hosts that cannot link silently are skipped by the guard, never force-linked.
        viewModelScope.launch {
            hostRepository.observeAll().collect { hosts ->
                hosts.filter {
                    shouldRestoreLink(it, settingsRepository.requireAuthOnConnect.value)
                }.forEach { host ->
                    if (restoredHostIds.value.contains(host.id)) return@forEach
                    restoredHostIds.update { it + host.id }
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
            // A deleted host must not stay in the repository's link map: refreshSessionLists walks
            // it, so a leftover entry means probing a host that no longer exists on every return.
            sessionRepository.disconnectHost(id)
            hostRepository.delete(host)
        }
    }

    /**
     * Links the host. Both kinds end up in the same state: a multiplexer host fills in its remote
     * session list, a bare SSH host has no list, so linking it is the connectivity check. Nothing
     * here creates a session — that is what "+ new session" is for, once the link is up.
     */
    fun connect(hostId: Long, keyPassphrase: String? = null) {
        viewModelScope.launch {
            val spec = specForHost(hostId, keyPassphrase) ?: return@launch
            clearNotice(hostId)
            sessionRepository.connectHost(spec)
        }
    }

    fun disconnect(hostId: Long) {
        clearNotice(hostId)
        sessionRepository.disconnectHost(hostId)
    }

    fun refresh(hostId: Long) {
        clearNotice(hostId)
        sessionRepository.refreshHost(hostId)
    }

    /** Re-reads every linked host: sessions come and go on the remote while the terminal is up. */
    fun refreshLinkedHosts() {
        sessionRepository.refreshSessionLists()
    }

    fun newSession(hostId: Long, keyPassphrase: String? = null) {
        viewModelScope.launch {
            val spec = specForHost(hostId, keyPassphrase) ?: return@launch
            clearNotice(hostId)
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
            clearNotice(hostId)
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
        attached: Boolean,
        hostTabs: List<TabSession>,
        notice: HostNotice?,
        creating: Boolean,
    ): HostRuntime {
        val status = link?.status
        val linkError = link?.error?.takeIf { status == HostLinkStatus.Error }
        return HostRuntime(
            indicator = hostIndicator(status, attached),
            creating = creating,
            message = notice?.message ?: linkError,
            messageIsError = notice?.isError ?: (linkError != null),
            rows =
                if (host.multiplexer == null) {
                    // No remote list to read: the rows are the connections this app holds.
                    hostTabs.map { tab -> SessionRow(key = tab.id, localSessionId = tab.id) }
                } else {
                    multiplexerSessionRows(
                        remoteSessions = link?.remoteSessions.orEmpty(),
                        localSessionIdsByRemoteName =
                            hostTabs
                                .mapNotNull { tab ->
                                    tab.spec.multiplexerSessionName?.let { name -> name to tab.id }
                                }
                                .toMap(),
                    )
                },
        )
    }

    private fun setActionError(hostId: Long, error: Throwable) {
        setNotice(hostId, error.message ?: "Failed to create the session", isError = true)
    }

    private fun clearNotice(hostId: Long) {
        hostNotices.update { it - hostId }
    }

    private fun setNotice(hostId: Long, message: String, isError: Boolean) {
        hostNotices.update { it + (hostId to HostNotice(message, isError)) }
    }

    private data class HostNotice(val message: String, val isError: Boolean)

    private data class LinkSnapshot(
        val links: Map<Long, HostLink>,
        val attached: Set<Long>,
        val tabs: List<TabSession>,
    )

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

internal fun shouldRestoreLink(host: HostProfile, requireAuthOnConnect: Boolean): Boolean =
    host.linked &&
        !requireAuthOnConnect &&
        !host.requireAuthOnConnect &&
        (host.authMethod != AuthMethod.KeyWithPassphrase || host.keyPassphrase.isNotBlank())
