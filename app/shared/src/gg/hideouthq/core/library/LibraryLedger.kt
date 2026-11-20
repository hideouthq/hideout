package gg.hideouthq.core.library

import gg.hideouthq.core.data.CitadelFile
import gg.hideouthq.core.data.ConfigRead
import gg.hideouthq.core.data.ConfigRecovery
import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.PlaceResult
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.ReplaceResult
import gg.hideouthq.core.data.StageResult
import gg.hideouthq.core.data.TwinLinks
import gg.hideouthq.core.data.gamePath
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

/** One set a mod is to have, in the order the sets win. */
internal sealed interface SetPlan {
    val source: InstalledSource

    val componentKey: String

    /** The set the mod already has from [source], kept with its bytes: untouched where its names stay, renamed through anchors where they change. */
    data class Kept(override val source: InstalledSource, override val componentKey: String) : SetPlan

    /** The set the mod already has from [source], its recorded files put back from the game's backups, which still have them. */
    data class Stored(override val source: InstalledSource, override val componentKey: String) : SetPlan

    /**
     * A complete set new to the mod: unpacked as [origin] says, or, for a set the mod has, its repaired files or its
     * retained originals, which keep that set's [origin] and [componentKey] and record their [derivation].
     */
    data class Staged(
        override val source: InstalledSource,
        val originalName: String?,
        val variant: String?,
        val origin: SetOrigin,
        val version: VersionPosition?,
        val vpks: VpkSet<StagedVpk>,
        override val componentKey: String = vpks.key,
        val derivation: SetDerivation? = null,
    ) : SetPlan {
        /** The set as installed: its files in their part order, named by its mod's placement. */
        fun toSet(): InstalledSet = InstalledSet(source, originalName, variant, origin, version, componentKey, vpks.members.map { SetFile(it.part, it.vpk.image) }, derivation)

        companion object {
            /** [set]'s own place in its mod, holding [vpks] instead of its files, made as [derivation] says or, without one, its originals. */
            fun replacing(set: InstalledSet, vpks: VpkSet<StagedVpk>, derivation: SetDerivation?): Staged =
                Staged(set.source, set.originalName, set.variant, set.origin, set.version, vpks, set.componentKey, derivation)
        }
    }
}

internal sealed interface InstallOutcome {
    data class Installed(val record: ManifestRecord) : InstallOutcome

    /** [source] is the set whose file failed, when one did. */
    data class Refused(val problem: LibraryProblem, val leftInPlace: List<String> = emptyList(), val interrupted: String? = null, val source: InstalledSource? = null) : InstallOutcome
}

internal sealed interface Recovery {
    data object Settled : Recovery

    data class Waiting(val reason: String) : Recovery
}

internal sealed interface ChangeOutcome {
    data object Done : ChangeOutcome

    data class Refused(val problem: LibraryProblem, val interrupted: String? = null) : ChangeOutcome, ArchiveResult
}

/** How deleting a download ended, or [ChangeOutcome.Refused] when it could not start or finish. */
internal sealed interface ArchiveResult {
    data class Settled(val outcome: RemovalOutcome) : ArchiveResult

    data object Kept : ArchiveResult

    data object Unregistered : ArchiveResult
}

internal sealed interface ManifestAuthority {
    data class Known(val snapshot: LibraryManifest?) : ManifestAuthority

    data class Uncertain(val previous: LibraryManifest?, val reason: String) : ManifestAuthority
}

internal sealed interface SaveResult {
    data object Saved : SaveResult

    data class Refused(val reason: String) : SaveResult

    data class Unconfirmed(val reason: String) : SaveResult
}

private val SaveResult.failure: String?
    get() = when (this) {
        SaveResult.Saved -> null
        is SaveResult.Refused -> reason
        is SaveResult.Unconfirmed -> reason
    }

internal val SaveResult.problem: LibraryProblem?
    get() = when (this) {
        SaveResult.Saved -> null
        is SaveResult.Refused -> LibraryProblem.ManifestSave(reason)
        is SaveResult.Unconfirmed -> LibraryProblem.Interrupted(reason)
    }

/** What looking at files found, from best to worst; an unreadable file outranks a changed one, which Keep could not take. */
internal enum class FilesCheck { Intact, Changed, Unknown, Missing }

/** Why a file at [path] that looked like this does not hold its recorded bytes; null when it does. */
internal fun FilesCheck.problem(path: String): LibraryProblem? = when (this) {
    FilesCheck.Intact -> null
    FilesCheck.Changed -> LibraryProblem.FileChanged(path)
    FilesCheck.Unknown -> LibraryProblem.FileUnreadable(path)
    FilesCheck.Missing -> LibraryProblem.FileMissing(path)
}

/** What [LibraryLedger.look] found: the record with each set's edits as its files show them, and how each file looked stands. */
internal class Looked(val record: ManifestRecord, val files: Map<OwnedFile, FilesCheck>) {
    /** The worst any file is in. */
    val check: FilesCheck get() = files.values.maxOrNull() ?: FilesCheck.Intact
}

internal class LibraryLedger(
    private val store: LibraryStore,
    private val inventory: GameInventory,
    private val ports: InstallPorts,
    private val ids: () -> String,
    private val preserve: (Preserved) -> Unit,
    private val fence: suspend (GameRoot, gameMayRun: Boolean) -> String? = { _, _ -> null },
) {
    @Volatile
    var authority: ManifestAuthority = ManifestAuthority.Uncertain(previous = null, reason = NotRead)
        private set

    val manifest: LibraryManifest
        get() = when (val current = authority) {
            is ManifestAuthority.Known -> current.snapshot
            is ManifestAuthority.Uncertain -> current.previous
        } ?: LibraryManifest()

    val uncertainty: String? get() = (authority as? ManifestAuthority.Uncertain)?.reason

    /** The install list was missing when read: retained originals on disk may be all a lost list's mods have left, so none is swept. */
    var listWasMissing = false
        private set

    suspend fun reload(): ManifestLoad {
        val loaded = when (val read = attempt { store.load() }) {
            is Attempt.Done -> read.value
            is Attempt.Failed -> ManifestLoad.Unreadable("reading the install list failed: ${read.reason}")
        }
        when (loaded) {
            ManifestLoad.Absent -> {
                authority = ManifestAuthority.Known(null)
                listWasMissing = true
            }
            is ManifestLoad.Loaded -> authority = ManifestAuthority.Known(loaded.manifest)
            is ManifestLoad.Unreadable -> Unit
        }
        return loaded
    }

    suspend fun save(next: LibraryManifest): SaveResult {
        val previous = when (val current = authority) {
            is ManifestAuthority.Uncertain -> return SaveResult.Unconfirmed(current.reason)
            is ManifestAuthority.Known -> current.snapshot
        }
        val thrown = when (val saved = attempt { store.save(next) }) {
            is Attempt.Failed -> saved.reason
            is Attempt.Done -> return when (val result = saved.value) {
                ManifestSave.Saved -> SaveResult.Saved.also { authority = ManifestAuthority.Known(next) }
                is ManifestSave.Refused -> SaveResult.Refused(result.reason)
            }
        }
        val stored = (attempt { store.load() } as? Attempt.Done)?.value
        return when {
            stored is ManifestLoad.Loaded && stored.manifest == next -> SaveResult.Saved.also { authority = ManifestAuthority.Known(next) }
            stored is ManifestLoad.Loaded && stored.manifest == previous -> SaveResult.Refused(thrown)
            stored == ManifestLoad.Absent && previous == null -> SaveResult.Refused(thrown)
            else -> {
                val reason = "Hideout could not confirm that its install list was saved ($thrown), so it reads the list again before changing anything"
                authority = ManifestAuthority.Uncertain(previous, reason)
                SaveResult.Unconfirmed(reason)
            }
        }
    }

    /**
     * Saves [target] as the choices the files follow next. A book
     * whose active profile [ProfileBook.follows] the files changes no file, so the applied book takes it at once and
     * saved choices are forgotten instead. Saving what is saved already writes nothing. Refused while a change is in
     * flight, whose journal it must not disturb, and unconfirmed while the list is.
     */
    suspend fun saveChoices(target: ProfileBook): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        if (manifest.pending != null) return SaveResult.Refused(ChangeInFlight)
        val next = if (target.follows(manifest.records)) {
            manifest.copy(profiles = target, savedChoices = null)
        } else {
            manifest.copy(savedChoices = SavedChoices(target))
        }
        return if (next == manifest) SaveResult.Saved else save(next)
    }

    /** Forgets saved choices the files have reached, once no change is in flight; anything else stays as it is. */
    suspend fun clearReached(): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        val settled = manifest.settled()
        return if (settled == manifest) SaveResult.Saved else save(settled)
    }

    suspend fun registerDownload(download: OwnedDownload): SaveResult =
        save(manifest.copy(downloads = manifest.downloads.filter { it.archive.path != download.archive.path } + download))

    /** Registers [original], whose every file is already copied and read back; refused beside a change in flight. */
    suspend fun registerOriginal(original: RetainedOriginal): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        if (manifest.pending != null) return SaveResult.Refused(ChangeInFlight)
        if (manifest.originals.any { it.id == original.id }) return SaveResult.Refused("an original with id ${original.id} is retained already")
        return save(manifest.copy(originals = manifest.originals + original))
    }

    /**
     * Forgets the retained originals [ids] that no set, committed or in flight, was made from. Only once this is saved
     * may their copies be deleted. The ids actually forgotten.
     */
    suspend fun forgetOriginals(ids: Set<String>): Pair<SaveResult, Set<String>> {
        uncertainty?.let { return SaveResult.Unconfirmed(it) to emptySet() }
        val used = manifest.namedRecords().flatMap { it.sets }.mapNotNull { it.derivation?.original }.toSet()
        val forgotten = manifest.originals.map { it.id }.filter { it in ids && it !in used }.toSet()
        if (forgotten.isEmpty()) return SaveResult.Saved to emptySet()
        return save(manifest.copy(originals = manifest.originals.filter { it.id !in forgotten })) to forgotten
    }

    /**
     * Records that local mod [record] is who [matched] proved it is. When every set is pinned and no other mod in the game
     * is that submission already, it becomes GameBanana's, each set its own file's, named as GameBanana titles it when it
     * gave a title. One that is on changes in the install list alone, since `addons` holds it whoever it is; one that is
     * off and keeps no retained originals, which a move does not carry, moves through the journal into the
     * submission's off folder, as turning a mod on or off moves it. Otherwise, or when that move cannot start or is
     * undone, it keeps its folder and its title and gains only the submission, and its retained originals follow. Either
     * way GameBanana has answered for it, so it is no longer [ManifestRecord.unasked]. Refused while a change is in flight or once [record] is not as the list holds it, and unconfirmed when a move
     * stopped part way, for recovery to finish or undo.
     */
    suspend fun identify(record: ManifestRecord, matched: Matched): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        if (manifest.pending != null) return SaveResult.Refused(ChangeInFlight)
        if (record !in manifest.records) return SaveResult.Refused("the mod changed")
        val pinned = (matched as? Matched.Pinned)?.takeIf { pinned -> manifest.records.none { it.gameRoot == record.gameRoot && it.modId == pinned.submission.modId } }
            ?.takeIf { record.enabled || manifest.originals.none { it.installId == record.installId } }
        val sets = record.sets.mapIndexed { index, set ->
            val local = set.source as? InstalledSource.Local ?: return SaveResult.Refused("the mod is GameBanana's already")
            set.copy(source = pinned?.let { InstalledSource.Remote(it.files[index]) } ?: local.copy(submission = matched.submission))
        }
        val identified = record.copy(title = pinned?.title ?: record.title, sets = sets, unasked = false)
        if (identified == record) return SaveResult.Saved
        if (pinned != null && !record.enabled) {
            val moved = relocate(record, identified) as? ChangeOutcome.Refused ?: return SaveResult.Saved
            return moved.interrupted?.let { SaveResult.Unconfirmed(it) } ?: identify(record, Matched.Unpinned(pinned.submission))
        }
        val sources = record.sets.zip(sets).associate { (was, now) -> (was.source to was.componentKey) to now.source }
        return save(
            manifest.copy(
                records = manifest.records.map { if (it == record) identified else it },
                originals = manifest.originals.map { original -> sources[original.source to original.componentKey]?.takeIf { original.installId == record.installId }?.let { original.copy(source = it) } ?: original },
            ),
        )
    }

    /**
     * Records [seen], each record as [look] found its files, by the record the list holds: other bytes the user put in a
     * file hold its mod where it is until the user decides, and a file that holds its recorded bytes again lets it go.
     * It touches no file, so it may be saved while Deadlock runs; refused beside a change in flight, whose journal names
     * the records as they are.
     */
    suspend fun noteEdits(seen: Map<ManifestRecord, ManifestRecord>): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        if (manifest.pending != null) return SaveResult.Refused(ChangeInFlight)
        if (!manifest.records.containsAll(seen.keys)) return SaveResult.Refused("the mod changed")
        val noted = manifest.copy(records = manifest.records.map { seen[it] ?: it })
        return if (noted == manifest) SaveResult.Saved else save(noted)
    }

    /**
     * Keeps what the user edited in [record]'s files outside Hideout as the mod's own, once each file is read again: an
     * edited set's files become those under its names now, its directory and whichever chunks are there, each with its
     * size, SHA-256 and twin in the game's backups, and it forgets its derivation, since its files are no longer made
     * from its original. The mod is held no more, so the saved choices reach it. No game file moves, so the one save is
     * all a crash can interrupt. Why not, when a file cannot be read, an edited set's directory or a file of a set the
     * user did not edit is missing, beside a change in flight, or when it is not saved.
     */
    suspend fun keepEdits(record: ManifestRecord): LibraryProblem? {
        uncertainty?.let { return LibraryProblem.Interrupted(it) }
        if (manifest.pending != null) return LibraryProblem.Interrupted(ChangeInFlight)
        if (record !in manifest.records) return LibraryProblem.VersionChanged
        val looked = attempt { look(record, read = true) }.orElse { return LibraryProblem.Unexpected(it) }
        val folder = record.setFolder()
        val sets = record.sets.map { set ->
            val files = record.filesOf(set)
            val checks = files.associateWith { looked.files.getValue(it) }
            for (worst in listOf(FilesCheck.Unknown, FilesCheck.Missing)) checks.entries.firstOrNull { it.value == worst }?.let { return worst.problem(it.key.relativePath(record)) }
            if (FilesCheck.Changed !in checks.values) return@map looked.record.set(set.key)
            val present = asFound(record, set) ?: return LibraryProblem.FileUnreadable(record.folder())
            if (present.keys.firstOrNull() != VpkPart.Directory) return LibraryProblem.FileMissing(files.first().relativePath(record))
            val noted = looked.record.set(set.key).edits
            val found = present.map { (part, name) ->
                val image = noted[part] ?: files.firstOrNull { it.part == part && checks[it] == FilesCheck.Intact }?.image ?: run {
                    val entry = GameEntry(record.gameRoot, folder, name)
                    val path = "${folder.path}/$name"
                    when (val seen = attempt { inventory.file(record.gameRoot, path) }.orElse { FileObservation.Unreadable(it) }) {
                        FileObservation.Absent -> return LibraryProblem.FileMissing(path)
                        is FileObservation.Unreadable -> return LibraryProblem.FileUnreadable(path)
                        is FileObservation.Present -> FileImage(seen.sizeBytes, seen.sha256).also { attempt { ports.backups.keep(entry, it) } }
                    }
                }
                SetFile(part, image)
            }
            set.copy(files = found, derivation = null, edits = emptyMap())
        }
        val kept = record.copy(sets = sets)
        return if (kept == record) null else save(manifest.copy(records = manifest.records.map { if (it == record) kept else it })).problem
    }

    private suspend fun asFound(record: ManifestRecord, set: InstalledSet): Map<VpkPart, String>? {
        val stem = record.filesOf(set).first().name.removeSuffix(VpkPart.Directory.fileName(""))
        val listed = (attempt { inventory.list(record.gameRoot, record.setFolder().inCitadel) } as? Attempt.Done)?.value as? FolderListing.Listed ?: return null
        return listed.names.mapNotNull { name -> VpkPart.named(name, stem)?.let { it to name } }.sortedBy { it.first }.toMap()
    }

    /** Saves that [record]'s files are kept over a review while its submission offers [offered]. It touches no file, so it may be saved while Deadlock runs. */
    suspend fun keepReviewed(record: ManifestRecord, offered: Set<Long>): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        if (manifest.pending != null) return SaveResult.Refused(ChangeInFlight)
        if (record !in manifest.records) return SaveResult.Refused("the mod changed")
        val kept = record.copy(reviewed = offered)
        return if (kept == record) SaveResult.Saved else save(manifest.copy(records = manifest.records.map { if (it == record) kept else it }))
    }

    /** Saves [choice] for [installId], or forgets it when null. It touches no file, so it may be saved while Deadlock runs. */
    suspend fun saveRepairChoice(installId: String, choice: RepairChoice?): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        if (manifest.records.none { it.installId == installId }) return SaveResult.Refused("the mod is not installed")
        if (manifest.repairChoices[installId] == choice) return SaveResult.Saved
        val choices = if (choice == null) manifest.repairChoices - installId else manifest.repairChoices + (installId to choice)
        return save(manifest.copy(repairChoices = choices))
    }

    /**
     * Places a new mod off, in its own off folder: every set of [plan], winner first, in one journal, so that either
     * every set is installed or none is. Committed, it is first in every profile's order of [root] and on in
     * [profiles], and the choices worker turns it on when the active profile is one of them. It remembers the parts
     * its files offered that the user left out, [declined], and the mods the user kept on beside it, [keptBeside]; an
     * import's copy is [unasked] while GameBanana is still to say who it is.
     */
    suspend fun install(
        root: GameRoot, title: String, plan: List<SetPlan.Staged>, profiles: Set<ProfileId>, declined: Map<Long, Set<String>> = emptyMap(), keptBeside: Set<String> = emptySet(),
        unasked: Boolean = false,
    ): InstallOutcome {
        uncertainty?.let { return unconfirmedInstall(it) }
        val sets = plan.map { it.toSet() }
        val record = ManifestRecord(ids(), root, title, Placement.Off, sets, declined = declined, keptBeside = keptBeside, unasked = unasked)
        val copies = plan.zip(sets).flatMap { (set, placed) -> set.vpks.members.map { it.vpk }.zip(record.filesOf(placed)) }
        val folder = record.setFolder()
        val staging = PendingChange.Install(record, InstallStep.Staging, profiles)
        fenced(root)?.let { return InstallOutcome.Refused(LibraryProblem.Interrupted(it)) }
        when (val saved = save(manifest.copy(pending = staging))) {
            SaveResult.Saved -> Unit
            is SaveResult.Refused -> return InstallOutcome.Refused(LibraryProblem.ManifestSave(saved.reason))
            is SaveResult.Unconfirmed -> return unconfirmedInstall(saved.reason)
        }
        for ((vpk, file) in copies) {
            fenced(root)?.let { return unconfirmedInstall(it) }
            val problem = when (val written = attempt { ports.installer.stage(root, folder, vpk, staging.temporaryName(file)) }) {
                is Attempt.Failed -> LibraryProblem.PlacementFailed(written.reason)
                is Attempt.Done -> when (val result = written.value) {
                    is StageResult.Failed -> LibraryProblem.PlacementFailed(result.reason)
                    is StageResult.Written -> if (result.image == file.image) null else LibraryProblem.PlacementFailed("the copy of ${file.name} does not match the extracted file")
                }
            }
            if (problem != null) return undoStaging(staging).refusing(problem, record.setOf(file).source)
        }
        val publishing = staging.copy(step = InstallStep.Publishing)
        when (val saved = save(manifest.copy(pending = publishing))) {
            SaveResult.Saved -> Unit
            is SaveResult.Refused -> return undoStaging(staging).refusing(LibraryProblem.ManifestSave(saved.reason))
            is SaveResult.Unconfirmed -> return unconfirmedInstall(saved.reason)
        }
        for (file in record.files.sortedBy { it.isDirectory }) {
            fenced(root)?.let { return rollBack(publishing, LibraryProblem.Interrupted(it)) }
            val problem = when (val published = attempt { ports.installer.publish(root, folder, publishing.temporaryName(file), file.name) }) {
                is Attempt.Failed -> LibraryProblem.PlacementFailed(published.reason)
                is Attempt.Done -> when (val result = published.value) {
                    PublishResult.Published -> null
                    PublishResult.Taken -> LibraryProblem.NameTaken(file.name)
                    is PublishResult.Failed -> LibraryProblem.PlacementFailed(result.reason)
                }
            }
            if (problem != null) return rollBack(publishing, problem, record.setOf(file).source)
        }
        when (val proof = proof(publishing)) {
            is Proof.Unknown -> return InstallOutcome.Refused(LibraryProblem.Interrupted(proof.reason), interrupted = proof.reason)
            is Proof.Partial -> return rollBackFrom(publishing, proof.twins).refusing(LibraryProblem.PlacementFailed(ChangedWhilePlaced))
            Proof.Complete -> Unit
        }
        val releasing = publishing.copy(step = InstallStep.Releasing)
        when (val saved = save(manifest.copy(pending = releasing))) {
            SaveResult.Saved -> Unit
            is SaveResult.Refused -> return rollBack(publishing, LibraryProblem.ManifestSave(saved.reason))
            is SaveResult.Unconfirmed -> return unconfirmedInstall(saved.reason)
        }
        val released = removeTemporaries(releasing)
        released.fenced?.let { return rollBackFrom(releasing, record.files.map { it.name }.toSet()).refusing(LibraryProblem.Interrupted(it)) }
        return when (val committed = commitReleased(releasing, released)) {
            SaveResult.Saved -> InstallOutcome.Installed(record)
            is SaveResult.Refused -> rollBackFrom(releasing, record.files.map { it.name }.toSet()).refusing(LibraryProblem.ManifestSave(committed.reason))
            is SaveResult.Unconfirmed -> unconfirmedInstall(committed.reason)
        }
    }

    /**
     * Changes which files [record] has to [plan], winner first, at its own placement, in one journal, and names the mod
     * [title]. A kept set whose names stay is not touched; one whose names change, as in a mod that is on whose earlier
     * sets change, is renamed through anchors; a set that goes is anchored and released; a new one is copied in through
     * temporaries, a stored one linked in from the game's backups. Every name that leaves is released before any new
     * name is taken, so names can be handed round. Whatever stops it before the new record is saved puts the old one
     * back. A file that goes must hold its recorded bytes, except when [reinstalling]: then what each one holds, or that
     * it is missing, is journaled, the new files take their places, and an undo puts back exactly what was there; one
     * that holds other bytes moves to a folder of [CitadelPath.HideoutEdits] named [aside], or that name with a number
     * after it, as it goes, and so does each chunk an edited set has beyond its recorded files. A mod that is on must
     * have room for [plan] at its numbers already. The mod then remembers [declined], the parts its files offered that
     * the user left out.
     */
    suspend fun replace(record: ManifestRecord, title: String, plan: List<SetPlan>, reinstalling: Boolean = false, declined: Map<Long, Set<String>> = record.declined, aside: String = title): InstallOutcome {
        uncertainty?.let { return unconfirmedInstall(it) }
        fun installed(set: SetPlan) = record.sets.single { it.source == set.source && it.componentKey == set.componentKey }
        val kept = plan.map { set -> (set as? SetPlan.Kept)?.let(::installed) }
        val sets = plan.mapIndexed { index, set -> kept[index] ?: (set as? SetPlan.Stored)?.let { installed(it).copy(edits = emptyMap()) } ?: (set as SetPlan.Staged).toSet() }
        val target = record.copy(title = title, sets = sets, declined = declined)
        val planned = PendingChange.Replace(record, target, kept.filterNotNull().mapTo(HashSet()) { it.key }, ChangeToken(ids()), ReplaceStep.Staging)
        val copies = plan.zip(sets).flatMap { (set, placed) -> (set as? SetPlan.Staged)?.vpks?.members.orEmpty().map { it.vpk }.zip(target.filesOf(placed)) }
        val stored = plan.zip(sets).filter { (set, _) -> set is SetPlan.Stored }.flatMap { (_, placed) -> target.filesOf(placed) }
        val root = record.gameRoot
        val folder = target.setFolder()
        val found = mutableMapOf<String, FileImage?>()
        val looked = attempt { look(record, planned.going) }.orElse { return InstallOutcome.Refused(LibraryProblem.Unexpected(it)) }
        for (file in planned.going) {
            val check = looked.files.getValue(file)
            if (reinstalling && check != FilesCheck.Unknown) {
                if (check != FilesCheck.Intact) found[file.name] = looked.record.setOf(file).edits[file.part]
            } else {
                check.problem(file.relativePath(record))?.let { return InstallOutcome.Refused(it) }
            }
        }
        if (reinstalling) for (set in planned.going.filter { looked.files[it] == FilesCheck.Changed }.mapTo(LinkedHashSet(), record::setOf)) {
            val present = asFound(record, set) ?: return InstallOutcome.Refused(LibraryProblem.FileUnreadable(record.folder()))
            for ((_, name) in present - set.files.map { it.part }.toSet()) {
                val path = "${record.setFolder().path}/$name"
                when (val seen = attempt { inventory.file(root, path) }.orElse { FileObservation.Unreadable(it) }) {
                    FileObservation.Absent -> Unit
                    is FileObservation.Unreadable -> return InstallOutcome.Refused(LibraryProblem.FileUnreadable(path))
                    is FileObservation.Present -> found[name] = FileImage(seen.sizeBytes, seen.sha256)
                }
            }
        }
        val vacant = if (found.values.any { it != null }) vacantAside(root, aside) ?: return InstallOutcome.Refused(LibraryProblem.FileUnreadable(CitadelPath.HideoutEdits.value)) else null
        val change = planned.copy(found = found, aside = vacant)
        val swap = change.swap()
        swap.blocked()?.let { (problem, source) -> return InstallOutcome.Refused(problem, source = source) }
        fenced(root)?.let { return unconfirmedInstall(it) }
        save(manifest.copy(pending = change)).problem?.let { return InstallOutcome.Refused(it) }
        for ((vpk, file) in copies) {
            fenced(root)?.let { return unconfirmedInstall(it) }
            val staged = (attempt { ports.installer.stage(root, folder, vpk, change.token.temporaryName(file)) } as? Attempt.Done)?.value
            if (staged !is StageResult.Written || staged.image != file.image) {
                val reason = (staged as? StageResult.Failed)?.reason ?: "the copy of ${file.name} does not match the extracted file"
                return swap.undo(LibraryProblem.PlacementFailed(reason), target.setOf(file).source).outcome(target)
            }
        }
        for (file in stored) {
            fenced(root)?.let { return unconfirmedInstall(it) }
            when (val placed = attempt { ports.backups.place(file.image, GameEntry(root, folder, change.token.temporaryName(file))) }.orElse { PlaceResult.Failed(it) }) {
                PlaceResult.Placed -> Unit
                PlaceResult.Unavailable -> return swap.undo(LibraryProblem.PlacementFailed("the game's backups no longer hold ${file.name}"), target.setOf(file).source).outcome(target)
                is PlaceResult.Failed -> return swap.undo(LibraryProblem.PlacementFailed(placed.reason), target.setOf(file).source).outcome(target)
            }
        }
        val anchored = change.copy(step = ReplaceStep.Anchoring)
        save(manifest.copy(pending = anchored)).problem?.let { return InstallOutcome.Refused(it, interrupted = "replacement waits for recovery") }
        return anchored.swap().finish().outcome(target)
    }

    private suspend fun vacantAside(root: GameRoot, name: String): String? {
        val taken = ((attempt { inventory.list(root, CitadelPath.HideoutEdits.value) } as? Attempt.Done)?.value as? FolderListing.Listed)?.names?.toSet() ?: return null
        return generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name ($it)" }.first { it !in taken }
    }

    private fun PendingChange.Replace.swap(): FileSwap {
        val root = source.gameRoot
        val from = source.setFolder()
        val to = target.setFolder()
        return FileSwap(
            root, step,
            going = going.mapNotNull { file ->
                val held = held(file) ?: return@mapNotNull null
                Going(GameEntry(root, from, file.name), GameEntry(root, from, token.anchorName(file)), held, file.isDirectory, file.relativePath(source), aside.takeIf { held != file.image })
            } + strays.map { (name, image) -> Going(GameEntry(root, from, name), GameEntry(root, from, token.anchorName(name)), image, isDirectory = false, "${from.path}/$name", aside) },
            coming = coming.map { file ->
                val renamed = renamedFrom(file)
                val temporary = GameEntry(root, to, token.temporaryName(file))
                Coming(GameEntry(root, to, file.name), renamed?.let { GameEntry(root, from, token.anchorName(it)) } ?: temporary, temporary.takeIf { renamed == null }, file.image, file.isDirectory, file.relativePath(target), target.setOf(file).source)
            },
            at = { copy(step = it) },
            committed = { pending -> manifest.copy(records = manifest.records.map { if (it == source) target else it }, pending = pending) },
            rolledBack = { manifest.copy(pending = null) },
        )
    }

    private sealed interface Swapped {
        data object Done : Swapped

        data class RolledBack(val problem: LibraryProblem, val interrupted: String?, val source: InstalledSource?) : Swapped

        data class Wait(val reason: String) : Swapped
    }

    private fun Swapped.outcome(record: ManifestRecord): InstallOutcome = when (this) {
        Swapped.Done -> InstallOutcome.Installed(record)
        is Swapped.RolledBack -> InstallOutcome.Refused(problem, interrupted = interrupted, source = source)
        is Swapped.Wait -> unconfirmedInstall(reason)
    }

    private fun Swapped.changed(): ChangeOutcome = when (this) {
        Swapped.Done -> ChangeOutcome.Done
        is Swapped.RolledBack -> ChangeOutcome.Refused(problem, interrupted)
        is Swapped.Wait -> ChangeOutcome.Refused(LibraryProblem.Interrupted(reason), reason)
    }

    private fun Swapped.waits(): Recovery = when (this) {
        Swapped.Done -> Recovery.Settled
        is Swapped.RolledBack -> interrupted?.let { Recovery.Waiting(it) } ?: Recovery.Settled
        is Swapped.Wait -> Recovery.Waiting(reason)
    }

    private class Going(val at: GameEntry, val anchor: GameEntry, val image: FileImage, val isDirectory: Boolean, val path: String, val aside: String? = null)

    private class Coming(val at: GameEntry, val witness: GameEntry, val temporary: GameEntry?, val image: FileImage, val isDirectory: Boolean, val path: String, val source: InstalledSource? = null)

    private inner class FileSwap(
        private val root: GameRoot,
        private val step: ReplaceStep,
        private val going: List<Going>,
        val coming: List<Coming>,
        private val at: (ReplaceStep) -> PendingChange,
        private val committed: (PendingChange) -> LibraryManifest,
        private val rolledBack: () -> LibraryManifest,
    ) {
        private val anchored = going.associate { it.anchor to it.at }

        suspend fun blocked(): Pair<LibraryProblem, InstalledSource?>? {
            for (file in going) vacant(root, file.anchor.path)?.let { return it to null }
            val freed = going.map { it.at }.toSet()
            for (file in coming) {
                file.temporary?.let { vacant(root, it.path) }?.let { return it to file.source }
                if (file.at !in freed) vacant(root, file.at.path)?.let { return it to file.source }
            }
            return null
        }

        suspend fun finish(): Swapped {
            if (step == ReplaceStep.Staging || step == ReplaceStep.RollingBack) return undo(LibraryProblem.PlacementFailed(ReplacementUndone))
            var current = step
            if (current == ReplaceStep.Anchoring) {
                for (file in going) {
                    fenced(root)?.let { return Swapped.Wait(it) }
                    val linked = (attempt { ports.installer.link(file.at, file.anchor) } as? Attempt.Done)?.value
                    if (linked != LinkResult.Linked && linked != LinkResult.Taken) return undo(LibraryProblem.PlacementFailed(ReplacementAnchoring))
                    val proof = (attempt { ports.installer.inspect(file.anchor, file.at) } as? Attempt.Done)?.value
                    if (proof !is EntryState.Present || !proof.twin || proof.image != file.image) return undo(LibraryProblem.FileChanged(file.path))
                    file.aside?.let { aside -> (attempt { ports.backups.setAside(file.at, aside) } as? Attempt.Failed)?.let { return undo(LibraryProblem.PlacementFailed(it.reason)) } }
                }
                current = ReplaceStep.Releasing
                save(manifest.copy(pending = at(current))).failure?.let { return Swapped.Wait(it) }
            }
            if (current == ReplaceStep.Releasing) {
                for (file in going.sortedByDescending { it.isDirectory }) {
                    fenced(root)?.let { return Swapped.Wait(it) }
                    when (val removed = attempt { ports.installer.removeTwin(file.at, file.anchor, file.image) }) {
                        is Attempt.Failed -> return Swapped.Wait(removed.reason)
                        is Attempt.Done -> when (val result = removed.value) {
                            Removal.Removed, Removal.Absent -> Unit
                            is Removal.Failed -> return Swapped.Wait(result.reason)
                            is Removal.Kept -> return undo(LibraryProblem.FileChanged(file.path))
                        }
                    }
                }
                current = ReplaceStep.Publishing
                save(manifest.copy(pending = at(current))).failure?.let { return Swapped.Wait(it) }
            }
            if (current == ReplaceStep.Publishing) {
                for (file in coming.sortedBy { it.isDirectory }) {
                    fenced(root)?.let { return Swapped.Wait(it) }
                    val linked = (attempt { ports.installer.link(file.witness, file.at) } as? Attempt.Done)?.value
                    val proof = (attempt { ports.installer.inspect(file.at, file.witness) } as? Attempt.Done)?.value
                    if ((linked != LinkResult.Linked && linked != LinkResult.Taken) || proof !is EntryState.Present || !proof.twin || proof.image != file.image) {
                        val taken = linked == LinkResult.Taken || proof is EntryState.Present && !proof.twin
                        return undo(if (taken) LibraryProblem.NameTaken(file.path) else LibraryProblem.PlacementFailed(ReplacementPublishing), file.source)
                    }
                }
                for (file in coming) {
                    val proof = (attempt { ports.installer.inspect(file.at, file.witness) } as? Attempt.Done)?.value
                    if (proof !is EntryState.Present || !proof.twin || proof.image != file.image) return Swapped.Wait(ReplacementUnverified)
                }
                current = ReplaceStep.Cleanup
                save(committed(at(current))).failure?.let { return Swapped.Wait(it) }
            }
            val preserved = mutableListOf<String>()
            dropTemporaries(coming, preserved, needed = true)?.let { return Swapped.Wait(it) }
            for (file in going) {
                fenced(root)?.let { return Swapped.Wait(it) }
                val fed = coming.firstOrNull { it.witness == file.anchor }
                when (val removed = attempt { ports.installer.removeLeftover(file.anchor, fed?.at ?: file.at, file.image, needed = fed != null) }) {
                    is Attempt.Failed -> return Swapped.Wait(removed.reason)
                    is Attempt.Done -> when (val result = removed.value) {
                        Removal.Removed, Removal.Absent -> Unit
                        is Removal.Failed -> return Swapped.Wait(result.reason)
                        is Removal.Kept -> preserved += file.anchor.path
                    }
                }
            }
            save(manifest.copy(pending = null)).failure?.let { return Swapped.Wait(it) }
            report(Preserved(preserved))
            return Swapped.Done
        }

        suspend fun undo(problem: LibraryProblem, source: InstalledSource? = null): Swapped {
            fun waiting(reason: String) = Swapped.RolledBack(problem, reason, source)
            if (step != ReplaceStep.RollingBack) save(manifest.copy(pending = at(ReplaceStep.RollingBack))).failure?.let { return waiting(it) }
            for (file in coming.sortedByDescending { it.isDirectory }) {
                if (anchored[file.witness] == file.at) continue
                fenced(root)?.let { return waiting(it) }
                when (val removed = removeTwinOf(file.at, file.witness, file.image)) {
                    is Attempt.Failed -> return waiting(removed.reason)
                    is Attempt.Done -> if (removed.value is Removal.Kept) return waiting("a changed file prevents rollback")
                }
            }
            for (file in going.sortedBy { it.isDirectory }) {
                fenced(root)?.let { return waiting(it) }
                val anchor = (attempt { ports.installer.inspect(file.anchor, file.at) } as? Attempt.Done)?.value
                if (anchor == EntryState.Absent || anchor is EntryState.Present && anchor.twin) continue
                if (anchor !is EntryState.Present || anchor.image != file.image) return waiting("an anchor changed")
                val linked = (attempt { ports.installer.link(file.anchor, file.at) } as? Attempt.Done)?.value
                if (linked != LinkResult.Linked) return waiting("a foreign file prevents rollback")
            }
            for (file in going) {
                fenced(root)?.let { return waiting(it) }
                val removed = (attempt { ports.installer.removeLeftover(file.anchor, file.at, file.image, needed = true) } as? Attempt.Done)?.value
                if (removed != Removal.Removed && removed != Removal.Absent) return waiting("an anchor could not be removed")
            }
            val preserved = mutableListOf<String>()
            dropTemporaries(coming, preserved, needed = false)?.let { return waiting(it) }
            save(rolledBack()).failure?.let { return waiting(it) }
            report(Preserved(preserved))
            return Swapped.RolledBack(problem, interrupted = null, source)
        }

        suspend fun dropTemporaries(files: List<Coming>, preserved: MutableList<String>, needed: Boolean): String? {
            for (file in files) {
                val temporary = file.temporary ?: continue
                fenced(root)?.let { return it }
                when (val removed = attempt { ports.installer.removeLeftover(temporary, file.at, file.image, needed) }) {
                    is Attempt.Failed -> return removed.reason
                    is Attempt.Done -> when (val result = removed.value) {
                        Removal.Removed, Removal.Absent -> Unit
                        is Removal.Failed -> return result.reason
                        is Removal.Kept -> preserved += temporary.path
                    }
                }
            }
            return null
        }
    }

    private fun unconfirmedInstall(reason: String) = InstallOutcome.Refused(LibraryProblem.Interrupted(reason), interrupted = reason)

    suspend fun recover(): Recovery {
        uncertainty?.let { return Recovery.Waiting(it) }
        return when (val pending = manifest.pending) {
            null -> Recovery.Settled
            is PendingChange.Install -> when (val step = pending.step) {
                InstallStep.Staging -> undoStaging(pending).settling()
                InstallStep.Publishing -> recoverPublishing(pending)
                InstallStep.Releasing -> recoverReleasing(pending)
                is InstallStep.RollingBack -> finishRollback(pending, step.published).settling()
            }
            is PendingChange.Replace -> pending.swap().finish().waits()
            is PendingChange.ConfigEdit -> recoverConfig(pending)
            is PendingChange.Relocate -> when (pending.step) {
                RelocateStep.Linking -> undoLinking(pending).settling()
                RelocateStep.Releasing -> (releaseSources(pending) as? ChangeOutcome.Refused)?.interrupted.waiting() ?: Recovery.Settled
                RelocateStep.Cleanup -> cleanAnchors(pending.setMoves(), pending.sets()).waiting() ?: Recovery.Settled
            }
            is PendingChange.Uninstall -> (finishUninstall(pending) as? ChangeOutcome.Refused)?.interrupted.waiting() ?: Recovery.Settled
            is PendingChange.ArchiveRemoval -> when (val finished = finishArchiveRemoval(pending)) {
                is ChangeOutcome.Refused -> Recovery.Waiting(finished.interrupted ?: SettlingRemoval)
                is ArchiveResult.Settled, ArchiveResult.Kept, ArchiveResult.Unregistered -> Recovery.Settled
            }
            is PendingChange.Adopt -> when (pending.step) {
                AdoptStep.Linking -> undoAdoption(pending).settling()
                AdoptStep.Releasing -> when (val finished = finishAdoption(pending)) {
                    ChangeOutcome.Done -> Recovery.Settled
                    is ChangeOutcome.Refused -> finished.interrupted?.let { Recovery.Waiting(it) } ?: Recovery.Settled
                }
            }
            is PendingChange.Revert -> pending.swap().finish().waits()
            is PendingChange.Arrange -> when (pending.step) {
                MoveStep.Anchoring -> undoAnchors(pending).settling()
                MoveStep.RollingBack -> undoArrange(pending).settling()
                MoveStep.Releasing, MoveStep.Linking, MoveStep.Cleanup -> when (val finished = finishArrange(pending)) {
                    ChangeOutcome.Done -> Recovery.Settled
                    is ChangeOutcome.Refused -> finished.interrupted?.let { Recovery.Waiting(it) } ?: Recovery.Settled
                }
            }
        }
    }

    fun linesFor(root: GameRoot): HideoutLines = manifest.receipt(root)?.lines ?: HideoutLines(mods = true, customGames = false)

    suspend fun ensureLoading(root: GameRoot): LibraryProblem? = writeBlock(root, linesFor(root).copy(mods = true))

    /**
     * Writes Hideout's block as [lines] asks, with the mounts of [unmount], the folders of the managers imported from,
     * taken out first, and records [lines] as the receipt's intents.
     */
    suspend fun writeBlock(root: GameRoot, lines: HideoutLines, unmount: Set<ForeignRoot> = emptySet()): LibraryProblem? {
        uncertainty?.let { return LibraryProblem.Interrupted(it) }
        repeat(2) {
            val current = when (val read = attempt { ports.gameConfig.read(root) }) {
                is Attempt.Failed -> return LibraryProblem.GameInfo(GameInfoProblem.Unreadable)
                is Attempt.Done -> when (val config = read.value) {
                    ConfigRead.Missing -> return LibraryProblem.GameInfo(GameInfoProblem.Missing)
                    is ConfigRead.Unreadable -> return LibraryProblem.GameInfo(GameInfoProblem.Unreadable)
                    is ConfigRead.Present -> config
                }
            }
            when (val edit = GameInfoPatch.takeOver(current.bytes, lines, unmount)) {
                is GameInfoEdit.Refused -> return LibraryProblem.GameInfo(edit.problem)
                GameInfoEdit.Unchanged -> return recordIntent(root, lines)
                is GameInfoEdit.Patched -> when (val written = writeConfig(root, ConfigTarget.GameInfo(lines), current.image, edit.bytes)) {
                    LibraryProblem.GameInfoChanged -> Unit
                    else -> return written
                }
            }
        }
        return LibraryProblem.GameInfoChanged
    }

    suspend fun recordIntent(root: GameRoot, lines: HideoutLines): LibraryProblem? {
        uncertainty?.let { return LibraryProblem.Interrupted(it) }
        if (linesFor(root) == lines) return null
        val next = manifest.receipt(root)?.copy(lines = lines) ?: GameConfigReceipt(root, written = null, lines = lines)
        return save(manifest.withReceipt(next)).problem
    }

    /**
     * Replaces [target], which held [from] or was not there when null, with [next] through the ConfigEdit journal. Only
     * `gameinfo.gi` has a receipt, which learns what was written.
     */
    suspend fun writeConfig(root: GameRoot, target: ConfigTarget, from: FileImage?, next: ByteArray): LibraryProblem? {
        uncertainty?.let { return LibraryProblem.Interrupted(it) }
        fenced(root, target.whileGameRuns)?.let { return LibraryProblem.Interrupted(it) }
        var receipt = when (target) {
            is ConfigTarget.GameInfo -> manifest.receipt(root) ?: GameConfigReceipt(root, written = null, lines = target.lines)
            ConfigTarget.Autoexec -> null
        }
        fun LibraryManifest.receipted() = receipt?.let { withReceipt(it) } ?: this
        fun failure(reason: String) = if (target is ConfigTarget.GameInfo) LibraryProblem.GameInfoWriteFailed(reason) else LibraryProblem.AutoexecWriteFailed(reason)
        when (val prepared = attempt { ports.gameConfig.prepare(root, target) }) {
            is Attempt.Failed -> return failure(prepared.reason)
            is Attempt.Done -> prepared.value?.let { return failure(it) }
        }
        val edit = PendingChange.ConfigEdit(root, target, ids(), from, Sha256.image(next))
        save(manifest.receipted().copy(pending = edit)).problem?.let { return it }
        fenced(root, target.whileGameRuns)?.let { return save(manifest.copy(pending = null)).problem ?: LibraryProblem.Interrupted(it) }
        val path = target.segments.joinToString("/")
        suspend fun failed(reason: String): LibraryProblem {
            if (fenced(root, target.whileGameRuns) == null) recoverConfig(edit)
            return failure(reason)
        }
        val problem = when (val replaced = attempt { ports.gameConfig.replace(edit, next) }) {
            is Attempt.Failed -> return failed(replaced.reason)
            is Attempt.Done -> when (val result = replaced.value) {
                ReplaceResult.Replaced -> null
                ReplaceResult.Changed -> if (target is ConfigTarget.GameInfo) LibraryProblem.GameInfoChanged else LibraryProblem.FileChanged(path)
                is ReplaceResult.Conflict -> if (target is ConfigTarget.GameInfo) LibraryProblem.GameInfoConflict(result.detail) else LibraryProblem.FileChanged(path)
                is ReplaceResult.Failed -> return failed(result.reason)
            }
        }
        if (problem == null && target is ConfigTarget.GameInfo) receipt = receipt?.copy(written = edit.to, lines = target.lines)
        val cleared = save(manifest.receipted().copy(pending = null)).problem
        return if (problem == null && target == ConfigTarget.Autoexec) null else cleared ?: problem
    }

    private suspend fun recoverConfig(edit: PendingChange.ConfigEdit): Recovery {
        val receipt = manifest.receipt(edit.gameRoot)
        val (settled, preserved) = when (val recovered = attempt { ports.gameConfig.recover(edit) }) {
            is Attempt.Failed -> return Recovery.Waiting(recovered.reason)
            is Attempt.Done -> when (val result = recovered.value) {
                is ConfigRecovery.Applied -> when (val target = edit.target) {
                    is ConfigTarget.GameInfo -> receipt?.copy(written = edit.to, lines = target.lines) ?: GameConfigReceipt(edit.gameRoot, edit.to, target.lines)
                    ConfigTarget.Autoexec -> receipt
                } to Preserved(result.kept)
                is ConfigRecovery.Undone -> receipt to Preserved(result.kept)
                is ConfigRecovery.Conflict -> receipt to Preserved(result.kept, result.detail)
                is ConfigRecovery.Failed -> return Recovery.Waiting(result.reason)
            }
        }
        save((settled?.let { manifest.withReceipt(it) } ?: manifest).copy(pending = null)).failure?.let { return Recovery.Waiting(it) }
        report(preserved)
        return Recovery.Settled
    }

    private suspend fun recoverPublishing(publishing: PendingChange.Install): Recovery = when (val proof = proof(publishing)) {
        is Proof.Unknown -> Recovery.Waiting(proof.reason)
        is Proof.Partial -> rollBackFrom(publishing, proof.twins).settling()
        Proof.Complete -> {
            val releasing = publishing.copy(step = InstallStep.Releasing)
            save(manifest.copy(pending = releasing)).failure?.let { Recovery.Waiting(it) }
                ?: commit(releasing).failure?.let { Recovery.Waiting(it) }
                ?: Recovery.Settled
        }
    }

    private sealed interface Proof {
        data object Complete : Proof

        data class Partial(val twins: Set<String>) : Proof

        data class Unknown(val reason: String) : Proof
    }

    private suspend fun proof(publishing: PendingChange.Install): Proof {
        val twins = twins(publishing) ?: return Proof.Unknown(Uninspected)
        if (twins.size < publishing.record.files.size) return Proof.Partial(twins)
        return when (intact(publishing.record)) {
            null -> Proof.Unknown(Unread)
            false -> Proof.Partial(twins)
            true -> Proof.Complete
        }
    }

    private suspend fun recoverReleasing(releasing: PendingChange.Install): Recovery = when (intact(releasing.record)) {
        null -> Recovery.Waiting(Unread)
        false -> rollBackFrom(releasing, releasing.record.files.map { it.name }.toSet()).settling()
        true -> commit(releasing).failure?.let { Recovery.Waiting(it) } ?: Recovery.Settled
    }

    private suspend fun commit(releasing: PendingChange.Install): SaveResult {
        uncertainty?.let { return SaveResult.Unconfirmed(it) }
        val released = removeTemporaries(releasing)
        released.fenced?.let { return SaveResult.Unconfirmed(it) }
        return commitReleased(releasing, released)
    }

    private suspend fun commitReleased(releasing: PendingChange.Install, released: Released): SaveResult {
        val record = releasing.record
        val members = releasing.profiles
        val applied = manifest.profiles.withInstalled(record, members - manifest.profiles.active)
        val choices = SavedChoices(manifest.chosen().withInstalled(record, members))
        val saved = save(manifest.copy(records = manifest.records + record, profiles = applied, savedChoices = choices, pending = null).settled())
        when (saved) {
            SaveResult.Saved -> report(Preserved(released.kept + released.stuck))
            is SaveResult.Refused, is SaveResult.Unconfirmed -> Unit
        }
        return saved
    }

    private suspend fun rollBack(publishing: PendingChange.Install, problem: LibraryProblem, source: InstalledSource? = null): InstallOutcome {
        val twins = twins(publishing) ?: return InstallOutcome.Refused(problem, interrupted = Uninspected, source = source)
        return rollBackFrom(publishing, twins).refusing(problem, source)
    }

    private suspend fun rollBackFrom(pending: PendingChange.Install, proven: Set<String>): Undone {
        val rolling = pending.copy(step = InstallStep.RollingBack(proven))
        save(manifest.copy(pending = rolling)).failure?.let { return Undone(emptyList(), it) }
        return finishRollback(rolling, proven)
    }

    private fun Undone.settling(): Recovery = interrupted?.let { Recovery.Waiting(it) } ?: Recovery.Settled.also { report(Preserved(leftInPlace)) }

    private fun Undone.refusing(problem: LibraryProblem, source: InstalledSource? = null) = InstallOutcome.Refused(problem, leftInPlace, interrupted, source)

    private suspend fun finishRollback(rolling: PendingChange.Install, published: Set<String>): Undone {
        uncertainty?.let { return Undone(emptyList(), it) }
        val root = rolling.record.gameRoot
        val folder = rolling.record.setFolder()
        val left = mutableListOf<String>()
        var failure: String? = null
        for (file in rolling.record.files.sortedByDescending { it.isDirectory }.filter { it.name in published }) {
            fenced(root)?.let { return Undone(left, it) }
            val entry = GameEntry(root, folder, file.name)
            if (!file.isDirectory) {
                when (val gone = ports.installer.directoryGone(GameEntry(root, folder, rolling.record.directoryOf(file).name))) {
                    is Attempt.Failed -> return Undone(left, gone.reason)
                    is Attempt.Done -> if (!gone.value) {
                        if ((attempt { ports.installer.inspect(entry, entry) } as? Attempt.Done)?.value != EntryState.Absent) left += file.name
                        continue
                    }
                }
            }
            when (val removed = attempt { ports.installer.removeExact(entry, file.image) }) {
                is Attempt.Failed -> failure = removed.reason
                is Attempt.Done -> when (val result = removed.value) {
                    Removal.Removed, Removal.Absent -> Unit
                    is Removal.Kept -> left += file.name
                    is Removal.Failed -> failure = result.reason
                }
            }
        }
        val released = removeTemporaries(rolling)
        left += released.kept
        failure = released.fenced ?: released.failure ?: failure
        if (failure != null) return Undone(left, failure)
        return Undone(left, save(manifest.copy(pending = null)).failure)
    }

    private suspend fun undoStaging(staging: PendingChange.Install): Undone {
        uncertainty?.let { return Undone(emptyList(), it) }
        val released = removeTemporaries(staging)
        return Undone(released.kept, released.fenced ?: released.failure ?: save(manifest.copy(pending = null)).failure)
    }

    private class Released(val kept: List<String>, val stuck: List<String>, val failure: String?, val fenced: String? = null)

    private suspend fun removeTemporaries(pending: PendingChange.Install): Released {
        val root = pending.record.gameRoot
        val folder = pending.record.setFolder()
        val needed = pending.step == InstallStep.Releasing
        val kept = mutableListOf<String>()
        val stuck = mutableListOf<String>()
        var failure: String? = null
        for (file in pending.record.files) {
            fenced(root)?.let { return Released(kept, stuck, failure, it) }
            val temporary = pending.temporaryName(file)
            val reason = when (val removed = attempt { ports.installer.removeLeftover(GameEntry(root, folder, temporary), GameEntry(root, folder, file.name), file.image, needed) }) {
                is Attempt.Failed -> removed.reason
                is Attempt.Done -> when (val result = removed.value) {
                    Removal.Removed, Removal.Absent -> null
                    is Removal.Kept -> null.also { kept += temporary }
                    is Removal.Failed -> result.reason
                }
            }
            if (reason != null) {
                stuck += temporary
                failure = reason
            }
        }
        return Released(kept, stuck, failure)
    }

    private suspend fun twins(pending: PendingChange.Install): Set<String>? {
        val names = mutableSetOf<String>()
        for (file in pending.record.files) {
            when (val same = attempt { ports.installer.sameFile(pending.record.gameRoot, pending.record.setFolder(), pending.temporaryName(file), file.name) }) {
                is Attempt.Failed -> return null
                is Attempt.Done -> if (same.value) names += file.name
            }
        }
        return names
    }

    private suspend fun intact(record: ManifestRecord): Boolean? {
        var unknown = false
        for (file in record.files) when (notHolding(record, file)) {
            null -> Unit
            is LibraryProblem.FileUnreadable -> unknown = true
            else -> return false
        }
        return if (unknown) null else true
    }

    private suspend fun notHolding(record: ManifestRecord, file: OwnedFile): LibraryProblem? {
        val path = file.relativePath(record)
        return attempt { look(record, listOf(file)) }.orElse { return LibraryProblem.FileUnreadable(path) }.files.getValue(file).problem(path)
    }

    /**
     * What [files] of [record] hold now, and [record] with each of its sets' edits as they show them: other bytes in a
     * file are its edit, a file that holds its recorded bytes or none has none, and one that cannot be read keeps what
     * was recorded. Unless [read], a file the game's backups vouch for at its recorded or edited bytes is not read. One
     * of a record the install list holds that is read gets its twin there under the SHA-256 it has, with the modified
     * time it had before the read, so the next look need not read it, and a twin it no longer holds goes, as an edit in
     * place changes both names. It throws when the files cannot be looked at.
     */
    internal suspend fun look(record: ManifestRecord, files: List<OwnedFile> = record.files, read: Boolean = false): Looked {
        val owned = record in manifest.records
        val edits = record.sets.associate { it.key to it.edits.toMutableMap() }
        val found = files.associateWith { file ->
            val entry = GameEntry(record.gameRoot, record.setFolder(), file.name)
            val noted = edits.getValue(file.set)
            val edit = noted[file.part]
            val image = when {
                !read && twinned(entry, file.image) -> file.image
                !read && edit != null && twinned(entry, edit) -> edit
                else -> {
                    val modified = if (owned) (attempt { ports.backups.modified(entry) } as? Attempt.Done)?.value else null
                    when (val seen = inventory.file(record.gameRoot, file.relativePath(record))) {
                        FileObservation.Absent -> null
                        is FileObservation.Unreadable -> return@associateWith FilesCheck.Unknown
                        is FileObservation.Present -> FileImage(seen.sizeBytes, seen.sha256).also { image ->
                            if (owned) {
                                for (gone in setOfNotNull(file.image, edit) - image) attempt { ports.backups.forget(entry, gone) }
                                attempt { ports.backups.keep(entry, image, modified) }
                            }
                        }
                    }
                }
            }
            when (image) {
                null -> FilesCheck.Missing.also { noted -= file.part }
                file.image -> FilesCheck.Intact.also { noted -= file.part }
                else -> FilesCheck.Changed.also { noted[file.part] = image }
            }
        }
        // A chunk gone from a set whose other files hold other bytes is part of that edit, as a VPK rebuilt with fewer chunks.
        val edited = found.filterValues { it == FilesCheck.Changed }.keys.mapTo(HashSet()) { it.set }
        return Looked(record.copy(sets = record.sets.map { it.copy(edits = edits.getValue(it.key)) }), found.mapValues { (file, check) -> if (check == FilesCheck.Missing && file.set in edited) FilesCheck.Changed else check })
    }

    private suspend fun twinned(entry: GameEntry, image: FileImage): Boolean = (attempt { ports.backups.holds(entry, image) } as? Attempt.Done)?.value == true

    /** Refused when an entry of a loading folder, other than the files at [except] by path, takes one of [numbers]. */
    private suspend fun numberTaken(root: GameRoot, numbers: Iterable<Slot>, except: Set<String>): LibraryProblem? {
        val entries = when (val listing = onListing(root)) {
            is OnListing.Unreadable -> return LibraryProblem.AddonsUnreadable(listing.reason)
            is OnListing.Listed -> listing.entries.filter { it.relativePath !in except }
        }
        for (number in numbers) {
            val sharing = entries.filter { it.number == number }
            if (sharing.isNotEmpty()) return LibraryProblem.NumberTaken(number, sharing.map { it.label }.sorted())
        }
        return null
    }

    /** What [root]'s loading folders hold, with a failure to look at any counted as unreadable. A folder that is not there holds nothing. */
    internal suspend fun onListing(root: GameRoot): OnListing = OnListing.Listed(
        SetFolder.Loading.flatMap { folder ->
            when (val listing = attempt { inventory.list(root, folder.inCitadel) }) {
                is Attempt.Failed -> return OnListing.Unreadable(listing.reason)
                is Attempt.Done -> when (val listed = listing.value) {
                    is FolderListing.Unreadable -> return OnListing.Unreadable(listed.reason)
                    is FolderListing.Listed -> listed.entries.map { OnEntry(folder, it) }
                }
            }
        },
    )

    private suspend fun relocate(source: ManifestRecord, target: ManifestRecord): ChangeOutcome {
        preflight(source, target)?.let { return ChangeOutcome.Refused(it) }
        val linking = PendingChange.Relocate(source, ChangeToken(ids()), RelocateStep.Linking, target)
        save(manifest.copy(pending = linking)).refusal(pending = false)?.let { return it }
        when (val linked = linkTargets(linking)) {
            is Linked.Undo -> return undoLinking(linking).refusingChange(linked.problem)
            is Linked.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(linked.reason), linked.reason)
            Linked.Done -> Unit
        }
        val releasing = linking.copy(step = RelocateStep.Releasing)
        when (val saved = save(manifest.copy(pending = releasing))) {
            SaveResult.Saved -> Unit
            is SaveResult.Refused -> return undoLinking(linking).refusingChange(LibraryProblem.ManifestSave(saved.reason))
            is SaveResult.Unconfirmed -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(saved.reason), saved.reason)
        }
        return releaseSources(releasing)
    }

    private suspend fun preflight(record: ManifestRecord, target: ManifestRecord): LibraryProblem? {
        for ((file, moved) in record.files.zip(target.files)) {
            notHolding(record, file)?.let { return it }
            vacant(record.gameRoot, moved.relativePath(target))?.let { return it }
        }
        return null
    }

    private suspend fun linkTargets(change: PendingChange.Relocate): Linked {
        val names = Relocation(change)
        for (file in change.source.files.sortedBy { it.isDirectory }) {
            val path = file.relativePath(change.source)
            fenced(change.source.gameRoot)?.let { return Linked.Wait(it) }
            linkProblem(names.source(file), names.anchor(file), names.anchor(file).name, path)?.let { return Linked.Undo(it) }
            when (val seen = attempt { ports.installer.inspect(names.anchor(file), names.source(file)) }) {
                is Attempt.Failed -> return Linked.Wait(seen.reason)
                is Attempt.Done -> when (val state = seen.value) {
                    is EntryState.Present -> when {
                        !state.twin -> return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                        state.image != file.image -> return Linked.Undo(LibraryProblem.FileChanged(path))
                    }
                    is EntryState.Unreadable -> return Linked.Wait(state.reason)
                    EntryState.Absent, EntryState.NotPlain -> return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                }
            }
            fenced(change.source.gameRoot)?.let { return Linked.Wait(it) }
            linkProblem(names.anchor(file), names.target(file), names.moved(file).relativePath(change.target), path)?.let { return Linked.Undo(it) }
        }
        return Linked.Done
    }

    private suspend fun linkProblem(existing: GameEntry, created: GameEntry, createdPath: String, sourcePath: String): LibraryProblem? =
        when (val made = attempt { ports.installer.link(existing, created) }) {
            is Attempt.Failed -> LibraryProblem.PlacementFailed(made.reason)
            is Attempt.Done -> when (val result = made.value) {
                LinkResult.Linked -> null
                LinkResult.Taken -> LibraryProblem.NameTaken(createdPath)
                LinkResult.NotThere -> LibraryProblem.FileMissing(sourcePath)
                is LinkResult.Failed -> LibraryProblem.PlacementFailed(result.reason)
            }
        }

    private suspend fun undoLinking(change: PendingChange.Relocate): Undone = change.setMoves().undo(change.sets()).cleared()

    private suspend fun removeTwinOf(entry: GameEntry, witness: GameEntry, image: FileImage): Attempt<Removal?> {
        val state = when (val seen = attempt { ports.installer.inspect(entry, witness) }) {
            is Attempt.Failed -> return seen
            is Attempt.Done -> seen.value
        }
        return when (state) {
            EntryState.Absent, EntryState.NotPlain -> Attempt.Done(null)
            is EntryState.Unreadable -> Attempt.Failed(state.reason)
            is EntryState.Present -> if (!state.twin) Attempt.Done(null) else when (val removed = attempt { ports.installer.removeTwin(entry, witness, image) }) {
                is Attempt.Failed -> removed
                is Attempt.Done -> (removed.value as? Removal.Failed)?.let { Attempt.Failed(it.reason) } ?: removed
            }
        }
    }

    private suspend fun releaseSources(change: PendingChange.Relocate): ChangeOutcome {
        fun waiting(reason: String) = ChangeOutcome.Refused(LibraryProblem.Interrupted(reason), reason)
        suspend fun undo() = save(manifest.copy(pending = change.copy(step = RelocateStep.Linking))).failure?.let(::waiting)
            ?: undoLinking(change).refusingChange(LibraryProblem.PlacementFailed(ChangedWhileMoved))
        val names = Relocation(change)
        for (file in change.source.files) {
            when (val seen = attempt { ports.installer.inspect(names.target(file), names.anchor(file)) }) {
                is Attempt.Failed -> return waiting(seen.reason)
                is Attempt.Done -> when (val state = seen.value) {
                    is EntryState.Present -> if (!state.twin || state.image != file.image) return undo()
                    is EntryState.Unreadable -> return waiting(state.reason)
                    EntryState.Absent, EntryState.NotPlain -> return undo()
                }
            }
        }
        for (file in change.sets().flatten().sortedBy { it.isDirectory }) {
            fenced(change.source.gameRoot)?.let { return waiting(it) }
            when (val removed = attempt { ports.installer.removeTwin(file.source, file.anchor, file.image) }) {
                is Attempt.Failed -> return waiting(removed.reason)
                is Attempt.Done -> when (val result = removed.value) {
                    Removal.Removed, Removal.Absent -> Unit
                    is Removal.Kept -> return undo()
                    is Removal.Failed -> return waiting(result.reason)
                }
            }
        }
        val cleanup = change.copy(step = RelocateStep.Cleanup)
        save(manifest.copy(records = manifest.records.map { if (it == change.source) change.target else it }, pending = cleanup)).failure?.let { return waiting(it) }
        return cleanAnchors(change.setMoves(), change.sets())?.let(::waiting) ?: ChangeOutcome.Done
    }

    private fun PendingChange.Relocate.sets(): List<List<Moved<GameEntry>>> {
        val names = Relocation(this)
        return source.sets.map { set -> source.filesOf(set).map { Moved(names.source(it), names.anchor(it), names.target(it), it.image, it.isDirectory, it.relativePath(source), names.moved(it).relativePath(target), names.anchorPath(it)) } }
    }

    private fun PendingChange.Relocate.setMoves(): SetMoves<GameEntry> = SetMoves(ports.installer) { fenced(source.gameRoot) }

    private class Relocation(private val change: PendingChange.Relocate) {
        private val root = change.source.gameRoot
        private val from = change.source.setFolder()
        private val to = change.target.setFolder()
        private val targets = change.source.files.zip(change.target.files).toMap()

        fun source(file: OwnedFile) = GameEntry(root, from, file.name)

        fun moved(file: OwnedFile): OwnedFile = targets.getValue(file)

        fun target(file: OwnedFile) = GameEntry(root, to, moved(file).name)

        fun anchor(file: OwnedFile) = GameEntry(root, from, change.token.anchorName(file))

        fun anchorPath(file: OwnedFile) = "${change.source.folder()}/${change.token.anchorName(file)}"
    }

    /**
     * Gives the applied book [after], with the mods the user edited where their files are ([ProfileBook.holding]), and
     * places the records of [moves] as their targets have them: through one [PendingChange.Arrange] when a file changes
     * path, else in one save, as when nothing moves or only an off mod's sets change order. Refused with nothing touched
     * when a game cannot be changed now or its preflight fails.
     */
    suspend fun arrange(moves: List<Move>, after: ProfileBook): ChangeOutcome {
        uncertainty?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        val targets = moves.associate { it.source.installId to it.target }
        val records = manifest.records.map { targets[it.installId] ?: it }
        val applied = after.holding(records)
        if (moves.all { it.moving.isEmpty() }) return save(manifest.copy(records = records, profiles = applied)).refusal(pending = false) ?: ChangeOutcome.Done
        val anchoring = PendingChange.Arrange(ChangeToken(ids()), moves, applied, MoveStep.Anchoring)
        for (root in anchoring.gameRoots) fenced(root)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it)) }
        arrangePreflight(anchoring)?.let { return ChangeOutcome.Refused(it) }
        for (root in moves.filter { it.target.enabled && !it.source.enabled }.map { it.source.gameRoot }.distinct()) {
            ensureLoading(root)?.let { return ChangeOutcome.Refused(it) }
        }
        save(manifest.copy(pending = anchoring)).refusal(pending = false)?.let { return it }
        return finishArrange(anchoring)
    }

    private suspend fun arrangePreflight(change: PendingChange.Arrange): LibraryProblem? {
        val freed = change.moves.flatMap { move -> move.moving.flatten().map { (file, _) -> move.source.gameRoot to file.relativePath(move.source) } }.toSet()
        for (move in change.moves) {
            val root = move.source.gameRoot
            for ((file, renamed) in move.moving.flatten()) {
                notHolding(move.source, file)?.let { return it }
                if (root to renamed.relativePath(move.target) !in freed) vacant(root, renamed.relativePath(move.target))?.let { return it }
                vacant(root, "${move.source.folder()}/${change.token.anchorName(file)}")?.let { return it }
            }
        }
        for ((root, moves) in change.moves.groupBy { it.source.gameRoot }) {
            val landing = moves.flatMap { move -> move.moving.mapNotNull { pairs -> move.target.numberOf(move.target.setOf(pairs.first().second)) } }
            if (landing.isEmpty()) continue
            numberTaken(root, landing, except = moves.filter { it.source.enabled }.flatMap { move -> move.source.files.map { it.relativePath(move.source) } }.toSet())?.let { return it }
        }
        return null
    }

    private suspend fun vacant(root: GameRoot, path: String): LibraryProblem? = when (val seen = attempt { inventory.file(root, path) }) {
        is Attempt.Failed -> LibraryProblem.FileUnreadable(path)
        is Attempt.Done -> if (seen.value == FileObservation.Absent) null else LibraryProblem.NameTaken(path)
    }

    private suspend fun finishArrange(change: PendingChange.Arrange): ChangeOutcome {
        var current = change
        if (current.step == MoveStep.Anchoring) {
            when (val anchored = current.setMoves().anchor(current.sets())) {
                is Linked.Undo -> return undoAnchors(current).refusingChange(anchored.problem)
                is Linked.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(anchored.reason), anchored.reason)
                Linked.Done -> Unit
            }
            current = current.copy(step = MoveStep.Releasing)
            when (val saved = save(manifest.copy(pending = current))) {
                SaveResult.Saved -> Unit
                is SaveResult.Refused -> return undoAnchors(change).refusingChange(LibraryProblem.ManifestSave(saved.reason))
                is SaveResult.Unconfirmed -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(saved.reason), saved.reason)
            }
        }
        if (current.step == MoveStep.Releasing) {
            when (val released = current.setMoves().release(current.sets())) {
                is Linked.Undo -> return rollBackArrange(current, released.problem)
                is Linked.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(released.reason), released.reason)
                Linked.Done -> Unit
            }
            current = current.copy(step = MoveStep.Linking)
            save(manifest.copy(pending = current)).refusal(pending = true)?.let { return it }
        }
        if (current.step == MoveStep.Linking) {
            when (val linked = current.setMoves().link(current.sets())) {
                is Linked.Undo -> return rollBackArrange(current, linked.problem)
                is Linked.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(linked.reason), linked.reason)
                Linked.Done -> Unit
            }
            current = current.copy(step = MoveStep.Cleanup)
            val targets = current.moves.associate { it.source.installId to it.target }
            save(manifest.copy(records = manifest.records.map { targets[it.installId] ?: it }, profiles = current.after, pending = current)).refusal(pending = true)?.let { return it }
        }
        check(current.step == MoveStep.Cleanup) { "a rollback is finished by undoArrange" }
        cleanAnchors(current.setMoves(), current.sets())?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        return ChangeOutcome.Done
    }

    private fun PendingChange.Arrange.sets(): List<List<Moved<GameEntry>>> = moves.flatMap { move ->
        val root = move.source.gameRoot
        val from = move.source.setFolder()
        val to = move.target.setFolder()
        move.moving.map { pairs ->
            pairs.map { (file, renamed) ->
                Moved(
                    GameEntry(root, from, file.name), GameEntry(root, from, token.anchorName(file)), GameEntry(root, to, renamed.name),
                    file.image, file.isDirectory, file.relativePath(move.source), renamed.relativePath(move.target), "${move.source.folder()}/${token.anchorName(file)}",
                )
            }
        }
    }

    private fun PendingChange.Arrange.setMoves(): SetMoves<GameEntry> = SetMoves(ports.installer) { gameRoots.firstNotNullOfOrNull { fenced(it) } }

    private suspend fun undoAnchors(change: PendingChange.Arrange): Undone = change.setMoves().dropAnchors(change.sets()).cleared()

    private suspend fun cleanAnchors(moves: SetMoves<GameEntry>, sets: List<List<Moved<GameEntry>>>): String? {
        val cleaned = moves.clean(sets)
        cleaned.interrupted?.let { return it }
        save(manifest.copy(pending = null)).failure?.let { return it }
        report(Preserved(cleaned.leftInPlace))
        return null
    }

    private suspend fun rollBackArrange(change: PendingChange.Arrange, problem: LibraryProblem): ChangeOutcome {
        val rolling = change.copy(step = MoveStep.RollingBack)
        save(manifest.copy(pending = rolling)).refusal(pending = true)?.let { return it }
        return undoArrange(rolling).refusingChange(problem)
    }

    private suspend fun undoArrange(change: PendingChange.Arrange): Undone = change.setMoves().undo(change.sets()).cleared()

    private suspend fun Undone.cleared(): Undone = if (interrupted != null) this else Undone(leftInPlace, save(manifest.copy(pending = null)).failure)

    /** Takes over [plan] through the [PendingChange.Adopt] journal, refused with nothing touched when its preflight fails. */
    suspend fun adopt(plan: AdoptionPlan): ChangeOutcome {
        uncertainty?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        if (manifest.pending != null) return ChangeOutcome.Refused(LibraryProblem.Interrupted(ChangeInFlight))
        fenced(plan.gameRoot)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it)) }
        adoptPreflight(plan)?.let { return ChangeOutcome.Refused(it) }
        if (plan.moving.isEmpty()) return commitAdoption(plan, emptyList())
        val linking = PendingChange.Adopt(plan, AdoptStep.Linking)
        save(manifest.copy(pending = linking)).refusal(pending = false)?.let { return it }
        return finishAdoption(linking)
    }

    private suspend fun adoptPreflight(plan: AdoptionPlan): LibraryProblem? {
        val root = plan.gameRoot
        for (adoption in plan.adopted) {
            for (file in adoption.record.files) {
                val path = CitadelFile(root, file.citadelPath(adoption.record)).gamePath
                if (!adoption.claimed) {
                    vacant(root, path)?.let { return it }
                    continue
                }
                notHolding(adoption.record, file)?.let { return it }
            }
        }
        val enabled = plan.adopted.filter { it.record.enabled }
        if (enabled.isEmpty()) return null
        return numberTaken(root, enabled.flatMap { it.record.numbers }, except = enabled.filter { it.claimed }.flatMap { adoption -> adoption.record.files.map { it.relativePath(adoption.record) } }.toSet())
    }

    private suspend fun finishAdoption(change: PendingChange.Adopt): ChangeOutcome {
        val moves = change.setMoves()
        if (change.step == AdoptStep.Linking) {
            when (val linked = moves.anchor(change.sets().map { set -> set.sortedBy { it.isDirectory } })) {
                is Linked.Undo -> return undoAdoption(change).refusingChange(linked.problem)
                is Linked.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(linked.reason), linked.reason)
                Linked.Done -> Unit
            }
            when (val saved = save(manifest.copy(pending = change.copy(step = AdoptStep.Releasing)))) {
                SaveResult.Saved -> Unit
                is SaveResult.Refused -> return undoAdoption(change).refusingChange(LibraryProblem.ManifestSave(saved.reason))
                is SaveResult.Unconfirmed -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(saved.reason), saved.reason)
            }
        }
        val left = mutableListOf<String>()
        for (set in change.sets()) {
            when (val released = moves.release(listOf(set))) {
                Linked.Done -> Unit
                is Linked.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(released.reason), released.reason)
                is Linked.Undo -> for (file in set) {
                    if ((attempt { ports.gameFiles.inspect(file.source, file.source) } as? Attempt.Done)?.value != EntryState.Absent) left += file.sourcePath
                }
            }
        }
        return commitAdoption(change.plan, left)
    }

    private suspend fun commitAdoption(plan: AdoptionPlan, left: List<String>): ChangeOutcome {
        val choices = SavedChoices(plan.target)
        val journaled = manifest.pending != null
        val committed = manifest.copy(records = manifest.records + plan.adopted.map { it.record }, profiles = plan.book, savedChoices = choices, pending = null).settled()
        save(committed).refusal(pending = journaled)?.let { return it }
        report(Preserved(left))
        return ChangeOutcome.Done
    }

    private suspend fun undoAdoption(change: PendingChange.Adopt): Undone =
        change.setMoves().dropAnchors(change.sets().map { set -> set.sortedByDescending { it.isDirectory } }).cleared()

    private fun PendingChange.Adopt.sets(): List<List<Moved<CitadelFile>>> = plan.moving.flatMap { adoption ->
        val sources = adoption.sources.iterator()
        adoption.record.sets.map { set ->
            adoption.record.filesOf(set).map { file ->
                val source = CitadelFile(plan.gameRoot, sources.next())
                val target = CitadelFile(plan.gameRoot, file.citadelPath(adoption.record))
                Moved(source, target, target, file.image, file.isDirectory, source.gamePath, target.gamePath, target.gamePath)
            }
        }
    }

    private fun PendingChange.Adopt.setMoves(): SetMoves<CitadelFile> = SetMoves(ports.gameFiles) { fenced(plan.gameRoot) }

    /**
     * What making [root]'s Library the same as [backup]'s would change now, read only: the backup's mods, each on or
     * off as its book has it, the ones that are on at the numbers the planner gives its order against the files as they
     * are now and around what else `addons` holds, so a set on now with the same bytes keeps its name. Refused when a
     * record of the backup is of another game folder, when its order does not fit, when a file of the Library now
     * changed or cannot be read, since it would go and no backup can hold it, when a name it takes is not free, or when
     * the backup does not fit the rest of the install list. A file that is missing stays missing.
     */
    suspend fun planRevert(root: GameRoot, backup: LibraryManifest): MutationResult<PendingChange.Revert> {
        uncertainty?.let { return MutationResult.Refused(LibraryProblem.Interrupted(it)) }
        if (backup.records.any { it.gameRoot != root }) return MutationResult.Refused(LibraryProblem.BackupUnreadable("it was made in another game folder"))
        val source = manifest.records.filter { it.gameRoot == root }
        val loading = when (val listing = onListing(root)) {
            is OnListing.Unreadable -> return MutationResult.Refused(LibraryProblem.AddonsUnreadable(listing.reason))
            is OnListing.Listed -> listing.entries
        }
        val placed = planPlacement(backup.records, backup.profiles.activeProfile, foreignNumbers(loading, source), now = source)
        if (placed.pastLimit.isNotEmpty()) return MutationResult.Refused(LibraryProblem.NoRoomInOrder(backup.records.filter { it.enabled }.sumOf { it.sets.size }))
        val target = placed.records
        val planned = try {
            val book = backup.profiles.withOtherGames(root, manifest.profiles, applied = true)
            val wanted = (backup.savedChoices?.target ?: backup.profiles).withOtherGames(root, manifest.chosen(), applied = false)
            PendingChange.Revert(root, source, target, ChangeToken(ids()), ReplaceStep.Staging, book, SavedChoices(wanted).takeIf { wanted != book }, backup.originals, emptySet(), backup.repairChoices)
                .also { manifest.reverted(it, pending = null) }
        } catch (e: IllegalArgumentException) {
            return MutationResult.Refused(LibraryProblem.BackupUnreadable(e.message ?: "it does not fit the install list"))
        }
        val absent = mutableSetOf<String>()
        for (record in source) for (file in record.files) when (val problem = notHolding(record, file)) {
            null -> Unit
            is LibraryProblem.FileMissing -> absent += problem.path
            else -> return MutationResult.Refused(problem)
        }
        val change = planned.copy(absent = absent)
        change.swap().blocked()?.let { return MutationResult.Refused(it.first) }
        val on = source.filter { it.enabled }.flatMap { record -> record.files.map { it.relativePath(record) } }.toSet()
        numberTaken(root, change.target.filter { it.enabled }.flatMap { it.numbers }, except = on)?.let { return MutationResult.Refused(it) }
        return MutationResult.Done(change)
    }

    /**
     * Carries out [change], as [planRevert] made it, through one journal. The retained originals it needs that are not
     * registered are placed first and registered in its first save; one the store cannot give is left out, and the sets
     * made from it keep their files without the record of how. Whatever stops it before the backup's records are saved
     * puts the Library back as it was.
     */
    suspend fun revert(change: PendingChange.Revert): ChangeOutcome {
        uncertainty?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        val root = change.gameRoot
        var placed = change
        for (original in change.originals.filter { original -> manifest.originals.none { it.id == original.id } }) {
            placed = when (val result = attempt { ports.backups.placeOriginal(root, original) }.orElse { PlaceResult.Failed(it) }) {
                PlaceResult.Placed -> placed.copy(registered = placed.registered + original.id)
                PlaceResult.Unavailable -> placed.copy(
                    originals = placed.originals - original,
                    target = placed.target.map { record -> record.copy(sets = record.sets.map { if (it.derivation?.original == original.id) it.copy(derivation = null) else it }) },
                )
                is PlaceResult.Failed -> return ChangeOutcome.Refused(LibraryProblem.PlacementFailed(result.reason))
            }
        }
        if (placed.going.isEmpty() && placed.coming.isEmpty()) return save(manifest.reverted(placed, pending = null)).refusal(pending = false) ?: ChangeOutcome.Done
        fenced(root)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it)) }
        save(manifest.copy(originals = manifest.originals + placed.originals.filter { it.id in placed.registered }, pending = placed)).refusal(pending = false)?.let { return it }
        val swap = placed.swap()
        val missing = mutableSetOf<String>()
        val preserved = mutableListOf<String>()
        for (files in placed.coming.zip(swap.coming).groupBy { (file, _) -> file.record.installId to file.file.set }.values) {
            val staged = mutableListOf<Coming>()
            for ((file, coming) in files) {
                val temporary = coming.temporary ?: continue
                fenced(root)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
                when (val result = attempt { ports.backups.place(coming.image, temporary) }.orElse { PlaceResult.Failed(it) }) {
                    PlaceResult.Placed -> staged += coming
                    PlaceResult.Unavailable -> {
                        swap.dropTemporaries(staged, preserved, needed = false)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
                        missing += file.record.filesOf(file.record.setOf(file.file)).map { it.relativePath(file.record) }
                        break
                    }
                    is PlaceResult.Failed -> return swap.undo(LibraryProblem.PlacementFailed(result.reason)).changed()
                }
            }
        }
        val anchoring = placed.copy(step = ReplaceStep.Anchoring, missing = missing)
        save(manifest.copy(pending = anchoring)).refusal(pending = true)?.let { return it }
        report(Preserved(preserved))
        return anchoring.swap().finish().changed()
    }

    private fun PendingChange.Revert.swap(): FileSwap {
        fun RevertFile.entry(name: String) = GameEntry(gameRoot, record.setFolder(), name)
        val anchors = going.associateWith { it.entry(token.anchorName(it.file)) }
        return FileSwap(
            gameRoot, step,
            going = going.map { Going(it.entry(it.file.name), anchors.getValue(it), it.file.image, it.file.isDirectory, it.path) },
            coming = coming.map { file ->
                val anchor = fed[file]?.let(anchors::getValue)
                val temporary = file.entry(token.temporaryName(file.file))
                Coming(file.entry(file.file.name), anchor ?: temporary, temporary.takeIf { anchor == null }, file.file.image, file.file.isDirectory, file.path)
            },
            at = { copy(step = it) },
            committed = { pending -> manifest.reverted(this, pending) },
            rolledBack = { manifest.copy(originals = manifest.originals.filter { it.id !in registered }, pending = null) },
        )
    }

    private fun LibraryManifest.reverted(change: PendingChange.Revert, pending: PendingChange?) = copy(
        records = records.filter { it.gameRoot != change.gameRoot } + change.target,
        profiles = change.book,
        savedChoices = change.choices,
        originals = originals.filter { kept -> change.originals.none { it.id == kept.id } } + change.originals,
        repairChoices = repairChoices.filterKeys { id -> change.source.none { it.installId == id } } + change.repairChoices,
        pending = pending,
    )

    private val pinned = mutableSetOf<String>()

    fun pin(path: String) {
        pinned += path
    }

    fun unpin(path: String) {
        pinned -= path
    }

    private fun isPinned(path: String) = pinned.any { it.equals(path, ignoreCase = true) }

    fun removable(download: OwnedDownload, except: String?): Boolean {
        val path = download.archive.path
        if (download !in manifest.downloads || isPinned(path)) return false
        val pendingRecords = when (val change = manifest.pending) {
            is PendingChange.Replace -> listOf(change.source, change.target)
            is PendingChange.Install -> listOf(change.record)
            is PendingChange.Relocate -> listOf(change.source)
            is PendingChange.Uninstall -> listOf(change.record)
            is PendingChange.Arrange -> change.committed
            is PendingChange.Adopt -> change.plan.adopted.map { it.record }
            is PendingChange.Revert -> change.source + change.target
            is PendingChange.ConfigEdit, is PendingChange.ArchiveRemoval, null -> emptyList()
        }
        return (manifest.records + pendingRecords).none { record -> record.installId != except && record.sets.any { it.download?.path.equals(path, ignoreCase = true) } }
    }

    fun clearable(): List<OwnedDownload> = manifest.downloads.filter { removable(it, except = null) }

    /** The downloads of [record]'s sets that may go with it: registered, made by Hideout, and named by nothing else. */
    fun deletableDownloads(record: ManifestRecord): List<ArchiveIdentity> = record.sets.mapNotNull { set ->
        manifest.downloads.firstOrNull { it.archive == set.download }?.takeIf { removable(it, except = record.installId) }?.archive
    }.distinct()

    fun discards(): List<Pair<OwnedDownload, String?>> = manifest.downloads
        .filter { it.retention == DownloadRetention.DiscardAfterInstall && !isPinned(it.archive.path) }
        .map { download ->
            val installedInto = manifest.records.singleOrNull { record -> record.sets.any { it.download == download.archive && it.source == InstalledSource.Remote(download.source) } }
            download to installedInto?.installId
        }

    suspend fun uninstall(record: ManifestRecord, archives: List<ArchiveIdentity>): ChangeOutcome {
        uncertainty?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        val anchoring = PendingChange.Uninstall(record, archives, ChangeToken(ids()), UninstallStep.Anchoring)
        save(manifest.copy(pending = anchoring)).refusal(pending = false)?.let { return it }
        return finishUninstall(anchoring)
    }

    suspend fun removeArchive(download: OwnedDownload, installedInto: String?): ArchiveResult {
        uncertainty?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        check(manifest.pending == null) { "a download is deleted on its own only while nothing else is pending" }
        if (manifest.downloads.none { it.source == download.source && it.archive == download.archive }) return ArchiveResult.Unregistered
        if (!removable(download, installedInto)) return ArchiveResult.Kept
        val anchoring = PendingChange.ArchiveRemoval(download, installedInto, ChangeToken(ids()), ArchiveStep.Anchoring)
        save(manifest.copy(pending = anchoring)).refusal(pending = false)?.let { return it }
        return finishArchiveRemoval(anchoring)
    }

    private class Doomed<E>(
        private val links: TwinLinks<E>,
        private val original: E,
        private val anchor: E,
        val image: FileImage,
        val name: String,
        val anchorPath: String,
        val isDirectory: Boolean,
        val set: SetKey?,
    ) {
        suspend fun original(): EntryState = links.inspect(original, anchor)

        suspend fun anchorBeside(): LinkResult = links.link(original, anchor)

        suspend fun removeOriginal(): Removal = links.removeTwin(original, anchor, image)

        suspend fun removeAnchor(needed: Boolean): Removal = links.removeLeftover(anchor, original, image, needed)
    }

    private fun doomed(change: PendingChange.Uninstall): Pair<List<Doomed<*>>, List<Doomed<*>>> {
        val record = change.record
        val folder = record.setFolder()
        val files = record.files.sortedByDescending { it.isDirectory }.map { file ->
            Doomed(
                ports.installer, GameEntry(record.gameRoot, folder, file.name), GameEntry(record.gameRoot, folder, change.token.anchorName(file)),
                file.image, file.name, "${record.folder()}/${change.token.anchorName(file)}", file.isDirectory, file.set,
            )
        }
        val archives = change.archives.map { archive ->
            doomedDownload((record.sets.first { it.download == archive }.source as InstalledSource.Remote).identity, archive, change.token)
        }
        return files to archives
    }

    private fun doomed(change: PendingChange.ArchiveRemoval): Doomed<*> = doomedDownload(change.download.source, change.download.archive, change.token)

    private fun doomedDownload(source: SubmissionIdentity, archive: ArchiveIdentity, token: ChangeToken) = Doomed(
        ports.downloader, DownloadEntry(source, archive), DownloadEntry(source, archive, token), FileImage(archive.sizeBytes, archive.sha256),
        archive.fileName, "${archive.path}$TemporaryInfix${token.value}", isDirectory = false, set = null,
    )

    private suspend fun finishUninstall(change: PendingChange.Uninstall): ChangeOutcome {
        var current = change
        if (current.step == UninstallStep.Anchoring) {
            when (val anchored = anchorAll(current)) {
                is Linked.Undo -> return undoAnchoring(current).refusingChange(anchored.problem)
                is Linked.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(anchored.reason), anchored.reason)
                Linked.Done -> Unit
            }
            current = current.copy(step = UninstallStep.Releasing)
            save(manifest.copy(pending = current)).refusal(pending = true)?.let { return it }
        }
        if (current.step == UninstallStep.Releasing) {
            val settled = when (val released = releaseOriginals(current)) {
                is Freed.Wait -> return ChangeOutcome.Refused(LibraryProblem.Interrupted(released.reason), released.reason)
                is Freed.Settled -> released.step
            }
            current = current.copy(step = settled)
            save(manifest.copy(pending = current)).refusal(pending = true)?.let { return it }
        }
        return settle(current, current.step as UninstallStep.Settled)
    }

    private suspend fun finishArchiveRemoval(change: PendingChange.ArchiveRemoval): ArchiveResult {
        val archive = doomed(change)
        var current = change
        if (current.step == ArchiveStep.Anchoring) {
            if (removable(change.download, change.installedInto)) {
                fenced(null)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
                anchorOne(archive)
            }
            current = current.copy(step = ArchiveStep.Releasing)
            save(manifest.copy(pending = current)).refusal(pending = true)?.let { return it }
        }
        if (current.step == ArchiveStep.Releasing) {
            val outcome = if (removable(change.download, change.installedInto)) {
                fenced(null)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
                releaseArchive(archive)
            } else {
                RemovalOutcome.Preserved(DownloadInUse)
            }
            current = current.copy(step = ArchiveStep.Settled(outcome))
            save(manifest.copy(pending = current)).refusal(pending = true)?.let { return it }
        }
        return settleArchive(current, (current.step as ArchiveStep.Settled).outcome)
    }

    private suspend fun anchorAll(change: PendingChange.Uninstall): Linked {
        val (files, archives) = doomed(change)
        val directoriesStaying = mutableSetOf<SetKey?>()
        for (file in files) {
            if (file.set in directoriesStaying) continue
            fenced(change.record.gameRoot)?.let { return Linked.Wait(it) }
            when (val anchored = anchorOne(file)) {
                is Anchored.Wait -> return Linked.Wait(anchored.reason)
                is Anchored.Undo -> return Linked.Undo(anchored.problem)
                Anchored.Done, Anchored.Gone -> Unit
                Anchored.Stays -> if (file.isDirectory) directoriesStaying += file.set
            }
        }
        val deletable = deletableDownloads(change.record)
        for ((archive, download) in change.archives.zip(archives)) {
            if (archive !in deletable) continue
            fenced(change.record.gameRoot)?.let { return Linked.Wait(it) }
            anchorOne(download)
        }
        return Linked.Done
    }

    private sealed interface Anchored {
        data object Done : Anchored

        data object Gone : Anchored

        data object Stays : Anchored

        data class Undo(val problem: LibraryProblem) : Anchored

        data class Wait(val reason: String) : Anchored
    }

    private suspend fun anchorOne(file: Doomed<*>): Anchored {
        when (val seen = attempt { file.original() }) {
            is Attempt.Failed -> return Anchored.Wait(seen.reason)
            is Attempt.Done -> when (val state = seen.value) {
                EntryState.Absent -> return Anchored.Gone
                EntryState.NotPlain -> return Anchored.Stays
                is EntryState.Unreadable -> return Anchored.Wait(state.reason)
                is EntryState.Present -> when {
                    state.image != file.image -> return Anchored.Stays
                    state.twin -> return Anchored.Done
                }
            }
        }
        return when (val made = attempt { file.anchorBeside() }) {
            is Attempt.Failed -> Anchored.Undo(LibraryProblem.PlacementFailed(made.reason))
            is Attempt.Done -> when (val result = made.value) {
                LinkResult.Linked -> Anchored.Done
                LinkResult.Taken -> Anchored.Stays
                LinkResult.NotThere -> Anchored.Gone
                is LinkResult.Failed -> Anchored.Undo(LibraryProblem.PlacementFailed(result.reason))
            }
        }
    }

    private suspend fun undoAnchoring(change: PendingChange.Uninstall): Undone {
        val (files, archives) = doomed(change)
        val left = mutableListOf<String>()
        for (file in files + archives) {
            fenced(change.record.gameRoot)?.let { return Undone(left, it) }
            when (val removed = attempt { file.removeAnchor(needed = true) }) {
                is Attempt.Failed -> return Undone(left, removed.reason)
                is Attempt.Done -> when (val result = removed.value) {
                    Removal.Removed, Removal.Absent -> Unit
                    is Removal.Kept -> left += file.anchorPath
                    is Removal.Failed -> return Undone(left, result.reason)
                }
            }
        }
        return Undone(left, save(manifest.copy(pending = null)).failure)
    }

    private sealed interface Freed {
        data class Settled(val step: UninstallStep.Settled) : Freed

        data class Wait(val reason: String) : Freed
    }

    private suspend fun releaseOriginals(change: PendingChange.Uninstall): Freed {
        val (files, archives) = doomed(change)
        val outcomes = mutableMapOf<String, RemovalOutcome>()
        val directoriesKept = mutableSetOf<SetKey?>()
        for (file in files) {
            val outcome = if (file.set in directoriesKept) {
                when (val seen = attempt { file.original() }) {
                    is Attempt.Failed -> return Freed.Wait(seen.reason)
                    is Attempt.Done -> if (seen.value == EntryState.Absent) RemovalOutcome.Absent else RemovalOutcome.Preserved(KeptWithDirectory)
                }
            } else {
                fenced(change.record.gameRoot)?.let { return Freed.Wait(it) }
                when (val released = releaseOne(file)) {
                    is Attempt.Failed -> return Freed.Wait(released.reason)
                    is Attempt.Done -> released.value
                }
            }
            outcomes[file.name] = outcome
            if (file.isDirectory && outcome is RemovalOutcome.Preserved) directoriesKept += file.set
        }
        val deletable = deletableDownloads(change.record)
        val archiveOutcomes = mutableMapOf<String, RemovalOutcome>()
        for ((archive, download) in change.archives.zip(archives)) {
            archiveOutcomes[archive.path] = if (archive !in deletable) {
                RemovalOutcome.Preserved(DownloadInUse)
            } else {
                fenced(change.record.gameRoot)?.let { return Freed.Wait(it) }
                releaseArchive(download)
            }
        }
        return Freed.Settled(UninstallStep.Settled(outcomes, archiveOutcomes))
    }

    private suspend fun releaseOne(file: Doomed<*>): Attempt<RemovalOutcome> = when (val removed = attempt { file.removeOriginal() }) {
        is Attempt.Failed -> removed
        is Attempt.Done -> when (val result = removed.value) {
            Removal.Removed -> Attempt.Done(RemovalOutcome.Removed)
            Removal.Absent -> Attempt.Done(RemovalOutcome.Absent)
            is Removal.Kept -> Attempt.Done(RemovalOutcome.Preserved(result.reason))
            is Removal.Failed -> Attempt.Failed(result.reason)
        }
    }

    private suspend fun releaseArchive(archive: Doomed<*>): RemovalOutcome = when (val released = releaseOne(archive)) {
        is Attempt.Failed -> RemovalOutcome.Preserved(released.reason)
        is Attempt.Done -> released.value
    }

    private suspend fun releaseAnchors(doomed: List<Doomed<*>>, root: GameRoot?, left: MutableList<String>): String? {
        for (file in doomed) {
            fenced(root)?.let { return it }
            val removed = (attempt { file.removeAnchor(needed = false) } as? Attempt.Done)?.value
            if (removed != Removal.Removed && removed != Removal.Absent) left += file.anchorPath
        }
        return null
    }

    private suspend fun settle(change: PendingChange.Uninstall, settled: UninstallStep.Settled): ChangeOutcome {
        val (files, archives) = doomed(change)
        val left = settled.files.entries.mapNotNull { (name, outcome) ->
            (outcome as? RemovalOutcome.Preserved)?.let { change.record.files.first { it.name == name }.relativePath(change.record) }
        }.toMutableList()
        left += settled.archives.filterValues { it is RemovalOutcome.Preserved }.keys
        releaseAnchors(files + archives, change.record.gameRoot, left)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        val gone = settled.archives.filterValues { it == RemovalOutcome.Removed || it == RemovalOutcome.Absent }.keys
        val next = manifest.copy(
            records = manifest.records.filter { it.installId != change.record.installId },
            repairChoices = manifest.repairChoices - change.record.installId,
            profiles = manifest.profiles.without(setOf(change.record.installId)),
            savedChoices = manifest.savedChoices?.let { it.copy(target = it.target.without(setOf(change.record.installId))) },
            downloads = manifest.downloads.filter { it.archive.path !in gone || it.archive !in change.archives },
            pending = null,
        ).settled()
        save(next).refusal(pending = true)?.let { return it }
        report(Preserved(left))
        return ChangeOutcome.Done
    }

    private suspend fun settleArchive(change: PendingChange.ArchiveRemoval, outcome: RemovalOutcome): ArchiveResult {
        val left = mutableListOf<String>()
        if (outcome is RemovalOutcome.Preserved) left += change.download.archive.path
        releaseAnchors(listOf(doomed(change)), root = null, left)?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        val gone = outcome == RemovalOutcome.Removed || outcome == RemovalOutcome.Absent
        val downloads = if (gone) {
            manifest.downloads - change.download
        } else {
            manifest.downloads.map { if (it == change.download) it.copy(retention = DownloadRetention.Keep) else it }
        }
        save(manifest.copy(downloads = downloads, pending = null)).refusal(pending = true)?.let { return it }
        report(Preserved(left))
        return ArchiveResult.Settled(outcome)
    }

    private fun SaveResult.refusal(pending: Boolean): ChangeOutcome.Refused? = when (this) {
        SaveResult.Saved -> null
        is SaveResult.Refused -> ChangeOutcome.Refused(LibraryProblem.ManifestSave(reason), reason.takeIf { pending })
        is SaveResult.Unconfirmed -> ChangeOutcome.Refused(LibraryProblem.Interrupted(reason), reason)
    }

    private fun Undone.refusingChange(problem: LibraryProblem): ChangeOutcome {
        if (interrupted == null) report(Preserved(leftInPlace))
        return ChangeOutcome.Refused(problem, interrupted)
    }

    private fun String?.waiting(): Recovery? = this?.let { Recovery.Waiting(it) }

    private suspend fun fenced(root: GameRoot?, gameMayRun: Boolean = false): String? = uncertainty ?: root?.let { fence(it, gameMayRun) }

    private fun report(preserved: Preserved) {
        if (preserved.files.isNotEmpty() || preserved.conflict != null) preserve(preserved)
    }

    private fun LibraryManifest.withReceipt(receipt: GameConfigReceipt) = copy(gameConfigs = gameConfigs.filter { it.gameRoot != receipt.gameRoot } + receipt)
}

private val GameEntry.path: String get() = "${folder.path}/$name"

/** The `_dir` file of the set [file] belongs to. */
internal fun ManifestRecord.directoryOf(file: OwnedFile): OwnedFile = filesOf(setOf(file)).first()

private const val Uninspected = "the files of an interrupted install could not be inspected"

private const val Unread = "the files of an interrupted install could not be read"

private const val ChangedWhilePlaced = "a placed file was changed, replaced or removed before the install was recorded"

private const val ReplacementUndone = "an interrupted change of files was undone"

private const val ReplacementAnchoring = "a file that goes could not be kept beside itself while the files changed"

private const val ReplacementPublishing = "a new file could not be placed"

private const val ReplacementUnverified = "the changed files could not be verified"

private const val KeptWithDirectory = "its set's directory file stayed, so its chunks stay with it"

private const val DownloadInUse = "another install uses this download"

private const val SettlingRemoval = "deleting a download is waiting to be finished"

private const val NotRead = "the install list has not been read yet"

private const val ChangeInFlight = "an interrupted change is waiting to be finished or undone"

internal sealed interface Attempt<out T> {
    data class Done<T>(val value: T) : Attempt<T>

    data class Failed(val reason: String) : Attempt<Nothing>
}

internal suspend fun <T> attempt(block: suspend () -> T): Attempt<T> = try {
    Attempt.Done(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Attempt.Failed(e.message ?: e::class.simpleName ?: "failed")
}

internal inline fun <T> Attempt<T>.orElse(failed: (String) -> T): T = when (this) {
    is Attempt.Done -> value
    is Attempt.Failed -> failed(reason)
}
