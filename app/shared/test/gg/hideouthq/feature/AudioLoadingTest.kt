package gg.hideouthq.feature

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.PlayingSample
import gg.hideouthq.core.data.SamplePreview
import gg.hideouthq.core.data.SoundSamplePlayer
import gg.hideouthq.feature.discover.DiscoverViewModel
import gg.hideouthq.feature.moddetail.ModDetailViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AudioLoadingTest {
    @Test
    fun detailLoadingCancelsAndReadyPlaybackPausesAndResumes() = withViewModels { viewModels ->
        val app = AppContainer.fake()
        val player = LoadingPlayer()
        val vm = ModDetailViewModel("announcer-pack", app.catalog, app.modLibrary, player, app.previews)
        viewModels.keep(vm)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.toggleSample()
        runCurrent()
        assertTrue(vm.uiState.value.sound!!.loading)
        assertFalse(vm.uiState.value.sound!!.playing)
        vm.toggleSample()
        runCurrent()
        assertNull(player.nowPlaying.value)
        assertFalse(vm.uiState.value.sound!!.loading)
        vm.toggleSample()
        player.nowPlaying.value = player.nowPlaying.value!!.copy(lengthMillis = 20_000)
        runCurrent()
        assertFalse(vm.uiState.value.sound!!.loading)
        assertTrue(vm.uiState.value.sound!!.playing)
        vm.toggleSample()
        runCurrent()
        assertTrue(player.nowPlaying.value!!.paused)
        assertFalse(vm.uiState.value.sound!!.playing)
        vm.toggleSample()
        runCurrent()
        assertTrue(vm.uiState.value.sound!!.playing)
    }

    @Test
    fun discoverRepeatedClickCancelsBeforeFlowRecomposition() = withViewModels { viewModels ->
        val app = AppContainer.fake()
        val player = LoadingPlayer()
        val vm = DiscoverViewModel(app.catalog, app.modLibrary, player)
        viewModels.keep(vm)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.toggleSample("announcer-pack")
        assertTrue(player.nowPlaying.value!!.loading)
        vm.toggleSample("announcer-pack")
        assertNull(player.nowPlaying.value)
        vm.toggleSample("announcer-pack")
        runCurrent()
        val row = vm.uiState.value.sounds.first { it.item.id == "announcer-pack" }
        assertTrue(row.playback!!.loading)
        assertFalse(row.playing)
        vm.toggleSample("announcer-pack")
        assertNull(player.nowPlaying.value)
    }

    private class LoadingPlayer : SoundSamplePlayer {
        override val nowPlaying = MutableStateFlow<PlayingSample?>(null)
        override fun play(preview: SamplePreview) { nowPlaying.value = PlayingSample(preview.modId, 0, null) }
        override fun pause() { nowPlaying.value = nowPlaying.value?.copy(paused = true) }
        override fun resume() { nowPlaying.value = nowPlaying.value?.copy(paused = false) }
        override fun stop() { nowPlaying.value = null }
        override fun seekTo(fraction: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
    }
}
