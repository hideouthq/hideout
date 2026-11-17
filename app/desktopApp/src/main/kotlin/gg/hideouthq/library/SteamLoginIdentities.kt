package gg.hideouthq.library

import gg.hideouthq.core.data.deadlockstats.LocalSteamIdentity
import gg.hideouthq.core.library.KeyValues
import gg.hideouthq.core.library.KeyValuesException
import gg.hideouthq.core.library.KvBlock
import java.nio.file.InvalidPathException
import java.nio.file.Path

object SteamLoginIdentities {
    fun at(installs: List<SteamInstall>): List<LocalSteamIdentity> =
        installs.flatMap { identitiesAtRoot(it.root) }.distinctBy { it.steamId64 }

    private fun identitiesAtRoot(steamRoot: String): List<LocalSteamIdentity> {
        val path = try {
            Path.of(steamRoot).resolve("config").resolve("loginusers.vdf")
        } catch (e: InvalidPathException) {
            return emptyList()
        }
        val text = readSmallText(path) ?: return emptyList()
        val document = try {
            KeyValues.parse(text, escapes = true)
        } catch (e: KeyValuesException) {
            return emptyList()
        }
        val users = document.block("users") ?: return emptyList()
        return users.children.filterIsInstance<KvBlock>().mapNotNull { block ->
            val steamId64 = block.key.value.toLongOrNull() ?: return@mapNotNull null
            LocalSteamIdentity(
                steamId64 = steamId64,
                personaName = block.string("PersonaName"),
                mostRecent = block.string("MostRecent") == "1",
                lastLoginAt = block.string("Timestamp")?.toLongOrNull()?.takeIf { it > 0 },
            )
        }
    }
}
