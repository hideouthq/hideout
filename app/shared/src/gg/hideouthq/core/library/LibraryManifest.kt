package gg.hideouthq.core.library

import gg.hideouthq.preview.sha256Hex
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

const val ManifestSchemaVersion: Int = 13

/** GameBanana's kinds of Deadlock submission, by the name its API lists them under. Ids are only unique within one kind. */
enum class SubmissionKind(internal val idPrefix: String, internal val apiName: String) { Mod("mod", "Mod"), Sound("sound", "Sound") }

@Serializable
data class SubmissionRef(val kind: SubmissionKind, val id: Long) {
    init {
        require(id > 0) { "a submission id must be a GameBanana id" }
    }

    /** The id the rest of the app knows the submission by, such as "mod-721908"; also its `hideout_off` folder name. */
    val modId: String get() = "${kind.idPrefix}-$id"

    companion object {
        private val Pattern = Regex("""([a-z]+)-([1-9][0-9]{0,17})""")

        /** The submission an app-wide id such as "mod-721908" names, or null when it names none. */
        fun parse(modId: String): SubmissionRef? {
            val match = Pattern.matchEntire(modId) ?: return null
            val kind = SubmissionKind.entries.firstOrNull { it.idPrefix == match.groupValues[1] } ?: return null
            return SubmissionRef(kind, match.groupValues[2].toLong())
        }
    }
}

/**
 * The exact GameBanana file a set came from: which submission, which of its files, and the MD5 GameBanana lists for
 * it. GameBanana lists no MD5 for some files; such a file is still exact by its id, and what Hideout wrote is still
 * owned only by size and SHA-256.
 */
data class SubmissionIdentity(val kind: SubmissionKind, val submissionId: Long, val fileId: Long, val fileMd5: String?) {
    init {
        require(submissionId > 0) { "submissionId must be a GameBanana id" }
        require(fileId > 0) { "fileId must be a GameBanana id" }
        require(fileMd5 == null || fileMd5.isLowerHex(Md5HexLength)) { "fileMd5 must be 32 lowercase hex digits" }
    }

    val ref: SubmissionRef get() = SubmissionRef(kind, submissionId)

    val modId: String get() = ref.modId

    private companion object {
        const val Md5HexLength = 32
    }
}

data class FileImage(val sizeBytes: Long, val sha256: String) {
    init {
        require(sizeBytes >= 0) { "sizeBytes cannot be negative" }
        require(sha256.isLowerHex(Sha256HexLength)) { "sha256 must be lowercase hex" }
    }
}

/** A file's byte count and CRC-32, unsigned: what an archive's own index records of each file it holds. */
data class FilePrint(val sizeBytes: Long, val crc32: Long)

data class ArchiveIdentity(val path: String, val sizeBytes: Long, val sha256: String) {
    init {
        require(isPlainAbsolutePath(path)) { "the archive path must be absolute, without . or .. or control characters" }
        require(sizeBytes > 0) { "an archive cannot be empty" }
        require(sha256.isLowerHex(Sha256HexLength)) { "the archive sha256 must be lowercase hex" }
    }

    val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\')
}

/** One import from another mod manager, by the id its record in Hideout's data folder carries. */
@JvmInline
value class MigrationId(val value: String) {
    init {
        require(TokenPattern.matches(value)) { "a migration id must be safe in a file name" }
    }
}

sealed interface SetOrigin {
    data class Downloaded(val archive: ArchiveIdentity) : SetOrigin

    /** Unpacked from a file handed to Hideout, of which only [image], its size and SHA-256, outlives the install. */
    data class Imported(val image: FileImage) : SetOrigin

    /** Files another manager had put in the game, taken over where they were by [migration], whose record keeps their old names. */
    data class Adopted(val migration: MigrationId) : SetOrigin
}

/** Which of the mod's current GameBanana files a set came from when it was installed: [index] of [count], most downloaded first. */
data class VersionPosition(val index: Int, val count: Int)

/**
 * Which set of its mod a set is: 12 hex digits of the SHA-256 of its source, `remote/<fileId>/<md5>` or
 * `local/<localId>`, a newline and its component key. Derived, never stored. Every journal pairs a set's files by it.
 */
@JvmInline
value class SetKey internal constructor(val value: String) {
    /**
     * `set-3fa94c0d12be`: the stem of every file of the set while mod [installId] is off, 12 hex digits of the SHA-256
     * of the install id, a newline and the key. The files hold no number, so they stay as they are when other sets
     * move, and a later install of the same file never meets the files an uninstall left behind.
     */
    fun offStem(installId: String): String = OffStem + sha256Hex("$installId\n$value".encodeToByteArray()).take(KeyLength)

    companion object {
        fun of(source: InstalledSource, componentKey: String): SetKey {
            val origin = when (source) {
                is InstalledSource.Remote -> "remote/${source.identity.fileId}/${source.identity.fileMd5.orEmpty()}"
                is InstalledSource.Local -> "local/${source.id}"
            }
            return SetKey(sha256Hex("$origin\n$componentKey".encodeToByteArray()).take(KeyLength))
        }
    }
}

data class SetFile(val part: VpkPart, val image: FileImage)

/**
 * One file Hideout wrote, as its record places it now: [part] of [set], named [name], always one plain path component,
 * and holding [image] as last known. Only [ManifestRecord.filesOf] makes one.
 */
@ConsistentCopyVisibility
data class OwnedFile internal constructor(val set: SetKey, val part: VpkPart, val name: String, val image: FileImage) {
    val isDirectory: Boolean get() = part == VpkPart.Directory

    val sizeBytes: Long get() = image.sizeBytes

    val sha256: String get() = image.sha256
}

data class RuleIdentity(val id: String, val revision: Int) {
    override fun toString(): String = "$id@$revision"
}

/**
 * How a set's files were made: from the retained original set [original], whose files were [originalFiles] in the
 * set's own order, by [rules] at [codec], against the game as [context] names it, changing [edits] values in
 * [resources] resources. It is saved together with the files it describes, in the same save that makes them the
 * set's, so a repair is recorded exactly when its files are Hideout's and not before.
 */
data class SetDerivation(
    val original: String,
    val originalFiles: List<FileImage>,
    val rules: List<RuleIdentity>,
    val codec: String,
    val context: String,
    val resources: Int,
    val edits: Int,
) {
    init {
        require(TokenPattern.matches(original)) { "an original's id must be safe in a file name" }
    }
}

/**
 * A copy of a set's files exactly as they were before Hideout repaired them, kept in Hideout's own folder, apart from
 * downloads and caches, so "Use original files" can always put them back. Registered only after every file was copied
 * and the bytes copied had its recorded size and SHA-256; registered in [installId] for display, never as ownership.
 */
data class RetainedOriginal(val id: String, val installId: String, val source: InstalledSource, val componentKey: String, val files: List<SetFile>) {
    init {
        require(TokenPattern.matches(id)) { "an original's id must be safe in a file name" }
        require(files.map { it.part }.distinct().size == files.size) { "an original has each part once" }
    }

    /** Its files as [set] names them, in [set]'s own order, or null when the parts differ. */
    fun imagesFor(set: InstalledSet): List<FileImage>? {
        val byPart = files.associate { it.part to it.image }
        if (byPart.keys != set.files.map { it.part }.toSet()) return null
        return set.files.map { byPart.getValue(it.part) }
    }

    fun isOriginalOf(record: ManifestRecord, set: InstalledSet, derivation: SetDerivation): Boolean =
        id == derivation.original && installId == record.installId && source == set.source && componentKey == set.componentKey &&
            imagesFor(set) == derivation.originalFiles
}

/** What the user asked of an installation's files when Hideout can repair them. With no choice saved, a repair applies only while automatic repair is on. */
enum class RepairChoice {
    /** Use repaired files whenever a repair is available and not stale. */
    Repaired,

    /** Use the original files; no repair is applied again until the user asks for one. */
    Original,
}

/**
 * Where a set came from: one GameBanana file, or one file the user installed from their computer. A local file may be
 * known to be from a [Local.submission] when which of its files it is was never proved; it stays local all the same.
 */
sealed interface InstalledSource {
    val modId: String

    /** The submission whose page the set has: a GameBanana file's own, or the one a local file is known to be from. */
    val submission: SubmissionRef?
    data class Remote(val identity: SubmissionIdentity) : InstalledSource {
        override val modId: String get() = identity.modId
        override val submission: SubmissionRef get() = identity.ref
    }
    data class Local(val id: String, val fileName: String, override val submission: SubmissionRef? = null) : InstalledSource {
        init {
            require(TokenPattern.matches(id))
            require(fileName.isNotBlank() && fileName.none { it == '/' || it == '\\' || it.isISOControl() })
        }
        override val modId: String get() = "local-$id"
    }
}

/**
 * One file of a mod as installed: where it came from and the complete, indivisible group of VPK files Hideout owns
 * for it, [files], its directory first and then its chunks in order. Their names come from its mod's [Placement].
 */
data class InstalledSet(
    val source: InstalledSource,
    /** GameBanana's own name for a remote file, as it listed it when Hideout downloaded it. Null for adopted and local sets. */
    val originalName: String?,
    val variant: String?,
    val origin: SetOrigin,
    /** Null for adopted and local sets, and for a download whose GameBanana listing was incomplete. */
    val version: VersionPosition?,
    /** [source]'s own archive-relative path to this set's directory file, which tells apart several sets Hideout took from one archive. */
    val componentKey: String,
    val files: List<SetFile>,
    /** How these files were made from retained originals; null for files as the source had them. */
    val derivation: SetDerivation? = null,
    /**
     * By part, the bytes found in a file of the set in place of its recorded ones: the user's own edit, made outside
     * Hideout. Until the user keeps it or puts the original back, nothing Hideout does moves, replaces or deletes the
     * mod's files.
     */
    val edits: Map<VpkPart, FileImage> = emptyMap(),
) {
    init {
        require(componentKey.isNotBlank()) { "a set names its component" }
        require(files.firstOrNull()?.part == VpkPart.Directory && files.map { it.part } == files.map { it.part }.distinct().sorted()) { "a set has one directory first, then each chunk once in order" }
        require(originalName == null || originalName.isNotBlank() && originalName.none { it == '/' || it == '\\' || it.isISOControl() }) { "a file name is one plain name" }
        require(edits.all { (part, image) -> files.any { it.part == part && it.image != image } }) { "an edit is other bytes in one of the set's files" }
    }

    val key: SetKey = SetKey.of(source, componentKey)

    /** The GameBanana file this set came from, or null for a local file. */
    val fileId: Long? get() = (source as? InstalledSource.Remote)?.identity?.fileId

    /** The download the set was unpacked from, or null when it came from none: what Clear, uninstall and discards act on. */
    val download: ArchiveIdentity? get() = (origin as? SetOrigin.Downloaded)?.archive

    /**
     * The name to show for where the files came from: GameBanana's or the local file's own when known, else the name
     * the download was saved under, else the last part of its directory file's path in its source.
     */
    val archiveName: String get() = (source as? InstalledSource.Local)?.fileName ?: originalName ?: download?.fileName ?: componentKey.substringAfterLast('/')
}

/** Where a mod's files are, and the only place a slot lives. */
sealed interface Placement {
    /** In [first]'s loading folder: set i of the mod at [first] + i, winner first. */
    data class On(val first: Slot) : Placement

    /** In the mod's own `hideout_off` folder, each set under its [SetKey.offStem], holding no number. */
    data object Off : Placement
}

/**
 * One installed mod: one Library entry and one place in the load order, in the game root it was installed into. Its
 * [sets] are winner first. While it is on they hold consecutive slots in one loading folder from [Placement.On.first],
 * and the lowest loads first, so it wins where two change the same files; while it is off they hold none and sit in
 * its own `hideout_off` folder. A record with nothing left to own is an uninstall, not a record. Several sets may
 * share one source archive, local or remote, told apart by their own [InstalledSet.componentKey].
 */
data class ManifestRecord(
    val installId: String,
    val gameRoot: GameRoot,
    val title: String,
    val placement: Placement,
    val sets: List<InstalledSet>,
    /**
     * The file ids its GameBanana submission offered when the user kept its files over reviewing them: while the
     * submission offers exactly these, its files are not offered for review again. Null when never kept.
     */
    val reviewed: Set<Long>? = null,
    /**
     * By GameBanana file id, the parts that file offered and the user left out when its parts were last settled, by
     * component key: an update asks again only about parts new since. A file it has no parts left out of is absent.
     */
    val declined: Map<Long, Set<String>> = emptyMap(),
    /**
     * The mods, by install id, that showed this one's models with other bytes when it was installed and that the user
     * chose to keep on beside it: where either shows the other's models, the user meant it.
     */
    val keptBeside: Set<String> = emptySet(),
    /**
     * An import brought it as a local mod and GameBanana has not yet answered who it is. Finding it clears this, whether
     * or not GameBanana knew it; while it is set, Hideout asks again whenever it can.
     */
    val unasked: Boolean = false,
) {
    init {
        require(installId.isNotBlank()) { "installId must not be blank" }
        require(isPlainAbsolutePath(gameRoot.path)) { "the game root must be absolute, without . or .. or control characters" }
        require(sets.isNotEmpty()) { "a record must own at least one set" }
        require(sets.map { it.source.modId }.distinct().size == 1) { "every set of a record comes from one mod" }
        require(sets.map { it.source to it.componentKey }.distinct().size == sets.size) { "a component is installed once in a mod" }
        require(sets.map { it.key }.distinct().size == sets.size) { "two components of a mod share a key" }
        (placement as? Placement.On)?.let { require(it.first.pak.value + sets.size - 1 <= PakNumber.PerFolder) { "a mod's sets sit in one folder" } }
        require(declined.all { (fileId, parts) -> parts.isNotEmpty() && sets.any { it.fileId == fileId } && sets.none { it.fileId == fileId && it.componentKey in parts } }) {
            "a part left out is one of a file the mod has, and not installed"
        }
    }

    val enabled: Boolean get() = placement is Placement.On

    /** Whether the user edited a file of the mod outside Hideout and has not kept the edit or put the original back: the mod stays as it is until then. */
    val edited: Boolean get() = sets.any { it.edits.isNotEmpty() }

    val editedFiles: List<OwnedFile> get() = sets.flatMap { set -> filesOf(set).filter { it.part in set.edits } }

    /** The mod with its sets' edits kept as their own files: their bytes recorded, and an edited set made from no original any more. */
    fun kept(): ManifestRecord = copy(sets = sets.map { set ->
        if (set.edits.isEmpty()) set else set.copy(files = set.files.map { file -> set.edits[file.part]?.let { SetFile(file.part, it) } ?: file }, derivation = null, edits = emptyMap())
    })

    /** The mod every set came from, such as "mod-721908"; also its `hideout_off` folder name. */
    val modId: String get() = sets.first().source.modId

    /** The submission whose page the mod has: its GameBanana file's, or the one a local mod is known to be from. */
    val submission: SubmissionRef? get() = sets.first().source.submission

    /** The slots the sets hold while the mod is on, lowest first; none while it is off. */
    val numbers: List<Slot>
        get() = when (val placed = placement) {
            is Placement.On -> sets.indices.map { placed.first + it }
            Placement.Off -> emptyList()
        }

    /** The slot [set] holds while the mod is on; null while it is off. */
    fun numberOf(set: InstalledSet): Slot? {
        val index = sets.indexOf(set)
        require(index >= 0) { "the set is not one of the mod's" }
        return (placement as? Placement.On)?.let { it.first + index }
    }

    fun set(key: SetKey): InstalledSet = sets.firstOrNull { it.key == key } ?: throw IllegalArgumentException("the mod has no set ${key.value}")

    fun setOf(file: OwnedFile): InstalledSet = set(file.set)

    /** [set]'s files as the placement names them: `pakNN_<part>.vpk` while on, `<offStem>_<part>.vpk` while off. The only place a name is made. */
    fun filesOf(set: InstalledSet): List<OwnedFile> {
        val key = set.key
        val stem = numberOf(set)?.pak?.stem ?: key.offStem(installId)
        return set.files.map { OwnedFile(key, it.part, it.part.fileName(stem), it.image) }
    }

    val files: List<OwnedFile> get() = sets.flatMap(::filesOf)

    /** The same mod at [placement] with [sets], its own sets in their order or another. */
    fun placed(placement: Placement, sets: List<InstalledSet> = this.sets): ManifestRecord = copy(placement = placement, sets = sets)

    /** Which files the mod has and where each came from, winner first, whatever numbers or folder they have now. */
    val provenance: List<SetProvenance> get() = sets.map { SetProvenance(it.source, it.origin, it.componentKey) }
}

data class SetProvenance(val source: InstalledSource, val origin: SetOrigin, val componentKey: String)

/** The path an [OwnedFile] lives at under its record's [ManifestRecord.gameRoot], in forward-slash segments. */
fun OwnedFile.relativePath(record: ManifestRecord): String = "${record.folder()}/$name"

/**
 * The folder under a game root that a set's files are placed in: a loading folder, where the game loads them, or the
 * mod's own `hideout_off` folder, where it does not. Every segment is fixed or a validated [SubmissionRef.modId], never
 * a name from an archive or the user.
 */
sealed interface SetFolder {
    val segments: List<String>

    /**
     * The [index]th folder Hideout's block mounts sets from: `addons`, then `hideout_addons2` onwards. No [ForeignRoot]
     * matches those names, so an import never adopts or unmounts them, and Deadlock Mod Manager's `addons2` stays its own.
     */
    data class On(val index: Int) : SetFolder {
        init { require(index in 1..LoadingFolders) { "Hideout loads sets from $LoadingFolders folders at most" } }
        override val segments: List<String> get() = GameSegments + if (index == 1) "addons" else "hideout_addons$index"
    }

    data class Off(val modId: String) : SetFolder {
        constructor(submission: SubmissionRef) : this(submission.modId)
        init { require(SubmissionRef.parse(modId) != null || modId.startsWith("local-") && TokenPattern.matches(modId.removePrefix("local-"))) }
        override val segments: List<String> get() = listOf("game", "citadel", "hideout_off", modId)
    }

    companion object {
        /** The segments every folder shares, which are Deadlock's and are never created by Hideout. */
        val GameSegments: List<String> = listOf("game", "citadel")

        /** Where every set loads while a library holds 99 or fewer. */
        val Addons: On = On(1)

        /** Every folder Hideout loads sets from, in the order its block mounts them. */
        val Loading: List<On> = (1..LoadingFolders).map(::On)
    }
}

private const val LoadingFolders = 10

/** The folder below `game/citadel`, as [gg.hideouthq.core.data.GameInventory.list] takes it. */
val SetFolder.inCitadel: String get() = segments.drop(SetFolder.GameSegments.size).joinToString("/")

/** The folder below the game root, `/`-separated. */
internal val SetFolder.path: String get() = segments.joinToString("/")

/** [name] as a log line or a problem shows something in this folder: alone in `addons`, after the folder's name past it. */
fun SetFolder.On.label(name: String): String = if (this == SetFolder.Addons) name else "$inCitadel/$name"

fun ManifestRecord.setFolder(): SetFolder = (placement as? Placement.On)?.first?.folder ?: SetFolder.Off(modId)

/** How many loading folders past `addons` [root]'s block mounts for these records: up to the last one a set that is on sits in. */
fun List<ManifestRecord>.overflowIn(root: GameRoot): Int = filter { it.gameRoot == root && it.enabled }.maxOfOrNull { it.numbers.first().folder.index - 1 } ?: 0

fun ManifestRecord.folder(): String = setFolder().path

/** Where [set] loads among what a compatibility check compares it with: its slot while its mod is on, else its place in its mod from 1. */
fun ManifestRecord.rank(set: InstalledSet): Int = numberOf(set)?.value ?: (sets.indexOf(set) + 1)

/** Whether [name] is a final name Hideout gives a set's file here: `pakNN_<part>.vpk` only in a loading folder, `<offStem>_<part>.vpk` only in an off folder. */
fun SetFolder.admits(name: String): Boolean = when (this) {
    is SetFolder.On -> OnName.matches(name)
    is SetFolder.Off -> OffName.matches(name)
}

/** Whether [name] is a name this folder [admits] with `.hideout-<token>` after it: an install's temporary, or a change's anchor or temporary. */
fun SetFolder.admitsTemporary(name: String): Boolean {
    val at = name.indexOf(TemporaryInfix)
    return at > 0 && admits(name.substring(0, at)) && TemporarySuffix.matches(name.substring(at + TemporaryInfix.length))
}

private val OnName = Regex("""pak(0[1-9]|[1-9][0-9])_(dir|[0-9]{3})\.vpk""")
private val OffName = Regex("""set-[0-9a-f]{12}_(dir|[0-9]{3})\.vpk""")
private val TemporarySuffix = Regex("[a-z0-9][a-z0-9-]{0,${MaxTokenLength - 1}}(-new)?")
private const val OffStem = "set-"
private const val KeyLength = 12

/**
 * Whether a download stays after its install. Chosen once, when the download is registered, from Settings › Mods ›
 * "Keep downloaded files": turning that setting off later never makes a kept download disposable.
 */
enum class DownloadRetention {
    Keep,

    /** Deleted once the install that downloaded it has ended, whether it installed or not. */
    DiscardAfterInstall,
}

/** A file Hideout's downloader published under a name nothing else had: [archive], GameBanana's [source]. */
data class OwnedDownload(val source: SubmissionIdentity, val archive: ArchiveIdentity, val retention: DownloadRetention = DownloadRetention.Keep)

/** Whether the user last asked for Deadlock with Hideout's mods loading (Play modded, an install, Reapply) or without (Play vanilla, Settings' Restore original). */
enum class LoadingIntent { Modded, Vanilla }

/**
 * What Hideout did to one game's `gameinfo.gi`: the file exactly as Hideout last wrote it, evidence for a later restore
 * and never authorization to overwrite newer edits, and the block it asks for there. Its mods are off after Play vanilla or
 * Restore original, and its custom games paths are on while custom games are in use here. [game] is the game's build,
 * from its shipped files, when Hideout wrote [written], so a later rewrite can be told apart as an update's.
 */
data class GameConfigReceipt(val gameRoot: GameRoot, val written: FileImage?, val lines: HideoutLines, val game: String? = null) {
    init {
        require(isPlainAbsolutePath(gameRoot.path)) { "the game root must be absolute, without . or .. or control characters" }
    }
}

/**
 * Where an interrupted install stopped, which decides what recovery may prove is Hideout's. A final name is Hideout's
 * only while it and its temporary copy are one file, or once [Releasing] is saved. Equal bytes, or a temporary that
 * is gone, prove nothing.
 */
sealed interface InstallStep {
    /** Temporary copies may exist, possibly incomplete. Nothing is published. */
    data object Staging : InstallStep

    /** Every temporary copy was written and verified, and final names are being linked to them. No copy is removed in this step. */
    data object Publishing : InstallStep

    /** Every final name was linked and was still one file with its copy when this step was saved. Copies are being released before the commit. */
    data object Releasing : InstallStep

    /** Undoing: [published] names the final files proven Hideout's, by being one file with their copy or by [Releasing]. */
    data class RollingBack(val published: Set<String>) : InstallStep
}

/**
 * The one physical change in flight, saved before the change touches the game and cleared in the save that commits
 * it. Recovery only ever touches names generated from it under a game root that still validates as Deadlock's.
 */
sealed interface PendingChange {
    /**
     * Changing which files a mod has, from [source] to [target], at its one placement: what goes leaves [source]'s
     * folder and what comes takes [target]'s, which differ only for an off local mod that becomes its submission's
     * files. [kept] are the sets kept with their bytes and metadata: one whose names stay is never touched, and one
     * whose names change, as a kept set of a mod that is on does when the sets before it change, is renamed through
     * anchors. Every other set of [source] goes and every other set of [target] is new, copied in through temporaries.
     * [found] is what a file that goes held when a reinstall began instead of its recorded bytes: other bytes, or null
     * when it was missing; every other change starts only from files that hold what the record says. It also names,
     * with its bytes, each chunk an edited set that goes has in [source]'s folder beyond its recorded files, which goes
     * with them. Each one found holding other bytes is moved to the folder [aside] of [CitadelPath.HideoutEdits] as it
     * goes, so what the user edited is never deleted.
     */
    data class Replace(
        val source: ManifestRecord,
        val target: ManifestRecord,
        val kept: Set<SetKey>,
        val token: ChangeToken,
        val step: ReplaceStep,
        val found: Map<String, FileImage?> = emptyMap(),
        val aside: String? = null,
    ) : PendingChange {
        init {
            require(source.installId == target.installId && source.gameRoot == target.gameRoot && source.placement == target.placement) { "a replacement keeps the mod where it is" }
            require(kept.all { key -> source.sets.firstOrNull { it.key == key }?.let { it == target.sets.firstOrNull { set -> set.key == key } } == true }) {
                "a kept set keeps its source, bytes and metadata"
            }
            val leaving = going.associateBy { it.name }
            val recorded = source.files.mapTo(HashSet()) { it.name }
            require(found.all { (name, image) -> leaving[name]?.let { it.image != image } ?: (name !in recorded && image != null) }) { "only a file that goes can have been found missing or changed, and only a file the record does not name found beside it" }
            require((aside != null) == found.values.any { it != null }) { "what a reinstall found in place of a file's bytes is set aside" }
        }

        /** What [file] of [source] held when the change began: its recorded bytes unless [found] says otherwise, null when it was missing. */
        fun held(file: OwnedFile): FileImage? = if (file.name in found) found[file.name] else file.image

        /** The files [source]'s folder had beside an edited set that goes, by name, which go aside with it. */
        val strays: Map<String, FileImage>
            get() {
                val names = going.mapTo(HashSet()) { it.name }
                return buildMap { for ((name, image) in found) if (name !in names && image != null) put(name, image) }
            }

        private val stays: Set<SetKey> get() = kept.filterTo(HashSet()) { source.numberOf(source.set(it)) == target.numberOf(target.set(it)) }

        /** The files of [source] that leave their names: every file of a set that goes, and every file of a kept set that is renamed. */
        val going: List<OwnedFile> get() = stays.let { stays -> source.sets.filter { it.key !in stays }.flatMap(source::filesOf) }

        /** The files of [target] that take new names: every file of a new set, and every file of a kept set that is renamed. */
        val coming: List<OwnedFile> get() = stays.let { stays -> target.sets.filter { it.key !in stays }.flatMap(target::filesOf) }

        /** The file of [source] that [file] of [target] is renamed from, or null when [file] is new, copied in through its temporary. */
        fun renamedFrom(file: OwnedFile): OwnedFile? = file.set.takeIf { it in kept }?.let { key -> source.filesOf(source.set(key)).single { it.part == file.part } }
    }

    /** Placing new mod [record], always off, in its own off folder, to be on in [profiles] once it is committed, kept through a rollback too. */
    data class Install(val record: ManifestRecord, val step: InstallStep, val profiles: Set<ProfileId>) : PendingChange {
        init {
            require(record.placement == Placement.Off) { "a new mod is placed off" }
            require(TokenPattern.matches(record.installId)) { "a pending install's id must be safe in a file name" }
            if (step is InstallStep.RollingBack) {
                require(record.files.map { it.name }.containsAll(step.published)) { "only the set's own files can have been published" }
            }
        }

        /** Where [file] is copied and verified before it is published under its own name. */
        fun temporaryName(file: OwnedFile): String = "${file.name}$TemporaryInfix${record.installId}"
    }

    /** Replacing the game's [target] that hashed to [from], or was not there when [from] is null, with a file that hashes to [to]. */
    data class ConfigEdit(val gameRoot: GameRoot, val target: ConfigTarget, val token: String, val from: FileImage?, val to: FileImage) : PendingChange {
        init {
            require(TokenPattern.matches(token)) { "a config edit's token must be safe in a file name" }
            require(isPlainAbsolutePath(gameRoot.path)) { "the game root must be absolute, without . or .. or control characters" }
            require(from != null || target == ConfigTarget.Autoexec) { "only autoexec.cfg can be written where none was" }
        }
    }

    /**
     * Moving committed off record [source] to [target]'s folder: an off local mod becoming its submission's files, whose
     * keys and so whose names change with its sources while every part keeps its bytes, file by file in order. Each
     * source file first gets an anchor, a second name beside it, and each target name is linked from that anchor, so a
     * target is Hideout's only while it is one file with its anchor.
     */
    data class Relocate(val source: ManifestRecord, val token: ChangeToken, val step: RelocateStep, val target: ManifestRecord) : PendingChange {
        init {
            require(!source.enabled && !target.enabled && target.installId == source.installId && target.gameRoot == source.gameRoot) { "a move keeps the mod, off, in its game" }
            require(target.files.map { it.part to it.image } == source.files.map { it.part to it.image }) { "a move keeps every part's bytes in order" }
        }

        /** The record the manifest holds during [step]: the source until [RelocateStep.Cleanup] is saved with the target. */
        val committed: ManifestRecord get() = if (step == RelocateStep.Cleanup) target else source
    }

    /** Removing the whole mod [record], and with it [archives], downloads of its own sets, when the user asked for them to go. */
    data class Uninstall(val record: ManifestRecord, val archives: List<ArchiveIdentity>, val token: ChangeToken, val step: UninstallStep) : PendingChange {
        init {
            require(archives.distinct().size == archives.size) { "a download goes once" }
            require(record.sets.mapNotNull { it.download }.containsAll(archives)) { "only the record's own downloads can go with it" }
            if (step is UninstallStep.Settled) {
                require(step.files.keys == record.files.map { it.name }.toSet()) { "a settled uninstall has one outcome per file" }
                require(step.archives.keys == archives.map { it.path }.toSet()) { "a settled uninstall has one outcome per download that goes" }
            }
        }
    }

    /**
     * Deleting one registered [download] on its own: cleared from Settings, or discarded after the install that made
     * it, [installedInto], the one record that may still name it. It anchors and releases the archive the way an
     * uninstall does, and touches no game.
     */
    data class ArchiveRemoval(val download: OwnedDownload, val installedInto: String?, val token: ChangeToken, val step: ArchiveStep) : PendingChange

    /**
     * Taking over [plan]'s files where another manager left them. Every Hideout name a moving file takes is vacant, so
     * the name is its own anchor: during [AdoptStep.Linking] each is linked from the file it is made from, each set's
     * chunks first and directory last, and proven one file with it holding its recorded bytes; during
     * [AdoptStep.Releasing] the other manager's names go, each set's directory first, while each is still one file
     * with its Hideout name, and the records, the applied book and the saved target are committed in the save that
     * clears this. Restarting from [AdoptStep.Linking] undoes; from [AdoptStep.Releasing] it finishes. Nothing ever
     * makes a name another manager uses.
     */
    data class Adopt(val plan: AdoptionPlan, val step: AdoptStep) : PendingChange

    /**
     * Taking [sets] Hideout does not own out of [gameRoot]'s loading folders into [folder], a folder of
     * [CitadelPath.HideoutMoved], where each file keeps its path below `game/citadel`. As in [Adopt], every name a file
     * takes is vacant, so it is its own anchor: during [MoveOutStep.Linking] each is linked from its file, each set's
     * chunks first and directory last, and proven one file with it holding its [OutFile.image]; during
     * [MoveOutStep.Releasing] the names in the loading folder go, each set's directory first, while each is still one
     * file with its new name. Restarting from [MoveOutStep.Linking] undoes; from [MoveOutStep.Releasing] it finishes.
     * Nothing is ever deleted: each file's bytes stay under its new name.
     */
    data class MoveOut(val gameRoot: GameRoot, val folder: CitadelPath, val sets: List<List<OutFile>>, val step: MoveOutStep) : PendingChange {
        init {
            require(folder.parent == CitadelPath.HideoutMoved) { "a move out has a folder of its own" }
            require(sets.isNotEmpty() && sets.all { set -> set.count { it.isDirectory } == 1 }) { "each set moves whole, with its one directory" }
            require(sets.flatten().all { file -> SetFolder.Loading.any { it.inCitadel == file.path.parent?.value } }) { "only a loading folder's files move out" }
            require(sets.flatten().map { it.path }.let { it.distinct().size == it.size }) { "a file moves once" }
        }

        /** Where [file] goes: its own path below `game/citadel`, under [folder]. */
        fun target(file: OutFile): CitadelPath = folder.resolve(file.path.value)
    }

    /**
     * Placing the committed records of [moves] as their targets have them, in every game at once, so that the applied
     * book becomes [after]: turned on at numbers, turned off, at other numbers, or with their sets in another order. A
     * switch that turns mods off and others on at the numbers they free is one change. Every file keeps its bytes. Each
     * file whose path changes gets an anchor beside it, and each target name is linked from that anchor, so a name is
     * Hideout's only while it is one file with its own move's anchor. The targets and [after] are saved together, with
     * [MoveStep.Cleanup].
     */
    data class Arrange(val token: ChangeToken, val moves: List<Move>, val after: ProfileBook, val step: MoveStep) : PendingChange {
        init {
            require(moves.any { it.moving.isNotEmpty() }) { "an arrangement moves a file" }
            require(moves.map { it.source.installId }.distinct().size == moves.size) { "a mod moves once" }
            require(moves.flatMap { move -> move.target.numbers.map { move.source.gameRoot to it } }.let { it.distinct().size == it.size }) { "two moving mods cannot take one number" }
        }

        /** The records the manifest holds during [step]: the sources until [MoveStep.Cleanup] is saved with the targets. */
        val committed: List<ManifestRecord> get() = moves.map { if (step == MoveStep.Cleanup) it.target else it.source }
    }

    /**
     * Restoring [gameRoot]'s Library to a backup's, from its records now, [source], to the backup's as
     * [LibraryLedger.planRevert] placed them, [target]. A set both place in the same folder under the same names with
     * the same bytes is not touched. Every other file of [source] goes through an anchor beside it, but for those
     * [absent] when the restore began; every other file of [target] comes, linked from the anchor of a file that goes
     * with its bytes ([fed]), else from the backup's store through a temporary. A set the store could not give is left
     * out whole, its files [missing], so no directory is mounted without its chunks, and comes back missing.
     * [registered] are the retained originals placed for it and registered in the first save. [target], the applied
     * [book], the saved [choices], the backup's [originals] and its [repairChoices] are committed in one save, with
     * [ReplaceStep.Cleanup].
     */
    data class Revert(
        val gameRoot: GameRoot,
        val source: List<ManifestRecord>,
        val target: List<ManifestRecord>,
        val token: ChangeToken,
        val step: ReplaceStep,
        val book: ProfileBook,
        val choices: SavedChoices?,
        val originals: List<RetainedOriginal>,
        val registered: Set<String>,
        val repairChoices: Map<String, RepairChoice>,
        val absent: Set<String> = emptySet(),
        val missing: Set<String> = emptySet(),
    ) : PendingChange {
        init {
            require((source + target).all { it.gameRoot == gameRoot }) { "a restore changes one game" }
            require(target.map { it.installId }.distinct().size == target.size && target.flatMap { it.numbers }.let { it.distinct().size == it.size }) { "a restore commits each mod once, under numbers of its own" }
            require(paths(source).containsAll(absent)) { "only a file of the Library now can have been missing" }
            require(paths(target).containsAll(missing)) { "only a file of the backup can be left out" }
            require(originals.map { it.id }.containsAll(registered)) { "only the backup's originals are placed" }
            require(target.map { it.installId }.containsAll(repairChoices.keys)) { "a repair choice is the backup's own" }
        }

        /** Every file of [source] that leaves its name: the present ones of each set [target] does not place exactly as it is. */
        val going: List<RevertFile> = changing(source, target).filter { it.path !in absent }

        /** Every file of [target] that takes its name, but for the sets left out. */
        val coming: List<RevertFile> = changing(target, source).filter { it.path !in missing }

        /**
         * Each file of [coming] linked from the anchor of a file that goes with its bytes, to that file: the one at its
         * own path when that has them, so an undo never takes it for another file of the same bytes, else the first.
         * Every other file comes from the store.
         */
        val fed: Map<RevertFile, RevertFile> = run {
            val atPath = going.associateBy { it.path }
            val withImage = going.distinctBy { it.file.image }.associateBy { it.file.image }
            coming.mapNotNull { file -> (atPath[file.path]?.takeIf { it.file.image == file.file.image } ?: withImage[file.file.image])?.let { file to it } }.toMap()
        }

        private fun changing(records: List<ManifestRecord>, others: List<ManifestRecord>): List<RevertFile> {
            val placed = others.flatMap { record -> record.sets.map { set -> record.filesOf(set).map { it.relativePath(record) to it.image }.toSet() } }.toSet()
            return records.flatMap { record ->
                record.sets.map(record::filesOf).filter { files -> files.any { it.relativePath(record) in absent } || files.map { it.relativePath(record) to it.image }.toSet() !in placed }
                    .flatMap { files -> files.map { RevertFile(record, it) } }
            }
        }
    }
}

data class RevertFile(val record: ManifestRecord, val file: OwnedFile) {
    val path: String get() = file.relativePath(record)
}

private fun paths(records: List<ManifestRecord>): List<String> = records.flatMap { record -> record.files.map { it.relativePath(record) } }

/** Which of the game's config files a [PendingChange.ConfigEdit] replaces, and what its receipt learns once it lands. */
sealed interface ConfigTarget {
    val path: CitadelPath

    /** The file's path under the game root, as forward-slash segments. */
    val segments: List<String> get() = SetFolder.GameSegments + path.value.split('/')

    /**
     * Whether it may be replaced, and an interrupted replacement settled, while Deadlock may be running: only
     * autoexec.cfg, which the game reads when it starts.
     */
    val whileGameRuns: Boolean get() = this == Autoexec

    /** `gameinfo.gi`, carrying out [lines], which the game's [GameConfigReceipt] records. */
    data class GameInfo(val lines: HideoutLines) : ConfigTarget {
        override val path: CitadelPath get() = Path

        companion object {
            val Path: CitadelPath = CitadelPath("gameinfo.gi")
            val Segments: List<String> = SetFolder.GameSegments + Path.value
        }
    }

    /** `cfg/autoexec.cfg`. It has no receipt. */
    data object Autoexec : ConfigTarget {
        override val path: CitadelPath = CitadelPath("cfg/autoexec.cfg")
    }
}

enum class AdoptStep { Linking, Releasing }

enum class MoveOutStep { Linking, Releasing }

/** One file [PendingChange.MoveOut] takes out of a loading folder: where it is, the bytes it held when the move began, and whether it is its set's directory. */
data class OutFile(val path: CitadelPath, val image: FileImage, val isDirectory: Boolean)

/**
 * One record an import makes of files already in the game: [record] as it is committed, and the path each of its
 * files has now, in [ManifestRecord.files] order. It is [claimed] when every file already sits at its own Hideout
 * path, so committing it touches no file; otherwise every file moves to a vacant name in its off folder. It is on
 * exactly when it is claimed, where the game loads it already, so an import never mounts anything new.
 */
data class Adoption(val record: ManifestRecord, val sources: List<CitadelPath>) {
    init {
        require(sources.size == record.files.size) { "one source per file" }
        require(sources.distinct().size == sources.size) { "a file is adopted once" }
        require(sources.none { it.within(CitadelPath.HideoutOff) || it.offLimits }) { "a file is adopted from where another manager keeps it" }
        val targets = record.files.map { it.citadelPath(record) }
        require(sources == targets || sources.zip(targets).none { (from, to) -> from == to }) { "a record is claimed where it is or moved whole" }
        require(record.enabled == claimed) { "an adopted mod is on exactly when it is claimed where it loads" }
    }

    val claimed: Boolean get() = sources.first() == record.files.first().citadelPath(record)
}

/**
 * What one import commits in [gameRoot]: the [adopted] records, the applied [book] with them in it, and the saved
 * [target] the files follow next. [adopted] may be empty when an import only brings profiles over records Hideout
 * holds already. When [target] is [book], the saved choices settle in the same save.
 */
data class AdoptionPlan(
    val gameRoot: GameRoot,
    val migration: MigrationId,
    val adopted: List<Adoption>,
    val book: ProfileBook,
    val target: ProfileBook,
) {
    init {
        require(adopted.all { it.record.gameRoot == gameRoot }) { "an import takes over one game" }
        require(adopted.map { it.record.installId }.distinct().size == adopted.size) { "an adopted record is made once" }
        require(adopted.flatMap { it.record.numbers }.let { it.distinct().size == it.size }) { "two adopted records cannot take one number" }
        require(adopted.flatMap { it.sources }.let { it.distinct().size == it.size }) { "a file is adopted into one record" }
        require(adopted.all { (it.record.installId in book.activeProfile.enabled) == it.record.enabled }) { "the applied book has exactly the adopted records that are on" }
    }

    val moving: List<Adoption> get() = adopted.filterNot { it.claimed }
}

fun OwnedFile.citadelPath(record: ManifestRecord): CitadelPath = CitadelPath("${record.setFolder().inCitadel}/$name")

enum class ReplaceStep { Staging, Anchoring, Releasing, Publishing, Cleanup, RollingBack }

/**
 * Whether the change may be finished or undone while Deadlock may be running: it touches nothing the game loads. An
 * install, always off, a move between off folders, the removal of a download, and an uninstall or change of files of
 * a mod that is off only touch that mod's own off folder or Hideout's downloads; autoexec.cfg is read only at start.
 */
val PendingChange.whileGameRuns: Boolean
    get() = when (this) {
        is PendingChange.Install, is PendingChange.Relocate, is PendingChange.ArchiveRemoval -> true
        is PendingChange.Uninstall -> !record.enabled
        is PendingChange.Replace -> !source.enabled
        is PendingChange.ConfigEdit -> target.whileGameRuns
        is PendingChange.Arrange, is PendingChange.Adopt, is PendingChange.Revert, is PendingChange.MoveOut -> false
    }

val PendingChange.gameRoots: Set<GameRoot>
    get() = when (this) {
        is PendingChange.Replace -> setOf(source.gameRoot)
        is PendingChange.Install -> setOf(record.gameRoot)
        is PendingChange.ConfigEdit -> setOf(gameRoot)
        is PendingChange.Relocate -> setOf(source.gameRoot)
        is PendingChange.Uninstall -> setOf(record.gameRoot)
        is PendingChange.ArchiveRemoval -> emptySet()
        is PendingChange.Arrange -> moves.map { it.source.gameRoot }.toSet()
        is PendingChange.Adopt -> setOf(plan.gameRoot)
        is PendingChange.Revert -> setOf(gameRoot)
        is PendingChange.MoveOut -> setOf(gameRoot)
    }

/** A fresh token for one change, safe in a file name. Its anchors are named with it, so no two changes share one. */
@JvmInline
value class ChangeToken(val value: String) {
    init {
        require(TokenPattern.matches(value)) { "a change token must be safe in a file name" }
    }

    /** The anchor that keeps [file]'s bytes reachable while this change moves or removes it: a second name beside it. */
    fun anchorName(file: OwnedFile): String = anchorName(file.name)

    fun anchorName(name: String): String = "$name$TemporaryInfix$value"

    /** Where this change copies [file] in before it takes its own name. */
    fun temporaryName(file: OwnedFile): String = "${file.name}$TemporaryInfix$value-new"
}

/**
 * How far moving a mod to another folder got. Restarting from [Linking] undoes; from [Releasing] or [Cleanup] it
 * finishes, unless releasing finds a target or a source changed and goes back to [Linking]. Anchors stay until the
 * target record is saved.
 */
enum class RelocateStep {
    /** Anchors and target names are being linked, chunks first and the directory last, or undone, every source name back from its anchor. */
    Linking,

    /** Every target was one file with its anchor, with the recorded bytes, when this was saved. Once every target still is, source names go, chunks first: an undo puts a directory back only with all its chunks, and off folders are never mounted. */
    Releasing,

    /** Saved together with the target record. Anchors that are still one file with their target go. */
    Cleanup,
}

/**
 * How far giving sets new names through [SetMoves] got. Restarting from [Anchoring] undoes; from [Releasing],
 * [Linking] or [Cleanup] it finishes, and a name it cannot free or take rolls it back.
 */
enum class MoveStep {
    /** Anchors are being linked beside every source file. No name the game reads changes. */
    Anchoring,

    /** Every anchor was one file with its source, with the recorded bytes. Sources go, each set's directory first. */
    Releasing,

    /** Every source went. Target names are linked from the anchors, each set's chunks first and its directory last. */
    Linking,

    /** Saved together with the target records. Anchors that are still one file with their target go. */
    Cleanup,

    /** Targets that are one file with their anchor go, sources are linked back from the anchors, then the anchors go. */
    RollingBack,
}

/** How far an uninstall got. Each step is saved before the calls it allows. */
sealed interface UninstallStep {
    /** Anchors are being made for the files that still hold what Hideout wrote. Nothing is deleted yet. */
    data object Anchoring : UninstallStep

    /** Originals that are still one file with their anchor and hold the recorded bytes go, directory first. */
    data object Releasing : UninstallStep

    /**
     * What happened to every file and to every download that goes, by archive path, saved in one step. No original
     * path is looked at again: a file someone puts back there is theirs. Only anchors remain to be cleaned before the
     * record goes.
     */
    data class Settled(val files: Map<String, RemovalOutcome>, val archives: Map<String, RemovalOutcome>) : UninstallStep
}

/** How far deleting one download on its own got, saved before the calls each step allows, as for an uninstall. */
sealed interface ArchiveStep {
    data object Anchoring : ArchiveStep

    data object Releasing : ArchiveStep

    /** What happened to the archive. Its path is never looked at again; only its anchor remains to be cleaned. */
    data class Settled(val outcome: RemovalOutcome) : ArchiveStep
}

/** What became of one file an uninstall was asked to remove. */
sealed interface RemovalOutcome {
    data object Removed : RemovalOutcome

    /** Nothing was there to remove. */
    data object Absent : RemovalOutcome

    /** Something stays there because Hideout could not prove it was its own unchanged file. */
    data class Preserved(val reason: String) : RemovalOutcome
}

/** Between a generated name and the install id in a temporary name: never a `.vpk` the game would mount. */
const val TemporaryInfix: String = ".hideout-"

const val MaxTokenLength: Int = 64

internal val TokenPattern = Regex("[a-z0-9][a-z0-9-]{0,${MaxTokenLength - 1}}")

/**
 * The durable record of every set Hideout has installed: Hideout's only claim to own a file. No two mods that are on in
 * one game root hold the same number, and a game holds one record of each mod, whose off folder is its own, so no two
 * records name the same file; a pending install, always off, counts the same way. A manifest that fails to parse or
 * carries a schema this code does not know is never treated as an empty, writable library; see
 * [gg.hideouthq.core.data.ManifestLoad].
 */
data class LibraryManifest(
    val schemaVersion: Int = ManifestSchemaVersion,
    val records: List<ManifestRecord> = emptyList(),
    val downloads: List<OwnedDownload> = emptyList(),
    val gameConfigs: List<GameConfigReceipt> = emptyList(),
    val pending: PendingChange? = null,
    /** The profiles as the files and every physical change go by: the applied book, whose active profile [ProfileBook.follows] [records]. */
    val profiles: ProfileBook = ProfileBook.main(records),
    /**
     * The choices the user saved that the files do not match yet, or null when they do. It is the authority for what
     * is chosen; [records] stay the authority for what files exist, where, and under which number.
     */
    val savedChoices: SavedChoices? = null,
    /** Original sets kept so repaired files can be undone, whatever the downloads or caches hold. */
    val originals: List<RetainedOriginal> = emptyList(),
    val repairChoices: Map<String, RepairChoice> = emptyMap(),
) {
    init {
        val claims = records + (pending as? PendingChange.Adopt)?.plan?.adopted.orEmpty().map { it.record }
        val named = claims + listOfNotNull((pending as? PendingChange.Install)?.record)
        require(named.map { it.installId }.distinct().size == named.size) { "installId must be unique across records" }
        require(named.map { it.gameRoot to it.modId }.distinct().size == named.size) { "a game holds one record of each mod" }
        val numbers = claims.flatMap { record -> record.numbers.map { record.gameRoot to it } }
        require(numbers.distinct().size == numbers.size) { "two mods on in one game hold the same number" }
        require(gameConfigs.map { it.gameRoot }.distinct().size == gameConfigs.size) { "one game has one config receipt" }
        require(downloads.map { it.archive.path }.distinct().size == downloads.size) { "one archive is one download" }
        require(profiles.follows(records)) { "the applied book follows the files" }
        require(savedChoices == null || savedChoices.target.covers(records)) { "the saved choices order exactly what is installed" }
        when (val change = pending) {
            is PendingChange.Replace -> {
                require(records.contains(if (change.step == ReplaceStep.Cleanup) change.target else change.source))
                val others = records.filter { it.gameRoot == change.source.gameRoot && it.installId != change.source.installId }.flatMap { it.numbers }
                require(change.target.numbers.none { it in others }) { "a mod's new files take no number another mod that is on holds" }
            }
            is PendingChange.Relocate -> require(records.any { it == change.committed }) { "a move names a record exactly as the manifest holds it" }
            is PendingChange.Uninstall -> {
                require(records.any { it == change.record }) { "an uninstall names a record exactly as the manifest holds it" }
                require(change.archives.all { archive -> downloads.any { it.archive == archive } }) { "only a registered download can be deleted" }
            }
            is PendingChange.ArchiveRemoval -> require(change.download in downloads) { "only a registered download can be deleted" }
            is PendingChange.Arrange -> {
                require(records.containsAll(change.committed)) { "an arrangement names its records exactly as the manifest holds them" }
                val moving = change.moves.associate { it.source.installId to it.target }
                val stationary = records.filter { it.installId !in moving }.flatMap { record -> record.numbers.map { record.gameRoot to it } }.toSet()
                require(change.moves.none { move -> move.target.numbers.any { move.source.gameRoot to it in stationary } }) { "an arrangement takes no number a mod that stays on holds" }
                require(change.after.follows(records.map { moving[it.installId] ?: it })) { "an arrangement's book follows the files it commits" }
            }
            is PendingChange.Adopt -> {
                val committed = records + change.plan.adopted.map { it.record }
                require(change.plan.book.follows(committed) && change.plan.target.covers(committed)) { "an import's books order exactly the records it commits" }
            }
            is PendingChange.Revert -> {
                val held = records.filter { it.gameRoot == change.gameRoot }.toSet()
                require(held == (if (change.step == ReplaceStep.Cleanup) change.target else change.source).toSet()) { "a restore names the game's records exactly as the manifest holds them" }
                val committed = records.filter { it.gameRoot != change.gameRoot } + change.target
                require(change.book.follows(committed) && change.choices?.target?.covers(committed) != false) { "a restore's books order exactly the records it commits" }
                require(originals.map { it.id }.containsAll(change.registered)) { "the originals placed for a restore are registered with it" }
            }
            is PendingChange.Install, is PendingChange.ConfigEdit, is PendingChange.MoveOut, null -> Unit
        }
        require(originals.map { it.id }.distinct().size == originals.size) { "an original is retained once" }
        require(originals.map { it.id }.containsAll(namedRecords().flatMap { it.sets }.mapNotNull { it.derivation?.original })) { "a repaired set names an original that is kept" }
    }

    /** Every record this manifest names: the committed ones, and the sources and targets of its pending change. */
    fun namedRecords(): List<ManifestRecord> = records + when (val change = pending) {
        is PendingChange.Replace -> listOf(change.source, change.target)
        is PendingChange.Install -> listOf(change.record)
        is PendingChange.Relocate -> listOf(change.source, change.target)
        is PendingChange.Uninstall -> listOf(change.record)
        is PendingChange.Arrange -> change.moves.flatMap { listOf(it.source, it.target) }
        is PendingChange.Adopt -> change.plan.adopted.map { it.record }
        is PendingChange.Revert -> change.source + change.target
        is PendingChange.ConfigEdit, is PendingChange.ArchiveRemoval, is PendingChange.MoveOut, null -> emptyList()
    }

    fun receipt(root: GameRoot): GameConfigReceipt? = gameConfigs.firstOrNull { it.gameRoot == root }
}

internal fun LibraryManifest.chosen(): ProfileBook = savedChoices?.target ?: profiles

/** The mods the user edited outside Hideout, by install id: they stay where they are until the user decides on the edits. */
internal val LibraryManifest.edited: Set<String> get() = records.filter { it.edited }.mapTo(HashSet()) { it.installId }

/**
 * Whether no change is in flight and the files are at the saved choices, or at them but for the [edited] mods and the
 * mods [pastLimit], which stay off where they are in the order: nothing is left for the files to do until the user
 * decides on an edit or chooses again.
 */
internal fun LibraryManifest.reached(pastLimit: Set<String> = emptySet()): Boolean = pending == null && savedChoices.let { saved ->
    saved == null || profiles.reaches(saved.target.membership(saved.target.active, pastLimit, false), edited)
}

internal fun LibraryManifest.settled(): LibraryManifest = if (pending == null && savedChoices?.target == profiles) copy(savedChoices = null) else this

private const val Sha256HexLength = 64

internal fun isSha256(value: String): Boolean = value.isLowerHex(Sha256HexLength)

private fun String.isLowerHex(length: Int): Boolean = this.length == length && all { it in '0'..'9' || it in 'a'..'f' }

internal fun isPlainAbsolutePath(path: String): Boolean {
    if (path.any { it.isISOControl() }) return false
    val parts = when {
        path.startsWith("\\\\") -> path.drop(2).split('\\', '/')
        path.length >= 3 && path[0].isLetter() && path[1] == ':' && (path[2] == '\\' || path[2] == '/') -> path.drop(3).split('\\', '/')
        path.startsWith("/") -> path.drop(1).split('/')
        else -> return false
    }
    return parts.none { it.isEmpty() || it == "." || it == ".." }
}
