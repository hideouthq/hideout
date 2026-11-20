package gg.hideouthq.feature.shell

import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.citadelPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FolderOpeningStateTest {
    @Test fun failedResultReachesTheNoticeAndRetryUsesTheExactCapturedFolder() = runBlocking {
        val calls = mutableListOf<Pair<GameRoot, CitadelPath>>()
        var accepted = false
        val state = FolderOpeningState(FolderOpener { root, folder -> calls += root to folder; accepted })
        val root = GameRoot("/Games 日本語/Deadlock")
        val folder = SetFolder.Off("local-skin").citadelPath
        assertFalse(state.open(root, folder))
        val failure = state.failed!!
        assertEquals("/Games 日本語/Deadlock/game/citadel/hideout_off/local-skin", failure.path)
        accepted = true
        state.retry()
        assertEquals(listOf(root to folder, root to folder), calls)
        assertNull(state.failed)
        assertFalse(state.opening)
    }

    @Test fun exceptionsAreVisibleAndWindowsPathsCanBeCopiedWithoutRewritingCharacters() = runBlocking {
        val state = FolderOpeningState(FolderOpener { _, _ -> throw IOException("association failed") })
        assertFalse(state.open(GameRoot("C:\\Games 日本語 & mods\\Deadlock\\"), CitadelPath.Addons))
        val failure = state.failed!!
        assertEquals("C:\\Games 日本語 & mods\\Deadlock\\game\\citadel\\addons", failure.path)
        state.dismiss(failure)
        assertNull(state.failed)
    }

    @Test fun cancellingDoesNotBecomeAFailureNoticeOrLeaveRetryBusy() = runBlocking {
        val state = FolderOpeningState(FolderOpener { _, _ -> throw CancellationException() })
        assertFailsWith<CancellationException> { state.open(GameRoot("/Deadlock"), CitadelPath.Addons) }
        assertNull(state.failed)
        assertFalse(state.opening)
    }

    @Test fun copyingAnOlderFailureCannotDismissANewerRequest() = runBlocking {
        val state = FolderOpeningState(FolderOpener { _, _ -> false })
        state.open(GameRoot("/first"), CitadelPath.Addons)
        val first = state.failed!!
        state.open(GameRoot("/second"), CitadelPath.Addons)
        state.dismiss(first)
        assertEquals(GameRoot("/second"), state.failed?.root)
    }
}
