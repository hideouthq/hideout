package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecLines
import gg.hideouthq.core.autoexec.SectionMarkers
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.isSha256
import kotlin.jvm.JvmInline

/**
 * What another manager, or a bundle, says it has, parsed at the boundary from its own files into the one shape every
 * source shares. These are claims, never proof: the game folder is the truth for which files exist, which load and in
 * what order. A claim only says which found sets are which mod's, who the mod is on GameBanana, what it is called and
 * which profiles have it, in what order. [footprint] is the one place the source shows, and it is data, so nothing after
 * the readers branches on which manager it was.
 */
data class ForeignLibrary(
    /** In the source's own order. */
    val mods: List<ForeignMod>,
    val profiles: List<ForeignProfile>,
    /** The profile live now, whose membership becomes Hideout's active profile's; null when the source cannot say. */
    val activeProfile: ProfileKey?,
    /** The crosshair and console command lines the source keeps for the game. */
    val autoexec: AutoexecLines,
    val footprint: Footprint,
)

@JvmInline
value class ModKey(val value: String)

@JvmInline
value class ProfileKey(val value: String)

data class ForeignMod(
    val key: ModKey,
    /** The source's title for it, or null when it keeps none. */
    val name: String?,
    val identity: ModIdentity,
    /** How to recognise its sets among those found: a set any one of them matches is claimed. */
    val matchers: List<SetMatcher>,
    /** Its place in the source's own order, lowest first: for sets that do not load today, never to reorder those that do. */
    val position: Int?,
    /** The user has it as far as the source says, so files of it the game lacks are worth saying; not one a source always names. */
    val listed: Boolean = true,
)

/** How a claim recognises a set among those found in the game. */
sealed interface SetMatcher {
    /**
     * The set whose directory, or only, file is at [path]. [originalName] is what the source's archive called that file;
     * [sizes] are the byte counts the source recorded for that name, which tell apart two mods claiming one path.
     */
    data class At(val path: CitadelPath, val originalName: String? = null, val sizes: Set<Long> = emptySet()) : SetMatcher

    /** Every set in [folder] whose directory file's name starts with [prefix]; the rest of that name is the original name. */
    data class Prefixed(val folder: CitadelPath, val prefix: String) : SetMatcher

    /** The set whose directory file has this SHA-256, wherever it is. */
    data class Hash(val sha256: String) : SetMatcher {
        init {
            require(isSha256(sha256)) { "sha256 must be 64 lowercase hex digits" }
        }
    }

    /** A bundle's own copy, outside the game, for when no game file holds its bytes; [enabled] as the bundle says. */
    data class Bundled(val files: List<BundledFile>, val enabled: Boolean) : SetMatcher
}

/** One file of a bundle: [path] is relative to the bundle's folder, in forward-slash segments, and never leaves it. */
data class BundledFile(val path: String, val name: String, val image: FileImage?)

data class ForeignProfile(
    val key: ProfileKey,
    val name: String,
    val folder: CitadelPath?,
    /** Winner first, in the profile's own order; a member the source gives no place comes after those it does, as listed. */
    val members: List<ProfileMember>,
)

/** One mod a profile has on, to be resolved to whichever record ends up holding it. */
sealed interface ProfileMember {
    data class Mod(val key: ModKey) : ProfileMember

    data class Files(val matcher: SetMatcher) : ProfileMember

    /** Known only by its GameBanana submission id, in whichever kind it is one of. */
    data class Submission(val submissionId: Long) : ProfileMember
}

/**
 * The source's hold on the game besides its mods. Taking over acts on exactly this, so another manager means another
 * reader, never another branch. A source with [roots] has their mounts taken out of `gameinfo.gi`, so whatever loads
 * from them comes along, and another manager's keep loading; a bundle has none.
 */
data class Footprint(
    /** The folders the source keeps. Sets in an [gg.hideouthq.core.library.RootRole.AdoptOff] one are the source's, claimed or not. */
    val roots: List<ForeignRoot>,
    /** The source's sections of `autoexec.cfg`, whose places Hideout's sections take. */
    val sections: List<SectionMarkers>,
    val manifests: List<CitadelPath>,
) {
    companion object {
        val None = Footprint(emptyList(), emptyList(), emptyList())
    }
}
