package gg.hideouthq.core.data.live

import gg.hideouthq.core.model.Hero
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeroMatcherTest {
    private val matcher = HeroMatcher(
        listOf("The Doorman", "Mo & Krill", "Lady Geist", "Silver", "Haze", "Seven").map { Hero("hero_${it.lowercase().filter(Char::isLetter)}", it) },
    )

    @Test
    fun categoryNamesMatchWhateverTheirSpacingAndArticle() {
        assertEquals("hero_thedoorman", matcher.named("Doorman"))
        assertEquals("hero_mokrill", matcher.named("Mo & Krill"))
        assertEquals("hero_mokrill", matcher.named("mo&krill"))
        assertEquals("hero_ladygeist", matcher.named("LADY GEIST"))
        assertNull(matcher.named("Lady"))
    }

    @Test
    fun aTitleNamesAHeroOnlyWhenItNamesExactlyOne() {
        assertEquals("hero_ladygeist", matcher.mentionedIn("Bride of Victor | Lady Geist"))
        assertEquals("hero_thedoorman", matcher.mentionedIn("Akiyama as the Doorman"))
        assertNull(matcher.mentionedIn("Silver Haze"))
        assertNull(matcher.mentionedIn("Hazelnut skin"))
        assertNull(matcher.mentionedIn("Ladyship"))
    }
}
