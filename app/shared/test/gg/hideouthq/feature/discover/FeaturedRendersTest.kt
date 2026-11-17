package gg.hideouthq.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.SubmissionFile
import gg.hideouthq.core.data.live.LiveCatalogRepository
import gg.hideouthq.core.data.live.LiveFixtures
import gg.hideouthq.core.data.live.SnapshotStore
import gg.hideouthq.core.data.live.StubSource
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.data.preview.PreviewImageAddress
import gg.hideouthq.core.designsystem.image.RemoteImage
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef as InstalledRef
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.SubmissionRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FeaturedRendersTest {
    private val md5 = PreviewFixtures.Md5
    private val snapshot = LiveFixtures.snapshot().let { snapshot ->
        val files = mapOf(InstalledRef(SubmissionKind.Mod, 1) to SubmissionFile(101, md5), InstalledRef(SubmissionKind.Mod, 2) to SubmissionFile(202, md5))
        snapshot.copy(submissions = snapshot.submissions.map { it.copy(facts = it.facts?.copy(currentFile = files[it.key])) })
    }
    private val skin = PreviewFixtures.entry(PreviewFixtures.manifest(SubmissionRef("Mod", 1), 101, md5))
    private val pick = PreviewFixtures.entry(PreviewFixtures.manifest(SubmissionRef("Mod", 2), 202, md5))

    private class Loads(private val leadLoaded: CompletableDeferred<Unit>) : RemoteImages {
        val asked = mutableListOf<String>()

        @Composable
        override fun rememberImage(url: String, contentScale: ContentScale): RemoteImage? = null

        override suspend fun prefetch(url: String) {
            asked += url
            if (asked.size == 1) leadLoaded.await()
        }
    }

    @Test
    fun theFeaturedRendersAreLoadedOnceTheIndexNamesThemTheLeadFirst() = runTest {
        val previews = MutableStateFlow<PreviewIndex?>(null)
        val catalog = LiveCatalogRepository(StubSource(fetch = { snapshot }), SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now), previews = previews)
        val leadLoaded = CompletableDeferred<Unit>()
        val images = Loads(leadLoaded)
        backgroundScope.launch { prefetchFeaturedRenders(catalog, flowOf(emptyList()), images) }
        runCurrent()
        assertEquals(emptyList(), images.asked, "no render is known before the index")

        previews.value = PreviewFixtures.index(skin, pick)
        runCurrent()
        val lead = PreviewImageAddress.Picture(pick.bundleId, PictureRole.Featured).url
        val next = PreviewImageAddress.Picture(skin.bundleId, PictureRole.Featured).url
        assertEquals(listOf(lead), images.asked, "the render Discover opens on goes first, alone")

        leadLoaded.complete(Unit)
        runCurrent()
        assertEquals(listOf(lead, next), images.asked)

        previews.value = PreviewFixtures.index(skin, pick, generation = 2)
        runCurrent()
        assertEquals(listOf(lead, next), images.asked, "a render is loaded once")
    }
}
