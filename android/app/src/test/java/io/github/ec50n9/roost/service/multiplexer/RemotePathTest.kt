package io.github.ec50n9.roost.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {
    @Test
    fun leavesCommandAloneWithoutConfiguredPath() {
        assertEquals("exec zmx attach 'x'", RemotePath.apply(null, "zmx", "exec zmx attach 'x'"))
        assertEquals("exec zmx attach 'x'", RemotePath.apply("   ", "zmx", "exec zmx attach 'x'"))
    }

    @Test
    fun takesTheDirectoryWhenTheExecutablePathIsPasted() {
        // What `which zmx` prints on a mise install.
        assertEquals(
            "export PATH='/home/x/.local/share/mise/installs/zmx/latest':\"\$PATH\"; exec zmx attach 'x'",
            RemotePath.apply(
                "/home/x/.local/share/mise/installs/zmx/latest/zmx",
                "zmx",
                "exec zmx attach 'x'",
            ),
        )
    }

    @Test
    fun keepsDirectoriesAndToleratesTrailingSlashes() {
        assertEquals(
            "export PATH='/opt/bin:/usr/local/bin:/home/x/.local/bin':\"\$PATH\"; true",
            RemotePath.apply(
                " /opt/bin/ : /usr/local/bin :/home/x/.local/bin ",
                "zmx",
                "true",
            ),
        )
    }

    @Test
    fun onlyStripsTheConfiguredMultiplexerItself() {
        // Tradeoff: a directory that happens to be named like the multiplexer is read as the
        // executable and loses its last segment. The field is documented as "multiplexer path",
        // so pasting an executable is the normal case.
        assertEquals(
            "export PATH='/opt':\"\$PATH\"; true",
            RemotePath.apply("/opt/zmx", "zmx", "true"),
        )
        assertEquals(
            "export PATH='/opt/bin/tmux':\"\$PATH\"; true",
            RemotePath.apply("/opt/bin/tmux", "zmx", "true"),
        )
    }

    @Test
    fun quotesEntriesSoTheyCannotBreakTheCommand() {
        assertEquals(
            "export PATH='/tmp/a'\\''b':\"\$PATH\"; true",
            RemotePath.apply("/tmp/a'b", "zmx", "true"),
        )
    }

    @Test
    fun probeAsksAnInteractiveShellBecauseThatIsWhatBuildsTheUsersPath() {
        val command = RemotePath.probeCommand()
        assertTrue(command.contains("-ic"))
        assertTrue(command.contains("\$PATH"))
        assertTrue(command.contains("\$HOME"))
        // A login shell only sources the profile; mise/homebrew PATH lines live in the rc file.
        assertFalse(command.contains("-lc"))
    }

    @Test
    fun readsThePathAnInteractiveShellReports() {
        assertEquals(
            "/home/u/.local/share/mise/shims:/usr/bin",
            RemotePath.parseProbeOutput("\u0001/home/u/.local/share/mise/shims:/usr/bin\u0001/home/u\u0002"),
        )
    }

    @Test
    fun keepsTheAnswerAndDropsTheBannerAroundIt() {
        // A heavy prompt prints to stdout before the markers run.
        assertEquals(
            "/opt/bin:/usr/bin",
            RemotePath.parseProbeOutput(
                "Last login: Mon Nov  9 02:41:10 2026\r\nzsh: no job control in this shell\n" +
                    "\u0001/opt/bin:/usr/bin\u0001/home/u\u0002\n",
            ),
        )
    }

    @Test
    fun expandsTildeBecauseTheRemoteNeverDoes() {
        // Measured on a real host: the interactive PATH contains an unexpanded "~" entry.
        assertEquals(
            "/home/u/.local/bin:/home/u/.local/kitty.app/bin:/usr/bin",
            RemotePath.parseProbeOutput(
                "\u0001/home/u/.local/bin:~/.local/kitty.app/bin:/usr/bin\u0001/home/u\u0002",
            ),
        )
    }

    @Test
    fun rejectsAnswersThatAreNotAUsablePath() {
        assertNull(RemotePath.parseProbeOutput(""))
        assertNull(RemotePath.parseProbeOutput("zsh: no job control in this shell\n"))
        assertNull(RemotePath.parseProbeOutput("\u0001\u0001/home/u\u0002"))
        assertNull(RemotePath.parseProbeOutput("\u0001one\ntwo\u0001/home/u\u0002"))
        assertNull(RemotePath.parseProbeOutput("\u0001relative/path\u0001/home/u\u0002"))
    }
}
