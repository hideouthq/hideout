package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyValuesTest {
    @Test
    fun windowsLibraryFoldersUnescapeBackslashesAndKeepNesting() {
        val document = KeyValues.parse(WindowsLibraryFolders, escapes = true)
        val root = assertNotNull(document.block("LibraryFolders"))
        val first = assertNotNull(root.block("0"))
        assertEquals("""C:\Program Files (x86)\Steam""", first.string("path"))
        assertEquals("""D:\SteamLibrary""", root.block("1")?.string("PATH"))
        assertEquals("38892644148", root.block("1")?.block("apps")?.string("1422450"))
    }

    @Test
    fun spansPointAtTheSourceTextOfEveryToken() {
        val document = KeyValues.parse(WindowsLibraryFolders, escapes = true)
        val path = document.block("libraryfolders")!!.block("0")!!.children.first() as KvLeaf
        assertEquals("\"path\"", WindowsLibraryFolders.substring(path.key.start, path.key.end))
        assertEquals("\"C:\\\\Program Files (x86)\\\\Steam\"", WindowsLibraryFolders.substring(path.value.start, path.value.end))
        val block = document.block("libraryfolders")!!
        assertEquals('{', WindowsLibraryFolders[block.open])
        assertEquals('}', WindowsLibraryFolders[block.close])
        assertEquals(WindowsLibraryFolders.trimEnd().length, block.end)
    }

    @Test
    fun gameInfoShapeParsesWithBareTokensCommentsAndConditions() {
        val document = KeyValues.parse(GameInfoShape, escapes = false)
        val searchPaths = assertNotNull(document.block("GameInfo")?.block("FileSystem")?.block("SearchPaths"))
        val entries = searchPaths.children.map { it as KvLeaf }
        assertEquals(
            listOf("Game_Language" to "citadel_*LANGUAGE*", "Game" to "citadel/addons", "Mod" to "citadel", "Game" to "citadel", "Game" to "core"),
            entries.map { it.key.value to it.value.value },
        )
        val lowViolence = document.block("GameInfo")!!.children.first { it.key.value == "LowViolence" } as KvLeaf
        assertEquals("\$WIN32 || \$POSIX", lowViolence.condition?.value)
        assertEquals("1", document.block("GameInfo")?.block("RenderSystem")?.let { (it.children.first() as KvLeaf).value.value })
        val quoted = document.block("GameInfo")!!.children.first { it.key.value == "title" } as KvLeaf
        assertEquals("""C:\no\escapes""", quoted.value.value)
        assertNull(document.block("GameInfo")?.string("LowViolence"), "a conditional entry is not the plain value")
    }

    @Test
    fun onlyAsciiWhitespaceSeparatesTokensSoLatin1DecodedUtf8StaysWhole() {
        val utf8 = "Game citadel/à_mods // café\n".toByteArray(Charsets.UTF_8)
        val text = String(utf8, Charsets.ISO_8859_1)
        val leaf = KeyValues.parse(text, escapes = false).nodes.single() as KvLeaf
        assertEquals("citadel/à_mods", String(leaf.value.value.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
        assertEquals(utf8.indexOf('/'.code.toByte()) - "citadel".length, leaf.value.start)
        assertEquals("a\u00A0b", (KeyValues.parse("k a\u00A0b", escapes = false).nodes.single() as KvLeaf).value.value)
    }

    @Test
    fun unknownEscapesKeepTheirBackslash() {
        val document = KeyValues.parse("\"k\" \"a\\qb\\\"c\\\\d\"", escapes = true)
        assertEquals("a\\qb\"c\\d", (document.nodes.single() as KvLeaf).value.value)
    }

    @Test
    fun malformedOrDeeplyNestedTextIsRefused() {
        listOf(
            "\"a\" \"unterminated", "\"a\"\n{\n\"b\" \"c\"\n", "\"a\" \"b\"\n}", "\"a\"", "\"a\" }", "{ \"a\" \"b\" }", "[\$X] \"a\" \"b\"",
            "\"a\" \"b\" [\$NOT_CLOSED\n", "a {".repeat(100_000),
        ).forEach { text -> assertFailsWith<KeyValuesException>(text.take(40)) { KeyValues.parse(text, escapes = true) } }
    }

    private companion object {
        val WindowsLibraryFolders = """
            "libraryfolders"
            {
            	"0"
            	{
            		"path"		"C:\\Program Files (x86)\\Steam"
            		"label"		""
            		"contentid"		"5625567501194973287"
            		"apps"
            		{
            			"228980"		"128606066"
            		}
            	}
            	"1"
            	{
            		"path"		"D:\\SteamLibrary"
            		"apps"
            		{
            			"1422450"		"38892644148"
            		}
            	}
            }
        """.trimIndent() + "\n"

        val GameInfoShape = """
            // A third tool's note: «don't touch» — with [def: "1"] and a trailing \\
            "GameInfo"
            {
                game        "citadel"
                title       "C:\no\escapes"
                LowViolence 1 [${'$'}WIN32 || ${'$'}POSIX]
                FileSystem
                {
                    SearchPaths
                    {
                        Game_Language       citadel_*LANGUAGE*
                        Game                citadel/addons      // [def: "x"]
                        Mod                 citadel
                        // Game             citadel/disabled
                        Game                citadel
                        Game                core
                    }
                }
                RenderSystem { Vulkan "1" }
            }
        """.trimIndent()
    }
}
