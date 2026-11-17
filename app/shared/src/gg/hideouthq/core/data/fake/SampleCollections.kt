package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.model.CollectionLook
import gg.hideouthq.core.model.Glow
import gg.hideouthq.core.model.MonthDay
import gg.hideouthq.core.model.Rgb
import gg.hideouthq.core.model.Season
import gg.hideouthq.core.model.SkinCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object SampleCollections : CollectionsRepository {
    override val collections: StateFlow<List<SkinCollection>> = MutableStateFlow(
        listOf(
            collection(
                "halloween", "Halloween", Season(MonthDay(10, 1), MonthDay(11, 7)),
                CollectionLook(
                    Rgb(0x2A1530), Rgb(0x0D080E), Rgb(0xF2A65A),
                    listOf(Glow(Rgb(0xF2A65A), 0.45f, 0.8f, 0.18f, 0.38f), Glow(Rgb(0x7A3FA0), 0.35f, 0.25f, 0.3f, 0.45f)),
                    arch = false, rim = listOf(Rgb(0xF2A65A)),
                ),
                "werecat", "plague-doctor", "sadistic-warden", "lilith", "faun-celeste", "grey-talon-upgrade",
            ),
            collection(
                "winter", "Winter", Season(MonthDay(12, 1), MonthDay(1, 7)),
                CollectionLook(
                    Rgb(0x1A2C3E), Rgb(0x080D13), Rgb(0x8FD8E6),
                    listOf(Glow(Rgb(0x7FE3D0), 0.3f, 0.3f, 0.1f, 0.55f), Glow(Rgb(0x8FB4E8), 0.3f, 0.75f, 0.18f, 0.45f)),
                    arch = false, rim = listOf(Rgb(0xCFE8FF)),
                ),
                "faun-celeste", "propeller-hat", "garden-goddess",
            ),
            collection(
                "gothic", "Gothic", null,
                CollectionLook(Rgb(0x2E0C18), Rgb(0x0B0507), Rgb(0xE0506D), listOf(Glow(Rgb(0xC23A55), 0.45f, 0.5f, 0.22f, 0.3f)), arch = true, rim = listOf(Rgb(0xFF7A93))),
                "sadistic-warden", "lilith", "plague-doctor", "werecat",
            ),
            collection(
                "neon", "Neon", null,
                CollectionLook(
                    Rgb(0x1C0C34), Rgb(0x06040D), Rgb(0xB44FFF),
                    listOf(Glow(Rgb(0xFF4FD8), 0.45f, 0.18f, 0.4f, 0.4f), Glow(Rgb(0x4FF0FF), 0.45f, 0.82f, 0.4f, 0.4f)),
                    arch = false, rim = listOf(Rgb(0xFF4FD8), Rgb(0x4FF0FF)),
                ),
                "propeller-hat", "grey-talon-upgrade", "garden-goddess",
            ),
        ),
    ).asStateFlow()

    private fun collection(id: String, title: String, season: Season?, look: CollectionLook, vararg members: String) =
        SkinCollection(id, title, season, look, cover = members.take(CoverSize), members = members.toList())

    private const val CoverSize = 3
}
