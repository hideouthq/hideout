package gg.hideouthq.library

import gg.hideouthq.core.data.deadlockstats.LocalSteamIdentity
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalPathApi::class)
class SteamLoginIdentitiesTest {
    private val root = createTempDirectory("steam-login-identities")

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun steamRoot(name: String, loginUsers: String): SteamInstall {
        ((root / name / "config").createDirectories() / "loginusers.vdf").writeText(loginUsers)
        return SteamInstall((root / name).toString(), SteamClient.Desktop)
    }

    private fun users(vararg users: Pair<Long, Map<String, String>>) = "\"users\"\n{\n" +
        users.joinToString("") { (id, fields) -> "\t\"$id\"\n\t{\n" + fields.entries.joinToString("") { "\t\t\"${it.key}\"\t\t\"${it.value}\"\n" } + "\t}\n" } + "}\n"

    @Test
    fun everyAccountIsReadWithItsNamesAndLastLogin() {
        val steam = steamRoot("steam", users(
            76561198000000001 to mapOf("AccountName" to "first", "PersonaName" to "First", "RememberPassword" to "1", "MostRecent" to "0", "Timestamp" to "1700000000"),
            76561198000000002 to mapOf("AccountName" to "second", "PersonaName" to "Second", "MostRecent" to "1"),
        ))
        assertEquals(
            listOf(
                LocalSteamIdentity(76561198000000001, "First", mostRecent = false, lastLoginAt = 1700000000),
                LocalSteamIdentity(76561198000000002, "Second", mostRecent = true),
            ),
            SteamLoginIdentities.at(listOf(steam)),
        )
    }

    @Test
    fun duplicateAccountsAcrossRootsAreMergedBySteamId() {
        val shared = users(76561198000000001 to mapOf("AccountName" to "shared", "PersonaName" to "Shared"))
        assertEquals(1, SteamLoginIdentities.at(listOf(steamRoot("a", shared), steamRoot("b", shared))).size)
    }

    @Test
    fun aMissingOrMalformedFileYieldsNoIdentitiesRatherThanFailing() {
        val empty = SteamInstall((root / "empty").createDirectories().toString(), SteamClient.Desktop)
        val malformed = steamRoot("malformed", "not valid keyvalues {{{")
        for (steam in listOf(empty, malformed, SteamInstall((root / "does-not-exist").toString(), SteamClient.Desktop))) {
            assertEquals(emptyList(), SteamLoginIdentities.at(listOf(steam)))
        }
    }
}
