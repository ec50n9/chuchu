package io.github.ec50n9.roost.service.multiplexer

import io.github.ec50n9.roost.model.MultiplexerType

interface Multiplexer {
    val type: MultiplexerType

    fun availabilityCommand(): String

    fun listSessionsCommand(): String

    fun parseSessions(output: String): List<RemoteMultiplexerSession>

    fun launchCommand(
        sessionName: String,
        createIfMissing: Boolean,
        trustedRemoteName: Boolean = false,
    ): String

    fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String
}
