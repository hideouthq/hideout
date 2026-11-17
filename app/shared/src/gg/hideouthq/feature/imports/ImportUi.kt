package gg.hideouthq.feature.imports

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.library.ImportProblem
import gg.hideouthq.core.library.ImportWait
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.migration.Preview

/** Where mods can come from. */
@Immutable
sealed interface SourceOption {
    data class Installed(val manager: Manager) : SourceOption

    data object ExportFile : SourceOption
}

@Immutable
data class ImportCounts(val mods: Int, val profiles: Int, val crosshairs: Int, val commands: Int) {
    val isEmpty: Boolean get() = mods == 0 && profiles == 0 && crosshairs == 0 && commands == 0
}

/**
 * A source the list offers: a manager [found] on this PC, with its [counts] once it was looked at, a manager Hideout
 * can be pointed at, or an export file. A manager with nothing to bring is not offered.
 */
@Immutable
data class SourceCard(val option: SourceOption, val found: Boolean = false, val counts: ImportCounts? = null)

/** What can come along, one checkbox row each. */
enum class ImportItem { Mods, Profiles, Crosshair, Autoexec }

/** A row of what comes along. [names] lists the profiles on the Profiles row. */
@Immutable
data class ImportRow(val item: ImportItem, val count: Int, val ticked: Boolean, val enabled: Boolean = true, val names: List<String> = emptyList())

/**
 * Everything the import page draws, in setup and in Library's dialog alike, mapped by [importUi]. The page is the
 * [sources] while it is [listing], and otherwise what [from] brings as [rows]. [looking] until every manager found was
 * counted. [asking] is the source whose folder is being asked for. [problem] is why [from] has nothing to import, or
 * why the run [stopped].
 */
@Immutable
data class ImportUi(
    val looking: Boolean = true,
    val sources: List<SourceCard> = emptyList(),
    val listing: Boolean = false,
    val asking: SourceOption? = null,
    val from: SourceOption? = null,
    val preview: Preview.Ready? = null,
    val rows: List<ImportRow> = emptyList(),
    val problem: ImportProblem? = null,
    val importing: Boolean = false,
    val stopped: Boolean = false,
    /** What the run waits for, when it waits. */
    val wait: ImportWait? = null,
    val finished: Boolean = false,
) {
    /** How many mods [from] lists that have no files to bring. */
    val missing: Int get() = preview?.missing ?: 0

    /** A manager found on this PC has something to bring, so setup asks about it. */
    val offers: Boolean get() = sources.any { it.found && it.counts != null }

    val canImport: Boolean get() = !listing && !importing && rows.any { it.ticked }

    /**
     * The run needs no one watching: it finished, or it waits for something to close, which Library says by itself. A
     * run that stopped, or still works, does not count.
     */
    val settled: Boolean get() = finished || wait != null
}

/** The counts a source card shows. A crosshair is one, however many lines it has. */
val Preview.Ready.counts: ImportCounts
    get() = ImportCounts(mods.size, profiles.size, if (crosshair.isEmpty()) 0 else 1, commands.size)
