package io.github.ec50n9.roost.service.multiplexer

/**
 * SSH exec channels run the command directly, without the account's shell rc files, so a
 * multiplexer installed by mise/nix/asdf/homebrew is not on PATH there even though the
 * interactive shell finds it. A host can carry those directories in its profile and every
 * exec-channel command gets them prepended.
 *
 * The value may also be the executable itself (`which zmx`), which is what users have at hand;
 * PATH wants its directory, so a matching basename is stripped. `~` is not expanded by the
 * remote shell, so entries have to be absolute.
 *
 * The session the multiplexer spawns inherits this PATH too, so an attached shell starts with it.
 */
object RemotePath {
    fun apply(path: String?, executable: String, command: String): String {
        val entries =
            path.orEmpty()
                .split(':')
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .map { if (it.substringAfterLast('/') == executable) it.substringBeforeLast('/') else it }
                .filter { it.isNotEmpty() }
        if (entries.isEmpty()) return command
        return "export PATH=${shellQuote(entries.joinToString(":"))}:\"\$PATH\"; $command"
    }

    /**
     * Reads the PATH an *interactive* shell builds, which is exactly the environment the user has
     * when they ssh in and type `tmux` themselves: `-i` is what makes the rc files that add mise /
     * homebrew / nix run. A login shell (`-l`) is not enough — it only sources the profile, and
     * those PATH lines normally live in `.zshrc`.
     *
     * The markers survive the banners a heavy prompt prints; stderr is dropped so a failing rc
     * cannot pollute the payload.
     */
    fun probeCommand(): String =
        "\${SHELL:-/bin/sh} -ic 'printf \"\\001%s\\001%s\\002\" \"\$PATH\" \"\$HOME\"' 2>/dev/null"

    /**
     * Turns [probeCommand]'s output into a PATH this app can store, or null when the shell did not
     * answer usably. `~` is expanded with the reported HOME because the interactive PATH really can
     * contain it (`~/.local/kitty.app/bin`) and the remote shell does not expand it there.
     */
    fun parseProbeOutput(output: String): String? {
        val end = output.indexOf('\u0002')
        if (end < 0) return null
        val sections = output.substring(0, end).split('\u0001')
        if (sections.size < 3) return null
        // The banner may itself contain the markers' bytes in theory, so the payload is the tail.
        val home = sections.last().trim()
        val rawPath = sections[sections.size - 2].trim()
        if (rawPath.isBlank() || rawPath.length > MAX_PROBED_PATH_CHARS) return null
        if (rawPath.contains('\n') || rawPath.contains('\r')) return null
        if (!rawPath.contains('/')) return null
        val expanded = if (home.isBlank()) rawPath else rawPath.replace("~", home)
        if (!expanded.split(':').any { it.startsWith('/') }) return null
        return expanded
    }

    // A PATH is a few hundred bytes; anything far beyond that is banner noise, not an answer.
    private const val MAX_PROBED_PATH_CHARS = 4096

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
