package gg.hideouthq.core.customgames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteOriginTest {
    @Test
    fun aBareHttpsOriginIsKeptWithoutItsTrailingSlash() {
        assertEquals("https://example.test", SiteOrigin.parse("https://example.test")?.value)
        assertEquals("https://example.test", SiteOrigin.parse(" https://example.test/ ")?.value)
        assertEquals("https://example.test:8443", SiteOrigin.parse("https://example.test:8443")?.value)
    }

    @Test
    fun anythingElseIsNoOrigin() {
        for (bad in listOf("", "example.test", "http://example.test", "https://", "https://example.test/join", "https://example.test?a=1", "https://example.test#a", "https://me@example.test", "https://exa mple.test", "hideout://join")) {
            assertNull(SiteOrigin.parse(bad), bad)
        }
    }
}
