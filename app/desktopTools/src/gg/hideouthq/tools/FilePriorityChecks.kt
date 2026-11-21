package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.navigation.Route
import java.io.File

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/file-priority")
    for (width in listOf(1440, 1000)) {
        for (cards in listOf(false, true)) checkFilePriority(out, width, cards, unavailable = false)
    }
    checkFilePriority(out, 1000, cards = false, unavailable = true)
    checkFilePriority(out, 1000, cards = false, unavailable = false, manyPaths = true)
    checkWaitingPeer(out)
    val base = sampleLibraryManifest()
    val container = AppContainer.fake(files = InstalledFixture(base.records).files, libraryStore = InMemoryLibraryStore(base))
    runCheck(out, "no_index_source", HideoutAppState(container, Route.Library)) {
        advance(900)
        openRowMenu(base.records.first().title)
        key(Key.Escape)
        advance(500)
        check(!hasText("File priority…"))
        capture("no_unavailable_action")
    }
    println("All 8 file-priority render checks passed.")
}

private fun checkFilePriority(out: File, width: Int, cards: Boolean, unavailable: Boolean, manyPaths: Boolean = false) {
    val base = sampleLibraryManifest()
    val ordered = base.records.filter { it.enabled }.sortedBy { it.numbers.first() }
    val chosen = ordered.first()
    val earlier = ordered[1]
    val records = base.records.map { record ->
        when (record.installId) {
            chosen.installId -> record.placed(Placement.On(earlier.numbers.first()))
            earlier.installId -> record.placed(Placement.On(chosen.numbers.first()))
            else -> record
        }
    }
    val store = InMemoryLibraryStore(base.copy(records = records, profiles = ProfileBook.main(records)))
    val files = InstalledFixture(records).files
    val hashes = listOf(chosen, earlier).flatMap { it.files }.filter { it.isDirectory }.mapTo(mutableSetOf()) { it.sha256 }
    val paths = if (manyPaths) (1..60).mapTo(linkedSetOf()) { "materials/shared/${it.toString().padStart(2, '0')}.vtex_c" }
        else setOf("materials/models/heroes/ivy/wardrobe/shared_clothing_material.vmat_c", "models/heroes/ivy/shared_body.vmdl_c", "readme.txt")
    val container = AppContainer.fake(files = files, libraryStore = store, processes = FakeProcesses(ProcessScan.Seen(game = true)),
        resourceIndexSource = ResourceIndexSource { _, _, image ->
            if (unavailable) ResourceIndex.Unavailable else ResourceIndex.Ready(if (image.sha256 in hashes) paths.associateWith { image.sha256.hashCode().toLong() } else emptyMap())
        })
    val state = HideoutAppState(container, Route.Library)
    val label = "library_${width}_${if (cards) "cards" else "list"}_${if (unavailable) "unavailable" else if (manyPaths) "many_paths" else "ready"}"
    runCheck(out, label, state, widthDp = width, heightDp = 900) {
        advance(1_200)
        if (cards) {
            clickLabel("Cards")
            advance(500)
        }
        openRowMenu(chosen.title)
        key(Key.Escape)
        advance(500)
        check(!hasText("Show files ("))
        capture("quiet_library")
        clickLabel("File priority…")
        advance(500)
        check(hasText("File priority: ${chosen.title}"))
        if (unavailable) {
            check(hasText("Couldn’t read the file list for ${earlier.title}."))
            check(!hasText("No other enabled mod"))
            check(!hasText("Move above"))
            check(container.modLibrary.state.value.canInstall && container.modLibrary.state.value.canEditChoices)
            capture("unavailable_incomplete")
        } else {
            check(hasText("Move above ${earlier.title}"))
            check(hasText("Above ${chosen.title}"))
            check(!hasText("Currently applied:"))
            capture("priority_summary")
            if (width == 1440 && !cards) keepBoth(chosen.installId, earlier.installId, store)
            val shown = paths - "readme.txt"
            clickLabel("Show files (${shown.size})")
            advance(500)
            if (manyPaths) {
                check(paths.take(50).all { hasText(it) })
                check(paths.drop(50).none { hasText(it) })
                revealPriorityLabel("Show more files")
                clickLabel("Show more files")
                advance(500)
                check(paths.all { hasText(it) })
                key(Key.Escape)
                advance(500)
                check(!hasText("File priority: ${chosen.title}"))
                check(files.linkedPaths.isEmpty() && files.removedPaths.isEmpty())
                capture("paginated_paths_closed")
                return@runCheck
            }
            check(shown.all { hasText(it) } && !hasText("readme.txt")) { "a packer file is not a shared file" }
            fun top(path: String) = nodes().single { it.hasLabel(path) && it.config.contains(SemanticsProperties.Text) }.boundsInRoot.top
            val model = top("models/heroes/ivy/shared_body.vmdl_c")
            val material = top("materials/models/heroes/ivy/wardrobe/shared_clothing_material.vmat_c")
            check(model < material) { "the model clash leads the paths" }
            capture("expanded_paths")
            clickLabel("Move above ${earlier.title}")
            advance(800)
            check(container.modLibrary.state.value.orderView!!.sets.first() == chosen.installId)
            check(store.manifest!!.records == records) { "Move above saves priority without moving a running game's files" }
            check(files.linkedPaths.isEmpty() && files.removedPaths.isEmpty())
            check(hasText("Currently applied: Above ${chosen.title}"))
            capture("after_move")
            check(store.manifest!!.savedChoices?.target?.activeProfile?.orders?.get(SampleGameRoot)?.first() == chosen.installId)
            clickNode(nodes().single { it.hasLabel("Close") && it.config.contains(SemanticsProperties.Text) })
            advance(500)
            if (width == 1000) {
                key(Key.Escape)
                advance(500)
            }
            openRowMenu(earlier.title)
            key(Key.Escape)
            advance(500)
            clickLabel("File priority…")
            advance(500)
            check(hasText("File priority: ${earlier.title}"))
            check(!hasText("File priority: ${chosen.title}"))
            check(hasText("Above ${earlier.title}"))
            check(paths.none { hasText(it) })
            capture("another_mod_starts_collapsed")
        }
    }
    println("File priority $label passed")
}

private fun checkWaitingPeer(out: File) {
    val base = sampleLibraryManifest()
    val ordered = base.records.filter { it.enabled }.sortedBy { it.numbers.first() }
    val chosen = ordered.first()
    val earlier = ordered[1]
    val records = base.records.map { if (it.installId == earlier.installId) it.placed(Placement.Off) else it }
    val store = InMemoryLibraryStore(base.copy(records = records, profiles = ProfileBook.main(records)))
    val hashes = listOf(chosen, earlier).flatMap { it.files }.filter { it.isDirectory }.mapTo(mutableSetOf()) { it.sha256 }
    val paths = setOf("models/heroes/ivy/shared_body.vmdl_c")
    val container = AppContainer.fake(files = InstalledFixture(records).files, libraryStore = store, processes = FakeProcesses(ProcessScan.Seen(game = true)),
        resourceIndexSource = ResourceIndexSource { _, _, image -> ResourceIndex.Ready(if (image.sha256 in hashes) paths.associateWith { image.sha256.hashCode().toLong() } else emptyMap()) })
    runCheck(out, "waiting_peer", HideoutAppState(container, Route.Library), heightDp = 900) {
        advance(1_200)
        container.modLibrary.setEnabled(earlier.installId, true)
        advance(1_200)
        check(container.modLibrary.state.value.shownProfiles?.activeProfile?.enabled.orEmpty().containsAll(setOf(chosen.installId, earlier.installId))) { "both mods are on" }
        openRowMenu(chosen.title)
        key(Key.Escape)
        advance(500)
        clickLabel("File priority…")
        advance(500)
        check(hasText("Currently applied: ${earlier.title} is off")) { "the dialog says which of the two the game doesn't have yet" }
        capture("waiting_peer")
    }
    println("File priority waiting_peer passed")
}

private fun Script.keepBoth(chosen: String, earlier: String, store: InMemoryLibraryStore) {
    fun keptBeside() = store.manifest!!.records.single { it.installId == chosen }.keptBeside
    check(hasText("1 model hidden")) { "the row loses a model to the mod above it" }
    clickLabel("Keep both")
    advance(800)
    check(hasText("Kept both") && labelled("Keep both").isEmpty())
    check(keptBeside() == setOf(earlier)) { "Keep both saves the pair with the mod, got ${keptBeside()}" }
    capture("kept_both")
    clickNode(nodes().single { it.hasLabel("Close") && it.config.contains(SemanticsProperties.Text) })
    advance(500)
    check(hasText("1 model hidden"))
    capture("kept_both_chip")
    clickLabel("File priority…")
    advance(500)
    check(hasText("Kept both")) { "the dialog shows the pair as kept when it opens again" }
    clickLabel("Undo")
    advance(800)
    check(keptBeside().isEmpty() && labelled("Keep both").size == 1)
    capture("keep_both_undone")
}

private fun Script.revealPriorityLabel(label: String) {
    repeat(30) {
        val target = nodes().first { it.hasLabel(label) }
        val viewport = generateSequence(target.parent) { it.parent }.firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        val bounds = target.boundsInRoot
        if (bounds.height > 0f && bounds.height >= target.size.height - 1f &&
            (viewport == null || bounds.top >= viewport.boundsInRoot.top && bounds.bottom <= viewport.boundsInRoot.bottom)) return
        val pane = checkNotNull(viewport).boundsInRoot
        moveToDp(pane.center.x / scale, pane.center.y / scale)
        scroll(2f)
        advance(120)
    }
    error("Could not scroll $label into view")
}
