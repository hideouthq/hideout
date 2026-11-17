package gg.hideouthq.core.ui

import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.LocalRefusal
import gg.hideouthq.core.library.ArchiveRejection
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.RejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryLabelsTest {
    @Test
    fun onlyAFailureTheSameFileWouldRepeatGoesWithoutTryAgain() {
        val repeats = listOf(
            LibraryProblem.LocalInputRefused(LocalRefusal.SplitArchive),
            LibraryProblem.LocalInputRefused(LocalRefusal.Empty),
            LibraryProblem.ArchiveRefused(ArchiveRejection(RejectionReason.Encrypted, null)),
            LibraryProblem.ArchiveRefused(ArchiveRejection(RejectionReason.NestedArchive, null)),
            LibraryProblem.NoCompleteSet(emptyList()),
            LibraryProblem.SameFileInstalled("Skin", "skin"),
        )
        val mayGoOtherwise = listOf(
            LibraryProblem.LocalInputRefused(LocalRefusal.Unreadable),
            LibraryProblem.ArchiveRefused(ArchiveRejection(RejectionReason.TimedOut, null)),
            LibraryProblem.ExtractionFailed("no space"),
            LibraryProblem.Download(DownloadProblem.Network("reset")),
            LibraryProblem.GameRunning,
        )
        assertEquals(repeats.map { true }, repeats.map(LibraryLabels::fileAtFault))
        assertEquals(mayGoOtherwise.map { false }, mayGoOtherwise.map(LibraryLabels::fileAtFault))
    }
}
