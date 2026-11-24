package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecLines
import gg.hideouthq.core.data.BundleVpk
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ClaimedMod
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.IncomingFile
import gg.hideouthq.core.library.IncomingMember
import gg.hideouthq.core.library.IncomingProfile
import gg.hideouthq.core.library.IncomingSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LoadPosition
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.OwnedFile
import gg.hideouthq.core.library.PakNumbers
import gg.hideouthq.core.library.RootRole
import gg.hideouthq.core.library.citadelPath
import gg.hideouthq.core.library.groupVpkSets
import gg.hideouthq.core.library.ref

/** What an import from one source would bring, before numbers are chosen. */
data class Assessment(
    /** Mods already in the game: those that load today in engine order, then the rest in the source's order. */
    val prospects: List<Prospect>,
    /**
     * Files of a source that is taken over that hold the bytes of a set that comes or of a Hideout record, which go once
     * those are Hideout's, so no copy keeps loading beside the one Hideout can turn off.
     */
    val duplicates: List<IncomingFile>,
    /** A bundle's mods with no copy in the game. */
    val copies: List<CopyProspect>,
    /** The source's profiles besides the live one, their members by prospect, copy or Hideout record, winner first. */
    val profiles: List<IncomingProfile>,
    val autoexec: AutoexecLines,
    /** The other manager's own files in the game, which go once everything else is Hideout's. */
    val manifests: List<CitadelPath>,
    /** The name the source's live profile chose; null when it has none or never chose one. */
    val liveProfileName: String? = null,
    /** How many of the mods the source lists have no file in the game, nor in a bundle, so cannot come. */
    val missing: Int = 0,
)

/**
 * One mod already in the game as it would come: who its source says it is, and its sets winner first. [fromSource] is
 * false for files outside the folders only the source keeps that it never listed, which come along all the same:
 * those another manager listed, by that manager's word, and those no manager lists.
 */
data class Prospect(val mod: ClaimedMod, val fileName: String, val position: LoadPosition?, val sets: List<IncomingSet>, val fromSource: Boolean = true)

/** One bundle mod to copy in from [files], which hold [sets] complete sets. */
data class CopyProspect(val mod: ClaimedMod, val fileName: String, val enabled: Boolean, val files: List<LocalFile>, val sets: Int)

/**
 * Pure. Joins [library]'s claims to [survey]'s sets against [held], Hideout's records, and the measured [bundle] files
 * a bundle names, by their paths in it.
 *  - A set that loads today from a folder the source takes over comes along; claims only add who it is and what it is
 *    called. One that does not load comes along when a claim names it, it sits in a folder the source parks disabled
 *    mods in, or it is a bare `pakNN` in `addons`, which loads once Hideout mounts that. A bundle takes nothing over, so
 *    only its own mods come, from `addons` where their bytes are, or copied from the bundle.
 *  - A set, or a bundle's copy, whose measured bytes are a Hideout record's is Hideout's and never comes.
 *  - Copies of one set come once: the one that loads, else the one in the live profile's folder, else the first by path.
 *    From a source that is taken over the other copies, and copies of a Hideout record, are [Assessment.duplicates].
 *  - A set two mods claim goes to the one whose recorded size it has, else to neither.
 *  - One mod's sets that load are one prospect wherever they load, and those that do not are a second one. A set no
 *    claim names is a prospect of its own.
 *  - A set that comes and the source claims nothing of is who one of [others], the other managers on this computer,
 *    says it is, which only names it: what comes is the source's to say. A path claim of theirs names only a file of a
 *    size it recorded, if it recorded any, since another one was put in that slot since.
 *  - A prospect no manager claims anything of is who its imprint says it is, when one of its sets has one.
 *  - A mod the source lists that no set found anywhere in the game is, no bundle file holds and Hideout does not hold
 *    already, by its submission, its name or its bytes, is [Assessment.missing].
 */
fun assess(library: ForeignLibrary, survey: GameSurvey, held: List<ManifestRecord>, bundle: Map<String, BundleVpk> = emptyMap(), others: List<ForeignLibrary> = emptyList()): Assessment {
    val roots = library.footprint.roots.toSet()
    val takesOver = roots.isNotEmpty()
    val records = held.filter { it.gameRoot == survey.root }
    val heldContent = records.flatMap { record -> record.sets.map { set -> set.files.map { it.image } to record.installId } }.toMap()
    val heldDirectories = records.flatMap { record -> record.sets.map { set -> set.files.first().image.sha256 to record.installId } }.toMap()
    val resolved = mutableMapOf<CitadelPath, IncomingMember>()
    val duplicates = mutableListOf<IncomingFile>()

    val candidates = survey.sets.filter { if (takesOver) it.root in roots else it.root == ForeignRoot.Addons }
    val foreign = candidates.filter { set ->
        val holder = heldContent[set.content] ?: return@filter true
        resolved[set.directory.path] = IncomingMember.Held(holder)
        if (takesOver) duplicates += set.files
        false
    }
    val livingFolder = library.profiles.firstOrNull { it.key == library.activeProfile }?.folder
    val coming = mutableListOf<Pair<FoundSet, ForeignMod?>>()
    val copiesOf = mutableMapOf<CitadelPath, List<FoundSet>>()
    for (copies in foreign.groupBy { it.content }.values) {
        val chosen = copies.filter { it.position != null }.minByOrNull { it.position!! }
            ?: copies.firstOrNull { it.folder == livingFolder }
            ?: copies.minBy { it.directory.path.value }
        copiesOf[chosen.directory.path] = copies
        val owner = ownerAmong(library.mods, copies, chosen)
        val willLoad = copies.any { it.folder == CitadelPath.Addons && PakNumbers.mounted(it.directory.path.name) != null }
        val comes = if (takesOver) chosen.position != null || willLoad || owner != null || chosen.root.role == RootRole.AdoptOff else owner != null
        if (comes) {
            coming += chosen to (owner ?: others.firstNotNullOfOrNull { ownerAmong(it.mods, copies, chosen, bySize = true) })
            if (takesOver) duplicates += copies.filter { it !== chosen }.flatMap { it.files }
        }
    }

    val ownMods = library.mods.toSet()
    val groups = coming.groupBy({ it.second }, { it.first })
    val drafts = mutableListOf<Prospect>()
    val members = mutableMapOf<Prospect, List<FoundSet>>()
    for ((owner, sets) in groups) {
        val parts = if (owner == null) sets.map { listOf(it) } else listOf(sets.filter { it.position != null }.sortedBy { it.position }, sets.filter { it.position == null }.sortedBy { it.directory.path.value })
        for (part in parts.filter { it.isNotEmpty() }) {
            val keys = uniqueKeys(part.map { set -> originalName(set, owner) })
            val recorded = owner?.identity ?: ModIdentity.None
            val imprinted = part.firstNotNullOfOrNull { set -> set.imprint?.claim?.takeIf { it != ModIdentity.None } }.takeIf { recorded == ModIdentity.None }
            val prospect = Prospect(
                mod = ClaimedMod(imprinted ?: recorded, owner?.name, selfReported = imprinted != null),
                fileName = keys.first(),
                position = part.first().position,
                sets = part.zip(keys).map { (set, key) -> IncomingSet(key, set.files) },
                fromSource = owner in ownMods || owner == null && part.all { it.root.managers.size == 1 },
            )
            drafts += prospect
            members[prospect] = part
        }
    }
    val owners = groups.flatMap { (owner, sets) -> sets.map { it to owner?.takeIf { it in ownMods } } }.toMap()
    val prospects = drafts.filter { it.position != null }.sortedBy { it.position } +
        drafts.filter { it.position == null }.sortedWith(compareBy<Prospect> { prospect -> owners[members.getValue(prospect).first()]?.position ?: Int.MAX_VALUE }.thenBy { members.getValue(it).first().directory.path.value })
    prospects.forEachIndexed { index, prospect ->
        members.getValue(prospect).forEach { set -> copiesOf.getValue(set.directory.path).forEach { resolved[it.directory.path] = IncomingMember.Adopted(index) } }
    }

    val copies = mutableListOf<CopyProspect>()
    val copyOf = mutableMapOf<ModKey, Int>()
    val heldOf = mutableMapOf<ModKey, String>()
    for (mod in library.mods) {
        val bundled = mod.matchers.filterIsInstance<SetMatcher.Bundled>().firstOrNull() ?: continue
        if (owners.values.any { it == mod }) continue
        val vpks = bundled.files.mapNotNull { bundle[it.path] }
        vpks.firstNotNullOfOrNull { heldDirectories[it.sha256] }?.let {
            heldOf[mod.key] = it
            continue
        }
        val grouping = groupVpkSets(vpks)
        if (vpks.size < bundled.files.size || grouping.sets.isEmpty() || grouping.rejected.isNotEmpty()) continue
        copyOf[mod.key] = copies.size
        copies += CopyProspect(ClaimedMod(mod.identity, mod.name), bundled.files.first().name, bundled.enabled, vpks.map { LocalFile(it.location, it.path.substringAfterLast('/'), it.sizeBytes) }, grouping.sets.size)
    }

    /** What one profile member names: prospects, a copy, or Hideout's own records. */
    fun membersOf(member: ProfileMember): List<IncomingMember> = when (member) {
        is ProfileMember.Mod -> prospects.indices.filter { index -> members.getValue(prospects[index]).any { owners[it]?.key == member.key } }.map { IncomingMember.Adopted(it) } +
            listOfNotNull(copyOf[member.key]?.let { IncomingMember.Copied(it) }, heldOf[member.key]?.let { IncomingMember.Held(it) })
        is ProfileMember.Files -> survey.sets.filter { matches(member.matcher, it) }.mapNotNull { resolved[it.directory.path] } +
            records.filter { record -> record.sets.any { set -> matchesHeld(member.matcher, record, record.filesOf(set).first()) } }.map { IncomingMember.Held(it.installId) } +
            library.mods.filter { mod -> member.matcher in mod.matchers }.mapNotNull { mod -> copyOf[mod.key]?.let { IncomingMember.Copied(it) } }
        is ProfileMember.Submission -> prospects.indices.filter { index -> prospects[index].mod.claim.ref?.id == member.submissionId }.map { IncomingMember.Adopted(it) } +
            records.filter { record -> (record.sets.first().source as? InstalledSource.Remote)?.identity?.submissionId == member.submissionId }.map { IncomingMember.Held(it.installId) }
    }

    val profiles = library.profiles.filter { it.key != library.activeProfile }.map { profile -> IncomingProfile(profile.name, profile.members.flatMap(::membersOf).distinct()) }
    val liveName = library.profiles.firstOrNull { it.key == library.activeProfile }?.takeUnless { it.key.value == "default" }?.name
    fun held(mod: ForeignMod) = records.any { record ->
        record.title == mod.name || mod.identity.ref?.let { it == record.sets.first().source.submission } == true ||
            record.sets.any { set -> mod.matchers.any { matchesHeld(it, record, record.filesOf(set).first()) } }
    }
    val missing = library.mods.count { mod ->
        mod.listed && mod.key !in copyOf && mod.key !in heldOf && survey.sets.none { set -> mod.matchers.any { matches(it, set) } } && !held(mod)
    }
    return Assessment(prospects, duplicates, copies, profiles, library.autoexec, library.footprint.manifests, liveName, missing)
}

/** The one mod of [mods] that claims [copies], or the one whose recorded size [chosen] has when more do. [bySize] holds every path claim to its sizes. */
private fun ownerAmong(mods: List<ForeignMod>, copies: List<FoundSet>, chosen: FoundSet, bySize: Boolean = false): ForeignMod? {
    fun claims(matcher: SetMatcher, set: FoundSet) = matches(matcher, set) &&
        (!bySize || matcher !is SetMatcher.At || matcher.sizes.isEmpty() || set.directory.image.sizeBytes in matcher.sizes)
    val claimants = mods.filter { mod -> mod.matchers.any { matcher -> copies.any { claims(matcher, it) } } }
    val sized = claimants.filter { mod -> mod.matchers.any { it is SetMatcher.At && chosen.directory.image.sizeBytes in it.sizes && copies.any { set -> matches(it, set) } } }
    return claimants.singleOrNull() ?: sized.singleOrNull()
}

private fun matches(matcher: SetMatcher, set: FoundSet): Boolean = when (matcher) {
    is SetMatcher.At -> matcher.path.value.equals(set.directory.path.value, ignoreCase = true)
    is SetMatcher.Prefixed -> matcher.folder.value.equals(set.folder.value, ignoreCase = true) && set.directory.path.name.startsWith(matcher.prefix)
    is SetMatcher.Hash -> matcher.sha256 == set.directory.image.sha256
    is SetMatcher.Bundled -> false
}

private fun matchesHeld(matcher: SetMatcher, record: ManifestRecord, directory: OwnedFile): Boolean = when (matcher) {
    is SetMatcher.Hash -> matcher.sha256 == directory.sha256
    is SetMatcher.At -> matcher.path == directory.citadelPath(record)
    is SetMatcher.Prefixed, is SetMatcher.Bundled -> false
}

private fun originalName(set: FoundSet, owner: ForeignMod?): String {
    val matchers = owner?.matchers.orEmpty()
    matchers.filterIsInstance<SetMatcher.At>().firstOrNull { matches(it, set) }?.originalName?.let { return it }
    matchers.filterIsInstance<SetMatcher.Prefixed>().firstOrNull { matches(it, set) }?.let { prefixed -> set.directory.path.name.removePrefix(prefixed.prefix).takeIf { it.isNotBlank() }?.let { return it } }
    return set.directory.path.name
}

private fun uniqueKeys(names: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return names.map { name -> generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name ($it)" }.first { seen.add(it) } }
}
