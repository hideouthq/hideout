package gg.hideouthq.core.data.fake

import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.CoverTone
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory

internal object SampleCatalog {
    private val NonSlug = Regex("[^a-z0-9]+")
    private const val BytesPerMb = 1_000_000L

    val Today = CalendarDate(2026, 10, 6)

    val CheckedAt = Today.epochSeconds + 9 * 3_600

    /**
     * Each hero's real `background_image_webp` from the Deadlock API, verified against the live
     * endpoint (never constructed from the display name: Celeste's internal name is `unicorn`, Silver's
     * is `werewolf`, and so on), so sample renders of the Discover hero show real background art.
     */
    private val HeroBackgrounds: Map<String, String> = mapOf(
        "Abrams" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/abrams_bg.webp",
        "Apollo" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/fencer_bg.webp",
        "Baba" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/baba_bg.webp",
        "Bebop" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/bebop_bg.webp",
        "Billy" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/billy_bg.webp",
        "Calico" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/calico_bg.webp",
        "Celeste" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/unicorn_bg.webp",
        "Deadman Danny" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/deadpack_bg.webp",
        "Drifter" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/drifter_bg.webp",
        "Dynamo" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/dynamo_bg.webp",
        "Graves" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/necro_bg.webp",
        "Grey Talon" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/grey_talon_bg.webp",
        "Haze" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/haze_bg.webp",
        "Holliday" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/astro_bg.webp",
        "Infernus" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/infernus_bg.webp",
        "Ivy" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/ivy_bg.webp",
        "Kelvin" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/kelvin_bg.webp",
        "Lady Geist" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/geist_bg.webp",
        "Lash" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/lash_bg.webp",
        "McGinnis" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/mcginnis_bg.webp",
        "Mina" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/mina_bg.webp",
        "Mirage" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/mirage_bg.webp",
        "Mo & Krill" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/krill_bg.webp",
        "Nurse Harrow" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/nurse_bg.webp",
        "Paige" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/patience_bg.webp",
        "Paradox" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/paradox_bg.webp",
        "Pocket" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/pocket_bg.webp",
        "Rat King" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/ratking_bg.webp",
        "Rem" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/familiar_bg.webp",
        "Seven" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/seven_bg.webp",
        "Shiv" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/shiv_bg.webp",
        "Silver" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/werewolf_bg.webp",
        "Sinclair" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/magician_bg.webp",
        "Solomon" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/chessmaster_bg.webp",
        "The Doorman" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/doorman_bg.webp",
        "Venator" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/priest_bg.webp",
        "Victor" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/victor_bg.webp",
        "Vindicta" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/vindicta_bg.webp",
        "Violet" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/artist_bg.webp",
        "Viscous" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/viscous_bg.webp",
        "Vyper" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/vyper_bg.webp",
        "Warden" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/warden_bg.webp",
        "Wraith" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/wraith_bg.webp",
        "Yamato" to "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/backgrounds/yamato_bg.webp",
    )

    val heroes: List<Hero> = listOf(
        "Abrams", "Apollo", "Baba", "Bebop", "Billy", "Calico", "Celeste", "Deadman Danny", "Drifter", "Dynamo",
        "Graves", "Grey Talon", "Haze", "Holliday", "Infernus", "Ivy", "Kelvin", "Lady Geist", "Lash", "McGinnis",
        "Mina", "Mirage", "Mo & Krill", "Nurse Harrow", "Paige", "Paradox", "Pocket", "Rat King", "Rem", "Seven",
        "Shiv", "Silver", "Sinclair", "Solomon", "The Doorman", "Venator", "Victor", "Vindicta", "Violet", "Viscous",
        "Vyper", "Warden", "Wraith", "Yamato",
    ).map { Hero(heroId(it), it, backgroundUrl = HeroBackgrounds[it]) }

    val shortListHeroIds: List<String> = listOf(
        "Celeste", "Grey Talon", "Haze", "Infernus", "Ivy", "Mina", "Pocket", "Seven", "Silver", "Vindicta", "Vyper", "Yamato",
    ).map(::heroId)

    val mods: List<Mod> = listOf(
        skin("lilith", "Lilith, the Hell Warden", "Silver", "Cobizal", 4_100, 40, 93, date(10, 2), date(10, 4), 2, CoverTone.Green),
        skin("faun-celeste", "Faun Celeste", "Celeste", "basilisken", 12_400, 310, 112, date(9, 28), date(9, 30), 1, CoverTone.Cream),
        skin("plague-doctor", "Plague Doctor Pocket", "Pocket", "GigWhat", 9_800, 240, 58, date(9, 21), date(9, 24), 3, CoverTone.Ink),
        skin("propeller-hat", "Vyper Propeller Hat", "Vyper", "chipmajor", 7_200, 175, 21, date(10, 3), date(10, 3), 4, CoverTone.Teal),
        skin("sadistic-warden", "Mina - Sadistic Warden", "Mina", "ArcDestiny", 5_100, 132, 77, date(9, 15), date(9, 29), 5, CoverTone.Ember),
        skin("grey-talon-upgrade", "Grey Talon Upgrade", "Grey Talon", "nekiteska", 3_400, 88, 49, date(8, 1), date(9, 10), 6, CoverTone.Cream),
        skin("garden-goddess", "Garden Goddess Ivy", "Ivy", "c a t a t o n i c", 2_200, 61, 73, date(7, 12), date(8, 30), 7, CoverTone.Moss),
        skin("werecat", "Night of the Werecat", "Silver", "UserUnknownVR", 1_700, 45, 64, date(8, 24), date(9, 2), 8, CoverTone.Ink),
        other("hudlock", "HUDlock 2.0", ModCategory.Hud, "Gameplay HUD", "Waltee", 107_000, 2_300, 34, date(3, 2), date(9, 20), 9, CoverTone.Teal),
        other("compact-shop", "Compact item shop", ModCategory.Hud, "Items HUD", null, 41_000, 820, 12, date(6, 9), date(7, 1), 10, CoverTone.Green),
        other("clean-ability-bar", "Clean ability bar", ModCategory.Hud, "Character HUD", null, 28_000, 540, 9, date(4, 20), date(4, 20), 11, CoverTone.Ink, patch = false),
        other("announcer-pack", "Announcer pack", ModCategory.Sounds, "Voice lines", null, 33_000, 700, 48, date(9, 30), date(10, 1), 12, CoverTone.Ember),
        other("heavy-weapon-sfx", "Heavy weapon SFX", ModCategory.Sounds, "Weapons & items", null, 19_000, 380, 27, date(5, 5), date(6, 11), 13, CoverTone.Ink),
        other("lobby-jazz", "Lobby jazz", ModCategory.Sounds, "Music", null, 12_000, 260, 15, date(8, 18), date(8, 18), 14, CoverTone.Cream),
        other("retro-hit-markers", "Retro hit markers", ModCategory.Sounds, "Gameplay", null, 9_400, 150, 4, date(1, 1), date(2, 14), 15, CoverTone.Moss, patch = false),
        other("clear-outlines", "Clear enemy outlines", ModCategory.Gameplay, "Gameplay", "voxl", 18_400, 420, 2, date(9, 12), date(10, 1), 16, CoverTone.Teal),
        other("minimal-particles", "Minimal ability particles", ModCategory.Gameplay, "Gameplay", "fen", 11_200, 260, 6, date(8, 30), date(9, 18), 17, CoverTone.Ink),
        other("brighter-orbs", "Brighter soul orbs", ModCategory.Gameplay, "Gameplay", "Mirai", 9_700, 210, 1, date(9, 2), date(9, 2), 18, CoverTone.Moss),
        other("reduced-shake", "Reduced screen shake", ModCategory.Gameplay, "Gameplay", "okto", 6_100, 140, 1, date(7, 22), date(8, 14), 19, CoverTone.Cream),
    )

    val discoverFeed = DiscoverFeed(
        featured = listOf("lilith", "garden-goddess", "werecat", "grey-talon-upgrade"),
        heroesToBrowse = (
            listOf("Abrams", "Bebop", "Celeste", "Haze", "Infernus", "Ivy", "Lash", "Mina", "Pocket", "Seven", "Silver", "Vindicta", "Yamato").map(::heroId) +
                heroes.map { it.id }
            ).distinct(),
        trendingSkins = listOf("faun-celeste", "plague-doctor", "propeller-hat", "sadistic-warden"),
        trendingHud = listOf("hudlock", "compact-shop"),
        trendingSounds = listOf("announcer-pack", "heavy-weapon-sfx", "lobby-jazz", "retro-hit-markers"),
        trendingGameplay = listOf("clear-outlines", "minimal-particles", "brighter-orbs", "reduced-shake"),
    )

    fun heroId(name: String): String = name.lowercase().replace(NonSlug, "-").trim('-')

    private fun date(month: Int, day: Int) = CalendarDate(2026, month, day)

    private fun skin(
        id: String, title: String, hero: String, author: String, downloads: Int, likes: Int, sizeMb: Int,
        published: CalendarDate, updated: CalendarDate, rank: Int, tone: CoverTone,
    ) = Mod(
        id = id, title = title, category = ModCategory.Skins, heroId = heroId(hero), author = author,
        downloads = downloads, likes = likes, sizeBytes = sizeMb * BytesPerMb, has3dPreview = true,
        publishedAt = published.epochSeconds, updated = updated, trendingRank = rank, cover = CoverImage(tone = tone),
    )

    private fun other(
        id: String, title: String, category: ModCategory, kind: String, author: String?, downloads: Int, likes: Int,
        sizeMb: Int, published: CalendarDate, updated: CalendarDate, rank: Int, tone: CoverTone, patch: Boolean = true,
    ) = Mod(
        id = id, title = title, category = category, kind = kind, author = author, downloads = downloads,
        likes = likes, sizeBytes = sizeMb * BytesPerMb, worksOnCurrentPatch = patch, publishedAt = published.epochSeconds, updated = updated,
        trendingRank = rank, cover = CoverImage(tone = tone),
        audioPreviewUrl = if (category == ModCategory.Sounds) "sample://$id" else null,
    )
}
