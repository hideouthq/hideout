package gg.hideouthq.core.data.live

import kotlin.test.Test
import kotlin.test.assertTrue

class TrendingTest {
    @Test
    fun newerAndBusierModsRankHigher() {
        assertTrue(Trending.score(downloads = 2_000, likes = 50, ageDays = 3) > Trending.score(downloads = 2_000, likes = 50, ageDays = 60))
        assertTrue(Trending.score(downloads = 5_000, likes = 50, ageDays = 10) > Trending.score(downloads = 2_000, likes = 50, ageDays = 10))
        assertTrue(Trending.score(downloads = 3_000, likes = 80, ageDays = 4) > Trending.score(downloads = 400_000, likes = 900, ageDays = 700))
    }
}
