package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.autoexec.SectionMarkers
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import kotlinx.serialization.json.JsonObject

object GrimoireReader {
    private val Sections = listOf(SectionMarkers.GrimoireCommands, SectionMarkers.GrimoireCrosshair)
    private val Locker = listOf("cards" to "hero cards", "sounds" to "hero sounds", "colors" to "hero colors", "trippyskins" to "trippy skins")

    fun read(metadata: ByteArray?, profiles: ByteArray?, autoexec: ByteArray?): ForeignLibrary {
        val locker = Locker.mapIndexed { index, (key, what) ->
            ForeignMod(ModKey("locker:$key"), "Grimoire Locker · $what", ModIdentity.None, listOf(SetMatcher.At(CitadelPath("grimoire/pak0${index + 1}_dir.vpk"))), position = null, listed = false)
        }
        val rows = metadata?.let(::parseJson).obj.orEmpty().filterKeys { !it.startsWith("locker:") }.values.mapNotNull { it.obj?.let(::row) }
        return ForeignLibrary(
            mods = locker + rows.groupBy { it.group }.map { (group, rows) -> mod(group, rows) },
            profiles = profiles?.let(::parseJson).items.mapNotNull { it.obj?.let(::profile) }.distinctBy { it.key },
            activeProfile = null,
            autoexec = AutoexecDocument.parse(autoexec).foreignLines(Sections),
            footprint = Footprint(ForeignRoot.keptBy(Manager.Grimoire), Sections, manifests = emptyList()),
        )
    }

    private class Row(val sha256: String, val group: String, val ref: SubmissionRef?, val fileId: Long?, val name: String?, val lastPriority: Int?)

    private fun row(row: JsonObject): Row? {
        val sha256 = row["sha256"].sha256 ?: return null
        val name = row["modName"].words
        val id = row["gameBananaId"].whole?.takeIf { it > 0 }
        val section = row["sourceSection"].text
        if (id == null && name == null) return null
        val ref = id?.takeIf { section != "Wip" }?.let { SubmissionRef(if (section == "Sound") SubmissionKind.Sound else SubmissionKind.Mod, it) }
        val group = when {
            ref != null -> "gamebanana:${ref.kind.idPrefix}:${ref.id}"
            id != null -> "wip:$id"
            else -> row["localGroupId"].words?.let { "local:$it" } ?: "sha256:$sha256"
        }
        return Row(sha256, group, ref, row["gameBananaFileId"].whole?.takeIf { it > 0 }, name, row["lastPriority"].whole?.toInt())
    }

    private fun mod(group: String, rows: List<Row>): ForeignMod {
        val ref = rows.first().ref
        val fileId = rows.mapNotNull { it.fileId }.distinct().singleOrNull()
        return ForeignMod(
            key = ModKey(group),
            name = rows.firstNotNullOfOrNull { it.name },
            identity = ModIdentity.of(ref, fileId),
            matchers = rows.map { it.sha256 }.distinct().map(SetMatcher::Hash),
            position = rows.mapNotNull { it.lastPriority }.minOrNull(),
        )
    }

    private fun profile(profile: JsonObject): ForeignProfile? {
        val id = profile["id"].words ?: return null
        val entries = profile["mods"].items.mapNotNull { it.obj }
        val legacy = entries.map { it["priority"].number }.distinct().size < entries.size
        val ordered = if (legacy) entries else entries.sortedWith(compareBy(nullsLast()) { it["priority"].number })
        val members = ordered.filterNot { it["enabled"].isFalse }.mapNotNull { entry ->
            entry["sha256"].sha256?.let { ProfileMember.Files(SetMatcher.Hash(it)) }
                ?: entry["gameBananaId"].whole?.takeIf { it > 0 }?.let(ProfileMember::Submission)
        }
        return ForeignProfile(ProfileKey(id), profile["name"].words ?: id, folder = null, members = members.distinct())
    }
}
