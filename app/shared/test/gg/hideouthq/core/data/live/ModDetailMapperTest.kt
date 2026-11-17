package gg.hideouthq.core.data.live

import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.ChangeLine
import gg.hideouthq.core.model.ChangeKindTag
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.data.gamebanana.SubmissionUpdate
import gg.hideouthq.core.data.gamebanana.GameBananaFixtures
import gg.hideouthq.core.data.gamebanana.parseProfile
import gg.hideouthq.core.model.Author
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModDetailMapperTest {
    private val profile = parseProfile(GameBananaFixtures.Profile, SubmissionRef(SubmissionKind.Mod, 723542))

    @Test
    fun aPageBecomesTheModPagesDetails() {
        val detail = ModDetailMapper.toDetail(profile)
        assertEquals("mod-723542", detail.modId)
        assertEquals(listOf(Author("Human1", "Concept Art"), Author("Nobel", "3D Art"), Author("Deer_uwu", "Key Authors")), detail.authors)
        assertEquals("Victor Bride skin for Lady Geist\n\nModel by Nobel", detail.about)
        assertEquals("1.0", detail.version)
        assertEquals(listOf("https://images.gamebanana.com/img/ss/mods/530-90_6ac11043bd7f1.jpg", "https://images.gamebanana.com/img/ss/mods/6ac1141148b02.jpg"), detail.images.map { it.url })
        assertEquals("https://images.gamebanana.com/img/ss/mods/6ac11043bd7f1.jpg", detail.images.first().fullUrl)
        assertEquals("https://gamebanana.com/mods/723542", detail.sourceUrl)
    }

    @Test
    fun withoutCreditsTheSubmitterIsTheAuthorAndABlankVersionIsNone() {
        val detail = ModDetailMapper.toDetail(profile.copy(credits = emptyList(), version = "  "))
        assertEquals(listOf(Author("Nobel_", null)), detail.authors)
        assertNull(detail.version)
    }

    @Test
    fun updatesBecomeTheChangelogWithTheirLinesTaggedOrTheirNotesLineByLine() {
        val detail = ModDetailMapper.toDetail(
            profile,
            listOf(
                SubmissionUpdate("Wolf form fixes", "1.2", 1_791_100_800, listOf("Fixed it" to "Bugfix", "Smoother" to "Tweak", "New" to "Addition", "Gone" to "Removal"), null, setOf(7L)),
                SubmissionUpdate("First release", null, 1_790_000_000, emptyList(), "<p>Line one<br>Line two</p>", emptySet()),
            ),
        )
        val (latest, first) = detail.updates
        assertEquals("1.2", latest.version)
        assertEquals(CalendarDate.fromEpochSeconds(1_791_100_800), latest.date)
        assertEquals(
            listOf(ChangeLine(ChangeKindTag.Fixed, "Fixed it"), ChangeLine(ChangeKindTag.Improved, "Smoother"), ChangeLine(ChangeKindTag.Added, "New"), ChangeLine(null, "Gone")),
            latest.lines,
        )
        assertEquals(setOf(7L), latest.files)
        assertEquals(listOf(ChangeLine(null, "Line one"), ChangeLine(null, "Line two")), first.lines)
        assertEquals("https://gamebanana.com/mods/updates/723542", detail.updatesUrl)
        assertNull(ModDetailMapper.toDetail(profile).updatesUrl, "no updates, no link")
    }
}
