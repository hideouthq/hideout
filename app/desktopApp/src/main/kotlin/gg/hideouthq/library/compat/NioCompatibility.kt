package gg.hideouthq.library.compat

import gg.hideouthq.core.library.CheckBasis
import gg.hideouthq.core.library.CheckResult
import gg.hideouthq.core.library.CompatFinding
import gg.hideouthq.core.library.CompatibilityPort
import gg.hideouthq.core.library.DeriveResult
import gg.hideouthq.core.library.FileEvidence
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FindingKind
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallCheck
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.Provider
import gg.hideouthq.core.library.RetainResult
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.RuleIdentity
import gg.hideouthq.core.library.SetCheck
import gg.hideouthq.core.library.SetDerivation
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetMember
import gg.hideouthq.core.library.StagedOriginal
import gg.hideouthq.core.library.StagedRef
import gg.hideouthq.core.library.StagedVpk
import gg.hideouthq.core.library.StagingId
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.VpkSet
import gg.hideouthq.core.library.folder
import gg.hideouthq.core.library.rank
import gg.hideouthq.library.StagingName
import gg.hideouthq.library.attributesOrNull
import gg.hideouthq.library.deleteTree
import gg.hideouthq.library.headOf
import gg.hideouthq.library.measure
import gg.hideouthq.library.publishFolder
import gg.hideouthq.library.retainedFile
import gg.hideouthq.library.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

class NioCompatibility(private val originalsRoot: Path, private val stagingRoot: Path, now: () -> Instant = Instant::now) : CompatibilityPort {
    override val unavailable: String? = null

    override val rules: List<RuleIdentity> = ModelRules.all.map { RuleIdentity(it.id, it.revision) }

    override val codec: String = Codec

    private val directories = GameDirectories(now)

    private fun packages(root: GameRoot, log: ReadLog?): GamePackages? = try {
        GamePackages.open(Path.of(root.path), directories, log)
    } catch (e: Exception) {
        null
    }

    override suspend fun game(root: GameRoot): String? = withContext(Dispatchers.IO) {
        try {
            directories.of(Path.of(root.path)).identity
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun check(record: ManifestRecord, originals: List<RetainedOriginal>, above: List<Provider>): CheckResult = withContext(Dispatchers.IO) {
        val log = ReadLog()
        val game = packages(record.gameRoot, log) ?: return@withContext CheckResult.Failed("the game's own files could not be read")
        game.use {
            val providers = try {
                above.associateWith { provider -> if (provider == Provider.Unreadable) null else providerPaths(record.gameRoot, provider, log) }
            } catch (e: IOException) {
                return@withContext CheckResult.Failed("a file in addons could not be read: ${e.message}")
            } catch (e: Kv3Failure) {
                return@withContext CheckResult.Failed("a file in addons could not be read: ${e.message}")
            }
            val sets = record.sets.map { set ->
                val derivation = set.derivation
                val files = if (derivation == null) deployed(record, set) else {
                    try {
                        for ((path, file) in deployed(record, set).zip(set.files)) {
                            val actual = measure(path)
                            if (actual != file.image) return@withContext CheckResult.Failed("the deployed repaired files do not match their record")
                            log.whole(path, record.installId, actual)
                        }
                    } catch (e: IOException) {
                        return@withContext CheckResult.Failed("the deployed repaired files could not be read")
                    }
                    val original = originals.firstOrNull { it.isOriginalOf(record, set, derivation) } ?: return@withContext CheckResult.Failed("the original files of a repaired set are not kept")
                    val kept = retainedFiles(original, set) ?: return@withContext CheckResult.Failed("the kept original files are missing")
                    if (kept.zip(derivation.originalFiles).any { (path, image) -> measure(path) != image }) return@withContext CheckResult.Failed("the kept original files do not match their record")
                    kept
                }
                checkSet(set, record.rank(set), files, derivation?.originalFiles ?: set.files.map { it.image }, game, providers, log, record.installId)
            }
            CheckResult.Checked(InstallCheck(record.installId, CheckBasis(game.identity, codec, rules, log.evidence()), sets))
        }
    }

    override suspend fun changed(evidence: Set<FileEvidence>): Set<FileEvidence> = withContext(Dispatchers.IO) { evidence.filterTo(HashSet()) { !ReadLog.holds(it) } }

    private fun checkSet(set: InstalledSet, rank: Int, files: List<Path>, inputs: List<FileImage>, game: GamePackages, providers: Map<Provider, Set<String>?>, log: ReadLog, installId: String): SetCheck {
        fun incomplete(reason: String) = SetCheck(set.source, set.componentKey, inputs, emptyList(), reason)
        return try {
            SetSources(set, files, log, installId).use { sources ->
                if (sources.sizes != inputs.map { it.sizeBytes }) return incomplete("its files are not the ones recorded")
                val pack = VpkPackages.read(sources.dir)
                val above = providers.filterKeys { it.rank < rank }
                val unknownAbove = above.any { it.value == null }
                fun shadow(path: String): Provider? = above.entries.firstOrNull { (_, paths) -> paths != null && path in paths }?.key
                val context = RuleContext(game::resource, sources.resources(pack)) { path -> unknownAbove || shadow(path) != null }
                val findings = mutableListOf<CompatFinding>()
                val models = pack.entries.filter { it.path.endsWith(".vmdl_c") }.groupBy { it.path }
                for ((path, copies) in models) {
                    val winner = shadow(path)
                    if (winner != null && winner.managed) continue
                    if (winner != null || unknownAbove) {
                        val why = if (winner != null) "${winner.name}, which Hideout does not manage, loads first" else "what loads first could not be read"
                        findings += rules.map { CompatFinding(it, path, FindingKind.Unknown, why) }
                        continue
                    }
                    for ((occurrence, model) in copies.withIndex()) {
                        val bytes = try {
                            VpkPackages.payload(pack, model, sources.dir, sources::chunk, ModelRepairs.MaxResourceBytes)
                        } catch (e: Kv3Failure) {
                            findings += rules.map { CompatFinding(it, path, FindingKind.Unknown, e.message, occurrence = occurrence) }
                            continue
                        }
                        for (verdict in ModelRepairs.assess(path, bytes, context)) {
                            val identity = RuleIdentity(verdict.rule.id, verdict.rule.revision)
                            when (val outcome = verdict.outcome) {
                                RuleOutcome.NotAffected -> Unit
                                is RuleOutcome.Repairable -> findings += CompatFinding(identity, path, FindingKind.Repairable, "${outcome.edits.size} values", outcome.edits.size, occurrence)
                                is RuleOutcome.Unrepairable -> findings += CompatFinding(identity, path, FindingKind.NeedsAuthor, outcome.reason, occurrence = occurrence)
                                is RuleOutcome.Declined -> findings += CompatFinding(identity, path, FindingKind.Unknown, outcome.reason, occurrence = occurrence)
                            }
                        }
                    }
                }
                SetCheck(set.source, set.componentKey, inputs, findings)
            }
        } catch (e: Kv3Failure) {
            incomplete(e.message)
        } catch (e: IOException) {
            incomplete(e.message ?: "its files could not be read")
        }
    }

    override suspend fun derive(record: ManifestRecord, set: InstalledSet, original: RetainedOriginal, expected: SetCheck, basis: CheckBasis, above: List<Provider>): DeriveResult = withContext(Dispatchers.IO) {
        val game = packages(record.gameRoot, log = null) ?: return@withContext DeriveResult.Refused("the game's own files could not be read")
        game.use { derive(record, set, original, expected, basis, above, game) }
    }

    private fun derive(record: ManifestRecord, set: InstalledSet, original: RetainedOriginal, expected: SetCheck, basis: CheckBasis, above: List<Provider>, game: GamePackages): DeriveResult {
        if (game.identity != basis.game) return DeriveResult.Stale("the game changed since the check")
        if (basis.codec != codec || basis.rules != rules) return DeriveResult.Stale("the rules changed since the check")
        val images = original.imagesFor(set) ?: return DeriveResult.Refused("the kept original files do not match the set")
        if (images != expected.inputs) return DeriveResult.Stale("the files changed since the check")
        val files = retainedFiles(original, set) ?: return DeriveResult.Refused("the kept original files are missing")
        for ((path, image) in files.zip(images)) if (measure(path) != image) return DeriveResult.Refused("the kept original ${path.fileName} no longer holds what was copied")
        val wanted = expected.findings.filter { it.kind == FindingKind.Repairable }.groupBy { it.resource to it.occurrence }
        if (wanted.isEmpty()) return DeriveResult.Refused("nothing in the set is repairable")
        val providers = try {
            above.filter { it.rank < record.rank(set) }.associateWith { provider -> if (provider == Provider.Unreadable) null else providerPaths(record.gameRoot, provider, log = null) }
        } catch (e: IOException) {
            return DeriveResult.Stale("a file in addons changed: ${e.message}")
        } catch (e: Kv3Failure) {
            return DeriveResult.Stale("a file in addons changed: ${e.message}")
        }
        val id = UUID.randomUUID().toString()
        val folder = stagingRoot.resolve(id)
        var kept = false
        return try {
            SetSources(set, files, log = null, installId = null).use { sources ->
                val pack = VpkPackages.read(sources.dir)
                val unknownAbove = providers.any { it.value == null }
                val context = RuleContext(game::resource, sources.resources(pack)) { path -> unknownAbove || providers.values.any { it != null && path in it } }
                val replaced = LinkedHashMap<VpkEntry, ByteArray>()
                val applied = linkedSetOf<RuleIdentity>()
                var edits = 0
                for ((copy, findings) in wanted) {
                    val (resource, occurrence) = copy
                    val entry = pack.find(resource).getOrNull(occurrence) ?: return DeriveResult.Stale("$resource is missing from the set")
                    val bytes = VpkPackages.payload(pack, entry, sources.dir, sources::chunk, ModelRepairs.MaxResourceBytes)
                    when (val repair = ModelRepairs.repair(resource, bytes, context)) {
                        is ResourceRepair.Refused -> return DeriveResult.Refused("$resource: ${repair.reason}")
                        is ResourceRepair.Repaired -> {
                            val made = repair.verdicts.filter { it.outcome is RuleOutcome.Repairable }
                            val plan = findings.associate { it.rule to it.edits }
                            val now = made.associate { RuleIdentity(it.rule.id, it.rule.revision) to (it.outcome as RuleOutcome.Repairable).edits.size }
                            if (plan != now) return DeriveResult.Stale("$resource would be repaired differently from what the check found")
                            replaced[entry] = repair.bytes
                            applied += now.keys
                            edits += now.values.sum()
                        }
                    }
                }
                Files.createDirectories(stagingRoot)
                Files.createDirectory(folder)
                val members = writeSet(set, record.filesOf(set).map { it.name }, images, pack, sources, replaced, folder, id)
                readBack(pack, replaced, members, folder)?.let { return DeriveResult.Refused("the repaired files did not read back exactly: $it") }
                kept = true
                val derivation = SetDerivation(original.id, images, rules.filter { it in applied }, codec, basis.game, replaced.size, edits)
                DeriveResult.Derived(StagingId(id), VpkSet(set.componentKey, members), derivation)
            }
        } catch (e: Kv3Failure) {
            DeriveResult.Refused(e.message)
        } catch (e: IOException) {
            DeriveResult.Refused(e.message ?: "the set could not be repaired")
        } finally {
            if (!kept) deleteTree(folder)
        }
    }

    private fun writeSet(set: InstalledSet, names: List<String>, originals: List<FileImage>, pack: VpkPackage, sources: SetSources, replaced: Map<VpkEntry, ByteArray>, folder: Path, id: String): List<SetMember<StagedVpk>> {
        val chunks = set.files.drop(1)
        val sizes = chunks.associate { it.chunk to originals[set.files.indexOf(it)].sizeBytes }
        val dirFile = folder.resolve("0.vpk")
        val appended = newFile(dirFile).use { out -> VpkPackages.rewrite(pack, sources.dir, sizes, replaced, out) }
        if (appended.keys.any { it !in sizes }) throw IOException("a repaired file names a chunk the set does not have")
        val members = mutableListOf(SetMember(VpkPart.Directory, staged(StagingId(id), 0, names.first(), dirFile)))
        chunks.forEachIndexed { index, chunk ->
            val target = folder.resolve("${index + 1}.vpk")
            newFile(target).use { out ->
                copy(sources.file(chunk), out)
                appended[chunk.chunk]?.forEach { out.write(it) }
            }
            members += SetMember(chunk.part, staged(StagingId(id), index + 1, names[index + 1], target))
        }
        return members
    }

    private fun readBack(original: VpkPackage, replaced: Map<VpkEntry, ByteArray>, members: List<SetMember<StagedVpk>>, folder: Path): String? = FileSource.open(folder.resolve("0.vpk")).use { dir ->
        val written = VpkPackages.read(dir)
        val chunks = members.drop(1).associate { (it.part as VpkPart.Chunk).index to folder.resolve("${it.vpk.ref.index}.vpk") }
        val opened = HashMap<Int, FileSource>()
        try {
            val entries = written.entries
            val before = original.entries
            fun chunk(index: Int) = chunks[index]?.let { opened.getOrPut(index) { FileSource.open(it) } }
            if (entries.size != before.size) return "the directory entry count changed"
            for ((index, entry) in before.withIndex()) {
                val bytes = replaced[entry] ?: continue
                val read = entries[index]
                if (read.path != entry.path) return "the directory entry for ${entry.path} changed"
                val payload = VpkPackages.payload(written, read, dir, ::chunk, ModelRepairs.MaxResourceBytes)
                if (!payload.contentEquals(bytes)) return "${entry.path} does not read back as repaired"
            }
            null
        } finally {
            opened.values.forEach { it.close() }
        }
    }

    override suspend fun retain(record: ManifestRecord, set: InstalledSet, id: String): RetainResult = withContext(Dispatchers.IO) {
        if (!Token.matches(id)) return@withContext RetainResult.Failed("$id is not a name Hideout makes")
        val files = deployed(record, set)
        try {
            Files.createDirectories(originalsRoot)
            publishFolder(originalsRoot, id) { incoming ->
                for ((file, path) in record.filesOf(set).zip(files)) {
                    val image = newFile(retainedFile(incoming, file.part)).use { out -> copy(path, out) }
                    if (image != file.image) return@withContext RetainResult.Failed("${file.name} changed while it was copied")
                }
            }
            RetainResult.Retained(RetainedOriginal(id, record.installId, set.source, set.componentKey, set.files))
        } catch (e: IOException) {
            deleteTree(originalsRoot.resolve(id))
            RetainResult.Failed("the original files could not be kept: ${e.message}")
        }
    }

    override suspend fun stageOriginal(original: RetainedOriginal, set: InstalledSet): StagedOriginal = withContext(Dispatchers.IO) {
        val files = retainedFiles(original, set) ?: return@withContext StagedOriginal.Failed("the kept original files are missing")
        val images = original.imagesFor(set) ?: return@withContext StagedOriginal.Failed("the kept original files do not match the set")
        val id = UUID.randomUUID().toString()
        val folder = stagingRoot.resolve(id)
        try {
            Files.createDirectories(stagingRoot)
            Files.createDirectory(folder)
            val members = set.files.mapIndexed { index, file ->
                val target = folder.resolve("$index.vpk")
                val image = newFile(target).use { out -> copy(files[index], out) }
                if (image != images[index]) throw IOException("the kept ${file.part.label} file changed")
                SetMember(file.part, staged(StagingId(id), index, files[index].fileName.toString(), target))
            }
            StagedOriginal.Staged(StagingId(id), VpkSet(set.componentKey, members))
        } catch (e: IOException) {
            deleteTree(folder)
            StagedOriginal.Failed(e.message ?: "the original files could not be staged")
        }
    }

    override suspend fun release(id: String) = withContext(Dispatchers.IO) {
        if (Token.matches(id)) deleteTree(originalsRoot.resolve(id))
    }

    override suspend fun sweep(registered: Set<String>) = withContext(Dispatchers.IO) {
        val attributes = attributesOrNull(originalsRoot) ?: return@withContext
        if (!attributes.isDirectory || attributes.isSymbolicLink) return@withContext
        Files.newDirectoryStream(originalsRoot).use { entries -> entries.toList() }
            .filter { it.fileName.toString() !in registered }
            .forEach(::deleteTree)
    }

    override suspend fun discard(staging: StagingId) = withContext(Dispatchers.IO) {
        if (StagingName.matches(staging.value)) deleteTree(stagingRoot.resolve(staging.value))
    }

    private fun deployed(record: ManifestRecord, set: InstalledSet): List<Path> {
        val folder = record.folder().split('/')
        return record.filesOf(set).map { file -> plain(Path.of(record.gameRoot.path), folder + file.name) }
    }

    private fun retainedFiles(original: RetainedOriginal, set: InstalledSet): List<Path>? {
        if (!Token.matches(original.id) || original.imagesFor(set) == null) return null
        val folder = originalsRoot.resolve(original.id)
        val files = set.files.map { retainedFile(folder, it.part) }
        return files.takeIf { list -> list.all { attributesOrNull(it)?.isRegularFile == true } }
    }

    private fun providerPaths(root: GameRoot, provider: Provider, log: ReadLog?): Set<String> {
        val path = plain(Path.of(root.path), provider.path.split('/'))
        val source = try {
            log?.open(path, provider.installId) ?: FileSource.open(path)
        } catch (e: NoSuchFileException) {
            return emptySet()
        }
        return source.use { VpkPackages.read(it).entries.map { entry -> entry.path }.toSet() }
    }

    private fun staged(staging: StagingId, index: Int, name: String, file: Path): StagedVpk {
        val image = measure(file)
        return StagedVpk(StagedRef(staging, index), name, image.sizeBytes, image.sha256, VpkFormat.read(headOf(file, image.sizeBytes), image.sizeBytes))
    }

    private class SetSources(private val set: InstalledSet, private val files: List<Path>, log: ReadLog?, installId: String?) : AutoCloseable {
        private val opened = ArrayList<ByteSource>()

        init {
            try {
                files.forEach { path -> opened += log?.open(path, installId) ?: FileSource.open(path) }
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        val dir: ByteSource get() = opened[set.files.indexOfFirst { it.part == VpkPart.Directory }]

        val sizes: List<Long> get() = opened.map { it.size }

        fun file(part: SetFile): Path = files[set.files.indexOf(part)]

        fun chunk(index: Int): ByteSource? = set.files.indexOfFirst { (it.part as? VpkPart.Chunk)?.index == index }.takeIf { it >= 0 }?.let { opened[it] }

        fun resources(pack: VpkPackage): (String) -> ByteArray? = { path -> pack.find(path).singleOrNull()?.let { VpkPackages.payload(pack, it, dir, ::chunk, ModelRepairs.MaxResourceBytes) } }

        override fun close() = opened.forEach { it.close() }
    }

    companion object {
        const val Codec: String = "hideout-kv3-1+resource-1+vpk-2"
        private val Token = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private const val CopyBytes = 1 shl 20

        internal fun plain(base: Path, segments: List<String>): Path {
            var path = base
            segments.forEachIndexed { index, segment ->
                if (segment.isEmpty() || segment == "." || segment == ".." || segment.any { it == '/' || it == '\\' }) throw IOException("$segment is not a plain name")
                path = path.resolve(segment)
                val attributes = attributesOrNull(path) ?: if (index == segments.lastIndex) return path else throw IOException("$path is not there")
                if (attributes.isSymbolicLink || (index < segments.lastIndex && !attributes.isDirectory) || (index == segments.lastIndex && !attributes.isRegularFile)) {
                    throw IOException("$path is a link or not what it should be")
                }
            }
            return path
        }

        private fun newFile(path: Path): DurableFile = DurableFile(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))

        private fun copy(from: Path, to: OutputStream): FileImage {
            val digest = sha256()
            var size = 0L
            FileChannel.open(from, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(CopyBytes)
                while (true) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    digest.update(buffer.array(), 0, read)
                    to.write(buffer.array(), 0, read)
                    size += read
                }
            }
            return FileImage(size, digest.digest().toHexString())
        }
    }

    private class DurableFile(private val channel: FileChannel) : OutputStream() {
        private val out = BufferedOutputStream(Channels.newOutputStream(channel), CopyBytes)

        override fun write(b: Int) = out.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() {
            channel.use {
                out.flush()
                it.force(true)
            }
        }
    }
}

private val SetFile.chunk: Int get() = (part as VpkPart.Chunk).index
