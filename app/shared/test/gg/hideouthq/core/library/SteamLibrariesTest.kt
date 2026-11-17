package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamLibrariesTest {
    @Test
    fun currentLibraryFoldersListEveryLibraryInOrder() {
        val text = """
            "libraryfolders"
            {
            	"0" { "path" "/home/user/.local/share/Steam" "apps" { "570" "1" } }
            	"1" { "path" "/mnt/games/SteamLibrary" "apps" { "1422450" "2" } }
            	"2" { "path" "C:\\Program Files (x86)\\Steam" }
            	"contentstatsid" "-123"
            }
        """.trimIndent()
        assertEquals(
            listOf("/home/user/.local/share/Steam", "/mnt/games/SteamLibrary", """C:\Program Files (x86)\Steam"""),
            steamLibraryFolders(KeyValues.parse(text, escapes = true)),
        )
    }

    @Test
    fun manifestsNamingAnythingButOnePlainFolderAreIgnored() {
        fun manifest(installDir: String) = KeyValues.parse("\"AppState\" { \"appid\" \"1422450\" \"installdir\" \"$installDir\" }", escapes = false)
        listOf("", ".", "..", "../../..", "../Deadlock", "Deadlock/../../x", """..\..\Windows""", "/etc", """C:\Games""", "C:", " Deadlock", "Dead\tlock")
            .forEach { assertNull(deadlockInstallDir(manifest(it)), "'$it' should be refused") }
        assertNull(deadlockInstallDir(KeyValues.parse("\"AppState\" { \"appid\" \"1422450\" }", escapes = true)))
    }

    @Test
    fun absolutePathsAreRecognisedOnEitherPlatform() {
        listOf("/", "/home/u", """C:\""", "c:/steam", """\\server\share""", "//server/share").forEach { assertTrue(isAbsolutePath(it), it) }
        listOf("", "steam", "C:", "C:steam", "~/steam", "./x", "/a\u0001b", "é:\\x").forEach { assertFalse(isAbsolutePath(it), it) }
    }
}
