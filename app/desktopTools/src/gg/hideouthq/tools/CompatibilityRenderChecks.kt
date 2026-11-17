package gg.hideouthq.tools

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeCompatibility
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.CompatFinding
import gg.hideouthq.core.library.CompatLabel
import gg.hideouthq.core.library.FindingKind
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.InstallCompatibility
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.PakNumber
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.RepairWork
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/compatibility")
    val checks = listOf<Pair<String, () -> Unit>>(
        "bulk_progress" to { checkBulkRepairShowsProgress(out, "bulk_progress", widthDp = 1440, heightDp = 900, cards = false) },
        "bulk_progress_compact" to { checkBulkRepairShowsProgress(out, "bulk_progress_compact", widthDp = 1000, heightDp = 680, cards = true) },
        "library_compat" to { checkLibraryLabelsReviewRepairAndRestore(out) },
        "partial_repair" to { checkPartRepairSaysWhatWasRepairedAndWhatWasLeft(out) },
        "mod_page" to { checkModPageChipOpensItsDetails(out) },
        "details_steady" to { checkDetailsStayInPlaceThroughARepair(out, "details_steady", widthDp = 1440, heightDp = 900) },
        "details_steady_compact" to { checkDetailsStayInPlaceThroughARepair(out, "details_steady_compact", widthDp = 1000, heightDp = 680) },
        "review_steady" to { checkReviewStaysInPlaceThroughItsRepairs(out, "review_steady", widthDp = 1440, heightDp = 900) },
        "review_steady_compact" to { checkReviewStaysInPlaceThroughItsRepairs(out, "review_steady_compact", widthDp = 1000, heightDp = 680) },
        "off_repair_list" to { checkAnOffModsRepairMovesNothing(out, "off_repair_list", widthDp = 1440, heightDp = 900, cards = false) },
        "off_repair_cards" to { checkAnOffModsRepairMovesNothing(out, "off_repair_cards", widthDp = 1000, heightDp = 680, cards = true) },
        "game_update" to { checkAGameUpdateRemakesChosenRepairsWithoutOfferingThem(out) },
        "details_retry" to { checkARequestThatEndsANoteKeepsTheDetailsInPlace(out, "details_retry", widthDp = 1440, heightDp = 900) },
        "review_offer_waiting" to { checkReviewKeepsOfferingANewRepairWhileOthersWait(out, "review_offer_waiting", widthDp = 1440, heightDp = 900) },
        "author_only" to { checkAProblemOnlyItsAuthorCanFixLeavesTheLibraryQuiet(out) },
        "auto_repair" to { checkAutomaticRepairIsQuietUntilARepairDoesNotFinish(out) },
    )
    val only = args.getOrNull(1)?.split(',')?.toSet()
    val selected = checks.filter { only == null || it.first in only }
    val failed = selected.mapNotNull { (name, run) ->
        try {
            run()
            println("Compatibility render check '$name' passed")
            null
        } catch (failure: Throwable) {
            println("Compatibility render check '$name' FAILED:\n${failure.message}")
            failure.printStackTrace(System.out)
            name
        }
    }
    if (failed.isNotEmpty()) {
        println("${failed.size} of ${selected.size} compatibility render checks failed: ${failed.joinToString()}")
        exitProcess(1)
    }
    println("All ${selected.size} compatibility render checks passed.")
}

private const val Werewolf = "Werewolf: Silver Thug"
private const val Ghost = "Geist: Old Bones"
private const val Frank = "Frank: Plain"

private const val IvyReason = "[zipline] look converted already while others keep the old pivot"

private class CompatFixture(
    secondRepairable: Boolean = false,
    first: List<CompatFinding>? = null,
    third: List<CompatFinding> = emptyList(),
    variant: String? = null,
    automatic: Boolean = false,
    beforeDerive: (suspend () -> Unit)? = null,
) {
    private val bytes = mapOf(Werewolf to ByteArray(40) { 1 }, Ghost to ByteArray(50) { 2 }, Frank to ByteArray(60) { 3 })
    val records: List<ManifestRecord> = listOf(Werewolf, Ghost, Frank).mapIndexed { index, title ->
        val number = index + 1
        ManifestRecord(
            "compat$number", SampleGameRoot, title, Placement.On(PakNumber(number)),
            sets = listOf(InstalledSet(
                source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 900_000L + number, 1L + number, null)),
                originalName = null, variant = variant,
                origin = SetOrigin.Downloaded(ArchiveIdentity("/downloads/compat$number.zip", 900, "$number".repeat(64))), version = null,
                files = listOf(SetFile(VpkPart.Directory, Sha256.image(bytes.getValue(title)))),
                componentKey = "pak01_dir.vpk",
            )),
        )
    }
    val files = InMemoryGameFiles().apply {
        putBytes(SampleGameRoot, InMemoryGameFiles.GameInfo, sampleGameFiles(FakeConfig(seed = LibrarySeed.Empty)).gameInfo(SampleGameRoot)!!)
        records.forEach { record -> putBytes(SampleGameRoot, record.files.single().relativePath(record), bytes.getValue(record.title)) }
    }
    val port = FakeCompatibility(files).apply {
        this.beforeDerive = beforeDerive
        findings[sha256Hex(bytes.getValue(Werewolf))] = first ?: listOf(FakeCompatibility.repairable(resource = "models/heroes_wip/werewolf/werewolf.vmdl_c", edits = 14))
        findings[sha256Hex(bytes.getValue(Frank))] = third
        findings[sha256Hex(bytes.getValue(Ghost))] = listOf(if (secondRepairable) FakeCompatibility.repairable(resource = "models/heroes_staging/ghost/ghost.vmdl_c") else FakeCompatibility.needsAuthor(resource = "models/heroes_staging/ghost/ghost.vmdl_c"))
    }
    val processes = FakeProcesses()
    val container = AppContainer.fake(
        files = files, libraryStore = InMemoryLibraryStore(LibraryManifest(records = records)), compatibility = port, processes = processes, preferences = automaticRepair(automatic),
    )

    fun state(): HideoutAppState = HideoutAppState(container, Route.Library)

    fun record(title: String): ManifestRecord = (container.modLibrary.state.value.library as LibraryStatus.Ready).records.single { it.title == title }

    fun install(title: String): InstallCompatibility? = container.modLibrary.state.value.compatibility.installs[record(title).installId]

    fun find(title: String, found: List<CompatFinding>) {
        port.findings[sha256Hex(bytes.getValue(title))] = found
    }
}

private fun automaticRepair(on: Boolean): Preferences = Preferences(MemoryStorage()).apply { runBlocking { setAutoRepair(on) } }

private fun Script.repairBoth() {
    clickLabel("Review")
    clickButton("Repair 2")
    await(what = "both repairs") { chips(Werewolf, "Repair applied") > 0 && chips(Ghost, "Repair applied") > 0 }
    clickButton("Close")
    clickButton("Dismiss")
}

private fun Script.clickFirst(label: String) {
    clickNode(nodes().first { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) })
    advance(500)
}

internal fun Script.clickButton(label: String) {
    val matches = nodes().filter { node ->
        node.config.contains(SemanticsActions.OnClick) && node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == label } == true
    }
    check(matches.size == 1) { "Expected one button '$label', found ${matches.size}" }
    clickNode(matches.single())
    advance(500)
}

private fun Script.chips(title: String, status: String): Int = nodes().count { it.hasLabel("$title: $status. Show details") }

private fun Script.topOf(title: String): Float = nodes().filter { it.hasLabel(title) }.minOf { it.boundsInRoot.top }

private fun Script.await(limitMillis: Long = 4_000, what: String, condition: () -> Boolean) {
    var waited = 0L
    while (!condition()) {
        check(waited < limitMillis) { "$what did not happen within $limitMillis ms" }
        advance(50)
        waited += 50
    }
    advance(400)
}

internal fun Script.awaitReal(limitMillis: Long = 10_000, what: String, condition: () -> Boolean) {
    val end = System.nanoTime() + limitMillis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < end) { "$what did not happen within $limitMillis ms" }
        advance(50)
        Thread.sleep(20)
    }
    advance(400)
}

private fun Script.drawnIn(title: String, text: String): Rect? {
    val holder = nodes().singleOrNull { node -> node.config.contains(SemanticsActions.OnClick) && node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == title } == true }?.boundsInRoot ?: return null
    return scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        .filter { node -> node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == text } == true && holder.contains(node.boundsInRoot.center) }
        .singleOrNull()?.boundsInRoot?.translate(-holder.left, -holder.top)
}

private fun Script.dialog(title: String): List<SemanticsNode> {
    val pane = nodes().singleOrNull { it.config.getOrElseNullable(SemanticsProperties.PaneTitle) { null } == title } ?: return emptyList()
    return descendants(pane)
}

private fun List<SemanticsNode>.text(text: String): SemanticsNode? =
    singleOrNull { node -> !node.config.contains(SemanticsActions.OnClick) && node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == text } == true }

private fun List<SemanticsNode>.button(label: String): SemanticsNode? =
    singleOrNull { node -> node.config.contains(SemanticsActions.OnClick) && node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == label } == true }

private fun List<SemanticsNode>.saying(fragment: String): Boolean =
    any { node -> node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { fragment in it.text } == true }

private class Snapshot(val name: String, val spots: Map<String, Rect?>)

private class Steadiness(private val check: String) {
    val problems = mutableListOf<String>()

    fun expect(condition: Boolean, what: String) {
        if (!condition) problems += what
    }

    fun same(frames: List<Snapshot>, vararg spots: String) {
        val first = frames.first()
        for (spot in spots) for (frame in frames.drop(1)) {
            val before = first.spots[spot]
            val after = frame.spots[spot]
            when {
                before == null -> problems += "$spot: absent in '${first.name}'"
                after == null -> problems += "$spot: absent in '${frame.name}', present in '${first.name}' at $before"
                abs(after.left - before.left) > 0.5f || abs(after.top - before.top) > 0.5f || abs(after.width - before.width) > 0.5f || abs(after.height - before.height) > 0.5f ->
                    problems += "$spot: moved from $before in '${first.name}' to $after in '${frame.name}' (dx=${after.left - before.left}, dy=${after.top - before.top}, dh=${after.height - before.height})"
            }
        }
    }

    fun report(frames: List<Snapshot>) {
        frames.forEach { frame -> println("$check ${frame.name}: " + frame.spots.entries.joinToString { (spot, rect) -> "$spot=${rect?.let { "(${it.left}, ${it.top}, ${it.width}x${it.height})" } ?: "absent"}" }) }
        problems.forEach { println("$check problem: $it") }
        check(problems.isEmpty()) { "$check:\n" + problems.joinToString("\n") }
    }
}

private fun checkBulkRepairShowsProgress(out: File, name: String, widthDp: Int, heightDp: Int, cards: Boolean) {
    val fixture = CompatFixture(secondRepairable = true)
    val gate = CompletableDeferred<Unit>()
    val verificationGate = CompletableDeferred<Unit>()
    fixture.port.beforeDerive = { gate.await() }
    fixture.port.beforeCheck = { record -> if (record.sets.any { it.derivation != null }) verificationGate.await() }
    try {
        runCheck(out, name, fixture.state(), widthDp = widthDp, heightDp = heightDp) {
            advance(1_200)
            if (cards) clickLabel("Cards")
            check(hasText("2 mods can be repaired for this game version")) { "the summary offers both repairs" }
            val listTop = topOf(Frank)
            capture("before_review")
            clickLabel("Review")
            capture("before_repair")
            clickLabel("Repair 2")
            advance(400)
            capture("review_in_flight")
            check(hasText("Mods with known problems")) { "Review stays open after Repair all" }
            check(chips(Werewolf, "Repairing…") > 0 && chips(Ghost, "Queued") > 0) { "Review shows each accepted repair's own progress" }
            clickButton("Close")
            capture("repair_in_flight")
            val activeShown = hasText("Repairing…") && chips(Werewolf, "Repairing…") > 0
            val queuedShown = hasText("Queued") && chips(Ghost, "Queued") > 0
            val summaryInFlight = hasText("Repairing 2 mods…")
            val stableInFlight = topOf(Frank) == listTop
            gate.complete(Unit)
            await(what = "the first replacement") { fixture.record(Werewolf).sets.single().derivation != null }
            capture("verifying_in_flight")
            val verifyingShown = hasText("Verifying…") && chips(Werewolf, "Verifying…") > 0
            val requeuedShown = chips(Ghost, "Queued") > 0
            val noStaleSuccess = chips(Werewolf, "Repair applied") == 0
            val summaryVerifying = hasText("Repairing 2 mods…")
            val stableVerifying = topOf(Frank) == listTop
            verificationGate.complete(Unit)
            await(what = "both repairs") {
                fixture.record(Werewolf).sets.single().derivation != null && fixture.record(Ghost).sets.single().derivation != null &&
                    chips(Werewolf, "Repair applied") > 0 && chips(Ghost, "Repair applied") > 0
            }
            capture("finished")
            check(activeShown) { "the Library must show the active repair while derivation is suspended" }
            check(queuedShown) { "the other accepted repair must be visibly queued" }
            check(verifyingShown) { "the applied repair must keep visible progress until its fresh check completes" }
            check(requeuedShown) { "a repair whose plan went stale stays queued while it is checked again, never blank" }
            check(noStaleSuccess) { "nothing is called applied before a check of the new files lands" }
            check(summaryInFlight && summaryVerifying) { "the summary stays through the repairs, saying how many are under way" }
            check(stableInFlight && stableVerifying) { "the list does not move while repairs run" }
            check(hasText("2 mods repaired")) { "the summary says how the repairs ended, from their current state" }
            check(topOf(Frank) == listTop) { "nor when they end" }
            check(chips(Frank, "Repair applied") == 0 && !hasText("No known problem")) { "nothing is called fine" }
            clickButton("Dismiss")
            check(!hasText("2 mods repaired")) { "the report goes once dismissed" }
            capture("dismissed")
        }
    } finally {
        gate.complete(Unit)
        verificationGate.complete(Unit)
    }
}

private fun checkLibraryLabelsReviewRepairAndRestore(out: File) {
    val fixture = CompatFixture()
    runCheck(out, "library_compat", fixture.state(), widthDp = 1440, heightDp = 900) {
        advance(1_200)
        check(hasText("Repair available")) { "the repairable mod should carry its chip" }
        check(hasText("Needs author update")) { "the mod only its author can fix should carry its chip" }
        check(!hasText("Repair applied")) { "nothing is called repaired before it is" }
        check(!hasText("Partly repaired"))
        check(nodes().none { it.hasLabel("$Frank: Repair available. Show details") || it.hasLabel("$Frank: Repair applied. Show details") }) { "a mod with no known problem has no chip" }
        check(shows("1 mod can be repaired for this game version")) { "the summary offers the repair and leaves the author's problem to its chip" }
        capture("labels")

        clickLabel("Review")
        check(hasText("Mods with known problems")) { "Review lists the mods" }
        capture("review")
        clickLabel("Repair 1")
        await(what = "the repair") { fixture.record(Werewolf).sets.single().derivation != null && chips(Werewolf, "Repair applied") > 0 }
        check(hasText("Repair applied")) { "the chip follows the committed repair" }
        capture("review_repaired")
        clickButton("Close")
        check(!hasText("1 mod can be repaired")) { "the summary drops the repaired mod" }
        check(shows("1 mod repaired")) { "and says how the repair ended, and nothing else" }
        capture("repaired")

        clickFirst("$Werewolf: Repair applied. Show details")
        check(hasText("$Werewolf and this game version")) { "the chip opens the mod's details" }
        check(hasText("Repaired") && hasText("Camera and attachment points")) { "the details say exactly what was repaired, by feature" }
        check(hasText("werewolf.vmdl_c · 14 values"))
        check(hasText("haven’t been tested in a match yet")) { "the details say the repair is untested in a match" }
        capture("details_repaired")
        clickLabel("Use original files")
        await(what = "the restore") { fixture.record(Werewolf).sets.single().derivation == null }
        check(hasText("You chose the original files")) { "the remembered choice is shown" }
        check(fixture.port.released.isNotEmpty()) { "the original copy is freed once nothing comes from it" }
        capture("details_original")
        clickButton("Close")
        check(chips(Werewolf, "Repair available") == 0) { "the original files are the user's answer, so no chip asks again" }
        check(!hasText("1 mod can be repaired")) { "a mod whose original files were chosen is not pushed again" }

        clickFirst("$Ghost: Needs author update. Show details")
        check(hasText("Left unchanged") && hasText("Why: mixed frames")) { "the author's problem is named, with the rule's reason" }
        check(!hasText("Apply repair")) { "nothing offers to repair what cannot be repaired" }
        capture("details_needs_author")
    }
}

private fun checkAProblemOnlyItsAuthorCanFixLeavesTheLibraryQuiet(out: File) {
    val fixture = CompatFixture(first = emptyList(), automatic = true)
    runCheck(out, "author_only", fixture.state(), widthDp = 1440, heightDp = 900) {
        advance(1_200)
        check(chips(Ghost, "Needs author update") > 0) { "the mod only its author can fix carries its chip" }
        check(!hasText("need an author update") && !shows("Review")) { "and the Library has no summary for it" }
        capture("no_bar")
        clickLabel("Turn $Ghost on or off")
        await(what = "the mod off") { !fixture.record(Ghost).enabled }
        check(chips(Ghost, "Needs author update") > 0 && !shows("Review")) { "off, it keeps its chip, and still no summary" }
        capture("off")
    }
}

private fun checkAutomaticRepairIsQuietUntilARepairDoesNotFinish(out: File) {
    val gate = CompletableDeferred<Unit>()
    val fixture = CompatFixture(secondRepairable = true, automatic = true, beforeDerive = { gate.await() })
    try {
        runCheck(out, "auto_repair", fixture.state(), widthDp = 1440, heightDp = 900) {
            advance(1_200)
            check(chips(Werewolf, "Repairing…") > 0 && chips(Ghost, "Queued") > 0) { "the repairs start without being asked" }
            check(!shows("Review") && !hasText("Repairing 2 mods")) { "and the Library has no summary of them" }
            capture("repairing_quietly")
            clickFirst("$Werewolf: Repairing…. Show details")
            val original = dialog("$Werewolf and this game version").button("Use original files")
            check(original != null && !original.config.contains(SemanticsProperties.Disabled)) { "Use original files is offered before the first repair lands" }
            check(dialog("$Werewolf and this game version").button("Apply repair")?.config?.contains(SemanticsProperties.Disabled) == true) { "Apply repair waits for the repair under way" }
            capture("details_while_repairing")
            clickButton("Close")
            gate.complete(Unit)
            await(what = "both repairs") { chips(Werewolf, "Repair applied") > 0 && chips(Ghost, "Repair applied") > 0 }
            check(!shows("Review") && !hasText("mods repaired")) { "repairs nobody asked for end without a report" }
            capture("repaired_quietly")

            fixture.port.beforeDerive = null
            fixture.port.refuseDerive = "the repaired model did not read back"
            fixture.port.game = "build:updated"
            fixture.container.modLibrary.checkCompatibility()
            await(what = "both repairs of the updated game failing") { listOf(Werewolf, Ghost).all { fixture.install(it)?.problem != null } }
            check(hasText("2 didn’t finish") && !hasText("can be repaired")) { "the summary counts the failures once, and offers nothing Hideout repairs by itself" }
            check(labelled("Try again").size == 1 && !shows("Review")) { "the bar itself offers to try them again" }
            capture("failed")
            clickFirst("$Werewolf: Repair didn’t finish. Show details")
            check(hasText("The last change didn’t finish")) { "the mod's details say why" }
            clickButton("Close")

            fixture.port.refuseDerive = null
            fixture.processes.scan = ProcessScan.Seen(game = true)
            awaitReal(what = "Deadlock seen running") { fixture.container.modLibrary.state.value.deadlock == GameActivity.Running }
            clickLabel("Try again")
            await(what = "the retries waiting") { listOf(Werewolf, Ghost).all { fixture.install(it)?.pending == RepairWork.WaitingForGame(restoring = false) } }
            check(hasText("2 wait for Deadlock to close")) { "the retries the bar asked for wait for Deadlock" }
            capture("retry_waiting")
            fixture.processes.scan = ProcessScan.Seen()
            awaitReal(what = "the retried repairs") { chips(Werewolf, "Repair applied") > 0 && chips(Ghost, "Repair applied") > 0 }
            check(hasText("2 mods repaired")) { "and what the bar asked for is reported" }
            capture("retried")
        }
    } finally {
        gate.complete(Unit)
    }
}

private const val Lilith = "Lilith, the Hell Warden"

private class ModPageFixture {
    private val bytes = ByteArray(48) { 9 }
    private val record = ManifestRecord(
        "lilith1", SampleGameRoot, Lilith, Placement.On(PakNumber(1)),
        sets = listOf(InstalledSet(
            source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 551_201L, 7L, null)),
            originalName = null, variant = null,
            origin = SetOrigin.Downloaded(ArchiveIdentity("/downloads/lilith.zip", 900, "7".repeat(64))), version = null,
            files = listOf(SetFile(VpkPart.Directory, Sha256.image(bytes))),
            componentKey = "pak01_dir.vpk",
        )),
    )
    private val files = InMemoryGameFiles().apply {
        putBytes(SampleGameRoot, InMemoryGameFiles.GameInfo, sampleGameFiles(FakeConfig(seed = LibrarySeed.Empty)).gameInfo(SampleGameRoot)!!)
        putBytes(SampleGameRoot, record.files.single().relativePath(record), bytes)
    }
    val port = FakeCompatibility(files).apply {
        findings[sha256Hex(bytes)] = listOf(
            FakeCompatibility.repairable(resource = "models/heroes_staging/succubus/succubus.vmdl_c", edits = 22),
            FakeCompatibility.needsAuthor(resource = "models/heroes_staging/succubus/succubus.vmdl_c", rule = FakeCompatibility.Sound),
        )
    }
    private val base = AppContainer.fake(files = files, libraryStore = InMemoryLibraryStore(LibraryManifest(records = listOf(record))), compatibility = port, preferences = automaticRepair(false))

    private val catalog = object : CatalogRepository by base.catalog {
        override val mods = base.catalog.mods.map { mods -> mods.map { if (it.id == "lilith") it.copy(id = "mod-551201", worksOnCurrentPatch = false) else it } }
        override fun detail(modId: String) = base.catalog.detail(if (modId == "mod-551201") "lilith" else modId)
    }
    val container = AppContainer(catalog, base.launcher, base.soundPlayer, base.images, base.previews,
        base.modLibrary, base.preferences, base.folderPicker, base.folderOpener, base.about, migration = base.migration, autoexec = base.autoexec, crosshairs = base.crosshairs, communityCrosshairs = base.communityCrosshairs)

    fun record(): ManifestRecord = (container.modLibrary.state.value.library as LibraryStatus.Ready).records.single()

    fun useUnknownFindings() {
        port.findings[sha256Hex(bytes)] = listOf(FakeCompatibility.unknown(resource = "models/heroes_staging/succubus/succubus.vmdl_c"))
        container.modLibrary.checkCompatibility()
    }
}

private fun checkModPageChipOpensItsDetails(out: File) {
    val fixture = ModPageFixture()
    runCheck(out, "mod_page", HideoutAppState(fixture.container, Route.ModDetail("mod-551201")), widthDp = 1440, heightDp = 900) {
        advance(1_500)
        check(hasText("Repair available")) { "the installed mod's page carries the same chip as its Library row" }
        check(!hasText("Repair applied") && !hasText("Partly repaired"))
        check(shows("Not updated for this patch")) { "GameBanana's flag stays, without warning beside Hideout's own chip" }
        capture("chip")

        clickFirst("$Lilith: Repair available. Show details")
        check(hasText("$Lilith and this game version")) { "the chip opens the mod's details" }
        check(hasText("Can be repaired") && hasText("Left unchanged")) { "what can be repaired is apart from what only the author can fix" }
        check(hasText("Camera and attachment points") && hasText("Animation sounds"))
        check(hasText("succubus.vmdl_c · 22 values"))
        check(hasText("haven’t been tested in a match yet")) { "the repair's rules are untested, and it says so" }
        capture("details")
        clickButton("Apply repair")
        await(what = "the repair") { fixture.record().sets.single().derivation != null && hasText("Partly repaired") }
        check(hasText("Repaired") && hasText("Camera and attachment points")) { "the details say what was repaired" }
        check(hasText("Left unchanged") && hasText("Animation sounds")) { "and what still needs the author" }
        capture("repaired")
        clickButton("Use original files")
        await(what = "the restore") { fixture.record().sets.single().derivation == null }
        check(hasText("You chose the original files")) { "the remembered choice is shown" }
        capture("original")
        clickButton("Close")
        check(!hasText("Repair available") && !hasText("Partly repaired")) { "the original files are the user's answer, so no chip asks again" }

        fixture.useUnknownFindings()
        await(what = "the check again") { fixture.container.modLibrary.state.value.compatibility.installs.values.single().unknown.isNotEmpty() }
        check(!hasText("Needs author update") && !hasText("Repair applied") && !hasText("Partly repaired")) { "what could not be checked has no chip, and nothing is called healthy" }
        capture("unknown")
    }
}

private fun checkPartRepairSaysWhatWasRepairedAndWhatWasLeft(out: File) {
    val ivy = "models/heroes_staging/tengu/tengu_v2/ivy.vmdl_c"
    val fixture = CompatFixture(
        first = listOf(
            FakeCompatibility.repairable(resource = ivy, rule = FakeCompatibility.Sound, edits = 6),
            CompatFinding(FakeCompatibility.Frame, ivy, FindingKind.NeedsAuthor, IvyReason),
        ),
        third = listOf(FakeCompatibility.unknown(resource = "models/heroes/frank/frank.vmdl_c")),
    )
    runCheck(out, "partial_repair", fixture.state(), widthDp = 1440, heightDp = 900) {
        advance(1_200)
        clickFirst("$Werewolf: Repair available. Show details")
        check(hasText("Can be repaired") && hasText("Animation sounds") && hasText("ivy.vmdl_c · 6 values")) { "what the repair changes, by feature" }
        check(hasText("Left unchanged") && hasText("Camera and attachment points")) { "and what it leaves" }
        check(hasText("Why: $IvyReason")) { "with the rule's own reason, whole" }
        capture("details_before")
        clickButton("Apply repair")
        await(what = "the partial repair") { fixture.record(Werewolf).sets.single().derivation != null && hasText("Partly repaired") }
        check(hasText("Repaired") && hasText("Animation sounds")) { "the details say the sounds were repaired" }
        check(hasText("Left unchanged") && hasText("Camera and attachment points") && hasText("Why: $IvyReason")) { "and the camera was left, and why" }
        check(!hasText("Can be repaired")) { "nothing is offered that was already done" }
        capture("details_partly_repaired")
        clickButton("Close")
        check(chips(Werewolf, "Partly repaired") > 0) { "the chip says partly" }
        check(shows("1 mod repaired")) { "the summary says how the repair ended; the camera left for the author is on the chip alone" }
        capture("partly_repaired")

        clickFirst(Frank)
        clickButton("Details")
        check(hasText("Couldn’t check") && hasText("Why: could not tell")) { "what could not be checked says so, with why" }
        check(!hasText("Left unchanged") && chips(Frank, "Needs author update") == 0) { "and is never called the author's problem" }
        capture("details_unknown")
    }
}

private const val Apply = "Apply repair"
private const val UseOriginal = "Use original files"

private fun checkDetailsStayInPlaceThroughARepair(out: File, name: String, widthDp: Int, heightDp: Int) {
    val fixture = CompatFixture()
    val derive = CompletableDeferred<Unit>()
    val verify = CompletableDeferred<Unit>()
    val recheck = CompletableDeferred<Unit>()
    fixture.port.beforeDerive = { derive.await() }
    fixture.port.beforeCheck = { record -> if (record.sets.any { it.derivation != null }) verify.await() }
    val steady = Steadiness(name)
    try {
        runCheck(out, name, fixture.state(), widthDp = widthDp, heightDp = heightDp) {
            advance(1_200)
            val heading = "$Werewolf and this game version"
            fun frame(label: String): Snapshot = detailsSnapshot(heading, label)
            clickFirst("$Werewolf: Repair available. Show details")
            val before = frame("before")
            clickButton(Apply)
            await(what = "the repair under way") { fixture.install(Werewolf)?.pending == RepairWork.Repairing }
            val repairing = frame("repairing")
            derive.complete(Unit)
            await(what = "the placed repair") { fixture.install(Werewolf)?.pending is RepairWork.Verifying }
            val verifying = frame("verifying")
            val shown = dialog(heading)
            steady.expect(!shown.saying("Repaired") && chips(Werewolf, "Repair applied") == 0, "nothing is called repaired before the new files are checked")
            steady.expect(shown.saying("Checking the repaired files against the game"), "verifying says what it is doing")
            verify.complete(Unit)
            await(what = "the verified repair") { chips(Werewolf, "Repair applied") > 0 }
            val finished = frame("finished")
            steady.expect(dialog(heading).saying("Repaired"), "the outcome is shown once checked")
            val frames = listOf(before, repairing, verifying, finished)
            steady.same(frames, "title", "close", "check again")
            steady.same(listOf(before, repairing, verifying), "apply")
            for (frame in frames.drop(1)) {
                val original = frame.spots["use original"]
                val apply = before.spots.getValue("apply")
                steady.expect(original == null || apply == null || !original.overlaps(apply), "Use original files took Apply repair's place in '${frame.name}': $original over $apply")
            }

            clickButton("Close")
            advance(400)
            val bar = Snapshot("bar", mapOf("list" to Rect(0f, topOf(Frank), 1f, topOf(Frank) + 1f)))
            capture("before_checking")
            fixture.port.beforeCheck = { recheck.await() }
            fixture.container.modLibrary.checkCompatibility()
            await(what = "checking") { hasText("Checking mods against the game") }
            capture("checking")
            val checking = Snapshot("checking", mapOf("list" to Rect(0f, topOf(Frank), 1f, topOf(Frank) + 1f)))
            steady.same(listOf(bar, checking), "list")
            steady.report(frames + listOf(bar, checking))
        }
    } finally {
        derive.complete(Unit)
        verify.complete(Unit)
        recheck.complete(Unit)
    }
}

private fun Script.detailsSnapshot(heading: String, label: String): Snapshot {
    capture(label)
    val dialog = dialog(heading)
    return Snapshot(label, mapOf(
        "title" to dialog.text(heading)?.boundsInRoot,
        "close" to dialog.button("Close")?.boundsInRoot,
        "check again" to dialog.button("Check again")?.boundsInRoot,
        "apply" to dialog.button(Apply)?.boundsInRoot,
        "use original" to dialog.button(UseOriginal)?.boundsInRoot,
    ))
}

private fun checkARequestThatEndsANoteKeepsTheDetailsInPlace(out: File, name: String, widthDp: Int, heightDp: Int) {
    val fixture = CompatFixture()
    val retry = CompletableDeferred<Unit>()
    val again = CompletableDeferred<Unit>()
    fixture.port.refuseDerive = "the repaired model did not read back"
    val steady = Steadiness(name)
    try {
        runCheck(out, name, fixture.state(), widthDp = widthDp, heightDp = heightDp) {
            advance(1_200)
            val heading = "$Werewolf and this game version"
            clickFirst("$Werewolf: Repair available. Show details")
            clickButton(Apply)
            await(what = "the failed repair") { fixture.install(Werewolf)?.problem != null }
            val failed = detailsSnapshot(heading, "failed")
            steady.expect(dialog(heading).saying("The last change didn’t finish"), "the failure is shown")
            fixture.port.refuseDerive = null
            fixture.port.beforeDerive = { retry.await() }
            clickButton(Apply)
            await(what = "the retry under way") { fixture.install(Werewolf)?.pending == RepairWork.Repairing }
            val retrying = detailsSnapshot(heading, "retrying")
            steady.expect(!dialog(heading).saying("The last change didn’t finish"), "a failure the retry ended is not shown")
            steady.same(listOf(failed, retrying), "title", "close", "check again", "apply", "use original")
            retry.complete(Unit)
            await(what = "the retried repair") { chips(Werewolf, "Repair applied") > 0 }
            val repaired = detailsSnapshot(heading, "repaired")

            fixture.port.beforeDerive = { again.await() }
            clickButton(UseOriginal)
            await(what = "the restore") { fixture.record(Werewolf).sets.single().derivation == null && fixture.install(Werewolf)?.let { it.pending == null && it.check != null } == true }
            val original = detailsSnapshot(heading, "original")
            steady.expect(dialog(heading).saying("You chose the original files"), "the choice is shown")
            clickButton(Apply)
            await(what = "the repair asked for again") { fixture.install(Werewolf)?.pending == RepairWork.Repairing }
            val reapplying = detailsSnapshot(heading, "reapplying")
            steady.same(listOf(original, reapplying), "title", "close", "check again", "apply")
            again.complete(Unit)
            await(what = "the repair") { chips(Werewolf, "Repair applied") > 0 }
            detailsSnapshot(heading, "reapplied")
            steady.report(listOf(failed, retrying, repaired, original, reapplying))
        }
    } finally {
        retry.complete(Unit)
        again.complete(Unit)
    }
}

private fun checkReviewKeepsOfferingANewRepairWhileOthersWait(out: File, name: String, widthDp: Int, heightDp: Int) {
    val fixture = CompatFixture(secondRepairable = true)
    val derive = CompletableDeferred<Unit>()
    val steady = Steadiness(name)
    try {
        runCheck(out, name, fixture.state(), widthDp = widthDp, heightDp = heightDp) {
            advance(1_200)
            val title = "Mods with known problems"
            repairBoth()
            fixture.port.game = "build:updated"
            fixture.find(Frank, listOf(FakeCompatibility.repairable(resource = "models/heroes/frank/frank.vmdl_c")))
            fixture.processes.scan = ProcessScan.Seen(game = true)
            awaitReal(what = "Deadlock seen running") { fixture.container.modLibrary.state.value.deadlock == GameActivity.Running }
            fixture.container.modLibrary.checkCompatibility()
            await(what = "the checks of the updated game") {
                listOf(Werewolf, Ghost).all { fixture.install(it)?.pending == RepairWork.WaitingForGame(restoring = false) } &&
                    fixture.install(Frank)?.label(enabled = true) == CompatLabel.RepairAvailable
            }
            clickLabel("Review")
            capture("waiting_with_offer")
            fun offered(moment: String) {
                val dialog = dialog(title)
                val repair = dialog.button("Repair 1")
                steady.expect(repair != null && !repair.config.contains(SemanticsProperties.Disabled), "Repair is offered for the new mod $moment")
            }
            steady.expect(chips(Werewolf, "Waits for Deadlock") > 0 && chips(Ghost, "Waits for Deadlock") > 0, "the chosen repairs wait for Deadlock")
            offered("while the chosen ones wait")
            fixture.port.beforeDerive = { derive.await() }
            fixture.processes.scan = ProcessScan.Seen()
            awaitReal(what = "a chosen repair under way") { fixture.install(Werewolf)?.pending == RepairWork.Repairing }
            capture("repairing_with_offer")
            offered("while a chosen one is repaired")
            derive.complete(Unit)
            await(what = "the chosen repairs remade") { listOf(Werewolf, Ghost).all { fixture.record(it).sets.single().derivation?.context == "build:updated" && fixture.install(it)?.pending == null } }
            capture("remade_with_offer")
            offered("once the chosen ones are remade")
            steady.report(emptyList())
        }
    } finally {
        derive.complete(Unit)
    }
}

private fun checkReviewStaysInPlaceThroughItsRepairs(out: File, name: String, widthDp: Int, heightDp: Int) {
    val fixture = CompatFixture(secondRepairable = true)
    val derive = CompletableDeferred<Unit>()
    val verify = CompletableDeferred<Unit>()
    fixture.port.beforeDerive = { derive.await() }
    fixture.port.beforeCheck = { record -> if (record.sets.any { it.derivation != null }) verify.await() }
    val steady = Steadiness(name)
    try {
        runCheck(out, name, fixture.state(), widthDp = widthDp, heightDp = heightDp) {
            advance(1_200)
            val title = "Mods with known problems"
            fun frame(label: String): Snapshot {
                capture(label)
                val dialog = dialog(title)
                return Snapshot(label, mapOf(
                    "title" to dialog.text(title)?.boundsInRoot,
                    "close" to dialog.button("Close")?.boundsInRoot,
                    "repair" to dialog.button("Repair 2")?.boundsInRoot,
                ))
            }
            clickLabel("Review")
            val before = frame("before")
            clickButton("Repair 2")
            await(what = "the first repair under way") { fixture.install(Werewolf)?.pending == RepairWork.Repairing }
            val repairing = frame("repairing")
            steady.expect(dialog(title).button("Repair 2")?.config?.contains(SemanticsProperties.Disabled) == true, "Repair cannot be asked twice while it runs")
            derive.complete(Unit)
            await(what = "the first placed repair") { fixture.install(Werewolf)?.pending is RepairWork.Verifying }
            val verifying = frame("verifying")
            verify.complete(Unit)
            await(what = "both repairs") { chips(Werewolf, "Repair applied") > 0 && chips(Ghost, "Repair applied") > 0 }
            val finished = frame("finished")
            steady.same(listOf(before, repairing, verifying, finished), "title", "close", "repair")
            steady.report(listOf(before, repairing, verifying, finished))
        }
    } finally {
        derive.complete(Unit)
        verify.complete(Unit)
    }
}

private fun checkAnOffModsRepairMovesNothing(out: File, name: String, widthDp: Int, heightDp: Int, cards: Boolean) {
    val fixture = CompatFixture(variant = "Silver fur")
    val steady = Steadiness(name)
    runCheck(out, name, fixture.state(), widthDp = widthDp, heightDp = heightDp) {
        advance(1_200)
        if (cards) clickLabel("Cards")
        clickLabel("Turn $Werewolf on or off")
        await(what = "the mod off, checked") { !fixture.record(Werewolf).enabled && chips(Werewolf, "Repair available") > 0 }
        fun frame(label: String): Snapshot {
            moveToDp(1f, 1f)
            advance(400)
            capture(label)
            return Snapshot(label, mapOf("title" to drawnIn(Werewolf, Werewolf), "subtitle" to drawnIn(Werewolf, "Silver fur")))
        }
        val before = frame("off_repairable")
        clickFirst("$Werewolf: Repair available. Show details")
        clickButton(Apply)
        await(what = "the repair") { fixture.record(Werewolf).sets.single().derivation != null && fixture.install(Werewolf)?.pending == null && fixture.install(Werewolf)?.check != null }
        clickButton("Close")
        advance(400)
        val after = frame("off_repaired")
        steady.expect(chips(Werewolf, "Repair applied") == 0, "a repair of a mod that is off is not called applied")
        steady.same(listOf(before, after), "title", "subtitle")
        clickLabel("Turn $Werewolf on or off")
        await(what = "the mod on") { fixture.record(Werewolf).enabled && chips(Werewolf, "Repair applied") > 0 }
        val on = frame("on_repaired")
        steady.same(listOf(before, on), "title", "subtitle")
        steady.report(listOf(before, after, on))
    }
}

private fun checkAGameUpdateRemakesChosenRepairsWithoutOfferingThem(out: File) {
    val fixture = CompatFixture(secondRepairable = true)
    val held = CompletableDeferred<Unit>()
    val steady = Steadiness("game_update")
    try {
        runCheck(out, "game_update", fixture.state(), widthDp = 1440, heightDp = 900) {
            advance(1_200)
            repairBoth()
            capture("repaired")
            val made = fixture.port.derived.size
            fixture.port.game = "build:updated"
            fixture.processes.scan = ProcessScan.Seen(game = true)
            awaitReal(what = "Deadlock seen running") { fixture.container.modLibrary.state.value.deadlock == GameActivity.Running }
            capture("updated_running")
            fixture.port.beforeCheck = { record -> if (record.title == Ghost) held.await() }
            fixture.processes.scan = ProcessScan.Seen()
            awaitReal(what = "the first check of the updated game") { fixture.install(Werewolf)?.check?.basis?.game == "build:updated" }
            capture("first_check_landed")
            steady.expect(chips(Werewolf, "Repair available") == 0 && !hasText("can be repaired for this game version"), "a repair the user chose is not offered again")
            steady.expect(chips(Werewolf, "Queued") > 0, "it is queued to be made again")
            held.complete(Unit)
            await(what = "both repairs remade") {
                listOf(Werewolf, Ghost).all { fixture.record(it).sets.single().derivation?.context == "build:updated" } &&
                    chips(Werewolf, "Repair applied") > 0 && chips(Ghost, "Repair applied") > 0
            }
            capture("remade")
            steady.expect(fixture.port.derived.size == made + 2, "both repairs were made again for the updated game, ${fixture.port.derived.size - made} made")
            steady.report(emptyList())
        }
    } finally {
        held.complete(Unit)
    }
}
