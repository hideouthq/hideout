package gg.hideouthq.core.migration

import gg.hideouthq.core.analytics.*

import gg.hideouthq.core.crosshair.CrosshairLines
import gg.hideouthq.core.crosshair.CrosshairOrigin
import gg.hideouthq.core.crosshair.Crosshairs
import gg.hideouthq.core.crosshair.YoursChange
import gg.hideouthq.core.data.BundleOpen
import gg.hideouthq.core.data.BundleVpk
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.ManagerData
import gg.hideouthq.core.data.MigrationRecords
import gg.hideouthq.core.data.RecordWrite
import gg.hideouthq.core.library.Adoption
import gg.hideouthq.core.library.AutoexecTakeover
import gg.hideouthq.core.library.BundleCopy
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.ImportProblem
import gg.hideouthq.core.library.ImportSummary
import gg.hideouthq.core.library.ImportWait
import gg.hideouthq.core.library.Incoming
import gg.hideouthq.core.library.KeptFile
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.MigrationId
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.Takeover
import gg.hideouthq.core.library.TakeoverOutcome
import gg.hideouthq.core.library.gateOf
import gg.hideouthq.core.library.matched
import gg.hideouthq.core.library.placeTakeover
import gg.hideouthq.core.library.randomId
import gg.hideouthq.core.library.reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Where an import comes from: a manager installed on this computer, or a bundle one exported. */
sealed interface ImportSource {
    /** [dataFolder] is the folder the user pointed at for its data, or null for where it usually keeps it. */
    data class Installed(val manager: Manager, val dataFolder: String? = null) : ImportSource

    /** A deadlock-mod-interchange v1 bundle: its folder, or its `mod-interchange.json`. */
    data class Bundle(val folder: String) : ImportSource
}

data class FoundSource(val manager: Manager, val found: Boolean, val inGame: Boolean = false)

/** Everything the import screens show, from the one owner of imports. Game, Deadlock and manager activity are [LibraryState]'s. */
data class MigrationState(
    /** Every manager Hideout imports from, those whose files are in the game first, then those found; empty until [Migration.start] has looked. */
    val sources: List<FoundSource> = emptyList(),
    val previews: Map<ImportSource, Preview> = emptyMap(),
    /** The one import running, or ended and not yet dismissed. */
    val run: ImportRun? = null,
)

/**
 * What importing a source would bring now, every count verified on disk rather than taken from the source's word, and
 * leaving out a profile or crosshair Hideout has already.
 */
sealed interface Preview {
    data object Looking : Preview

    data class Ready(
        /** The source's own mods. */
        val mods: List<PreviewMod>,
        /** The profiles that would be made. */
        val profiles: List<Profile>,
        val crosshair: List<String>,
        val commands: List<String>,
        /** How many mods the source lists that have no files to bring. */
        val missing: Int = 0,
        /** Files in the game the source does not list, which come along with its mods. */
        val found: List<PreviewMod> = emptyList(),
    ) : Preview

    data class Failed(val problem: ImportProblem) : Preview
}

/** A mod that would come: its title, the name of the file it comes from, and whether it comes in on. */
data class PreviewMod(val name: String, val file: String, val enabled: Boolean)

/** What to bring. Profiles cannot come without the mods they hold, so that choice does not exist. */
data class ImportChoice(val mods: ModsChoice, val crosshair: Boolean, val commands: Boolean) {
    companion object {
        val Everything = ImportChoice(ModsChoice.ModsAndProfiles, crosshair = true, commands = true)
    }
}

enum class ModsChoice { None, Mods, ModsAndProfiles }

data class ImportRun(val source: ImportSource, val choice: ImportChoice, val phase: ImportPhase) {
    val running: Boolean get() = phase !is ImportPhase.Finished && phase !is ImportPhase.Stopped
}

/** The one import's phases, in the order they run; the screens' step lists derive from it. */
sealed interface ImportPhase {
    /** Copying `gameinfo.gi`, `autoexec.cfg` and the manager's manifests into a new record before anything changes. */
    data object BackingUp : ImportPhase

    /** Reading the source and the game again; the record gains every file that may move. Nothing changed yet. */
    data object Reading : ImportPhase

    /**
     * Copying a bundle's files in and moving files into Hideout's names, committing records and profiles in one save,
     * each mod as its source names it; the library finds the local ones on GameBanana afterwards.
     */
    data object Placing : ImportPhase

    /** `gameinfo.gi`, `autoexec.cfg` and the manager's manifests. */
    data object Configuring : ImportPhase

    /** Deadlock or the manager imported from is open, or may be; it goes on by itself once both are closed. */
    data class Waiting(val wait: ImportWait) : ImportPhase

    data class Finished(val summary: ImportSummary) : ImportPhase

    /** Stopped at [problem]; whatever committed before stays, and importing again finishes it. */
    data class Stopped(val problem: ImportProblem, val summary: ImportSummary?) : ImportPhase
}

/**
 * Takes another manager's mods, profiles, crosshair and commands into Hideout. It finds the managers on this
 * computer, previews what each would bring, and runs one import at a time in [scope], the app's, so leaving a screen
 * never stops one. Nothing it does waits on GameBanana: mods come by the names and GameBanana files or submissions their
 * source records, and [ModLibrary] finds the rest afterwards. It never writes the game: every change goes through
 * [ModLibrary.takeOver], under the library's lock.
 * A manager's crosshair joins Yours through [crosshairs], not in use; its lines in autoexec.cfg stay as they are.
 * Before anything changes, the record in [records] keeps byte copies of the files an import may change; an import
 * that did not end runs again from Reading at the next [start], with no new backup.
 */
class Migration(
    private val library: ModLibrary,
    private val inventory: GameInventory,
    private val managers: ManagerData,
    private val records: MigrationRecords,
    private val crosshairs: Crosshairs,
    private val scope: CoroutineScope,
    private val ids: () -> String = ::randomId,
    private val clock: Clock = Clock.System,
    private val log: (String) -> Unit = {},
    private val analytics: UsageAnalytics? = null,
) {
    private val mutableState = MutableStateFlow(MigrationState())
    val state: StateFlow<MigrationState> = mutableState.asStateFlow()

    private val runLock = Mutex()
    private val looks = mutableMapOf<ImportSource, Job>()

    private val surveys = MutableStateFlow<Map<GameRoot, GameSurvey>>(emptyMap())

    private val starting = scope.launch(start = CoroutineStart.LAZY) {
        val settled = library.state.first { state -> state.library.let { it is LibraryStatus.Ready && it.interrupted == null } && state.game !is GameStatus.Searching }
        records.all().values.mapNotNull(MigrationRecordCodec::decode).forEach(::tookOver)
        detect(settled)
        resume()
    }

    private fun tookOver(record: MigrationRecord) {
        val manager = (record.source as? ImportSource.Installed)?.manager ?: return
        if (record.outcome is RecordOutcome.Finished && record.choice.mods != ModsChoice.None) library.importedFrom(record.root, manager)
    }

    /** Once the library is read and nothing it changed waits to be finished: finds the managers, then runs an import a crash left unfinished. */
    fun start() {
        starting.start()
    }

    /** Reads [source] and surveys the game into a preview of what importing it would bring now. A newer look replaces an older one. */
    fun look(source: ImportSource) {
        looks.remove(source)?.cancel()
        mutableState.update { it.copy(previews = it.previews + (source to Preview.Looking)) }
        looks[source] = scope.launch {
            val preview = preview(source)
            mutableState.update { it.copy(previews = it.previews + (source to preview)) }
        }
    }

    /** Imports [choice] from [source], unless an import runs already. */
    fun import(source: ImportSource, choice: ImportChoice) {
        if (state.value.run?.running == true) return
        mutableState.update { it.copy(run = ImportRun(source, choice, ImportPhase.BackingUp)) }
        scope.launch { runLock.withLock { run(source, choice, resumed = null) } }
    }

    /** Forgets an ended import's result; a running one stays. */
    fun dismiss() {
        mutableState.update { state -> if (state.run?.running == false) state.copy(run = null) else state }
    }

    private suspend fun detect(library: LibraryState) {
        val root = (library.game as? GameStatus.Ready)?.root
        val citadel = root?.let { (inventory.list(it, "") as? FolderListing.Listed)?.names }.orEmpty()
        val addons = root?.let { (inventory.list(it, CitadelPath.Addons.value) as? FolderListing.Listed)?.names }.orEmpty()
        val inGame = mapOf(
            Manager.DeadlockModManager to (DmmManifestName in addons),
            Manager.Grimoire to (citadel.any { name -> CitadelPath.parse(name)?.let(ForeignRoot::of) == ForeignRoot.Priority } || ".disabled" in addons),
        )
        val found = mapOf(
            Manager.DeadlockModManager to (managers.dmmState() != null || inGame.getValue(Manager.DeadlockModManager)),
            Manager.Grimoire to (managers.grimoire(null) != null),
        )
        val sources = Manager.entries.map { FoundSource(it, found.getValue(it), inGame.getValue(it)) }.sortedWith(compareBy({ !it.inGame }, { !it.found }))
        mutableState.update { state -> state.copy(sources = sources) }
    }

    private suspend fun resume() {
        val unfinished = records.all().values.mapNotNull(MigrationRecordCodec::decode).filter { it.outcome == null }.maxByOrNull { it.startedAt } ?: return
        if (state.value.run?.running == true) return
        mutableState.update { it.copy(run = ImportRun(unfinished.source, unfinished.choice, ImportPhase.Reading)) }
        runLock.withLock { run(unfinished.source, unfinished.choice, unfinished) }
    }

    private suspend fun preview(source: ImportSource): Preview {
        val (root, held) = library.state.value.let { gameOf(it) ?: return Preview.Failed(unavailable(it)) }
        val survey = when (val read = survey(root, held)) {
            is SurveyRead.Unreadable -> return Preview.Failed(ImportProblem.GameUnreadable(read.reason))
            is SurveyRead.Read -> read.survey
        }
        val read = when (val found = read(source, survey)) {
            is Read.Refused -> return Preview.Failed(found.problem)
            is Read.Found -> found
        }
        val assessment = assess(read.library, survey, held, read.bundle, others(source, survey))
        val takeover = takeover(MigrationId("preview"), source, root, read.library, survey, assessment, ImportChoice.Everything, emptyMap())
        val placed = placeTakeover(takeover, library.manifest, survey.addons, assessment.copies.indices.map { "copy-$it" }, ids)
        val on = placed.plan.target.activeProfile.enabled
        val there = library.manifest.profiles.profiles.map { it.id }.toSet()
        val crosshair = CrosshairLines.read(assessment.autoexec.crosshair).crosshair
        val (own, found) = placed.plan.adopted.zip(assessment.prospects).partition { (_, prospect) -> prospect.fromSource }
        fun shown(adopted: List<Pair<Adoption, Prospect>>) = adopted.map { (it, _) -> PreviewMod(it.record.title, it.sources.first().name, it.record.installId in on) }
        return Preview.Ready(
            mods = shown(own) + takeover.copies.map { PreviewMod(it.title, it.fileName, it.enabled) },
            profiles = placed.profiles.map { name -> (placed.plan.book.profiles + placed.plan.target.profiles).first { it.name == name } }.filter { it.id !in there },
            crosshair = assessment.autoexec.crosshair.takeIf { crosshairs.state.value.yours.entries.none { it.crosshair == crosshair } }.orEmpty(),
            commands = assessment.autoexec.commands,
            missing = assessment.missing,
            found = shown(found),
        )
    }

    private suspend fun run(source: ImportSource, choice: ImportChoice, resumed: MigrationRecord?) {
        val usageSource = when (source) {
            is ImportSource.Installed -> when (source.manager) { Manager.DeadlockModManager -> UsageSource.DeadlockModManager; Manager.Grimoire -> UsageSource.Grimoire }
            is ImportSource.Bundle -> UsageSource.Bundle
        }
        val usage = analytics?.begin(UsageOperation.ManagerImport, usageSource)
        fun phase(phase: ImportPhase) {
            if (phase is ImportPhase.Finished) usage?.finish(UsageOutcome.Completed, UsageStage.Import)
            if (phase is ImportPhase.Stopped) usage?.finish(UsageOutcome.Failed, UsageStage.Import, UsageFailure.Other)
            if (phase is ImportPhase.Stopped) log("Importing from $source stopped: ${phase.problem}")
            if (phase is ImportPhase.Finished) log("Imported from $source: ${phase.summary}")
            mutableState.update { it.copy(run = ImportRun(source, choice, phase)) }
        }
        try {
            val backup = resumed ?: run {
                phase(ImportPhase.BackingUp)
                when (val backed = backUp(source, choice)) {
                    is Backup.Taken -> backed.record
                    is Backup.Refused -> return phase(ImportPhase.Stopped(backed.problem, null))
                }
            }
            var record = backup
            suspend fun stop(problem: ImportProblem) {
                phase(ImportPhase.Stopped(problem, null))
                records.update(record.id, MigrationRecordCodec.encode(record.copy(outcome = RecordOutcome.Stopped(problem.recordedReason()))))
            }
            while (true) {
                phase(ImportPhase.Reading)
                val (root, held) = library.state.value.let { state -> gameOf(state)?.takeIf { it.first == record.root } ?: return stop(unavailable(state)) }
                val survey = when (val read = survey(root, held)) {
                    is SurveyRead.Unreadable -> return stop(ImportProblem.GameUnreadable(read.reason))
                    is SurveyRead.Read -> read.survey
                }
                val read = when (val found = read(source, survey)) {
                    is Read.Refused -> return stop(found.problem)
                    is Read.Found -> found
                }
                val assessment = assess(read.library, survey, held, read.bundle, others(source, survey))
                val mods = choice.mods != ModsChoice.None
                if (mods) record = record.copy(adopted = record.adopted + (assessment.prospects.flatMap { prospect -> prospect.sets.flatMap { it.files } } + assessment.duplicates).map { it.path to it.image })
                (records.update(record.id, MigrationRecordCodec.encode(record)) as? RecordWrite.Failed)?.let { return stop(ImportProblem.BackupFailed(it.reason)) }

                val takeover = takeover(record.id, source, root, read.library, survey, assessment, choice, record.copies)
                val manager = takeover.manager
                if (gateOf(library.state.value, manager) != null) {
                    awaitGate(manager) { phase(ImportPhase.Waiting(it)) }
                    continue
                }
                phase(ImportPhase.Placing)
                when (val outcome = library.takeOver(takeover) { phase(ImportPhase.Configuring) }) {
                    is TakeoverOutcome.Done -> {
                        val crosshair = CrosshairLines.read(assessment.autoexec.crosshair).takeIf { choice.crosshair && it.set.isNotEmpty() }?.crosshair
                        val origin = crosshair?.let { if (source is ImportSource.Installed) CrosshairOrigin.FromManager(source.manager, it) else CrosshairOrigin.Imported(it) }
                        val adopted = origin?.let { crosshairs.changeWhenRead(YoursChange.Adopt(it)) } != null
                        val summary = ImportSummary(outcome.on, outcome.off, outcome.profiles, adopted, outcome.commands)
                        val finished = record.copy(outcome = RecordOutcome.Finished(summary))
                        records.update(record.id, MigrationRecordCodec.encode(finished))
                        tookOver(finished)
                        return phase(ImportPhase.Finished(summary))
                    }
                    is TakeoverOutcome.Waiting -> {
                        phase(ImportPhase.Waiting(outcome.wait))
                        awaitGate(manager) { phase(ImportPhase.Waiting(it)) }
                    }
                    is TakeoverOutcome.Refused -> return stop(outcome.problem)
                }
            }
        } catch (e: CancellationException) {
            usage?.finish(UsageOutcome.Cancelled, UsageStage.Import)
            throw e
        } catch (e: Exception) {
            usage?.finish(UsageOutcome.Failed, UsageStage.Import, UsageFailure.Other)
            throw e
        }
    }

    private suspend fun awaitGate(manager: Manager?, waiting: (ImportWait) -> Unit) {
        library.state.map { gateOf(it, manager) }.distinctUntilChanged().first { wait -> wait?.let(waiting); wait == null }
    }

    private sealed interface Backup {
        data class Taken(val record: MigrationRecord) : Backup

        data class Refused(val problem: ImportProblem) : Backup
    }

    private suspend fun backUp(source: ImportSource, choice: ImportChoice): Backup {
        val (root, held) = library.state.value.let { gameOf(it) ?: return Backup.Refused(unavailable(it)) }
        val survey = when (val read = survey(root, held)) {
            is SurveyRead.Unreadable -> return Backup.Refused(ImportProblem.GameUnreadable(read.reason))
            is SurveyRead.Read -> read.survey
        }
        val copies = listOfNotNull(survey.gameInfo?.let { ConfigTarget.GameInfo.Path to it }, survey.autoexec?.let { ConfigTarget.Autoexec.path to it }).toMap() + survey.manifests
        val record = MigrationRecord(MigrationId(ids()), source, choice, root, clock.now().toEpochMilliseconds(), copies.mapValues { Sha256.image(it.value) })
        return when (val written = records.begin(record.id, MigrationRecordCodec.encode(record), copies)) {
            RecordWrite.Written -> Backup.Taken(record)
            is RecordWrite.Failed -> Backup.Refused(ImportProblem.BackupFailed(written.reason))
        }
    }

    private fun takeover(
        id: MigrationId,
        source: ImportSource,
        root: GameRoot,
        foreign: ForeignLibrary,
        survey: GameSurvey,
        assessment: Assessment,
        choice: ImportChoice,
        copies: Map<CitadelPath, FileImage>,
    ): Takeover {
        val mods = choice.mods != ModsChoice.None
        val incoming = if (!mods) emptyList() else assessment.prospects.map { prospect ->
            Incoming(prospect.mod.title ?: fileTitle(prospect.fileName), prospect.mod.matched(prospect.sets.size), prospect.fileName, prospect.position, prospect.sets)
        }
        val copied = if (!mods) emptyList() else assessment.copies.map { copy ->
            BundleCopy(copy.mod.title ?: fileTitle(copy.fileName), copy.mod.matched(copy.sets), copy.fileName, copy.enabled, copy.files)
        }
        val sections = foreign.footprint.sections
        return Takeover(
            migration = id,
            root = root,
            manager = (source as? ImportSource.Installed)?.manager,
            addonsMount = survey.mounts.indexOfFirst { it.value.equals(CitadelPath.Addons.value, ignoreCase = true) }.takeIf { it >= 0 },
            incoming = incoming,
            copies = copied,
            profiles = if (choice.mods == ModsChoice.ModsAndProfiles) assessment.profiles else emptyList(),
            liveProfileName = assessment.liveProfileName.takeIf { choice.mods == ModsChoice.ModsAndProfiles },
            unmount = if (mods) foreign.footprint.roots.toSet() else emptySet(),
            autoexec = when {
                !choice.commands || assessment.autoexec.commands.isEmpty() -> null
                sections.isNotEmpty() -> AutoexecTakeover.Sections(sections)
                else -> AutoexecTakeover.Lines(assessment.autoexec.commands)
            },
            duplicates = if (mods) assessment.duplicates.map { KeptFile(it.path, it.image) } else emptyList(),
            manifests = if (mods) assessment.manifests.mapNotNull { path -> copies[path]?.let { KeptFile(path, it) } } else emptyList(),
        )
    }

    private sealed interface Read {
        data class Found(val library: ForeignLibrary, val bundle: Map<String, BundleVpk>) : Read

        data class Refused(val problem: ImportProblem) : Read
    }

    private suspend fun read(source: ImportSource, survey: GameSurvey): Read = when (source) {
        is ImportSource.Installed -> when (source.manager) {
            Manager.DeadlockModManager -> {
                val state = managers.dmmState()
                if (state == null && survey.manifests.isEmpty()) Read.Refused(ImportProblem.SourceUnreadable("Deadlock Mod Manager keeps nothing on this computer"))
                else Read.Found(DmmReader.read(state, survey.manifests, survey.autoexec).let { if (survey.manifests.isEmpty()) it.copy(footprint = it.footprint.copy(roots = emptyList())) else it }, emptyMap())
            }
            Manager.Grimoire -> {
                val files = managers.grimoire(source.dataFolder)
                if (files == null) Read.Refused(ImportProblem.SourceUnreadable(source.dataFolder?.let { "$it holds none of Grimoire's files" } ?: "Grimoire keeps nothing on this computer"))
                else Read.Found(GrimoireReader.read(files.metadata, files.profiles, survey.autoexec), emptyMap())
            }
        }
        is ImportSource.Bundle -> when (val opened = managers.bundle(source.folder)) {
            is BundleOpen.Missing -> Read.Refused(ImportProblem.NotABundle(opened.reason))
            is BundleOpen.Found -> when (val parsed = BundleReader.read(opened.manifest)) {
                BundleRead.Refused -> Read.Refused(ImportProblem.NotABundle("its mod-interchange.json is not a version 1 export"))
                is BundleRead.Read -> {
                    val paths = parsed.library.mods.flatMap { mod -> mod.matchers.filterIsInstance<SetMatcher.Bundled>().flatMap { bundled -> bundled.files.map { it.path } } }.distinct()
                    Read.Found(parsed.library, paths.mapNotNull { path -> managers.bundleVpk(opened.folder, path)?.let { path to it } }.toMap())
                }
            }
        }
    }

    /** The other managers on this computer, whose word names their files that come along with [source]'s. */
    private suspend fun others(source: ImportSource, survey: GameSurvey): List<ForeignLibrary> {
        if (source !is ImportSource.Installed) return emptyList()
        return Manager.entries.filter { it != source.manager }.mapNotNull { (read(ImportSource.Installed(it), survey) as? Read.Found)?.library }
    }

    private suspend fun survey(root: GameRoot, held: List<ManifestRecord>): SurveyRead =
        surveyGame(inventory, root, held, surveys.value[root]).also { read -> if (read is SurveyRead.Read) surveys.update { it + (root to read.survey) } }
}

/** What a mod no manager names is called: its file's name without `_dir.vpk`, as `pak03` or `my_skin`. */
internal fun fileTitle(fileName: String): String = fileName.removeSuffix(".vpk").removeSuffix("_dir").ifBlank { fileName }

private fun ImportProblem.recordedReason(): String = when (this) {
    ImportProblem.GameMissing -> "the game folder is not available"
    is ImportProblem.LibraryBusy -> reason ?: "the library cannot take a change now"
    is ImportProblem.SourceUnreadable -> reason
    is ImportProblem.NotABundle -> reason
    is ImportProblem.GameUnreadable -> reason
    is ImportProblem.BackupFailed -> reason
    is ImportProblem.Library -> problem.reason()
}

private fun gameOf(state: LibraryState): Pair<GameRoot, List<ManifestRecord>>? {
    val root = (state.game as? GameStatus.Ready)?.root ?: return null
    val library = state.library as? LibraryStatus.Ready ?: return null
    return root to library.records
}

private fun unavailable(state: LibraryState): ImportProblem = when (val library = state.library) {
    is LibraryStatus.Ready -> ImportProblem.GameMissing
    is LibraryStatus.Blocked -> ImportProblem.LibraryBusy(library.reason)
    LibraryStatus.Loading -> ImportProblem.LibraryBusy(null)
}
