package gg.hideouthq.feature.moddetail

import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.library.CompatStatus
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.InstallCompatibility
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.Operation
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.cancellable
import gg.hideouthq.core.library.installStatus
import gg.hideouthq.core.library.pageRecords
import gg.hideouthq.core.library.status

data class PageInstallation(
    /** Unknown until the library is read and while it cannot be trusted, so the page never offers it as installable. */
    val status: InstallStatus = InstallStatus.Unknown,
    val profiles: List<Profile> = emptyList(),
    val selectedProfiles: Set<ProfileId> = emptySet(),
    val record: ManifestRecord? = null,
    val operation: Operation? = null,
    /** The installed mod may be uninstalled now: whatever Deadlock does while it is off, else once Deadlock is known closed. */
    val canUninstall: Boolean = false,
    val canEditChoices: Boolean = false,
    /** A new install, a retry or another version may start, whatever Deadlock is doing. */
    val canInstall: Boolean = false,
    /** Accepted library work is still settling; [profiles] already show what it asked for. */
    val applying: Boolean = false,
    /** What checking the installed mod against the game found and whether its files are changing; its chip and details read it. */
    val compat: InstallCompatibility? = null,
    /** Why compatibility checks cannot run here, when they cannot. */
    val compatUnavailable: String? = null,
) {
    /** The installed mod's compatibility chip, the same as Library shows for it; null when it has nothing to say. */
    val compatStatus: CompatStatus? get() = record?.let { compat?.status(it.enabled) }

    /** The GameBanana files the mod is installed from, winner first; empty when it is not installed. */
    val installedFileIds: List<Long> get() = record?.sets?.mapNotNull { it.fileId }?.distinct().orEmpty()
    val canCancel: Boolean get() = operation?.stage?.cancellable == true
}

internal fun LibraryState.pageInstallation(modId: String): PageInstallation {
    val record = (library as? LibraryStatus.Ready)?.records?.pageRecords(modId)?.firstOrNull()
    val book = shownProfiles
    val operation = operations[modId]
    val targets = when {
        operation != null && operation.stage !is OperationStage.Failed -> operation.profiles
        record != null -> book?.profiles?.filter { record.installId in it.enabled }?.map { it.id }?.toSet().orEmpty()
        else -> setOfNotNull(book?.active)
    }
    return PageInstallation(
        status = installStatus(modId),
        profiles = book?.profiles.orEmpty(),
        selectedProfiles = targets,
        record = record,
        operation = operation,
        canUninstall = record != null && canEditChoices && record.installId !in leaving,
        canEditChoices = canEditChoices,
        canInstall = canInstall,
        applying = applying,
        compat = record?.let { compatibility.installs[it.installId] },
        compatUnavailable = compatibility.unavailable,
    )
}
