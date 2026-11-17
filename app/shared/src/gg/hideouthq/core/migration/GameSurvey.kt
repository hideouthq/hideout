package gg.hideouthq.core.migration

import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderEntry
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.data.VpkLook
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Imprint
import gg.hideouthq.core.library.IncomingFile
import gg.hideouthq.core.library.LoadPosition
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.MountsRead
import gg.hideouthq.core.library.PakNumbers
import gg.hideouthq.core.library.RootRole
import gg.hideouthq.core.library.VpkFile
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.core.library.citadelPath
import gg.hideouthq.core.library.groupVpkSets

/**
 * What the game folder holds outside Hideout's records, read once per look: the truth every import plans against. It
 * looks in every folder [ForeignRoot] adopts from, whichever manager is imported, so it never branches on one.
 */
data class GameSurvey(
    val root: GameRoot,
    /** The folders `gameinfo.gi` mounts ahead of the base game, earliest first; empty when there is no `gameinfo.gi`. */
    val mounts: List<CitadelPath>,
    /** Every complete set found, each in its own folder. */
    val sets: List<FoundSet>,
    val manifests: Map<CitadelPath, ByteArray>,
    /** The names directly in `addons`, which no number an import gives may share. */
    val addons: List<String>,
    val gameInfo: ByteArray?,
    val autoexec: ByteArray?,
    /** Every VPK read, by path, with the stamp its folder listed it with: what surveying again need not read again. */
    val read: Map<CitadelPath, SurveyedVpk> = emptyMap(),
)

/**
 * One complete set as found, directory file first, with its directory file's [imprint]. [position] is where the engine
 * loads it today, or null when it does not.
 */
data class FoundSet(val folder: CitadelPath, val root: ForeignRoot, val files: List<IncomingFile>, val position: LoadPosition?, val imprint: Imprint?) {
    val directory: IncomingFile get() = files.first()

    /** Every file's bytes, directory first: two sets with equal content are copies of one another. */
    val content: List<FileImage> get() = files.map { it.image }
}

/** A VPK as the survey read it, and the stamp its folder had for it then. */
data class SurveyedVpk(val stamp: FileStamp?, val look: VpkLook.Present)

sealed interface SurveyRead {
    data class Read(val survey: GameSurvey) : SurveyRead

    /** A folder or `gameinfo.gi` could not be read or understood, so what the game holds or loads is not known. */
    data class Unreadable(val reason: String) : SurveyRead
}

/**
 * Reads [root]: `gameinfo.gi`'s mounts, `cfg/autoexec.cfg`, and every folder another manager adopts from, through
 * plain folders only, so `replays` and the like are never followed and `addons-backups` and staging are never
 * entered. Each `.vpk` there is hashed in one read that also reads its tree, unless it is one of
 * [held]'s files, which are Hideout's by the manifest, or [previous] read it under the same stamp. Sets are grouped as
 * installs group them. A set loads when its directory is a bare `pakNN_dir.vpk` in a mounted folder. Read-only.
 */
suspend fun surveyGame(inventory: GameInventory, root: GameRoot, held: List<ManifestRecord>, previous: GameSurvey? = null): SurveyRead {
    val owned = held.filter { it.gameRoot == root }.flatMap { record -> record.files.map { it.citadelPath(record) } }.toSet()
    val gameInfo = when (val read = inventory.read(root, ConfigTarget.GameInfo.Path, MaxConfigBytes)) {
        is FileRead.Present -> read.bytes
        FileRead.Absent -> null
        is FileRead.Unreadable -> return SurveyRead.Unreadable(read.reason)
    }
    val autoexec = (inventory.read(root, ConfigTarget.Autoexec.path, MaxConfigBytes) as? FileRead.Present)?.bytes
    val mounts = when (val read = gameInfo?.let(GameInfoPatch::mounts)) {
        null -> emptyList()
        is MountsRead.Read -> read.mounts
        is MountsRead.Problem -> return SurveyRead.Unreadable("gameinfo.gi could not be used: ${read.problem.name}")
    }

    val top = when (val listed = inventory.list(root, "")) {
        is FolderListing.Unreadable -> return SurveyRead.Unreadable(listed.reason)
        is FolderListing.Listed -> listed.entries
    }
    val folders = mutableListOf<Pair<CitadelPath, List<FolderEntry>>>()
    for (entry in top.filter { it.kind == EntryKind.Directory }) {
        val folder = CitadelPath.parse(entry.name)?.takeIf { it.adopted() } ?: continue
        val entries = when (val listed = inventory.list(root, folder.value)) {
            is FolderListing.Unreadable -> return SurveyRead.Unreadable(listed.reason)
            is FolderListing.Listed -> listed.entries
        }
        folders += folder to entries
        for (inner in entries.filter { it.kind == EntryKind.Directory }) {
            val sub = CitadelPath.parse("${folder.value}/${inner.name}")?.takeIf { it.adopted() } ?: continue
            when (val listed = inventory.list(root, sub.value)) {
                is FolderListing.Unreadable -> return SurveyRead.Unreadable(listed.reason)
                is FolderListing.Listed -> folders += sub to listed.entries
            }
        }
    }

    val read = mutableMapOf<CitadelPath, SurveyedVpk>()
    val vpks = mutableListOf<FoundVpk>()
    val manifests = mutableMapOf<CitadelPath, ByteArray>()
    for ((folder, entries) in folders) {
        for (entry in entries.filter { it.kind == EntryKind.File }) {
            val path = CitadelPath.parse("${folder.value}/${entry.name}") ?: continue
            if (entry.name == DmmManifestName) {
                (inventory.read(root, path, MaxManifestBytes) as? FileRead.Present)?.let { manifests[path] = it.bytes }
                continue
            }
            if (!entry.name.endsWith(".vpk", ignoreCase = true) || path in owned) continue
            val known = previous?.read?.get(path)?.takeIf { entry.stamp != null && it.stamp == entry.stamp }
            val vpk = known ?: when (val look = inventory.vpk(root, path)) {
                is VpkLook.Present -> SurveyedVpk(entry.stamp, look)
                VpkLook.Absent, is VpkLook.Unreadable -> continue
            }
            read[path] = vpk
            vpks += FoundVpk(path.value, vpk.look.image.sizeBytes, vpk.look.image.sha256, vpk.look.directory, vpk.look.imprint)
        }
    }
    val grouping = groupVpkSets(vpks)
    val sets = grouping.sets.map { set ->
        val files = set.members.map { IncomingFile(CitadelPath(it.vpk.path), it.part, it.vpk.image) }
        val folder = checkNotNull(files.first().path.parent)
        FoundSet(folder, checkNotNull(ForeignRoot.of(folder)), files, position(files.first().path, mounts), set.members.first().vpk.imprint)
    }
    val addons = folders.firstOrNull { it.first == CitadelPath.Addons }?.second?.map { it.name }.orEmpty()
    return SurveyRead.Read(GameSurvey(root, mounts, sets, manifests, addons, gameInfo, autoexec, read))
}

private fun CitadelPath.adopted(): Boolean = ForeignRoot.of(this)?.role.let { it == RootRole.AdoptOn || it == RootRole.AdoptOff }

private fun position(directory: CitadelPath, mounts: List<CitadelPath>): LoadPosition? {
    val number = PakNumbers.mounted(directory.name) ?: return null
    val mount = mounts.indexOfFirst { it.value.equals(directory.parent?.value, ignoreCase = true) }.takeIf { it >= 0 } ?: return null
    return LoadPosition(mount, number.value)
}

private class FoundVpk(override val path: String, override val sizeBytes: Long, override val sha256: String, override val directory: VpkRead, val imprint: Imprint?) : VpkFile

internal const val DmmManifestName = ".dmm.json"
private const val MaxManifestBytes = 8 * 1024 * 1024
