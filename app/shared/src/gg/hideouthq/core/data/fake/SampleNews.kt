package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.NewsRepository
import gg.hideouthq.core.data.news.SteamNewsPage
import gg.hideouthq.core.model.NewsKind
import gg.hideouthq.core.model.NewsPost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

class SampleNews : NewsRepository {
    override val posts: StateFlow<List<NewsPost>> = MutableStateFlow(posts(Clock.System.now().epochSeconds)).asStateFlow()

    private fun posts(now: Long) = listOf(
        post("sample-1", "Minor Update · 10-12-2026", NewsKind.PatchNotes, now - 2 * Day,
            "Balance changes for Silver, Mina and Yamato, item fixes, and adjustments around the mid boss. Several visual effects were reworked.",
            "• Silver: Wolf form duration reduced from 12s to 10s", "• Mina: Love Bites cooldown increased from 18s to 20s", "• Fixed the mid boss sometimes resetting its health"),
        post("sample-2", "Community art spotlight", NewsKind.Announcement, now - 4 * Day, "A look at some of the art the community made this month."),
        post("sample-3", "New hero coming next week", NewsKind.Announcement, now - 7 * Day, "A new hero joins the streets of the city next week."),
        post("sample-4", "Minor Update · 10-05-2026", NewsKind.PatchNotes, now - 9 * Day, "Troopers walk at their usual speed in lane again.", "• Fixed bugs that slowed troopers in lane"),
        post("sample-5", "Gameplay Update · 09-30-2026", NewsKind.PatchNotes, now - 14 * Day, "A large balance pass across items and heroes."),
    )

    private fun post(id: String, title: String, kind: NewsKind, at: Long, excerpt: String, vararg more: String) =
        NewsPost(id, title, kind, at, excerpt, listOf(excerpt) + more, imageUrl = null, url = SteamNewsPage)

    private companion object {
        const val Day = 86_400L
    }
}
