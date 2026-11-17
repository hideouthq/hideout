package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.designsystem.component.InstallButtonLabels
import gg.hideouthq.core.designsystem.component.InstallButtonState
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverTone
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.busy_gamebanana
import gg.hideouthq.resources.busy_goes_on_short
import gg.hideouthq.resources.busy_starts_short
import gg.hideouthq.resources.install_stalled
import gg.hideouthq.resources.install_waiting_game
import gg.hideouthq.resources.catalog_clock
import gg.hideouthq.resources.category_animation
import gg.hideouthq.resources.category_animations
import gg.hideouthq.resources.category_gameplay
import gg.hideouthq.resources.category_gameplay_one
import gg.hideouthq.resources.category_hud
import gg.hideouthq.resources.category_hud_one
import gg.hideouthq.resources.category_map
import gg.hideouthq.resources.category_maps
import gg.hideouthq.resources.category_model
import gg.hideouthq.resources.category_models
import gg.hideouthq.resources.category_other
import gg.hideouthq.resources.category_other_one
import gg.hideouthq.resources.category_quality_of_life
import gg.hideouthq.resources.category_quality_of_life_one
import gg.hideouthq.resources.category_skin
import gg.hideouthq.resources.category_skins
import gg.hideouthq.resources.category_sound
import gg.hideouthq.resources.category_sounds
import gg.hideouthq.resources.date_day_month
import gg.hideouthq.resources.date_day_month_year
import gg.hideouthq.resources.install
import gg.hideouthq.resources.profile_install_downloading
import gg.hideouthq.resources.profile_install_waiting
import gg.hideouthq.resources.profile_install_cancelling
import gg.hideouthq.resources.profile_install_choose
import gg.hideouthq.resources.install_installed
import gg.hideouthq.resources.install_installing
import gg.hideouthq.resources.install_preparing
import gg.hideouthq.resources.months_short
import gg.hideouthq.resources.size_gb
import gg.hideouthq.resources.size_kb
import gg.hideouthq.resources.size_mb
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

object ModLabels {
    const val Separator = " · "

    @Composable
    fun plural(category: ModCategory): String = stringResource(
        when (category) {
            ModCategory.Skins -> Res.string.category_skins
            ModCategory.Hud -> Res.string.category_hud
            ModCategory.Sounds -> Res.string.category_sounds
            ModCategory.Models -> Res.string.category_models
            ModCategory.QualityOfLife -> Res.string.category_quality_of_life
            ModCategory.Gameplay -> Res.string.category_gameplay
            ModCategory.Animations -> Res.string.category_animations
            ModCategory.Maps -> Res.string.category_maps
            ModCategory.Other -> Res.string.category_other
        },
    )

    @Composable
    fun singular(category: ModCategory): String = stringResource(
        when (category) {
            ModCategory.Skins -> Res.string.category_skin
            ModCategory.Hud -> Res.string.category_hud_one
            ModCategory.Sounds -> Res.string.category_sound
            ModCategory.Models -> Res.string.category_model
            ModCategory.QualityOfLife -> Res.string.category_quality_of_life_one
            ModCategory.Gameplay -> Res.string.category_gameplay_one
            ModCategory.Animations -> Res.string.category_animation
            ModCategory.Maps -> Res.string.category_map
            ModCategory.Other -> Res.string.category_other_one
        },
    )

    fun tint(tone: CoverTone?): Int? = tone?.ordinal

    @Composable
    fun size(bytes: Long): String {
        val amount = sizeAmount(bytes)
        val unit = when (amount.unit) {
            SizeUnit.KB -> Res.string.size_kb
            SizeUnit.MB -> Res.string.size_mb
            SizeUnit.GB -> Res.string.size_gb
        }
        return stringResource(unit, amount.value)
    }

    /** In decimal units: 146_145 → 146 KB, 2_400_000 → 2.4 MB, 93_000_000 → 93 MB, 1_250_000_000 → 1.3 GB. */
    fun sizeAmount(bytes: Long): SizeAmount = when {
        bytes < Megabyte - Kilobyte / 2 -> SizeAmount(((bytes + Kilobyte / 2) / Kilobyte).coerceAtLeast(1L).toString(), SizeUnit.KB)
        bytes < Ten * Megabyte -> SizeAmount(tenths(bytes, Megabyte), SizeUnit.MB)
        bytes < Gigabyte - Megabyte / 2 -> SizeAmount(((bytes + Megabyte / 2) / Megabyte).toString(), SizeUnit.MB)
        else -> SizeAmount(tenths(bytes, Gigabyte), SizeUnit.GB)
    }

    /** [bytes] in megabytes to one decimal, for a download that counts up: 24_600_000 → 24.6, 38_000_000 → 38. */
    fun megabytes(bytes: Long): String = tenths(bytes, Megabyte)

    private fun tenths(bytes: Long, unit: Long): String {
        val tenths = (bytes * Ten + unit / 2) / unit
        return if (tenths % Ten == 0L) "${tenths / Ten}" else "${tenths / Ten}.${tenths % Ten}"
    }

    /** 4100 → "4.1k", 12400 → "12.4k", 107000 → "107k", 950 → "950". */
    fun count(value: Int): String = when {
        value < Thousand -> value.toString()
        value < HundredThousand -> {
            val tenths = (value / (Thousand / Ten.toDouble())).roundToInt()
            if (tenths % Ten == 0) "${tenths / Ten}k" else "${tenths / Ten}.${tenths % Ten}k"
        }
        value < Million - Thousand / 2 -> "${(value / Thousand.toDouble()).roundToInt()}k"
        else -> {
            val tenths = (value / (Million / Ten.toDouble())).roundToInt()
            if (tenths % Ten == 0) "${tenths / Ten}M" else "${tenths / Ten}.${tenths % Ten}M"
        }
    }

    /** "2 Oct 2026". */
    @Composable
    fun date(date: CalendarDate): String =
        stringResource(Res.string.date_day_month_year, date.day, stringArrayResource(Res.array.months_short)[date.month - 1], date.year)

    /** "4 Oct". */
    @Composable
    fun dayMonth(date: CalendarDate): String =
        stringResource(Res.string.date_day_month, date.day, stringArrayResource(Res.array.months_short)[date.month - 1])

    /** "14:05". */
    @Composable
    fun clock(hour: Int, minute: Int): String = stringResource(Res.string.catalog_clock, hour.toString().padStart(2, '0'), minute.toString().padStart(2, '0'))

    fun installButton(status: InstallStatus): InstallButtonState = when (status) {
        InstallStatus.Unknown, InstallStatus.NotInstalled, InstallStatus.NeedsChoice, InstallStatus.Cancelling, is InstallStatus.Failed -> InstallButtonState.Idle
        InstallStatus.Preparing, is InstallStatus.Busy, InstallStatus.Waiting -> InstallButtonState.Downloading(0)
        is InstallStatus.Downloading -> InstallButtonState.Downloading(status.percent)
        InstallStatus.Installing, InstallStatus.WaitingForGame -> InstallButtonState.Downloading(PercentDone)
        is InstallStatus.Installed -> InstallButtonState.Installed
    }

    fun installButtonEnabled(status: InstallStatus): Boolean = status == InstallStatus.NotInstalled || status is InstallStatus.Failed || status is InstallStatus.Installed

    @Composable
    fun install(status: InstallStatus): InstallButtonLabels = InstallButtonLabels(
        install = stringResource(when (status) {
            InstallStatus.Cancelling -> Res.string.profile_install_cancelling
            InstallStatus.NeedsChoice -> Res.string.profile_install_choose
            else -> Res.string.install
        }),
        downloading = when (status) {
            InstallStatus.Preparing -> stringResource(Res.string.install_preparing)
            is InstallStatus.Busy -> stringResource(Res.string.busy_gamebanana)
            InstallStatus.Waiting -> stringResource(Res.string.profile_install_waiting)
            InstallStatus.Installing -> stringResource(Res.string.install_installing)
            InstallStatus.WaitingForGame -> stringResource(Res.string.install_waiting_game)
            is InstallStatus.Downloading -> if (status.stalled) stringResource(Res.string.install_stalled) else stringResource(Res.string.profile_install_downloading, status.percent)
            else -> stringResource(Res.string.profile_install_downloading, 0)
        },
        installed = stringResource(Res.string.install_installed),
    )

    /** What the button cannot say of a wait: when an install GameBanana holds goes on. Null for every other status. */
    @Composable
    fun installWait(status: InstallStatus): String? = (status as? InstallStatus.Busy)?.let {
        stringResource(if (it.started) Res.string.busy_goes_on_short else Res.string.busy_starts_short, busyWhen(it.until))
    }

    fun join(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(Separator)

    private const val PercentDone = 100
    private const val Ten = 10
    private const val Kilobyte = 1_000L
    private const val Megabyte = 1_000_000L
    private const val Gigabyte = 1_000_000_000L
    private const val Thousand = 1_000
    private const val HundredThousand = 100_000
    private const val Million = 1_000_000
}

data class SizeAmount(val value: String, val unit: SizeUnit)

enum class SizeUnit { KB, MB, GB }
