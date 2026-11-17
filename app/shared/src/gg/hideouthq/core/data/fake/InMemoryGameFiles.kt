package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.BackupStore
import gg.hideouthq.core.data.CitadelFile
import gg.hideouthq.core.data.ConfigRead
import gg.hideouthq.core.data.ConfigRecovery
import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderEntry
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.data.GameConfig
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.GameFiles
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.HeldFile
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.ModInstaller
import gg.hideouthq.core.data.PlaceResult
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.ReplaceResult
import gg.hideouthq.core.data.StageResult
import gg.hideouthq.core.data.TwinLinks
import gg.hideouthq.core.data.UnpackResult
import gg.hideouthq.core.data.VpkLook
import gg.hideouthq.core.data.gamePath
import gg.hideouthq.core.library.ArchiveEntry
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ArchiveNote
import gg.hideouthq.core.library.BackupCodec
import gg.hideouthq.core.library.BackupId
import gg.hideouthq.core.library.BackupInfo
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Imprint
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ManifestCodec
import gg.hideouthq.core.library.ManifestDecode
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.Screening
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.StagedRef
import gg.hideouthq.core.library.StagedVpk
import gg.hideouthq.core.library.StagingId
import gg.hideouthq.core.library.TemporaryInfix
import gg.hideouthq.core.library.UnpackLimits
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.core.library.admits
import gg.hideouthq.core.library.admitsTemporary
import gg.hideouthq.core.library.noteText
import gg.hideouthq.core.library.path
import gg.hideouthq.core.library.screenArchive
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.zip.CRC32
import kotlin.time.Instant

/** A step [InMemoryGameFiles] can be told to fail at, or to stop the whole program at as if it crashed. */
sealed interface FaultPoint {
    data class Stage(val temporary: String) : FaultPoint
    data class Publish(val name: String) : FaultPoint

    /** After the final name is linked to its copy and before publishing reports it, as when the folder sync fails. */
    data class Linked(val name: String) : FaultPoint
    data class Inspect(val name: String) : FaultPoint
    data object ReplaceConfig : FaultPoint

    /** Before a second name is made at [path]: a game path such as `game/citadel/addons/pak01_dir.vpk`, or an archive's. */
    data class Link(val path: String) : FaultPoint

    /** After the second name at [path] exists and before the link is reported. */
    data class LinkMade(val path: String) : FaultPoint

    /** Before [path] is checked against its witness or identity and deleted. */
    data class Remove(val path: String) : FaultPoint

    /** After [path] is deleted and before the removal is reported. */
    data class RemoveDone(val path: String) : FaultPoint

    /** Looking at [path] fails. */
    data class Look(val path: String) : FaultPoint
}

/** Thrown at a crash point: an [Error], so nothing that handles exceptions can roll back as a real crash could not. */
class SimulatedCrash(point: FaultPoint) : Error("crashed at $point")

class InMemoryGameFiles : GameInventory, ModInstaller, GameConfig {
    private sealed interface Node {
        class File(val id: Int, val image: FileImage, val bytes: ByteArray? = null, val written: Long = ++writes) : Node {
            val modified: Instant get() = Instant.fromEpochMilliseconds(written)
        }
        data object Folder : Node
        data object Link : Node
    }

    private val nodes = LinkedHashMap<Pair<GameRoot, String>, Node>()
    private val unreadableFiles = mutableMapOf<Pair<GameRoot, String>, String>()
    private val archives = mutableMapOf<String, List<Pair<String, ByteArray>>>()
    private val staging = mutableMapOf<StagingId, Map<Int, ByteArray>>()
    private val configChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private var nextId = 1
    private var nextStaging = 1

    val failing: MutableSet<FaultPoint> = mutableSetOf()

    var crashAt: FaultPoint? = null

    /** Runs as a config file is being replaced, standing in for Steam writing `gameinfo.gi` at that moment, or for the file moved aside meanwhile. */
    var duringConfigReplace: (suspend (GameRoot) -> Unit)? = null

    /** Watches of config files fail as they start, as the desktop adapter's does on a folder not made yet. */
    var configWatchFails: Boolean = false

    /** Runs just before a final name is published, standing in for another program creating a file at that moment. */
    var beforePublish: ((GameRoot, String) -> Unit)? = null

    /** Runs just after a final name is published, standing in for another program changing it at that moment. */
    var afterPublish: ((GameRoot, String) -> Unit)? = null

    /** Runs at every [FaultPoint] before it fails or crashes, standing in for another program acting at that moment. */
    var around: ((FaultPoint) -> Unit)? = null

    var hardLinks: Boolean = true

    private fun secondName(file: Node.File): Node.File = if (hardLinks) file else Node.File(nextId++, file.image, file.bytes?.copyOf())

    private fun one(a: Node?, b: Node?): Boolean = a is Node.File && b is Node.File && (a.id == b.id || !hardLinks && a.image == b.image)

    val publishedNames: MutableList<String> = mutableListOf()

    val linkedPaths: MutableList<String> = mutableListOf()

    val removedPaths: MutableList<String> = mutableListOf()

    private val archiveFiles = LinkedHashMap<String, Node.File>()

    val stagingFolders: Set<StagingId> get() = staging.keys.toSet()

    fun addAddonsEntry(root: GameRoot, name: String) {
        nodes.getOrPut(root to "$Addons/$name") { Node.File(nextId++, Sha256.image(ByteArray(0))) }
    }

    fun addFolder(root: GameRoot, relativePath: String) {
        nodes[root to relativePath] = Node.Folder
    }

    fun addLink(root: GameRoot, relativePath: String) {
        nodes[root to relativePath] = Node.Link
    }

    fun putFile(root: GameRoot, relativePath: String, sizeBytes: Long, sha256: String) {
        nodes[root to relativePath] = Node.File(nextId++, FileImage(sizeBytes, sha256))
    }

    fun putBytes(root: GameRoot, relativePath: String, bytes: ByteArray) {
        nodes[root to relativePath] = Node.File(nextId++, Sha256.image(bytes), bytes)
    }

    fun rewrite(root: GameRoot, relativePath: String, bytes: ByteArray) {
        val file = nodes[root to relativePath] as Node.File
        val rewritten = Node.File(file.id, Sha256.image(bytes), bytes.copyOf())
        nodes.entries.filter { (it.value as? Node.File)?.id == file.id }.forEach { it.setValue(rewritten) }
    }

    fun bytes(root: GameRoot, relativePath: String): ByteArray? = (nodes[root to relativePath] as? Node.File)?.bytes?.copyOf()

    val resourceIndexes: ResourceIndexSource = object : ResourceIndexSource {
        override suspend fun read(root: GameRoot, relativePath: String, image: FileImage): ResourceIndex =
            bytes(root, relativePath)?.takeIf { Sha256.image(it) == image }?.let { VpkFormat.resources(it, it.size.toLong()) } ?: ResourceIndex.Unavailable

        override suspend fun readForeign(root: GameRoot, name: String): ResourceIndex =
            bytes(root, "${SetFolder.Addons.path}/$name")?.let { VpkFormat.resources(it, it.size.toLong()) } ?: ResourceIndex.Unavailable
    }

    fun failFile(root: GameRoot, relativePath: String, reason: String) {
        unreadableFiles[root to relativePath] = reason
    }

    fun readable(root: GameRoot, relativePath: String) {
        unreadableFiles.remove(root to relativePath)
    }

    fun remove(root: GameRoot, relativePath: String) {
        nodes.remove(root to relativePath)
    }

    fun putArchive(path: String, entries: List<Pair<String, ByteArray>>, image: FileImage = Sha256.image(entries.flatMap { it.second.asIterable() }.toByteArray())) {
        archives[path] = entries
        archiveFiles[path] = Node.File(nextId++, image)
    }

    fun archiveFile(path: String): FileImage? = archiveFiles[path]?.image

    fun archivePaths(): Set<String> = archiveFiles.keys.toSet()

    fun rewriteArchive(path: String, image: FileImage) {
        val file = archiveFiles.getValue(path)
        val rewritten = Node.File(file.id, image)
        archiveFiles.entries.filter { it.value.id == file.id }.forEach { it.setValue(rewritten) }
    }

    fun replaceArchive(path: String, image: FileImage) {
        archiveFiles[path] = Node.File(nextId++, image)
    }

    fun removeArchive(path: String) {
        archiveFiles.remove(path)
    }

    fun everyFile(root: GameRoot): Map<String, Int> =
        nodes.entries.filter { (key, node) -> key.first == root && node is Node.File }.associate { (key, node) -> key.second to (node as Node.File).id }

    fun identity(root: GameRoot, relativePath: String): Int? = (nodes[root to relativePath] as? Node.File)?.id

    val archiveLinks: TwinLinks<DownloadEntry> = ArchiveLinks()

    val gameFiles: GameFiles = CitadelLinks()

    val originals: MutableMap<String, List<ByteArray>> = mutableMapOf()

    val backups: BackupStore = Backups()

    fun gameInfo(root: GameRoot): ByteArray? = (nodes[root to GameInfo] as? Node.File)?.bytes

    fun writeGameInfo(root: GameRoot, bytes: ByteArray) = writeConfig(root, GameInfo, bytes)

    fun writeConfig(root: GameRoot, relativePath: String, bytes: ByteArray) {
        putBytes(root, relativePath, bytes)
        configChanges.tryEmit(relativePath)
    }

    fun deleteConfig(root: GameRoot, relativePath: String) {
        nodes.remove(root to relativePath)
        configChanges.tryEmit(relativePath)
    }

    fun addonsFiles(root: GameRoot): Map<String, FileImage> = filesIn(root, SetFolder.Addons)

    fun filesIn(root: GameRoot, folder: SetFolder): Map<String, FileImage> = nodes.entries
        .filter { (key, node) -> key.first == root && key.second.isDirectlyIn(folder.path) && node is Node.File }
        .associate { (key, node) -> key.second.substringAfterLast('/') to (node as Node.File).image }

    override suspend fun list(root: GameRoot, folder: String): FolderListing {
        val path = if (folder.isEmpty()) Citadel else "$Citadel/$folder"
        brokenFolder(root, path)?.let { return FolderListing.Unreadable("$it is a link or not a folder") }
        val entries = LinkedHashMap<String, EntryKind>()
        for ((key, node) in nodes) {
            if (key.first != root || !key.second.startsWith("$path/")) continue
            val rest = key.second.removePrefix("$path/")
            val name = rest.substringBefore('/')
            if ('/' in rest) {
                entries.putIfAbsent(name, EntryKind.Directory)
            } else {
                entries[name] = when (node) {
                    is Node.File -> EntryKind.File
                    Node.Folder -> EntryKind.Directory
                    Node.Link -> EntryKind.Link
                }
            }
        }
        return FolderListing.Listed(entries.map { (name, kind) -> FolderEntry(name, kind, (nodes[root to "$path/$name"] as? Node.File)?.let { FileStamp(it.image.sizeBytes, it.written) }) })
    }

    override suspend fun file(root: GameRoot, relativePath: String): FileObservation {
        unreadableFiles[root to relativePath]?.let { return FileObservation.Unreadable(it) }
        return when (val node = nodes[root to relativePath]) {
            is Node.File -> FileObservation.Present(node.image.sizeBytes, node.image.sha256)
            else -> FileObservation.Absent
        }
    }

    override suspend fun vpk(root: GameRoot, path: CitadelPath): VpkLook {
        val relativePath = "$Citadel/${path.value}"
        unreadableFiles[root to relativePath]?.let { return VpkLook.Unreadable(it) }
        if (brokenFolder(root, relativePath.substringBeforeLast('/')) != null) return VpkLook.Absent
        val node = nodes[root to relativePath] as? Node.File ?: return VpkLook.Absent
        val bytes = node.bytes ?: return VpkLook.Present(node.image, 0, VpkRead.Unreadable("$relativePath has no bytes in this fake"), null)
        val modinfo = VpkFormat.modinfo(bytes, bytes.size.toLong())
        val imprint = modinfo?.let { Imprint.read(it.inline + bytes.copyOfRange(it.at.toInt(), (it.at + it.length).toInt())) }
        return VpkLook.Present(node.image, CRC32().apply { update(bytes) }.value, VpkFormat.read(bytes, bytes.size.toLong()), imprint)
    }

    override suspend fun read(root: GameRoot, path: CitadelPath, maxBytes: Int): FileRead {
        val relativePath = "$Citadel/${path.value}"
        unreadableFiles[root to relativePath]?.let { return FileRead.Unreadable(it) }
        brokenFolder(root, relativePath.substringBeforeLast('/'))?.let { return if (nodes[root to it] == Node.Link) FileRead.Unreadable.link(it) else FileRead.Unreadable("$it is not a folder") }
        val node = when (val found = nodes[root to relativePath]) {
            is Node.File -> found
            Node.Link -> return FileRead.Unreadable.link(relativePath)
            Node.Folder -> return FileRead.Unreadable("$relativePath is not a plain file")
            null -> return FileRead.Absent
        }
        val bytes = node.bytes ?: return FileRead.Unreadable("$relativePath has no bytes in this fake")
        return if (bytes.size > maxBytes) FileRead.Unreadable("$relativePath is larger than $maxBytes bytes") else FileRead.Present(bytes.copyOf())
    }

    override suspend fun unpack(archive: ArchiveIdentity, limits: UnpackLimits, originalName: String): UnpackResult {
        val entries = archives[archive.path]?.takeIf { archive.path in archiveFiles } ?: return UnpackResult.Failed("no archive at ${archive.path}")
        val facts = entries.mapIndexed { index, (name, bytes) -> ArchiveEntry(index, name, EntryKind.File, bytes.size.toLong(), encrypted = false) }
        val screened = when (val screening = screenArchive(facts, limits)) {
            is Screening.Rejected -> return UnpackResult.Rejected(screening.rejection)
            is Screening.Accepted -> screening
        }
        val id = StagingId("staging-${nextStaging++}")
        staging[id] = screened.vpks.associate { it.index to entries[it.index].second }
        val notes = screened.notes.mapNotNull { note ->
            val bytes = entries[note.index].second
            noteText(bytes.copyOf(minOf(bytes.size, limits.maxNoteBytes)), whole = bytes.size <= limits.maxNoteBytes)?.let { ArchiveNote(note.path, it) }
        }
        return UnpackResult.Unpacked(id, screened.vpks.map { vpk ->
            val bytes = entries[vpk.index].second
            StagedVpk(StagedRef(id, vpk.index), vpk.path, bytes.size.toLong(), sha256Hex(bytes), VpkFormat.read(bytes, bytes.size.toLong()), VpkFormat.resources(bytes, bytes.size.toLong()))
        }, notes)
    }

    fun putStaging(files: List<ByteArray>): StagingId {
        val id = StagingId("staging-${nextStaging++}")
        staging[id] = files.withIndex().associate { it.index to it.value.copyOf() }
        return id
    }

    override suspend fun discard(staging: StagingId) {
        this.staging.remove(staging)
    }

    override suspend fun discardAllStaging() {
        staging.clear()
    }

    override suspend fun stage(root: GameRoot, folder: SetFolder, vpk: StagedVpk, temporary: String): StageResult {
        fault(FaultPoint.Stage(temporary))?.let { return StageResult.Failed(it) }
        if (!folder.admitsTemporary(temporary)) return StageResult.Failed("$temporary is not a temporary name Hideout makes in ${folder.path}")
        linkIn(root, folder)?.let { return StageResult.Failed("$it is a link") }
        val bytes = staging[vpk.ref.staging]?.get(vpk.ref.index) ?: return StageResult.Failed("the staged file is gone")
        if (nodes.containsKey(root to "${folder.path}/$temporary")) return StageResult.Failed("$temporary already exists")
        val image = Sha256.image(bytes)
        nodes[root to "${folder.path}/$temporary"] = Node.File(nextId++, image, bytes)
        return StageResult.Written(image)
    }

    override suspend fun publish(root: GameRoot, folder: SetFolder, temporary: String, name: String): PublishResult {
        fault(FaultPoint.Publish(name))?.let { return PublishResult.Failed(it) }
        beforePublish?.invoke(root, name)
        if (!folder.admits(name) || !folder.admitsTemporary(temporary)) return PublishResult.Failed("$name is not a name Hideout gives a file in ${folder.path}")
        linkIn(root, folder)?.let { return PublishResult.Failed("$it is a link") }
        val staged = nodes[root to "${folder.path}/$temporary"] as? Node.File ?: return PublishResult.Failed("$temporary is not there")
        if (nodes.containsKey(root to "${folder.path}/$name")) return PublishResult.Taken
        nodes[root to "${folder.path}/$name"] = secondName(staged)
        fault(FaultPoint.Linked(name))?.let { return PublishResult.Failed(it) }
        publishedNames += name
        afterPublish?.invoke(root, name)
        return PublishResult.Published
    }

    override suspend fun sameFile(root: GameRoot, folder: SetFolder, temporary: String, name: String): Boolean {
        fault(FaultPoint.Inspect(name))?.let { throw IllegalStateException(it) }
        val staged = nodes[root to "${folder.path}/$temporary"]
        val final = nodes[root to "${folder.path}/$name"]
        return one(staged, final)
    }

    private val setFiles = SetFiles()

    override suspend fun link(existing: GameEntry, created: GameEntry): LinkResult = setFiles.link(existing, created)
    override suspend fun inspect(entry: GameEntry, witness: GameEntry): EntryState = setFiles.inspect(entry, witness)
    override suspend fun removeTwin(entry: GameEntry, witness: GameEntry, expected: FileImage): Removal = setFiles.removeTwin(entry, witness, expected)
    override suspend fun removeExact(entry: GameEntry, expected: FileImage): Removal = setFiles.removeExact(entry, expected)

    private val kept = mutableMapOf<GameRoot, MutableMap<BackupId, Triple<String, String, Map<HeldFile, ByteArray>>>>()

    private inner class Backups : BackupStore {
        private fun stored(root: GameRoot, sha256: String) = root to "$Stored/$sha256"

        private val seen = mutableMapOf<Pair<GameRoot, String>, Instant>()

        override suspend fun list(root: GameRoot): List<BackupInfo> = kept[root].orEmpty().mapNotNull { (id, texts) -> BackupCodec.decode(id, texts.first) }

        override suspend fun read(root: GameRoot, id: BackupId): LibraryManifest = when (val decoded = ManifestCodec.decode(kept[root]?.get(id)?.second ?: error("there is no backup ${id.value}"))) {
            is ManifestDecode.Ok -> decoded.manifest
            is ManifestDecode.Unsupported -> error("its schema version ${decoded.schemaVersion} is not supported")
            is ManifestDecode.Malformed -> error(decoded.reason)
        }

        override suspend fun keep(entry: GameEntry, image: FileImage, modifiedAt: Instant?) {
            val path = entry.root to "${entry.folder.path}/${entry.name}"
            val file = nodes[path] as? Node.File ?: return
            val at = stored(entry.root, image.sha256)
            if (!one(nodes[at], file)) {
                fault(FaultPoint.Link(at.second))?.let { error(it) }
                if (path !in unreadableFiles && file.image == image) nodes[at] = secondName(file)
            }
            if (modifiedAt != null && one(nodes[at], file)) seen[at] = modifiedAt
        }

        override suspend fun modified(entry: GameEntry): Instant? = (nodes[entry.root to "${entry.folder.path}/${entry.name}"] as? Node.File)?.modified

        override suspend fun holds(entry: GameEntry, image: FileImage): Boolean {
            val path = entry.root to "${entry.folder.path}/${entry.name}"
            val file = nodes[path] as? Node.File ?: return false
            if (path in unreadableFiles) return false
            val at = stored(entry.root, image.sha256)
            return one(nodes[at], file) && file.image.sizeBytes == image.sizeBytes && seen[at] == file.modified
        }

        override suspend fun has(root: GameRoot, image: FileImage): Boolean = (nodes[stored(root, image.sha256)] as? Node.File)?.image?.sizeBytes == image.sizeBytes

        override suspend fun setAside(entry: GameEntry, folder: String) {
            val file = nodes[entry.root to "${entry.folder.path}/${entry.name}"] as? Node.File ?: error("${entry.name} is not there")
            val at = entry.root to "$Edits/$folder/${entry.name}"
            fault(FaultPoint.Link(at.second))?.let { error(it) }
            when {
                nodes[at] == null -> nodes[at] = secondName(file)
                one(nodes[at], file) -> Unit
                else -> error("${at.second} is another file")
            }
        }

        override suspend fun forget(entry: GameEntry, image: FileImage) {
            val file = nodes[entry.root to "${entry.folder.path}/${entry.name}"] as? Node.File ?: return
            val at = stored(entry.root, image.sha256)
            if (one(nodes[at], file)) nodes.remove(at)
        }

        override suspend fun keepOriginal(root: GameRoot, original: RetainedOriginal) {
            val copies = originals[original.id]?.takeIf { it.size == original.files.size } ?: return
            for ((file, bytes) in original.files.zip(copies)) {
                val at = stored(root, file.image.sha256)
                if (nodes[at] is Node.File) continue
                fault(FaultPoint.Link(at.second))?.let { error(it) }
                if (Sha256.image(bytes) == file.image) nodes[at] = Node.File(nextId++, file.image, bytes.copyOf())
            }
        }

        override suspend fun publish(root: GameRoot, info: BackupInfo, library: LibraryManifest, held: Map<HeldFile, ByteArray>) {
            fault(FaultPoint.Publish(info.id.value))?.let { error(it) }
            kept.getOrPut(root) { linkedMapOf() }[info.id] = Triple(BackupCodec.encode(info), ManifestCodec.encode(library), held.mapValues { it.value.copyOf() })
        }

        override suspend fun held(root: GameRoot, id: BackupId): Map<HeldFile, ByteArray> {
            fault(FaultPoint.Look("$Citadel/${CitadelPath.HideoutBackups.value}/${id.value}/autoexec.cfg"))?.let { error(it) }
            return (kept[root]?.get(id) ?: error("there is no backup ${id.value}")).third.mapValues { it.value.copyOf() }
        }

        override suspend fun delete(root: GameRoot, id: BackupId) {
            kept[root]?.remove(id)
        }

        override suspend fun prune(root: GameRoot, named: Set<String>) {
            nodes.keys.removeAll { (game, path) -> game == root && path.startsWith("$Stored/") && path.removePrefix("$Stored/") !in named }
        }

        override suspend fun place(image: FileImage, entry: GameEntry): PlaceResult {
            fault(FaultPoint.Stage(entry.name))?.let { return PlaceResult.Failed(it) }
            linkIn(entry.root, entry.folder)?.let { return PlaceResult.Failed("$it is a link") }
            val file = nodes[stored(entry.root, image.sha256)] as? Node.File ?: return PlaceResult.Unavailable
            val path = entry.root to "${entry.folder.path}/${entry.name}"
            if (path in nodes) return PlaceResult.Failed("${entry.name} already exists")
            if (file.image != image) return PlaceResult.Unavailable
            nodes[path] = secondName(file)
            return PlaceResult.Placed
        }

        override suspend fun placeOriginal(root: GameRoot, original: RetainedOriginal): PlaceResult {
            val copies = original.files.map { file -> (nodes[stored(root, file.image.sha256)] as? Node.File)?.takeIf { it.image == file.image }?.bytes ?: return PlaceResult.Unavailable }
            originals[original.id] = copies.map { it.copyOf() }
            return PlaceResult.Placed
        }
    }

    private abstract inner class Links<E> : TwinLinks<E> {
        abstract fun path(entry: E): String
        abstract fun node(entry: E): Node?
        abstract fun put(entry: E, file: Node.File)
        abstract fun delete(entry: E)

        open fun blocked(entry: E): String? = null
        open fun unreadable(entry: E): String? = null
        open fun isTwin(witness: E, file: Node.File): Boolean = one(node(witness), file)

        override suspend fun link(existing: E, created: E): LinkResult {
            fault(FaultPoint.Link(path(created)))?.let { return LinkResult.Failed(it) }
            (blocked(existing) ?: blocked(created))?.let { return LinkResult.Failed(it) }
            val file = node(existing) as? Node.File ?: return LinkResult.NotThere
            if (node(created) != null) return LinkResult.Taken
            put(created, secondName(file))
            linkedPaths += path(created)
            fault(FaultPoint.LinkMade(path(created)))?.let { return LinkResult.Failed(it) }
            return LinkResult.Linked
        }

        override suspend fun inspect(entry: E, witness: E): EntryState {
            fault(FaultPoint.Look(path(entry)))?.let { return EntryState.Unreadable(it) }
            (blocked(entry) ?: unreadable(entry))?.let { return EntryState.Unreadable(it) }
            return when (val node = node(entry)) {
                null -> EntryState.Absent
                !is Node.File -> EntryState.NotPlain
                else -> EntryState.Present(node.image, twin = isTwin(witness, node))
            }
        }

        override suspend fun removeTwin(entry: E, witness: E, expected: FileImage): Removal {
            blocked(entry)?.let { return Removal.Failed(it) }
            return remove(entry, expected) { if (isTwin(witness, it)) null else "${name(entry)} is not one file with ${name(witness)}" }
        }

        override suspend fun removeExact(entry: E, expected: FileImage): Removal {
            blocked(entry)?.let { return Removal.Failed(it) }
            return remove(entry, expected) { null }
        }

        fun remove(entry: E, expected: FileImage, notOurs: (Node.File) -> String?): Removal {
            val path = path(entry)
            fault(FaultPoint.Remove(path))?.let { return Removal.Failed(it) }
            val node = node(entry) ?: return Removal.Absent
            if (node !is Node.File) return Removal.Kept("$path is not a plain file")
            notOurs(node)?.let { return Removal.Kept(it) }
            if (node.image != expected) return Removal.Kept("$path changed")
            delete(entry)
            removedPaths += path
            fault(FaultPoint.RemoveDone(path))?.let { return Removal.Failed(it) }
            return Removal.Removed
        }

        private fun name(entry: E) = path(entry).substringAfterLast('/')
    }

    private inner class SetFiles : Links<GameEntry>() {
        override fun path(entry: GameEntry) = "${entry.folder.path}/${entry.name}"
        override fun node(entry: GameEntry) = nodes[entry.root to path(entry)]
        override fun put(entry: GameEntry, file: Node.File) { nodes[entry.root to path(entry)] = file }
        override fun delete(entry: GameEntry) { nodes.remove(entry.root to path(entry)) }
        override fun blocked(entry: GameEntry) = linkIn(entry.root, entry.folder)?.let { "$it is a link" }
        override fun unreadable(entry: GameEntry) = unreadableFiles[entry.root to path(entry)]

        override suspend fun link(existing: GameEntry, created: GameEntry): LinkResult =
            if (created.folder.admits(created.name) || created.folder.admitsTemporary(created.name)) super.link(existing, created)
            else LinkResult.Failed("${created.name} is not a name Hideout gives a file in ${created.folder.path}")
    }

    private inner class ArchiveLinks : Links<DownloadEntry>() {
        override fun path(entry: DownloadEntry) = entry.anchor?.let { "${entry.archive.path}$TemporaryInfix${it.value}" } ?: entry.archive.path
        override fun node(entry: DownloadEntry) = archiveFiles[path(entry)]
        override fun put(entry: DownloadEntry, file: Node.File) { archiveFiles[path(entry)] = file }
        override fun delete(entry: DownloadEntry) { archiveFiles.remove(path(entry)) }
    }

    private inner class CitadelLinks : Links<CitadelFile>(), GameFiles {
        override fun path(entry: CitadelFile) = entry.gamePath
        override fun node(entry: CitadelFile) = nodes[entry.root to entry.gamePath]
        override fun put(entry: CitadelFile, file: Node.File) { nodes[entry.root to entry.gamePath] = file }
        override fun delete(entry: CitadelFile) { nodes.remove(entry.root to entry.gamePath) }
        override fun unreadable(entry: CitadelFile) = unreadableFiles[entry.root to entry.gamePath]
        override fun isTwin(witness: CitadelFile, file: Node.File) = blocked(witness) == null && super.isTwin(witness, file)

        override fun blocked(entry: CitadelFile): String? =
            if (entry.path.offLimits) "${entry.path.value} is in a folder Hideout leaves alone" else brokenFolder(entry.root, entry.gamePath.substringBeforeLast('/'))?.let { "$it is a link or not a folder" }

        override suspend fun link(existing: CitadelFile, created: CitadelFile): LinkResult =
            if (created.path.hideoutFolder == null) LinkResult.Failed("${created.path.value} is not a name Hideout gives a file") else super.link(existing, created)
    }

    private fun linkIn(root: GameRoot, folder: SetFolder): String? =
        folder.segments.indices.map { folder.segments.subList(0, it + 1).joinToString("/") }.firstOrNull { nodes[root to it] == Node.Link }

    override suspend fun read(root: GameRoot): ConfigRead {
        unreadableFiles[root to GameInfo]?.let { return ConfigRead.Unreadable(it) }
        val node = nodes[root to GameInfo] as? Node.File ?: return ConfigRead.Missing
        val bytes = node.bytes ?: return ConfigRead.Unreadable("gameinfo.gi has no bytes in this fake")
        return ConfigRead.Present(bytes.copyOf(), node.image)
    }

    override suspend fun prepare(root: GameRoot, target: ConfigTarget): String? =
        brokenFolder(root, target.segments.dropLast(1).joinToString("/"))?.let { "$it is a link or not a folder" }

    override suspend fun replace(edit: PendingChange.ConfigEdit, next: ByteArray): ReplaceResult {
        fault(FaultPoint.ReplaceConfig)?.let { return ReplaceResult.Failed(it) }
        val root = edit.gameRoot
        val path = edit.target.segments.joinToString("/")
        val current = nodes[root to path]
        if (if (edit.from == null) current != null else (current as? Node.File)?.image != edit.from) return ReplaceResult.Changed
        duringConfigReplace?.let { writer ->
            writer(root)
            if ((nodes[root to path] as? Node.File)?.image != edit.from) return ReplaceResult.Conflict("${edit.target.segments.last()} was written while it was being replaced")
        }
        writeConfig(root, path, next.copyOf())
        return ReplaceResult.Replaced
    }

    override suspend fun recover(edit: PendingChange.ConfigEdit): ConfigRecovery =
        when ((nodes[edit.gameRoot to edit.target.segments.joinToString("/")] as? Node.File)?.image) {
            edit.to -> ConfigRecovery.Applied()
            edit.from -> ConfigRecovery.Undone()
            else -> ConfigRecovery.Conflict("${edit.target.segments.last()} is neither the file before nor the file after")
        }

    override fun changes(root: GameRoot, path: CitadelPath): Flow<Unit> = flow {
        if (configWatchFails) throw IOException("the folder of ${path.value} is not there")
        emitAll(configChanges.filter { it == "$Citadel/${path.value}" }.map {})
    }

    private fun fault(point: FaultPoint): String? {
        around?.invoke(point)
        if (crashAt == point) throw SimulatedCrash(point)
        return if (point in failing) "failed at $point" else null
    }

    private fun String.isDirectlyIn(folder: String) = startsWith("$folder/") && '/' !in removePrefix("$folder/")

    private fun brokenFolder(root: GameRoot, folder: String): String? {
        val segments = folder.split('/')
        return (1..segments.size).map { segments.take(it).joinToString("/") }.firstOrNull { nodes[root to it].let { node -> node != null && node != Node.Folder } }
    }

    companion object {
        private var writes = 0L
        private const val Citadel = "game/citadel"
        const val Addons = "$Citadel/addons"
        const val GameInfo = "$Citadel/gameinfo.gi"
        const val Autoexec = "$Citadel/cfg/autoexec.cfg"

        val Stored = "$Citadel/${CitadelPath.HideoutBackups.value}/files"

        val Edits = "$Citadel/${CitadelPath.HideoutEdits.value}"
    }
}
