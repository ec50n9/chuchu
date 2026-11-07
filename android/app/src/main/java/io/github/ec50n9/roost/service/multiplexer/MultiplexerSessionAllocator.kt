package io.github.ec50n9.roost.service.multiplexer

object MultiplexerSessionAllocator {
    private const val SESSION_PREFIX = "roost"

    // The legacy `chuchu-` prefix is still recognized so tmux sessions created before the
    // fork was renamed stay reusable instead of showing up as foreign sessions.
    private val managedSessionRegex = Regex("^(?:roost|chuchu)-([1-9][0-9]*)$")

    fun reusableDetachedSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String? {
        val localNames = localSessionNames.toSet()
        return remoteSessions
            .asSequence()
            .filter { session -> !session.attached && session.name !in localNames }
            .mapNotNull { session ->
                managedSessionRegex
                    .matchEntire(session.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { index -> session.name to index }
            }
            .maxByOrNull { (_, index) -> index }
            ?.first
    }

    fun nextSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String {
        val usedIndices = (remoteSessions.map { it.name } + localSessionNames)
            .mapNotNull { name -> managedSessionRegex.matchEntire(name)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .toSet()
        var index = 1
        while (index in usedIndices) index += 1
        return "$SESSION_PREFIX-$index"
    }
}
