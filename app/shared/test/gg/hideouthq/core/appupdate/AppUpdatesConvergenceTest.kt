package gg.hideouthq.core.appupdate

import gg.hideouthq.core.appupdate.AppUpdatePhase.Available
import gg.hideouthq.core.appupdate.AppUpdatePhase.Failed
import gg.hideouthq.core.appupdate.AppUpdatePhase.GitHubOnly
import gg.hideouthq.core.appupdate.AppUpdatePhase.Idle
import gg.hideouthq.core.appupdate.AppUpdatePhase.Ready
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesConvergenceTest {
    private enum class Leftover(val text: String?) {
        None(null),
        UpdatedToRunning(recordTo(Running)),
        FailedToOffered(recordTo(Offered)),
        FailedToOlder(recordTo(AppVersion(1, 2, 0))),
        Garbage("{half a reco"),
    }

    private enum class MethodKind { None, AtExit, WhileOpen, NoPackageForFormat }

    private data class World(
        val leftover: Leftover,
        val kept: AppVersion?,
        val fresh: AppVersion?,
        val method: MethodKind,
        val packageOnDisk: Boolean,
    )

    private fun feed(latest: AppVersion) = feedOf(latest, PackageFormat.AppImage).let { feed ->
        feed.copy(packages = feed.packages + (PackageFormat.Deb to packageOf(latest, PackageFormat.Deb)))
    }

    private val MethodKind.format: PackageFormat?
        get() = when (this) {
            MethodKind.None -> null
            MethodKind.AtExit -> PackageFormat.AppImage
            MethodKind.WhileOpen -> PackageFormat.Deb
            MethodKind.NoPackageForFormat -> PackageFormat.Msi
        }

    private fun expectedPhase(world: World): AppUpdatePhase {
        val latest = world.fresh ?: world.kept
        if (latest == null || latest <= Running) return Idle
        val pkg = world.method.format?.let { feed(latest).packages[it] }
        return when {
            world.leftover == Leftover.FailedToOffered && latest == Offered -> Failed(latest, pkg, UpdateFailure.NotReplaced, NotesUrl)
            pkg == null -> GitHubOnly(latest, NotesUrl)
            world.packageOnDisk && latest == Offered -> Ready(pkg, RestartStage.Idle)
            world.fresh != null -> Ready(pkg, RestartStage.Idle)
            else -> Available(pkg)
        }
    }

    private fun TestScope.startIn(rig: Rig, world: World): AppUpdates {
        val method = when (world.method) {
            MethodKind.None -> null
            MethodKind.AtExit -> rig.methods.atExit
            MethodKind.WhileOpen -> rig.methods.whileOpen
            MethodKind.NoPackageForFormat -> AppUpdateMethod.AtExit(PackageFormat.Msi) { _, _ -> InstallOutcome.Done }
        }
        return rig.updates(method).apply {
            start()
            runCurrent()
        }
    }

    @Test
    fun aStartConvergesFromEveryCombinationOfLeftoverRecordKeptFeedAndMethod() = runTest {
        val versions = listOf(null, Running, Offered, Newer)
        val worlds = Leftover.entries.flatMap { leftover ->
            versions.filter { it != Newer }.flatMap { kept ->
                versions.flatMap { fresh ->
                    MethodKind.entries.flatMap { method ->
                        listOf(false, true).map { World(leftover, kept, fresh, method, it) }
                    }
                }
            }
        }
        assertEquals(5 * 3 * 4 * 4 * 2, worlds.size)

        for (world in worlds) {
            val rig = Rig(this)
            world.leftover.text?.let { rig.records.write(RecordFile, it) }
            rig.feed.lastGood = world.kept?.let { KnownFeed(feed(it), Instant.fromEpochSeconds(0)) }
            rig.feed.answer = world.fresh?.let { FeedFetch.Fresh(KnownFeed(feed(it), Instant.fromEpochSeconds(1))) } ?: FeedFetch.Unreachable("offline")
            if (world.packageOnDisk) world.method.format?.let { rig.packages.onDisk += packageOf(Offered, it) }

            val first = startIn(rig, world).state.value
            assertEquals(expectedPhase(world), first.phase, "first start of $world")
            assertEquals(if (world.leftover == Leftover.UpdatedToRunning) Running else null, first.justUpdatedTo, "toast of $world")
            assertEquals(world.fresh?.let { feed(it) } ?: world.kept?.let { feed(it) }, first.feed, "feed of $world")
            assertEquals("", rig.records.read(RecordFile).orEmpty(), "record of $world")

            val onDiskNow = world.method.format?.let { packageOf(Offered, it) in rig.packages.onDisk } == true
            val second = startIn(rig, world).state.value
            assertEquals(expectedPhase(world.copy(leftover = Leftover.None, packageOnDisk = onDiskNow)), second.phase, "second start of $world")
            assertEquals(null, second.justUpdatedTo, "second toast of $world")
        }
    }
}
