package gg.hideouthq.core.model

/**
 * Top-level kinds of mod, in the order the catalogue lists them. They mirror GameBanana's Deadlock
 * root categories, with every sound root folded into [Sounds]; a root the app does not know yet
 * lands in [Other].
 */
enum class ModCategory { Skins, Hud, Sounds, Models, QualityOfLife, Gameplay, Animations, Maps, Other }
