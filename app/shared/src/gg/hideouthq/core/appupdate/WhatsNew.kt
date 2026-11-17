package gg.hideouthq.core.appupdate

import gg.hideouthq.core.model.CalendarDate

/** What the What's new dialog shows for an [AppUpdateState]. Whether it is open and which version is selected belong to the screen. */
data class WhatsNewView(
    val headline: WhatsNewHeadline,
    val versions: List<WhatsNewVersion>,
    val footer: WhatsNewFooter,
    val notesUrl: String?,
)

/** The line under the dialog's title. [Installed.released] is null when the feed has no notes for the running version. */
sealed interface WhatsNewHeadline {
    data class Available(val version: AppVersion, val running: AppVersion) : WhatsNewHeadline

    data class Ready(val version: AppVersion, val running: AppVersion) : WhatsNewHeadline

    data class Installed(val running: AppVersion, val released: CalendarDate?) : WhatsNewHeadline
}

data class WhatsNewVersion(val notes: ReleaseNotes, val tag: WhatsNewTag?)

enum class WhatsNewTag { New, Installed }

sealed interface WhatsNewFooter {
    data class Download(val sizeBytes: Long) : WhatsNewFooter

    data class Downloading(val percent: Int) : WhatsNewFooter

    data class Restart(val stage: RestartStage) : WhatsNewFooter

    data object GitHub : WhatsNewFooter

    data object UpToDate : WhatsNewFooter
}

private const val ShownVersions = 10

fun whatsNew(state: AppUpdateState): WhatsNewView {
    val offered = state.phase.version
    val notes = state.feed?.versions.orEmpty()
    return WhatsNewView(
        headline = when {
            offered == null -> WhatsNewHeadline.Installed(state.running, notes.firstOrNull { it.version == state.running }?.date)
            state.phase is AppUpdatePhase.Ready -> WhatsNewHeadline.Ready(offered, state.running)
            else -> WhatsNewHeadline.Available(offered, state.running)
        },
        versions = notes.take(ShownVersions).map { release ->
            WhatsNewVersion(
                release,
                when (release.version) {
                    state.running -> WhatsNewTag.Installed
                    offered -> WhatsNewTag.New
                    else -> null
                },
            )
        },
        footer = when (val phase = state.phase) {
            AppUpdatePhase.Idle -> WhatsNewFooter.UpToDate
            is AppUpdatePhase.GitHubOnly -> WhatsNewFooter.GitHub
            is AppUpdatePhase.Available -> WhatsNewFooter.Download(phase.pkg.sizeBytes)
            is AppUpdatePhase.Downloading -> WhatsNewFooter.Downloading(phase.percent)
            is AppUpdatePhase.Ready -> WhatsNewFooter.Restart(phase.restart)
            is AppUpdatePhase.Failed -> phase.pkg?.takeIf { phase.why != UpdateFailure.Unwritable && phase.why != UpdateFailure.NotReplaced }
                ?.let { WhatsNewFooter.Download(it.sizeBytes) } ?: WhatsNewFooter.GitHub
        },
        notesUrl = state.feed?.notesUrl,
    )
}

