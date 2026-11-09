package io.github.ec50n9.roost.service.multiplexer

import io.github.ec50n9.roost.model.MultiplexerType

interface Multiplexer {
    val type: MultiplexerType

    /**
     * What the remote prints when this executable is not on the exec channel's PATH. It is the
     * signal that a PATH probe might help: every other failure (no server running, permission)
     * means the multiplexer *was* found and re-probing would only waste a round trip.
     */
    val missingExecutableText: String get() = "${type.id} executable not found"

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
