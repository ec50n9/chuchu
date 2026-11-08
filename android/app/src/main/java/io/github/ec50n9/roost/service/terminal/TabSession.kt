package io.github.ec50n9.roost.service.terminal

import kotlinx.coroutines.flow.StateFlow

class TabSession(
    val id: String,
    var spec: TabSpec,
    val engine: TerminalSessionEngine,
) {
    /**
     * True while the session is left running on the server with no local connection, because no
     * screen is watching it. Only multiplexer sessions can be parked: for bare SSH, mosh and local
     * shells the connection *is* the process.
     */
    var parked: Boolean = false

    val sessionState: StateFlow<SessionState> get() = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> get() = engine.hostKeyPrompt
}

data class TabDescriptor(
    val id: String,
    val hostId: Long?,
    val label: String,
)
