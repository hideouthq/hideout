package gg.hideouthq.core.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline

@JvmInline
value class BackupId(val value: String) {
    init {
        require(TokenPattern.matches(value)) { "a backup id must be safe in a file name" }
    }

    companion object {
        /** [value] as a backup id, or null when it is not one. */
        fun parse(value: String): BackupId? = if (TokenPattern.matches(value)) BackupId(value) else null
    }
}

@Serializable
enum class BackupReason {
    Manual,
    BeforeRestore,
    BeforeUpdates,

    @SerialName("BeforeAutoexec")
    BeforeGameSettings,
}

data class BackupInfo(val id: BackupId, val createdAt: Long, val reason: BackupReason, val mods: Int)

/**
 * What a backup of [root] holds besides copies of the game's settings files: that game's records, each with what the
 * user edited in its files kept as its own, so the backup holds the files as the game has them, the applied book and
 * saved choices as far as they order and turn on its mods, the retained originals its repaired sets are made from and
 * its repair choices. Downloads and receipts are not part of it, and nothing is pending.
 */
internal fun LibraryManifest.backupOf(root: GameRoot): LibraryManifest {
    val held = records.filter { it.gameRoot == root }.map { it.kept() }
    val ids = held.map { it.installId }.toSet()
    fun ProfileBook.only() = copy(profiles = profiles.map { it.copy(enabled = it.enabled intersect ids, orders = it.orders.filterKeys { game -> game == root }) })
    val used = held.flatMap { it.sets }.mapNotNull { it.derivation?.original }.toSet()
    return LibraryManifest(
        records = held,
        profiles = profiles.only(),
        savedChoices = savedChoices?.let { SavedChoices(it.target.only()) },
        originals = originals.filter { it.id in used },
        repairChoices = repairChoices.filterKeys { it in ids },
    ).settled()
}

/**
 * The SHA-256 of every file this Library's records and retained originals have, and of what the user edited in them:
 * the stored content a backup of it needs, and the twins that show the Library's own files unchanged.
 */
internal val LibraryManifest.contents: Set<String>
    get() = (records.flatMap { it.files }.map { it.sha256 } + records.flatMap { it.sets }.flatMap { it.edits.values }.map { it.sha256 } + originals.flatMap { it.files }.map { it.image.sha256 }).toSet()

/**
 * [this], a book of [root]'s mods as a backup holds it, with each profile also ordering and turning on the mods of every
 * other game as [current]'s profile of the same id does, or [current]'s active one when [current] has none of that id.
 * The active profile of an [applied] book takes [current]'s active one's, since the other games' files stay as they are.
 */
internal fun ProfileBook.withOtherGames(root: GameRoot, current: ProfileBook, applied: Boolean): ProfileBook = copy(profiles = profiles.map { profile ->
    val from = current.profiles.firstOrNull { it.id == profile.id }?.takeUnless { applied && profile.id == active } ?: current.activeProfile
    val orders = from.orders.filterKeys { it != root }
    profile.copy(enabled = profile.enabled + from.enabled.filter { id -> orders.values.any { id in it } }, orders = profile.orders + orders)
})

/**
 * `backup.json`, what one backup is, in a strict format of its own; its id is its folder's name. It carries
 * [ManifestSchemaVersion], so a change of the install list's format retires every backup made before it.
 */
object BackupCodec {
    fun encode(info: BackupInfo): String = Json.encodeToString(BackupDto.serializer(), with(info) { BackupDto(ManifestSchemaVersion, createdAt, reason, mods) })

    /** Backup [id] as [text] describes it, or null when it is not one this format reads. */
    fun decode(id: BackupId, text: String): BackupInfo? = try {
        Json.decodeFromString(BackupDto.serializer(), text).takeIf { it.schemaVersion == ManifestSchemaVersion }?.let { BackupInfo(id, it.createdAt, it.reason, it.mods) }
    } catch (e: IllegalArgumentException) {
        null
    }
}

@Serializable
private data class BackupDto(val schemaVersion: Int, val createdAt: Long, val reason: BackupReason, val mods: Int)
