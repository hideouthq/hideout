package gg.hideouthq.core.data.deadlockstats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplayCacheUrlTest {
    @Test
    fun acceptsMetadataUrlsOnTheReplayCdn() {
        assertEquals(ReplayMetadataRef(37959196, 404, 937530290, "http://replay404.valve.net/1422450/37959196_937530290.meta.bz2"),
            ReplayCacheUrl.parseMetadataUrl("http://replay404.valve.net/1422450/37959196_937530290.meta.bz2"))
        assertEquals(37959196L, ReplayCacheUrl.parseMetadataUrl("http://replay404.valve.net/1422450/37959196_937530290.meta.bz2?v=2")?.matchId)
        assertEquals(1L, ReplayCacheUrl.parseMetadataUrl("https://replay1.valve.net/1422450/1_1.meta.bz2")?.matchId)
    }

    @Test
    fun rejectsAnythingElse() {
        for (url in listOf(
            "http://replay183.valve.net/1422450/42476710_428480166.dem.bz2",
            // The authority is "replay1.valve.net@evil.com": the host is evil.com.
            "http://replay1.valve.net@evil.com/1422450/1_1.meta.bz2",
            "http://evilreplay1.valve.net.attacker.example/1422450/1_1.meta.bz2",
            "http://attacker.example/1422450/1_1.meta.bz2",
            "http://replay1.valve.net/other/14224501_1.meta.bz2",
            "http://replay1.valve.net/142245000/1_1.meta.bz2",
            "http://replay1.valve.net/1422450/../../etc/passwd",
            "http://replay1.valve.net/1422450/%2e%2e_1.meta.bz2",
            "http://replay1.valve.net:8080/1422450/1_1.meta.bz2",
            "ftp://replay1.valve.net/1422450/1_1.meta.bz2",
            "file:///1422450/1_1.meta.bz2",
            "",
            "not a url",
            "http://replay1.valve.net/1422450/" + "1".repeat(600) + "_1.meta.bz2",
            "http://replay1.valve.net/1422450/99999999999999_1.meta.bz2",
        )) assertNull(ReplayCacheUrl.parseMetadataUrl(url), url)
    }

    @Test
    fun findsAReferenceSteamStoredWithoutAScheme() {
        val header = "\u0001replay404.valve.net\u0000/1422450/37959196_937530290.meta.bz2\u0000"
        assertEquals(37959196L, ReplayCacheUrl.findMetadataUrl(header.toByteArray())?.matchId)
    }
}
