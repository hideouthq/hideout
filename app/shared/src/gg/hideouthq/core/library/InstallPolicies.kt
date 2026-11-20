package gg.hideouthq.core.library

import kotlin.jvm.JvmInline

/** A number one folder mounts a `pakNN_dir.vpk` under. Lower loads first and wins. */
@JvmInline
value class PakNumber(val value: Int) {
    init {
        require(value in 1..PerFolder) { "a pak number is 01..99" }
    }

    /** `pak07`: the stem of every file of a set on under this number. */
    val stem: String get() = "pak" + value.toString().padStart(2, '0')

    companion object {
        /** How many numbers one folder mounts, Deadlock's own limit. */
        const val PerFolder: Int = 99
    }
}

/**
 * Where a set that is on loads among Hideout's: 1 loads first and wins. Hideout's block mounts its loading folders one
 * after another, each with its own `pak01`..`pak99`, so `addons` holds 1..99, `hideout_addons2` 100..198, and so on.
 * The slot alone is the load order; its [folder] and [pak] number derive from it.
 */
@JvmInline
value class Slot(val value: Int) : Comparable<Slot> {
    init {
        require(value in All) { "a slot is 1..${All.last}" }
    }

    override fun compareTo(other: Slot): Int = value.compareTo(other.value)

    operator fun plus(offset: Int): Slot = Slot(value + offset)

    val folder: SetFolder.On get() = SetFolder.Loading[(value - 1) / PakNumber.PerFolder]

    val pak: PakNumber get() = PakNumber((value - 1) % PakNumber.PerFolder + 1)

    companion object {
        val All: IntRange = 1..PakNumber.PerFolder * SetFolder.Loading.size

        fun of(folder: SetFolder.On, pak: PakNumber): Slot = Slot((folder.index - 1) * PakNumber.PerFolder + pak.value)
    }
}

/** The `NN` in `pakNN_dir.vpk`: which numbers the entries of a loading folder take. */
object PakNumbers {
    private val Numbered = Regex("""pak([0-9]+).*""", RegexOption.IGNORE_CASE)

    /**
     * The number an entry of a loading folder named [name] starts with (`pak7_dir.vpk`, `PAK07_000.VPK`, a folder or
     * link named `pak07`, a temporary copy). A name such as `631628_pak07_dir.vpk` or `set-3fa94c0d12be_dir.vpk` takes
     * none: the game does not mount it under a number.
     */
    fun taken(name: String): PakNumber? =
        Numbered.matchEntire(name)?.groupValues?.get(1)?.trimStart('0')?.takeIf { it.length <= 2 }?.toIntOrNull()?.takeIf { it in 1..PakNumber.PerFolder }?.let(::PakNumber)

    /** The number the game mounts [name] under when it is a bare `pakNN_dir.vpk`, whatever its case; null for any other name. */
    fun mounted(name: String): PakNumber? = BareDirectory.matchEntire(name)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..PakNumber.PerFolder }?.let(::PakNumber)

    private val BareDirectory = Regex("""pak([0-9]{2})_dir\.vpk""", RegexOption.IGNORE_CASE)
}

sealed interface KnownProcess {
    data object Game : KnownProcess

    data class OtherManager(val manager: Manager) : KnownProcess
}

/**
 * Which known program a process is, from the basename of its first argument or its process name; null for any other.
 * The rest of a command line is never looked at, so a wrapper that names the game among its arguments is not the game.
 */
fun knownProcess(argv0: String?, name: String?): KnownProcess? {
    val names = listOfNotNull(argv0, name).map { it.substringAfterLast('/').substringAfterLast('\\') }
    return KnownProcesses.entries.firstOrNull { (_, pattern) -> names.any(pattern::matches) }?.key
}

// Linux cuts a process name to 15 bytes, so each long name also appears cut.
private val KnownProcesses: Map<KnownProcess, Regex> = mapOf(
    KnownProcess.Game to """deadlock\.exe""",
    KnownProcess.OtherManager(Manager.DeadlockModManager) to
        """deadlock-mod-manager|deadlock-mod-ma|deadlock-modmanager|deadlock-modman|(deadlock-mod-manager|Deadlock Mod Manager)\.exe|Deadlock Mod Manager_.+-portable\.exe""",
    KnownProcess.OtherManager(Manager.Grimoire) to """grimoire|grimoire.*\.AppImage|grimoire\.exe""",
).mapValues { (_, names) -> Regex(names, RegexOption.IGNORE_CASE) }

data class VersionSource(val fileId: Long, val description: String?, val filename: String)

/** Pass the whole current file list, before filtering previews or selecting downloads. */
fun versionNames(title: String, files: List<VersionSource>): Map<Long, String> {
    val titleWords = title.split(TitleWordSplit).filter { it.isNotEmpty() }.map { it.lowercase() }
    val words = files.map { file ->
        val stem = file.filename.substringBeforeLast('.').ifBlank { file.filename }
        stem.replace(TrailingHash, "").split(WordSplit).filter { it.isNotEmpty() }
    }
    val stripTitle = titleWords.isNotEmpty() && words.all { it.take(titleWords.size).map { word -> word.lowercase() } == titleWords }
    val descriptions = files.map { it.description?.trim()?.takeIf(String::isNotEmpty) }
    val descriptionCounts = descriptions.filterNotNull().groupingBy { it.lowercase() }.eachCount()
    val names = files.mapIndexed { index, file ->
        val description = descriptions[index]
        if (description != null && descriptionCounts[description.lowercase()] == 1 && !description.equals(title.trim(), ignoreCase = true)) {
            description
        } else {
            words[index].drop(if (stripTitle) titleWords.size else 0).ifEmpty { words[index] }
                .joinToString(" ").ifBlank { file.filename.ifBlank { file.fileId.toString() } }
                .replaceFirstChar { it.uppercaseChar() }.replace(VersionToken) { "V${it.groupValues[1]}" }
        }
    }
    val nameCounts = names.groupingBy { it.lowercase() }.eachCount()
    return files.mapIndexed { index, file ->
        val name = names[index]
        file.fileId to if (nameCounts[name.lowercase()] == 1) name else
            file.filename + if (files.count { it.filename == file.filename } > 1) " · ${file.fileId}" else ""
    }.toMap()
}

private val TrailingHash = Regex("[_-][0-9a-f]{5}$", RegexOption.IGNORE_CASE)
private val TitleWordSplit = Regex("[^\\p{L}\\p{N}]+")
private val WordSplit = Regex("[\\s_-]+")
private val VersionToken = Regex("\\bv(\\d+)\\b", RegexOption.IGNORE_CASE)

enum class EntryKind { File, Directory, Link, Other }

/** What an archive says about one entry, read before anything is extracted. [declaredBytes] is null when it does not say. */
data class ArchiveEntry(val index: Int, val name: String, val kind: EntryKind, val declaredBytes: Long?, val encrypted: Boolean)

data class UnpackLimits(
    val maxEntries: Int = 20_000,
    val maxVpkBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Everything decoded, VPK or not, declared or streamed. */
    val maxTotalBytes: Long = 16L * 1024 * 1024 * 1024,
    /** The notes kept for the install question, and how much of each. */
    val maxNotes: Int = 4,
    val maxNoteBytes: Int = 16 * 1024,
)

enum class RejectionReason {
    UnsupportedFormat,
    Encrypted,
    SplitVolume,
    Link,
    UnsupportedEntry,
    DuplicateName,
    TooManyEntries,
    TooLarge,
    SizeMismatch,
    UnsupportedMethod,
    DictionaryTooLarge,
    Corrupt,
    NoVpk,

    /** No VPK, but config files such as `gameinfo.gi`. */
    NoVpkConfig,

    /** No VPK, only cursor images. */
    NoVpkCursors,

    /** No VPK, but game files that are not packed into one. */
    NoVpkLooseFiles,

    /** No VPK, but another archive, to be unpacked first. */
    NestedArchive,
    TimedOut,
}

/** Why an archive is not installed, and the entry that decided it when one did. */
data class ArchiveRejection(val reason: RejectionReason, val entry: String?)

/** An entry to extract: its index in the archive and its path with `/` separators and no `.` segments. */
data class ArchiveFile(val index: Int, val path: String, val declaredBytes: Long?)

/** A readme or other text file an archive holds beside its VPKs, where authors write install steps; [text] is cut at [UnpackLimits.maxNoteBytes]. */
data class ArchiveNote(val path: String, val text: String)

sealed interface Screening {
    /** [vpks] and the first [notes] to extract; [declaredBytes] is everything the archive declares it decodes to, VPK or not. */
    data class Accepted(val vpks: List<ArchiveFile>, val notes: List<ArchiveFile>, val declaredBytes: Long) : Screening

    data class Rejected(val rejection: ArchiveRejection) : Screening
}

sealed interface EntryScreening {
    data class Accepted(val files: List<ArchiveFile>, val declaredBytes: Long) : EntryScreening

    data class Rejected(val rejection: ArchiveRejection) : EntryScreening
}

/**
 * Decides, before anything is written, whether an archive may be extracted and which entries to extract. Names are
 * only ever labels, never paths, so whatever they hold, `../x` or a drive letter, refuses nothing. What [screenEntries]
 * refuses is refused, as is a VPK over [limits]. The resource forks a Mac adds (`__MACOSX/`, `._name`) are passed over.
 */
fun screenArchive(entries: List<ArchiveEntry>, limits: UnpackLimits = UnpackLimits()): Screening {
    val accepted = when (val screening = screenEntries(entries, limits)) {
        is EntryScreening.Rejected -> return Screening.Rejected(screening.rejection)
        is EntryScreening.Accepted -> screening
    }
    val vpks = mutableListOf<ArchiveFile>()
    val notes = mutableListOf<ArchiveFile>()
    for (file in accepted.files) {
        val name = file.path.substringAfterLast('/').lowercase()
        if (name.startsWith("._") || file.path.split('/').any { it == "__MACOSX" }) continue
        if (name.endsWith(".vpk")) {
            if (file.declaredBytes != null && file.declaredBytes > limits.maxVpkBytes) return Screening.Rejected(ArchiveRejection(RejectionReason.TooLarge, file.path))
            vpks += file
        } else if (isNote(name) && notes.size < limits.maxNotes) {
            notes += file
        }
    }
    if (vpks.isEmpty()) {
        val names = accepted.files.filterNot { "__MACOSX" in it.path.split('/') }.map { it.path.substringAfterLast('/').lowercase() }.filterNot { it.startsWith("._") || it == ".ds_store" }
        return Screening.Rejected(ArchiveRejection(withoutVpks(names), null))
    }
    return Screening.Accepted(vpks, notes, accepted.declaredBytes)
}

/**
 * The files an archive would extract to, whatever they are. A link, an encrypted or unknown entry, two names that differ
 * only by case, a file that is also a folder, and declared sizes over [limits] are refused.
 */
fun screenEntries(entries: List<ArchiveEntry>, limits: UnpackLimits): EntryScreening {
    fun reject(reason: RejectionReason, entry: ArchiveEntry?) = EntryScreening.Rejected(ArchiveRejection(reason, entry?.name))
    if (entries.size > limits.maxEntries) return reject(RejectionReason.TooManyEntries, null)
    val seen = HashSet<String>()
    val folders = HashSet<String>()
    val files = mutableListOf<ArchiveFile>()
    var declared = 0L
    for (entry in entries) {
        val segments = entry.name.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) continue
        when (entry.kind) {
            EntryKind.Link -> return reject(RejectionReason.Link, entry)
            EntryKind.Other -> return reject(RejectionReason.UnsupportedEntry, entry)
            EntryKind.Directory, EntryKind.File -> Unit
        }
        if (entry.encrypted) return reject(RejectionReason.Encrypted, entry)
        val key = segments.joinToString("/").lowercase()
        val parents = segments.indices.drop(1).map { segments.subList(0, it).joinToString("/").lowercase() }
        if (entry.kind == EntryKind.Directory) {
            if (key in seen) return reject(RejectionReason.DuplicateName, entry)
            folders += parents + key
            continue
        }
        if (key in seen || key in folders || parents.any { it in seen }) return reject(RejectionReason.DuplicateName, entry)
        seen += key
        folders += parents
        val size = entry.declaredBytes
        if (size != null) {
            if (size < 0) return reject(RejectionReason.Corrupt, entry)
            if (size > limits.maxTotalBytes - declared) return reject(RejectionReason.TooLarge, entry)
            declared += size
        }
        files += ArchiveFile(entry.index, segments.joinToString("/"), size)
    }
    return EntryScreening.Accepted(files, declared)
}

private fun withoutVpks(names: List<String>): RejectionReason {
    val content = names.filterNot { isNote(it) || it.substringAfterLast('.') in Pictures }
    return when {
        content.isEmpty() -> RejectionReason.NoVpk
        content.any { it.substringAfterLast('.') in Archives || SplitPart.matches(it) } -> RejectionReason.NestedArchive
        content.any { it == "gameinfo.gi" || it.substringAfterLast('.') in ConfigExtensions } -> RejectionReason.NoVpkConfig
        content.all { it.substringAfterLast('.') in CursorExtensions } -> RejectionReason.NoVpkCursors
        else -> RejectionReason.NoVpkLooseFiles
    }
}

private fun isNote(name: String) = name.startsWith("readme") || name.endsWith(".txt") || name.endsWith(".md")

private val Pictures = setOf("png", "jpg", "jpeg", "gif", "webp")
private val ConfigExtensions = setOf("gi", "cfg", "vcfg")
private val Archives = setOf("zip", "7z", "rar")
private val SplitPart = Regex(".+\\.(7z|zip|rar)\\.\\d{3}")
private val CursorExtensions = setOf("cur", "ani", "bmp")

/**
 * The text of a note an archive holds, from its first [bytes], [whole] when that was all of it: UTF-8 or, with its
 * byte order mark, UTF-16, with line breaks kept and other control characters dropped; null when nothing is left.
 */
fun noteText(bytes: ByteArray, whole: Boolean): String? {
    val decoded = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> bytes.decodeToString().removePrefix("\uFEFF")
    }
    val text = decoded.replace("\r\n", "\n").replace('\r', '\n').filter { it == '\n' || it == '\t' || !it.isISOControl() }.trim()
    return text.takeIf { it.isNotEmpty() }?.let { if (whole) it else "$it…" }
}
