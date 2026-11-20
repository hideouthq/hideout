package gg.hideouthq.core.data.fake

import gg.hideouthq.core.model.Author
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.ChangeKindTag
import gg.hideouthq.core.model.ChangeLine
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.CoverTone
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.model.ModUpdate
import gg.hideouthq.core.model.Requirement

internal object SampleDetails {
    private const val DefaultImageCount = 4

    private val lilith = ModDetail(
        modId = "lilith",
        authors = listOf(Author("Cobizal", "Modder"), Author("tribilin", "Modeler")),
        about = "Play Silver as Lilith, the Hell Warden. A complete remodel with a new intro animation, shop art, icons and particles.",
        version = "1.2",
        images = listOf(CoverTone.Green, CoverTone.Green, CoverTone.Ink, CoverTone.Green, CoverTone.Teal, CoverTone.Moss, CoverTone.Ink, CoverTone.Cream)
            .map { CoverImage(tone = it) },
        sourceUrl = "https://gamebanana.com/mods/723081",
        updates = listOf(
            ModUpdate(
                "Wolf form fixes", "1.2", CalendarDate(2026, 10, 4),
                listOf(
                    ChangeLine(ChangeKindTag.Fixed, "Wolf form no longer sticks in a T-pose after respawning."),
                    ChangeLine(ChangeKindTag.Improved, "Fur looks right on low shader settings."),
                    ChangeLine(ChangeKindTag.Added, "Werewolf Transform Lilith Cabezas variant."),
                ),
            ),
            ModUpdate(
                "Werewolf Transform", "1.1", CalendarDate(2026, 10, 3),
                listOf(
                    ChangeLine(ChangeKindTag.Added, "Werewolf Transform variant with its own wolf model."),
                    ChangeLine(ChangeKindTag.Fixed, "Portrait cropping in the hero select screen."),
                ),
            ),
            ModUpdate("First release", "1.0", CalendarDate(2026, 10, 2), listOf(ChangeLine(ChangeKindTag.Added, "Full Lilith remodel, intro animation, UI images and particles."))),
        ),
        updatesUrl = "https://gamebanana.com/mods/updates/723081",
        requirements = listOf(
            Requirement("Night of the Werecat", "https://gamebanana.com/mods/551202", "werecat"),
            Requirement("Compact item shop", "https://gamebanana.com/mods/551230", "compact-shop"),
            Requirement("Lilith voice lines", "https://modding.example/lilith-voice"),
            Requirement("Shader cache cleared after installing"),
        ),
    )

    fun detailFor(mod: Mod, heroName: String?): ModDetail = if (mod.id == lilith.modId) lilith else ModDetail(
        modId = mod.id,
        authors = listOfNotNull(mod.author?.let { Author(it, "Modder") }),
        about = when (mod.category) {
            ModCategory.Skins -> "A new look for ${heroName ?: "this hero"}, with a reworked model and textures."
            ModCategory.Hud -> "An overhaul of the ${mod.kind ?: "HUD"} that keeps the information you need and drops the rest."
            ModCategory.Sounds -> "Replaces the game's ${mod.kind?.lowercase() ?: "sounds"} with a new set."
            else -> "Changes the world around you."
        },
        version = "1.0",
        images = List(DefaultImageCount) { CoverImage(tone = mod.cover.tone) },
    )
}
