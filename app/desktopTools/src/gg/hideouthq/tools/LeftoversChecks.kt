package gg.hideouthq.tools

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.LibraryStatus
import java.io.File

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/leftovers")
    oneSet(out)
    severalSets(out)
    whileRunning(out)
    copyOfAMod(out)
    println("All 4 leftover render checks passed.")
}

private const val Citadel = "game/citadel"

private val oneSet: InMemoryGameFiles.() -> Unit = {
    putBytes(SampleGameRoot, "$Citadel/addons/pak41_dir.vpk", FakeVpk.directory(seed = 41))
    putBytes(SampleGameRoot, "$Citadel/addons/631628_pak07_dir.vpk", FakeVpk.directory(seed = 7))
}

private val severalSets: InMemoryGameFiles.() -> Unit = {
    oneSet()
    putBytes(SampleGameRoot, "$Citadel/addons/pak42_dir.vpk", FakeVpk.directory(chunkReads = mapOf(0 to 4_000L, 1 to 4_000L), seed = 42))
    putBytes(SampleGameRoot, "$Citadel/addons/pak42_000.vpk", ByteArray(4_000) { it.toByte() })
    putBytes(SampleGameRoot, "$Citadel/addons/pak42_001.vpk", ByteArray(4_000) { (it * 3).toByte() })
    putBytes(SampleGameRoot, "$Citadel/hideout_addons2/pak03_dir.vpk", FakeVpk.directory(seed = 3))
}

private val copyOfAMod: InMemoryGameFiles.() -> Unit = {
    severalSets()
    val werecat = sampleLibraryManifest().records.single { it.title == "Night of the Werecat" }.sets.single()
    werecat.files.forEach { file -> putFile(SampleGameRoot, "$Citadel/addons/${file.part.fileName("pak43")}", file.image.sizeBytes, file.image.sha256) }
}

private fun oneSet(out: File) {
    val fixture = InstalledFixture(sampleLibraryManifest().records, planted = oneSet)
    runCheck(out, "one_set", fixture.state()) {
        advance(1_200)
        check(hasText("1 mod in addons isn’t from Hideout and still loads"))
        capture("note")
        clickLabel("Review…")
        advance(600)
        check(hasText("Mods Hideout didn’t add") && hasText("pak41_dir.vpk"))
        check(!hasText("631628_pak07_dir.vpk")) { "a name the game does not mount is not listed" }
        capture("dialog")
        clickLabel("Move out")
        advance(900)
        check(fixture.files.identity(SampleGameRoot, "$Citadel/addons/pak41_dir.vpk") == null)
        check(fixture.files.everyFile(SampleGameRoot).keys.any { it.startsWith("$Citadel/${CitadelPath.HideoutMoved.value}/") && it.endsWith("/addons/pak41_dir.vpk") })
        check(hasText("Nothing was deleted.") && hasText("Everything that loads now is in your library."))
        capture("after_move")
        clickNear("Open folder", "Nothing was deleted.")
        check(fixture.opened.single().second.value.startsWith("${CitadelPath.HideoutMoved.value}/"))
        clickNear("Close", "Nothing was deleted.")
        advance(600)
        check(!hasText("isn’t from Hideout") && !hasText("Mods Hideout didn’t add"))
        capture("note_gone")
    }
    println("Leftovers one_set passed")
}

private fun severalSets(out: File) {
    val fixture = InstalledFixture(sampleLibraryManifest().records, planted = severalSets)
    runCheck(out, "several_sets", fixture.state()) {
        advance(1_200)
        check(hasText("3 mods in the game’s mod folders aren’t from Hideout and still load"))
        capture("note")
        clickLabel("Review…")
        advance(600)
        check(hasText("pak42_dir.vpk") && hasText("hideout_addons2") && hasText("3 files"))
        capture("dialog")
        clickNear("Add", "pak42_dir.vpk")
        advance(500)
        check(hasText("Added to your library.") && !hasText("pak42_dir.vpk"))
        check((fixture.container.modLibrary.state.value.library as LibraryStatus.Ready).records.any { it.title == "pak42_dir" })
        capture("after_add")
        clickLabel("Move all out")
        advance(900)
        check(hasText("Everything that loads now is in your library.") && hasText("Nothing was deleted."))
        capture("after_move_all")
    }
    println("Leftovers several_sets passed")
}

private fun whileRunning(out: File) {
    val fixture = InstalledFixture(sampleLibraryManifest().records, planted = severalSets)
    runCheck(out, "while_running", fixture.state()) {
        advance(1_200)
        clickLabel("Review…")
        advance(600)
        fixture.processes.scan = ProcessScan.Seen(game = true)
        awaitReal(what = "Deadlock seen running") { fixture.container.modLibrary.state.value.deadlock == GameActivity.Running }
        check(hasText("Close Deadlock to add or move these."))
        check(nodes().filter { it.hasLabel("Move all out") || it.hasLabel("Add all to library") }.all { it.config.contains(SemanticsProperties.Disabled) })
        capture("dialog")
        clickNear("Close", "Close Deadlock")
        advance(600)
        check(hasText("Deadlock is running") && !hasText("aren’t from Hideout")) { "while it runs, the note about Deadlock comes first" }
        capture("note_yields")
        check(fixture.files.linkedPaths.isEmpty() && fixture.files.removedPaths.isEmpty())
    }
    println("Leftovers while_running passed")
}

private fun copyOfAMod(out: File) {
    val fixture = InstalledFixture(sampleLibraryManifest().records, planted = copyOfAMod)
    runCheck(out, "copy_of_a_mod", fixture.state()) {
        advance(1_200)
        check(hasText("4 mods in the game’s mod folders aren’t from Hideout and still load"))
        clickLabel("Review…")
        advance(600)
        check(hasText("pak43_dir.vpk") && hasText("Same as Night of the Werecat in your library"))
        check(!hasText("Add all to library") && hasText("Add 3 to library"))
        capture("dialog")
        clickLabel("Add 3 to library")
        advance(900)
        check(hasText("Added to your library.") && hasText("pak43_dir.vpk") && hasText("Same as Night of the Werecat in your library"))
        check((fixture.container.modLibrary.state.value.library as LibraryStatus.Ready).records.none { it.title == "pak43_dir" })
        check(!hasText("Add to library") && hasText("Move these out so they don’t load twice.")) { "a copy left alone offers only Move out" }
        capture("copy_left")
        clickNear("Move out", "Same as Night of the Werecat")
        advance(900)
        check(hasText("Everything that loads now is in your library.") && hasText("Nothing was deleted."))
        check(fixture.files.identity(SampleGameRoot, "$Citadel/addons/pak02_dir.vpk") != null) { "the library's own Night of the Werecat stays" }
        capture("after_move")
    }
    println("Leftovers copy_of_a_mod passed")
}

private fun Script.clickNear(label: String, near: String) {
    val anchor = nodes().first { node -> node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { near in it.text } == true }.boundsInRoot.center
    clickNode(nodes().filter { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }.minBy { (it.boundsInRoot.center - anchor).getDistance() })
}
