package gg.hideouthq.core.data

import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.HeroSettings
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A copy of `cfg/citadel_hero_settings.lst`, where Deadlock saves its reticle. The game reads Steam Cloud's, which each
 * Steam account has its own of, and keeps one in its own folder too; they differ only in their line endings. [held] is
 * the copy a backup keeps of it.
 */
sealed interface HeroSettingsCopy {
    val held: HeldFile

    /** `game/citadel/cfg/citadel_hero_settings.lst`. */
    data object Game : HeroSettingsCopy {
        override val held: HeldFile get() = HeldFile.HeroSettings
    }

    /** `<Steam>/userdata/<account>/1422450/remote/cfg/citadel_hero_settings.lst`. */
    data class Cloud(val account: SteamAccountId) : HeroSettingsCopy {
        override val held: HeldFile get() = HeldFile.CloudHeroSettings(account)
    }
}

/**
 * The game's copies of its saved settings. Each is replaced whole: the new file is written beside it and moved over
 * it in one step, so a stop at any point leaves the old file or the new one, and never half of either. Only these two
 * files are ever written, never Steam's own records of them.
 */
interface HeroSettingsFiles {
    /** The copies the game at [root] has: its own folder's, and Steam Cloud's of the account last signed in once Steam and it are found. */
    suspend fun copies(root: GameRoot): List<HeroSettingsCopy>

    suspend fun read(root: GameRoot, copy: HeroSettingsCopy): FileRead

    /**
     * Replaces [copy] with [bytes], making the game's own `cfg` folder if there is none but never one of Steam
     * Cloud's; it throws when it cannot.
     */
    suspend fun write(root: GameRoot, copy: HeroSettingsCopy, bytes: ByteArray)

    /** Emits whenever [copy] may have changed on disk; fails when it cannot watch. */
    fun changes(root: GameRoot, copy: HeroSettingsCopy): Flow<Unit>

    companion object {
        val Unavailable: HeroSettingsFiles = object : HeroSettingsFiles {
            override suspend fun copies(root: GameRoot): List<HeroSettingsCopy> = emptyList()

            override suspend fun read(root: GameRoot, copy: HeroSettingsCopy): FileRead = FileRead.Absent

            override suspend fun write(root: GameRoot, copy: HeroSettingsCopy, bytes: ByteArray) = throw IllegalStateException("the game's settings are not available here")

            override fun changes(root: GameRoot, copy: HeroSettingsCopy): Flow<Unit> = emptyFlow()
        }
    }
}

/** What the game has saved as its reticle, read from each of its copies of its settings. */
sealed interface ReticleRead {
    /** Every copy the game has: Steam Cloud's, when found, is saved, and the game folder's is null while not saved yet. */
    data class Read(val copies: Map<HeroSettingsCopy, HeroSettings?>) : ReticleRead {
        /** Steam Cloud's copy, which the game reads first, else its own folder's; null while neither is saved. */
        val settings: HeroSettings? get() = copies.entries.firstOrNull { it.key is HeroSettingsCopy.Cloud }?.value ?: copies[HeroSettingsCopy.Game]

        /** The reticle the game uses: its defaults while it has saved none. */
        val crosshair: Crosshair get() = settings?.crosshair ?: Crosshair.GameDefault

        /** Whether Steam Cloud's copy was found, so the game reads what Hideout writes. */
        val cloud: Boolean get() = copies.keys.any { it is HeroSettingsCopy.Cloud }
    }

    /**
     * Steam Cloud has no copy for the account signed in, as when it never ran Deadlock on this PC or Steam has not
     * synced it yet. The game folder's copy may be another account's, so Hideout does not use it, and never makes
     * Steam Cloud's.
     */
    data object NotSavedYet : ReticleRead

    /** A copy could not be read, or is not the game's settings, so Hideout never writes over it. */
    data class Unreadable(val reason: String) : ReticleRead
}

/** Every copy of the game at [root], read. */
suspend fun HeroSettingsFiles.readAll(root: GameRoot): ReticleRead {
    val copies = mutableMapOf<HeroSettingsCopy, HeroSettings?>()
    for (copy in copies(root)) {
        copies[copy] = when (val file = read(root, copy)) {
            FileRead.Absent -> if (copy is HeroSettingsCopy.Cloud) return ReticleRead.NotSavedYet else null
            is FileRead.Unreadable -> return ReticleRead.Unreadable(file.reason)
            is FileRead.Present -> HeroSettings.parse(file.bytes) ?: return ReticleRead.Unreadable("the ${if (copy is HeroSettingsCopy.Cloud) "cloud" else "game"} copy of citadel_hero_settings.lst is not kv3 text with a base block")
        }
    }
    return ReticleRead.Read(copies)
}
