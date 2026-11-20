package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.LocalRefusal
import gg.hideouthq.core.data.Withdrawal
import gg.hideouthq.core.library.GameInfoProblem
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.RejectedSet
import gg.hideouthq.core.library.RejectionReason
import gg.hideouthq.core.library.SetProblem
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.problem_addons_unreadable
import gg.hideouthq.resources.problem_already_installed
import gg.hideouthq.resources.problem_another_tool
import gg.hideouthq.resources.problem_archive_corrupt
import gg.hideouthq.resources.problem_archive_dictionary
import gg.hideouthq.resources.problem_archive_duplicate
import gg.hideouthq.resources.problem_archive_encrypted
import gg.hideouthq.resources.problem_archive_format
import gg.hideouthq.resources.problem_archive_link
import gg.hideouthq.resources.problem_archive_method
import gg.hideouthq.resources.problem_archive_no_vpk
import gg.hideouthq.resources.problem_archive_no_vpk_config
import gg.hideouthq.resources.problem_archive_no_vpk_cursors
import gg.hideouthq.resources.problem_archive_no_vpk_loose
import gg.hideouthq.resources.problem_archive_nested
import gg.hideouthq.resources.problem_archive_size
import gg.hideouthq.resources.problem_archive_split
import gg.hideouthq.resources.problem_archive_timed_out
import gg.hideouthq.resources.problem_archive_too_large
import gg.hideouthq.resources.problem_archive_too_many
import gg.hideouthq.resources.problem_autoexec_write
import gg.hideouthq.resources.problem_reticle_write
import gg.hideouthq.resources.problem_backup_failed
import gg.hideouthq.resources.problem_backup_unreadable
import gg.hideouthq.resources.problem_download_disk
import gg.hideouthq.resources.problem_download_http
import gg.hideouthq.resources.problem_download_md5
import gg.hideouthq.resources.problem_download_gone
import gg.hideouthq.resources.problem_download_network
import gg.hideouthq.resources.problem_download_network_resume
import gg.hideouthq.resources.problem_download_refused
import gg.hideouthq.resources.problem_download_server
import gg.hideouthq.resources.problem_download_size
import gg.hideouthq.resources.problem_extraction
import gg.hideouthq.resources.problem_file_changed
import gg.hideouthq.resources.problem_file_missing
import gg.hideouthq.resources.problem_file_not_offered
import gg.hideouthq.resources.problem_file_unreadable
import gg.hideouthq.resources.problem_game_not_found
import gg.hideouthq.resources.problem_custom_game_running
import gg.hideouthq.resources.problem_game_running
import gg.hideouthq.resources.problem_gameinfo_ambiguous
import gg.hideouthq.resources.problem_gameinfo_changed
import gg.hideouthq.resources.problem_gameinfo_conflict
import gg.hideouthq.resources.problem_gameinfo_layout
import gg.hideouthq.resources.problem_gameinfo_missing
import gg.hideouthq.resources.problem_gameinfo_no_base
import gg.hideouthq.resources.problem_gameinfo_no_search_paths
import gg.hideouthq.resources.problem_gameinfo_unparseable
import gg.hideouthq.resources.problem_gameinfo_unreadable
import gg.hideouthq.resources.problem_gameinfo_write
import gg.hideouthq.resources.problem_interrupted
import gg.hideouthq.resources.problem_library_unavailable
import gg.hideouthq.resources.problem_listing_incomplete
import gg.hideouthq.resources.problem_loading_not_verified
import gg.hideouthq.resources.problem_local_unavailable
import gg.hideouthq.resources.problem_local_unreadable
import gg.hideouthq.resources.problem_manifest_save
import gg.hideouthq.resources.problem_metadata
import gg.hideouthq.resources.problem_name_taken
import gg.hideouthq.resources.problem_no_complete_set
import gg.hideouthq.resources.problem_no_files
import gg.hideouthq.resources.problem_not_mod_file
import gg.hideouthq.resources.problem_no_room_in_order
import gg.hideouthq.resources.problem_not_gamebanana
import gg.hideouthq.resources.problem_not_installed
import gg.hideouthq.resources.problem_not_installed_file
import gg.hideouthq.resources.problem_number_taken
import gg.hideouthq.resources.problem_placement
import gg.hideouthq.resources.problem_private
import gg.hideouthq.resources.problem_same_file_installed
import gg.hideouthq.resources.problem_processes_unknown
import gg.hideouthq.resources.problem_profile_busy
import gg.hideouthq.resources.problem_profile_last
import gg.hideouthq.resources.problem_profile_missing
import gg.hideouthq.resources.problem_profile_name
import gg.hideouthq.resources.problem_profile_name_taken
import gg.hideouthq.resources.problem_set_missing
import gg.hideouthq.resources.problem_set_needs_chunks
import gg.hideouthq.resources.problem_set_orphan
import gg.hideouthq.resources.problem_set_short
import gg.hideouthq.resources.problem_set_unreadable
import gg.hideouthq.resources.problem_trashed
import gg.hideouthq.resources.problem_undo_conflict
import gg.hideouthq.resources.problem_undo_expired
import gg.hideouthq.resources.problem_unexpected
import gg.hideouthq.resources.problem_update_changed
import gg.hideouthq.resources.problem_version_changed
import gg.hideouthq.resources.problem_withheld
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

object LibraryLabels {
    @Composable
    fun problem(problem: LibraryProblem): String = when (problem) {
        LibraryProblem.InvalidProfileName -> stringResource(Res.string.problem_profile_name)
        LibraryProblem.ProfileNameTaken -> stringResource(Res.string.problem_profile_name_taken)
        LibraryProblem.ProfileNotFound -> stringResource(Res.string.problem_profile_missing)
        LibraryProblem.LastProfile -> stringResource(Res.string.problem_profile_last)
        LibraryProblem.Busy -> stringResource(Res.string.problem_profile_busy)
        LibraryProblem.UndoExpired -> stringResource(Res.string.problem_undo_expired)
        LibraryProblem.UndoConflict -> stringResource(Res.string.problem_undo_conflict)
        LibraryProblem.VersionChanged -> stringResource(Res.string.problem_version_changed)
        LibraryProblem.UpdateChanged -> stringResource(Res.string.problem_update_changed)
        LibraryProblem.ListingIncomplete -> stringResource(Res.string.problem_listing_incomplete)
        is LibraryProblem.LocalInputRefused -> stringResource(
            when (problem.reason) {
                LocalRefusal.UnsupportedType -> Res.string.problem_archive_format
                LocalRefusal.SplitArchive -> Res.string.problem_archive_split
                LocalRefusal.Empty -> Res.string.problem_not_mod_file
                LocalRefusal.Unreadable -> Res.string.problem_local_unreadable
                LocalRefusal.Unavailable -> Res.string.problem_local_unavailable
            },
        )
        is LibraryProblem.SameFileInstalled -> stringResource(Res.string.problem_same_file_installed, problem.title)
        LibraryProblem.GameNotFound -> stringResource(Res.string.problem_game_not_found)
        LibraryProblem.GameRunning -> stringResource(Res.string.problem_game_running)
        LibraryProblem.CustomGameRunning -> stringResource(Res.string.problem_custom_game_running)
        is LibraryProblem.ProcessesUnknown -> stringResource(Res.string.problem_processes_unknown)
        is LibraryProblem.LibraryUnavailable -> stringResource(Res.string.problem_library_unavailable)
        is LibraryProblem.Interrupted -> stringResource(Res.string.problem_interrupted)
        LibraryProblem.NotAGameBananaMod -> stringResource(Res.string.problem_not_gamebanana)
        LibraryProblem.AlreadyInstalled -> stringResource(Res.string.problem_already_installed)
        is LibraryProblem.Withdrawn -> when (problem.why) {
            Withdrawal.Trashed -> stringResource(Res.string.problem_trashed)
            Withdrawal.Withheld -> stringResource(Res.string.problem_withheld)
            Withdrawal.Private -> stringResource(Res.string.problem_private)
        }
        is LibraryProblem.MetadataUnavailable -> stringResource(Res.string.problem_metadata)
        LibraryProblem.NoFiles -> stringResource(Res.string.problem_no_files)
        LibraryProblem.FileNotOffered -> stringResource(Res.string.problem_file_not_offered)
        is LibraryProblem.Download -> when (val download = problem.problem) {
            is DownloadProblem.Network -> stringResource(if (download.resumable) Res.string.problem_download_network_resume else Res.string.problem_download_network)
            is DownloadProblem.Http -> when (download.status) {
                404, 410 -> stringResource(Res.string.problem_download_gone)
                401, 403 -> stringResource(Res.string.problem_download_refused, download.status)
                408, 429, in 500..599 -> stringResource(Res.string.problem_download_server, download.status)
                else -> stringResource(Res.string.problem_download_http, download.status)
            }
            is DownloadProblem.SizeMismatch -> stringResource(Res.string.problem_download_size)
            is DownloadProblem.Md5Mismatch -> stringResource(Res.string.problem_download_md5)
            is DownloadProblem.Disk -> stringResource(Res.string.problem_download_disk)
        }
        is LibraryProblem.ArchiveRefused -> {
            val entry = problem.rejection.entry.orEmpty()
            when (problem.rejection.reason) {
                RejectionReason.UnsupportedFormat, RejectionReason.UnsupportedEntry -> stringResource(Res.string.problem_archive_format)
                RejectionReason.Encrypted -> stringResource(Res.string.problem_archive_encrypted)
                RejectionReason.SplitVolume -> stringResource(Res.string.problem_archive_split)
                RejectionReason.Link -> stringResource(Res.string.problem_archive_link)
                RejectionReason.DuplicateName -> stringResource(Res.string.problem_archive_duplicate, entry)
                RejectionReason.TooManyEntries -> stringResource(Res.string.problem_archive_too_many)
                RejectionReason.TooLarge -> stringResource(Res.string.problem_archive_too_large)
                RejectionReason.SizeMismatch -> stringResource(Res.string.problem_archive_size)
                RejectionReason.UnsupportedMethod -> stringResource(Res.string.problem_archive_method)
                RejectionReason.DictionaryTooLarge -> stringResource(Res.string.problem_archive_dictionary)
                RejectionReason.Corrupt -> stringResource(Res.string.problem_archive_corrupt)
                RejectionReason.NoVpk -> stringResource(Res.string.problem_archive_no_vpk)
                RejectionReason.NoVpkConfig -> stringResource(Res.string.problem_archive_no_vpk_config)
                RejectionReason.NoVpkCursors -> stringResource(Res.string.problem_archive_no_vpk_cursors)
                RejectionReason.NoVpkLooseFiles -> stringResource(Res.string.problem_archive_no_vpk_loose)
                RejectionReason.NestedArchive -> stringResource(Res.string.problem_archive_nested)
                RejectionReason.TimedOut -> stringResource(Res.string.problem_archive_timed_out)
            }
        }
        is LibraryProblem.ExtractionFailed -> stringResource(Res.string.problem_extraction)
        is LibraryProblem.NoCompleteSet ->
            if (problem.rejected.all { it.problem is SetProblem.Unreadable }) stringResource(Res.string.problem_not_mod_file)
            else stringResource(Res.string.problem_no_complete_set, rejected(problem.rejected.first { it.problem !is SetProblem.Unreadable }))
        is LibraryProblem.NoRoomInOrder -> pluralStringResource(Res.plurals.problem_no_room_in_order, problem.count, problem.count)
        is LibraryProblem.AddonsUnreadable -> stringResource(Res.string.problem_addons_unreadable)
        is LibraryProblem.NameTaken -> stringResource(Res.string.problem_name_taken, problem.name)
        is LibraryProblem.PlacementFailed -> stringResource(Res.string.problem_placement)
        is LibraryProblem.GameInfo -> gameInfo(problem.problem)
        LibraryProblem.GameInfoChanged -> stringResource(Res.string.problem_gameinfo_changed)
        is LibraryProblem.GameInfoConflict -> stringResource(Res.string.problem_gameinfo_conflict)
        is LibraryProblem.GameInfoWriteFailed -> stringResource(Res.string.problem_gameinfo_write)
        is LibraryProblem.AutoexecWriteFailed -> stringResource(Res.string.problem_autoexec_write)
        is LibraryProblem.ReticleWriteFailed -> stringResource(Res.string.problem_reticle_write)
        LibraryProblem.AnotherToolLoadsMods -> stringResource(Res.string.problem_another_tool)
        LibraryProblem.LoadingNotVerified -> stringResource(Res.string.problem_loading_not_verified)
        is LibraryProblem.ManifestSave -> stringResource(Res.string.problem_manifest_save)
        is LibraryProblem.Unexpected -> stringResource(Res.string.problem_unexpected)
        LibraryProblem.NotInstalled -> stringResource(Res.string.problem_not_installed)
        LibraryProblem.NotTheInstalledFile -> stringResource(Res.string.problem_not_installed_file)
        is LibraryProblem.FileMissing -> stringResource(Res.string.problem_file_missing, problem.path)
        is LibraryProblem.FileChanged -> stringResource(Res.string.problem_file_changed, problem.path)
        is LibraryProblem.FileUnreadable -> stringResource(Res.string.problem_file_unreadable, problem.path)
        is LibraryProblem.NumberTaken -> stringResource(Res.string.problem_number_taken, problem.number.pak.value.toString().padStart(2, '0'), problem.names.joinToString())
        LibraryProblem.LoadOrderChanged -> "Your mods changed since this order was shown, so nothing was moved. Try again."
        is LibraryProblem.BackupFailed -> stringResource(Res.string.problem_backup_failed)
        is LibraryProblem.BackupUnreadable -> stringResource(Res.string.problem_backup_unreadable)
    }

    /** Whether [problem] lies in the file itself, so handing Hideout the same file again ends the same way. */
    fun fileAtFault(problem: LibraryProblem): Boolean = when (problem) {
        is LibraryProblem.LocalInputRefused -> problem.reason != LocalRefusal.Unreadable
        is LibraryProblem.ArchiveRefused -> problem.rejection.reason != RejectionReason.TimedOut
        is LibraryProblem.NoCompleteSet, is LibraryProblem.SameFileInstalled -> true
        else -> false
    }

    @Composable
    fun rejected(set: RejectedSet): String = when (val problem = set.problem) {
        is SetProblem.MissingChunks -> stringResource(Res.string.problem_set_missing, problem.names.joinToString())
        is SetProblem.ChunkTooShort -> stringResource(Res.string.problem_set_short, problem.name)
        is SetProblem.ChunkIndexTooLarge, is SetProblem.Unreadable -> stringResource(Res.string.problem_set_unreadable, set.key)
        SetProblem.NeedsChunks -> stringResource(Res.string.problem_set_needs_chunks, set.key)
        SetProblem.Orphan -> stringResource(Res.string.problem_set_orphan, set.key)
    }

    @Composable
    private fun gameInfo(problem: GameInfoProblem): String = when (problem) {
        GameInfoProblem.Missing -> stringResource(Res.string.problem_gameinfo_missing)
        GameInfoProblem.Unreadable -> stringResource(Res.string.problem_gameinfo_unreadable)
        GameInfoProblem.Unparseable -> stringResource(Res.string.problem_gameinfo_unparseable)
        GameInfoProblem.NoSearchPaths -> stringResource(Res.string.problem_gameinfo_no_search_paths)
        GameInfoProblem.AmbiguousSearchPaths -> stringResource(Res.string.problem_gameinfo_ambiguous)
        GameInfoProblem.NoBaseGameEntry -> stringResource(Res.string.problem_gameinfo_no_base)
        GameInfoProblem.UnsupportedLayout -> stringResource(Res.string.problem_gameinfo_layout)
    }
}
