package gg.hideouthq.core.model

/** Settings › Appearance › "Accent colour": which primary colour family the app wears. */
enum class Accent { Graphite, Ember, Soul, Ocean, Violet, Rose }

/** Settings › Appearance › "Mod cards": where a mod card on Discover, Browse and Library writes its title. */
enum class ModCardStyle { OnImage, UnderImage }

data class Appearance(val accent: Accent = Accent.Graphite, val modCards: ModCardStyle = ModCardStyle.OnImage)
