package io.github.ec50n9.roost.ui.screens.AddServer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ec50n9.roost.R
import io.github.ec50n9.roost.data.db.AppDatabase
import io.github.ec50n9.roost.data.repository.HostRepository
import io.github.ec50n9.roost.data.repository.KeyGenerationResult
import io.github.ec50n9.roost.data.repository.SshKeyRepository
import io.github.ec50n9.roost.model.AuthMethod
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.MultiplexerType
import io.github.ec50n9.roost.model.SshKey
import io.github.ec50n9.roost.model.Transport
import io.github.ec50n9.roost.service.ssh.HostKeyCheck
import io.github.ec50n9.roost.service.ssh.HostKeyPolicy
import io.github.ec50n9.roost.service.ssh.HostKeyStore
import io.github.ec50n9.roost.service.ssh.NativeSshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddServerViewModel(application: Application, private val hostId: Long?) :
    AndroidViewModel(application) {
    companion object {
        fun factory(application: Application, hostId: Long?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddServerViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AddServerViewModel(application, hostId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val hostKeyStore =
        HostKeyStore(
            application.applicationContext.getSharedPreferences(
                HostKeyStore.PREFS_NAME,
                Application.MODE_PRIVATE,
            )
        )

    private val _form = MutableStateFlow(AddServerForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    private val connectionTests = ConnectionTestController()
    val testState: StateFlow<ConnectionTestState> = connectionTests.state

    private val _allKeys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _allKeys.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    init {
        viewModelScope.launch { sshKeyRepository.observeAll().collect { _allKeys.value = it } }
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                val key = profile.keyId?.let { sshKeyRepository.getById(it) }
                _form.value =
                    AddServerForm(
                        id = profile.id,
                        name = profile.name,
                        host = profile.host,
                        port = profile.port.toString(),
                        username = profile.username,
                        password = profile.password,
                        keyId = profile.keyId,
                        privateKeyPem = key?.privateKeyPem ?: "",
                        publicKeyOpenSsh = key?.publicKeyOpenSsh ?: "",
                        keyPassphrase = profile.keyPassphrase,
                        transport = profile.transport,
                        authMethod = profile.authMethod,
                        requireAuthOnConnect = profile.requireAuthOnConnect,
                        postConnectCommand = profile.postConnectCommand.orEmpty(),
                        multiplexer = profile.multiplexer,
                        linked = profile.linked,
                        remotePath = profile.remotePath.orEmpty(),
                    )
            }
        }
    }

    fun updateName(name: String) {
        _form.value = _form.value.copy(name = name)
    }

    fun updateHost(host: String) {
        _form.value = _form.value.copy(host = host.filterNot { it.isWhitespace() })
        connectionTests.invalidate()
    }

    fun updatePort(port: String) {
        _form.value = _form.value.copy(port = port)
        connectionTests.invalidate()
    }

    fun updateUsername(username: String) {
        _form.value = _form.value.copy(username = username)
        connectionTests.invalidate()
    }

    fun updatePassword(password: String) {
        _form.value = _form.value.copy(password = password)
        connectionTests.invalidate()
    }

    fun updateKeyPassphrase(passphrase: String) {
        _form.value = _form.value.copy(keyPassphrase = passphrase)
        connectionTests.invalidate()
    }

    fun selectStoredKey(keyId: Long?) {
        viewModelScope.launch {
            val selected =
                keyId?.let { id ->
                    keys.value.firstOrNull { it.id == id } ?: sshKeyRepository.getById(id)
                }
            _form.value =
                _form.value.copy(
                    keyId = keyId,
                    privateKeyPem = selected?.privateKeyPem ?: "",
                    publicKeyOpenSsh = selected?.publicKeyOpenSsh ?: "",
                    keyPassphrase = "",
                )
            connectionTests.invalidate()
        }
    }

    fun generateKey(nameHint: String = "") {
        viewModelScope.launch {
            val current = _form.value
            val baseName = if (nameHint.isNotBlank()) nameHint else current.name
            when (val result = sshKeyRepository.generate(baseName, current.keyPassphrase)) {
                is KeyGenerationResult.Success -> {
                    _form.value =
                        _form.value.copy(
                            keyId = result.key.id,
                            privateKeyPem = result.key.privateKeyPem,
                            publicKeyOpenSsh = result.key.publicKeyOpenSsh,
                        )
                    connectionTests.invalidate()
                }
                KeyGenerationResult.Failed ->
                    _errorMessage.emit(
                        getApplication<Application>()
                            .getString(R.string.add_server_key_generation_failed)
                    )
            }
        }
    }

    fun updateTransport(transport: Transport) {
        val current = _form.value
        val nextAuthMethod =
            when {
                transport == Transport.SSH && current.authMethod == AuthMethod.None ->
                    AuthMethod.Password
                else -> current.authMethod
            }
        _form.value =
            current.copy(
                transport = transport,
                authMethod = nextAuthMethod,
                multiplexer = current.multiplexer.takeIf { it?.runtimeSupported == true },
            )
        connectionTests.invalidate()
    }

    fun updateAuthMethod(authMethod: AuthMethod) {
        val current = _form.value
        if (authMethod == AuthMethod.None) {
            _form.value =
                current.copy(transport = Transport.TailscaleSSH, authMethod = AuthMethod.None)
            connectionTests.invalidate()
            return
        }
        _form.value = current.copy(authMethod = authMethod)
        connectionTests.invalidate()
    }

    fun updateRequireAuthOnConnect(enabled: Boolean) {
        _form.value = _form.value.copy(requireAuthOnConnect = enabled)
    }

    fun updatePostConnectCommand(command: String) {
        _form.value = _form.value.copy(postConnectCommand = command)
    }

    fun updateMultiplexer(multiplexer: MultiplexerType?) {
        val current = _form.value
        val selected = multiplexer?.takeIf { it.runtimeSupported }
        _form.value = current.copy(multiplexer = selected)
    }

    fun testConnection() {
        val current = _form.value
        val host = current.host.filterNot { it.isWhitespace() }
        if (host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return
        val testToken = connectionTests.begin() ?: return
        viewModelScope.launch {
            var hostKeyError: String? = null
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val port = current.port.toIntOrNull() ?: 22
                        val policy = HostKeyPolicy { host, port, algorithm, keyBytes ->
                            when (
                                val result = hostKeyStore.check(host, port, algorithm, keyBytes)
                            ) {
                                is HostKeyCheck.Match -> true
                                is HostKeyCheck.Unknown -> {
                                    hostKeyError =
                                        getApplication<Application>()
                                            .getString(R.string.add_server_host_key_unverified)
                                    false
                                }
                                is HostKeyCheck.Changed -> {
                                    hostKeyError =
                                        getApplication<Application>()
                                            .getString(
                                                R.string.add_server_host_key_changed,
                                                result.previousFingerprint,
                                                result.fingerprint,
                                            )
                                    false
                                }
                            }
                        }
                        NativeSshService(hostKeyPolicy = policy).use { nativeSsh ->
                            check(nativeSsh.isAvailable()) {
                                getApplication<Application>()
                                    .getString(R.string.add_server_native_ssh_unavailable)
                            }
                            nativeSsh.connect(
                                host = host,
                                port = port,
                                username = username,
                                authMethod = current.authMethod,
                                password =
                                    if (current.authMethod == AuthMethod.Password) current.password
                                    else "",
                                publicKeyOpenSsh = current.publicKeyOpenSsh,
                                privateKeyPem = current.privateKeyPem,
                                keyPassphrase = current.keyPassphrase,
                            )
                        }
                    }
                }
            connectionTests.finish(
                testToken,
                if (result.isSuccess) {
                    ConnectionTestState(status = ConnectionTestStatus.Success)
                } else {
                    ConnectionTestState(
                        status = ConnectionTestStatus.Error,
                        message =
                            hostKeyError
                                ?: result.exceptionOrNull()?.message
                                ?: getApplication<Application>()
                                    .getString(R.string.terminal_connection_failed),
                    )
                },
            )
        }
    }

    fun save(onComplete: () -> Unit) {
        val current = _form.value
        val host = current.host.filterNot { it.isWhitespace() }
        val port = current.port.toIntOrNull() ?: 22
        if (!current.canSave() || host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return

        viewModelScope.launch {
            val profile =
                HostProfile(
                    id = current.id ?: 0L,
                    name = current.name.trim(),
                    host = host,
                    port = port,
                    username = username,
                    password = current.password,
                    keyId = current.keyId,
                    keyPassphrase = current.keyPassphrase,
                    transport = current.transport,
                    authMethod = current.authMethod,
                    requireAuthOnConnect = current.requireAuthOnConnect,
                    postConnectCommand = current.postConnectCommand.trim().ifBlank { null },
                    multiplexer = current.multiplexer.takeIf { it?.runtimeSupported == true },
                    linked = current.linked,
                    remotePath = current.remotePath.trim().ifBlank { null },
                )
            hostRepository.upsert(profile)
            onComplete()
        }
    }
}

data class AddServerForm(
    val id: Long? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val keyId: Long? = null,
    val privateKeyPem: String = "",
    val publicKeyOpenSsh: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
    val requireAuthOnConnect: Boolean = false,
    val postConnectCommand: String = "",
    val multiplexer: MultiplexerType? = null,
    // Not editable: connecting and disconnecting set it on the host row itself. The form still
    // carries it so saving an edited host does not silently unlink it.
    val linked: Boolean = false,
    // Not editable: the app probes the remote PATH and stores it in the host row. The form still
    // carries it so saving an edited host does not wipe what the probe found.
    val remotePath: String = "",
)

fun AddServerForm.canSave(): Boolean {
    if (name.isBlank() || host.isBlank() || username.isBlank()) return false
    return when (authMethod) {
        AuthMethod.Key,
        AuthMethod.KeyWithPassphrase -> keyId != null && privateKeyPem.isNotBlank()
        else -> true
    }
}

enum class ConnectionTestStatus {
    Idle,
    Running,
    Success,
    Error,
}

data class ConnectionTestState(
    val status: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val message: String? = null,
)

/** A test result belongs only to the connection parameters that started it. */
internal class ConnectionTestController {
    private var revision = 0L
    private val _state = MutableStateFlow(ConnectionTestState())
    val state: StateFlow<ConnectionTestState> = _state.asStateFlow()

    fun invalidate() {
        revision += 1
        // A running test keeps its progress on screen: clearing it would re-enable the button and
        // let a second connection start while the first handshake is still in flight.
        if (_state.value.status != ConnectionTestStatus.Running) {
            _state.value = ConnectionTestState()
        }
    }

    fun begin(): Long? {
        if (_state.value.status == ConnectionTestStatus.Running) return null
        revision += 1
        _state.value = ConnectionTestState(status = ConnectionTestStatus.Running)
        return revision
    }

    fun finish(token: Long, result: ConnectionTestState) {
        when {
            token == revision -> _state.value = result
            // The parameters changed while this test ran, so its result is worthless — but the
            // progress it put on screen must not stay stuck there.
            _state.value.status == ConnectionTestStatus.Running ->
                _state.value = ConnectionTestState()
        }
    }
}
