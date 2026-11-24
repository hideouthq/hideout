package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.autoexec.SectionMarkers
import gg.hideouthq.core.crosshair.community.dmmConfigLines
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import kotlinx.serialization.json.JsonObject

object DmmReader {
    private const val CurrentVersion = 42
    private const val ManifestVersion = 3L
    private val ModId = Regex("[1-9][0-9]{0,17}")
    private val SoundId = Regex("snd-([1-9][0-9]{0,17})")
    private val FileUrl = Regex("gamebanana-file://[^/]+/([0-9]+)")
    private val DownloadUrl = Regex("gamebanana\\.com/dl/([0-9]+)", RegexOption.IGNORE_CASE)
    private val Md5 = Regex("[0-9a-f]{32}")
    private val Sections = listOf(SectionMarkers.DmmCrosshair, SectionMarkers.DmmMap)

    fun read(state: ByteArray?, manifests: Map<CitadelPath, ByteArray>, autoexec: ByteArray?): ForeignLibrary {
        val persisted = state?.let(::persisted)
        val version = persisted?.version ?: CurrentVersion
        val profiles = persisted?.state?.get("profiles").obj.orEmpty().mapNotNull { (id, profile) -> profile.obj?.let { profile(id, it) } }
        val active = profiles.firstOrNull { it.id == persisted?.state?.get("activeProfileId").text }
        val localMods = persisted?.state?.get("localMods").items.mapNotNull { it.obj }
        val records = (listOfNotNull(active?.mods) + listOf(localMods) + profiles.filter { it !== active }.map { it.mods }).flatten()
            .mapNotNull { mod -> mod["remoteId"].words?.let { it to mod } }.groupBy({ it.first }, { it.second })

        fun sizes(key: String, original: String): Set<Long> = records[key].orEmpty()
            .firstNotNullOfOrNull { record -> record["installedFileTree"].obj?.get("files").items.takeIf { it.isNotEmpty() } }.orEmpty()
            .mapNotNull { file -> file.obj?.takeIf { it["name"].text == original }?.get("size").whole?.takeIf { it > 0 } }.toSet()
        val readable = manifests.mapNotNull { (path, bytes) -> manifest(path, bytes) }
        val listings = readable.flatMap { manifest ->
            manifest.entries.map { entry -> Listing(manifest, entry, listed(manifest.folder, entry) { sizes(entry.key, it) }) }
        }
        val parked = (
            listings.map { it.manifest.folder to it.entry.key } +
                profiles.flatMap { profile -> (profile.mods + if (profile === active) localMods else emptyList()).mapNotNull { mod -> mod["remoteId"].words?.let { profile.folder to it } } }
            ).distinct().map { (folder, key) -> key to SetMatcher.Prefixed(folder, "${key}_") }
        val keys = (records.keys + listings.map { it.entry.key }).distinct()
        return ForeignLibrary(
            mods = keys.map { key ->
                val said = records[key].orEmpty()
                ForeignMod(
                    key = ModKey(key),
                    name = said.firstNotNullOfOrNull { it["name"].words },
                    identity = identity(key, said, version),
                    matchers = listings.filter { it.entry.key == key }.flatMap { it.files } + parked.filter { it.first == key }.map { it.second },
                    position = said.firstNotNullOfOrNull { it["installOrder"].number?.toInt() }
                        ?: listings.filter { it.entry.key == key }.mapNotNull { it.entry.order }.minOrNull(),
                )
            },
            profiles = profiles.filter { profile -> profile === active || profile.mods.isEmpty() || readable.any { it.folder == profile.folder } }.map { profile ->
                fun rank(listing: Listing) = profile.mods.firstOrNull { it["remoteId"].words == listing.entry.key }?.get("installOrder").number ?: listing.entry.order?.toDouble()
                val members = listings.filter { it.manifest.folder == profile.folder && it.entry.enabled }.sortedWith(compareBy(nullsLast(), ::rank)).flatMap { it.files }
                ForeignProfile(ProfileKey(profile.id), profile.name, profile.folder, members.map(ProfileMember::Files))
            },
            activeProfile = active?.let { ProfileKey(it.id) },
            autoexec = AutoexecDocument.parse(autoexec).foreignLines(Sections).let { lines ->
                if (lines.crosshair.isNotEmpty()) lines else lines.copy(crosshair = appliedCrosshair(persisted?.state))
            },
            footprint = Footprint(
                roots = ForeignRoot.keptBy(Manager.DeadlockModManager),
                sections = Sections,
                manifests = readable.map { it.path },
            ),
        )
    }

    private fun appliedCrosshair(state: JsonObject?): List<String> {
        if (state?.get("crosshairsEnabled").isFalse) return emptyList()
        return state?.get("activeCrosshair").obj?.let(::dmmConfigLines).orEmpty()
    }

    private class Persisted(val version: Int, val state: JsonObject)

    private class Profile(val id: String, val name: String, val folder: CitadelPath, val mods: List<JsonObject>)

    private class Manifest(val path: CitadelPath, val folder: CitadelPath, val entries: List<Entry>)

    private class Entry(val key: String, val enabled: Boolean, val order: Int?, val shard: Long, val current: List<String>, val disabled: List<String>, val originals: List<String>)

    private class Listing(val manifest: Manifest, val entry: Entry, val files: List<SetMatcher.At>)

    private fun persisted(bytes: ByteArray): Persisted? {
        val config = parseJson(bytes).obj?.get("local-config") ?: return null
        val inner = (config.text?.let(::parseJson) ?: config).obj ?: return null
        return Persisted(inner["version"].whole?.toInt() ?: CurrentVersion, inner["state"].obj ?: return null)
    }

    private fun profile(id: String, profile: JsonObject): Profile? {
        val folder = when (val name = profile["folderName"].words) {
            null -> CitadelPath.Addons
            else -> CitadelPath.parse(name)?.takeIf { it.parent == null }?.let { CitadelPath.Addons.resolve(it.value) } ?: return null
        }
        return Profile(id, profile["name"].words ?: id, folder, profile["mods"].items.mapNotNull { it.obj })
    }

    private fun manifest(path: CitadelPath, bytes: ByteArray): Manifest? {
        val folder = path.parent ?: return null
        val manifest = parseJson(bytes).obj ?: return null
        if ((manifest["version"].whole ?: ManifestVersion) > ManifestVersion) return null
        val entries = manifest["mods"].obj.orEmpty().mapNotNull { (key, value) ->
            val entry = value.obj?.takeIf { key.isNotEmpty() } ?: return@mapNotNull null
            Entry(
                key = key,
                enabled = entry["enabled"].isTrue,
                order = entry["order"].whole?.toInt(),
                shard = entry["shard"].whole ?: 1,
                current = entry["currentVpks"].items.map { it.text.orEmpty() },
                disabled = entry["disabledVpks"].items.map { it.text.orEmpty() },
                originals = entry["originalVpkNames"].items.map { it.text.orEmpty() },
            )
        }
        return Manifest(path, folder, entries)
    }

    private fun listed(folder: CitadelPath, entry: Entry, sizes: (String) -> Set<Long>): List<SetMatcher.At> {
        val (where, names) = if (entry.enabled) shardFolder(folder, entry.shard) to entry.current else folder to entry.disabled
        val originals = entry.originals.takeIf { it.size == names.size }
        return names.mapIndexedNotNull { index, name ->
            val path = where?.takeIf { '/' !in name }?.let { CitadelPath.parse("${it.value}/$name") } ?: return@mapIndexedNotNull null
            val original = originals?.get(index)?.takeIf { it.isNotBlank() && it.none { c -> c == '/' || c == '\\' || c.isISOControl() } }
            SetMatcher.At(path, original, original?.let(sizes).orEmpty())
        }
    }

    private fun shardFolder(folder: CitadelPath, shard: Long): CitadelPath? = when {
        shard == 1L -> folder
        shard !in 2L..10L -> null
        folder == CitadelPath.Addons -> CitadelPath("addons$shard")
        folder.parent == CitadelPath.Addons -> CitadelPath("addons$shard/${folder.name}")
        else -> null
    }

    private fun identity(remoteId: String, records: List<JsonObject>, version: Int): ModIdentity {
        val ref = SoundId.matchEntire(remoteId)?.let { SubmissionRef(SubmissionKind.Sound, it.groupValues[1].toLong()) }
            ?: remoteId.takeIf(ModId::matches)?.let { id ->
                val legacySound = version < 26 && records.any { it["isAudio"].isTrue }
                SubmissionRef(if (legacySound) SubmissionKind.Sound else SubmissionKind.Mod, id.toLong())
            }
            ?: return ModIdentity.None
        return records.firstNotNullOfOrNull { record -> installedDownload(record)?.let { file(ref, it) } } ?: ModIdentity.Submission(ref)
    }

    private fun file(ref: SubmissionRef, download: JsonObject): ModIdentity.File? {
        val url = download["url"].text ?: return null
        val fileId = (FileUrl.matchEntire(url) ?: DownloadUrl.find(url))?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return ModIdentity.File(SubmissionIdentity(ref.kind, ref.id, fileId, download["md5Checksum"].text?.lowercase()?.takeIf(Md5::matches)))
    }

    private fun installedDownload(record: JsonObject): JsonObject? {
        val selected = record["selectedDownloads"].items.mapNotNull { it.obj }.ifEmpty { listOfNotNull(record["selectedDownload"].obj) }
        selected.singleOrNull()?.let { return it }
        val archives = (record["activeVariantArchive"].text.orEmpty().split(',') + record["installedFileTree"].obj?.get("files").items.mapNotNull { it.obj?.get("archive_name").text })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val archive = archives.singleOrNull() ?: return null
        return (selected + record["downloads"].items.mapNotNull { it.obj }).firstOrNull { it["name"].text == archive }
    }
}
