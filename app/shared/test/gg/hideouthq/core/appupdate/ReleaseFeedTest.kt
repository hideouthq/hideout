package gg.hideouthq.core.appupdate

import gg.hideouthq.core.library.resourceBytes
import gg.hideouthq.core.model.CalendarDate
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseFeedTest {
    private val feedUrl = "https://github.com/hideouthq/hideout/releases/latest/download/releases.json"
    private val sample = resourceBytes("appupdate/releases.json").decodeToString()
    private val sha = "e".repeat(64)

    private fun parse(text: String) = parseReleaseFeed(text, feedUrl)

    private fun feed(latest: String = "1.4.0", vararg versions: String) =
        """{"schema":1,"latest":"$latest","notesUrl":"https://example.test/releases","versions":[${versions.joinToString()}]}"""

    private fun version(number: String = "1.4.0", extra: String = "") =
        """{"version":"$number","date":"2026-11-02","kind":"feature"$extra}"""

    @Test
    fun versionsOrderAsNumbersAndOnlyAFullVersionParses() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 9))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertEquals(AppVersion(1, 4, 0), AppVersion.parse("1.4.0"))
        assertEquals("1.4.0", AppVersion(1, 4, 0).toString())
        for (malformed in listOf("", "1.4", "1.4.0.1", "v1.4.0", "1.4.x", "sample", "1.4.0-rc1", "99999999999.0.0")) {
            assertNull(AppVersion.parse(malformed), malformed)
        }
    }

    @Test
    fun theSampleFileReadsWhole() {
        val feed = assertNotNull(parse(sample))
        assertEquals(AppVersion(1, 4, 0), feed.latest)
        assertEquals("https://github.com/hideouthq/hideout/releases", feed.notesUrl)
        assertEquals(listOf("1.4.0", "1.3.2", "1.3.0"), feed.versions.map { it.version.toString() })

        val newest = feed.versions.first()
        assertEquals(CalendarDate(2026, 11, 2), newest.date)
        assertEquals(ReleaseKind.Feature, newest.kind)
        assertEquals(
            Highlight("Skin collections", "Hand-picked sets on Discover, like Halloween and Winter.", "https://github.com/hideouthq/hideout/releases/latest/download/1.4.0-collections@2x.webp"),
            newest.highlights.first(),
        )
        assertEquals(2, newest.new.size)
        assertEquals(ReleaseEntry("Stats", "With live tracking on, new matches show up about a minute after they end."), newest.improved.single())

        val fixes = feed.versions[1]
        assertEquals(ReleaseKind.Fixes, fixes.kind)
        assertEquals(emptyList(), fixes.highlights, "a missing group is skipped")
        assertEquals(emptyList(), fixes.new)
        assertEquals(3, fixes.fixed.size)
    }

    @Test
    fun everyFormatOfTheLatestVersionIsAPackage() {
        val packages = assertNotNull(parse(sample)).packages
        assertEquals(PackageFormat.entries.toSet(), packages.keys)
        assertEquals(
            ReleasePackage(
                AppVersion(1, 4, 0), PackageFormat.Msi, "https://github.com/hideouthq/hideout/releases/download/v1.4.0/Hideout-1.4.0.msi", 19293798, "a".repeat(64),
            ),
            packages[PackageFormat.Msi],
        )
        assertEquals("b".repeat(64), packages[PackageFormat.AppImage]?.sha256)
    }

    @Test
    fun aDownloadBlockOnAnOlderVersionOffersNothing() {
        val download = ""","download":{"msi":{"url":"https://example.test/a.msi","size":1,"sha256":"$sha"}}"""
        assertEquals(emptyMap(), parse(feed("1.4.0", version("1.4.0"), version("1.3.0", download)))?.packages)
    }

    @Test
    fun aBrokenRequiredFieldIsNoFeed() {
        assertNull(parse("not json"))
        assertNull(parse("""{"latest":"1.4.0","notesUrl":"https://example.test","versions":[]}"""), "no schema")
        assertNull(parse("""{"schema":"two","latest":"1.4.0","notesUrl":"https://example.test","versions":[]}"""), "schema isn't a number")
        assertNull(parse("""{"schema":1,"notesUrl":"https://example.test","versions":[]}"""), "no latest")
        assertNull(parse("""{"schema":1,"latest":"newest","notesUrl":"https://example.test","versions":[]}"""), "latest isn't a version")
        assertNull(parse("""{"schema":1,"latest":"1.4.0","versions":[]}"""), "no notesUrl")
        assertNull(parse("""{"schema":1,"latest":"1.4.0","notesUrl":" ","versions":[]}"""), "blank notesUrl")
        assertNull(parse("""{"schema":2,"latest":"1.4.0"}"""), "a newer schema without notesUrl")
    }

    @Test
    fun aSchemaThisBuildDoesNotKnowOffersTheLatestVersionWithNothingElse() {
        for (schema in listOf(0, 2)) {
            val text = """{"schema":$schema,"latest":"1.4.0","notesUrl":"https://example.test/releases","versions":{"1.4.0":{}},
                "releases":[${version("1.4.0", ""","download":{"msi":{"url":"https://example.test/a.msi","size":1,"sha256":"$sha"}}""")}]}"""
            assertEquals(ReleaseFeed(AppVersion(1, 4, 0), "https://example.test/releases", emptyList(), emptyMap()), parse(text), "schema $schema")
        }
    }

    @Test
    fun aFeedWithNoVersionsStillNamesTheLatest() {
        val feed = assertNotNull(parse("""{"schema":1,"latest":"1.4.0","notesUrl":"https://example.test"}"""))
        assertEquals(AppVersion(1, 4, 0), feed.latest)
        assertEquals(emptyList(), feed.versions)
        assertEquals(emptyMap(), feed.packages)
    }

    @Test
    fun unknownFieldsAreIgnoredAtEveryLevel() {
        val text = """{"schema":1,"future":{"a":1},"latest":"1.4.0","notesUrl":"https://example.test","versions":[
            {"version":"1.4.0","date":"2026-11-02","kind":"feature","mood":"good",
             "download":{"msi":{"url":"https://example.test/a.msi","size":1,"sha256":"$sha","mirror":"x"},"flatpak":{"url":"x"}},
             "new":[{"area":"Stats","text":"Better.","icon":"star"}],
             "highlights":[{"title":"T","text":"X","image":"a.webp","alt":"A"}]}]}"""
        val feed = assertNotNull(parse(text))
        assertEquals(setOf(PackageFormat.Msi), feed.packages.keys)
        assertEquals(1, feed.versions.single().new.size)
        assertEquals(1, feed.versions.single().highlights.size)
    }

    @Test
    fun aVersionWithAMalformedFieldIsDroppedAndTheRestKept() {
        val malformed = listOf(
            """{"date":"2026-11-02","kind":"fixes"}""",
            """{"version":"not-a-version","date":"2026-11-02","kind":"fixes"}""",
            """{"version":"1.3.1","date":"2 Nov 2026","kind":"fixes"}""",
            """{"version":"1.3.1","date":"2026-02-31","kind":"fixes"}""",
            """{"version":"1.3.1","kind":"fixes"}""",
            """{"version":"1.3.1","date":"2026-11-02","kind":"fixes","highlights":"none"}""",
            """17""",
        )
        for (entry in malformed) {
            val feed = assertNotNull(parse(feed("1.4.0", version("1.4.0"), entry, version("1.2.0"))), entry)
            assertEquals(listOf("1.4.0", "1.2.0"), feed.versions.map { it.version.toString() }, entry)
        }
    }

    @Test
    fun aKindThisBuildDoesNotKnowReadsAsFixes() {
        for (kind in listOf(""""hotfix"""", "null")) {
            val entry = """{"version":"1.4.0","date":"2026-11-02","kind":$kind}"""
            assertEquals(ReleaseKind.Fixes, parse(feed("1.4.0", entry))?.versions?.single()?.kind, kind)
        }
        assertEquals(ReleaseKind.Fixes, parse(feed("1.4.0", """{"version":"1.4.0","date":"2026-11-02"}"""))?.versions?.single()?.kind, "no kind")
    }

    @Test
    fun aMalformedHighlightOrLineIsDroppedAloneAndTheVersionKept() {
        val extra = ""","highlights":[{"title":"Broken"},{"title":"1","text":"X"},"card",{"title":"2","text":"X"},{"title":"3","text":"X"}],""" +
            """"fixed":[{"area":"Library"},{"area":"Stats","text":"Faster."},{"area":"Stats","text":{"en":"Faster."}}]"""
        val notes = assertNotNull(parse(feed("1.4.0", version("1.4.0", extra)))).versions.single()
        assertEquals(listOf("1", "2"), notes.highlights.map { it.title }, "the two kept are the first two that read")
        assertEquals(listOf(ReleaseEntry("Stats", "Faster.")), notes.fixed)
    }

    @Test
    fun aLatestVersionThatIsDroppedStillOffersTheVersionWithNoPackages() {
        val feed = assertNotNull(parse(feed("1.4.0", """{"version":"1.4.0"}""", version("1.3.0"))))
        assertEquals(AppVersion(1, 4, 0), feed.latest)
        assertEquals(emptyMap(), feed.packages)
        assertEquals(listOf(AppVersion(1, 3, 0)), feed.versions.map { it.version })
    }

    @Test
    fun theLatestVersionsPackagesSurviveNotesThatCannotBeRead() {
        val download = ""","download":{"msi":{"url":"https://example.test/a.msi","size":1,"sha256":"$sha"}}"""
        for (notes in listOf("""{"version":"1.4.0"$download}""", """{"version":"1.4.0","date":"soon","kind":"feature","highlights":"none"$download}""")) {
            val feed = assertNotNull(parse(feed("1.4.0", notes, version("1.3.0"))), notes)
            assertEquals(setOf(PackageFormat.Msi), feed.packages.keys, notes)
            assertEquals(listOf(AppVersion(1, 3, 0)), feed.versions.map { it.version }, notes)
        }
    }

    @Test
    fun aMalformedPackageIsDroppedAloneAndTheOldSinglePackageShapeOffersNone() {
        val good = """"rpm":{"url":"https://example.test/a.rpm","size":5,"sha256":"${sha.uppercase()}"}"""
        for (broken in listOf(
            """"msi":{"url":"https://example.test/a.msi","size":1}""",
            """"msi":{"url":"https://example.test/a.msi","size":0,"sha256":"$sha"}""",
            """"msi":{"url":"https://example.test/a.msi","size":1,"sha256":"abc"}""",
            """"msi":{"url":"file:///etc/passwd","size":1,"sha256":"$sha"}""",
            """"msi":"https://example.test/a.msi"""",
        )) {
            val packages = assertNotNull(parse(feed("1.4.0", version("1.4.0", ""","download":{$broken,$good}""")))).packages
            assertEquals(setOf(PackageFormat.Rpm), packages.keys, broken)
            assertEquals(sha, packages[PackageFormat.Rpm]?.sha256, "a hash reads in lower case")
        }
        val old = ""","download":{"url":"https://example.test/a.msi","size":1,"sha256":"$sha"}"""
        assertEquals(emptyMap(), parse(feed("1.4.0", version("1.4.0", old)))?.packages)
    }

    @Test
    fun aVersionNewerThanLatestIsNeverListedAndVersionsRunNewestFirst() {
        val feed = assertNotNull(parse(feed("1.4.0", version("1.3.0"), version("1.5.0"), version("1.4.0"), version("1.3.0"))))
        assertEquals(listOf("1.4.0", "1.3.0"), feed.versions.map { it.version.toString() })
    }

    @Test
    fun onlyBareImageNamesResolveAndOnlyBesideTheFeed() {
        fun image(name: String): String? {
            val highlight = ""","highlights":[{"title":"T","text":"X","image":$name}]"""
            return assertNotNull(parse(feed("1.4.0", version("1.4.0", highlight)))).versions.single().highlights.single().imageUrl
        }
        assertEquals("https://github.com/hideouthq/hideout/releases/latest/download/a@2x.webp", image("\"a@2x.webp\""))
        for (rejected in listOf("\"https://evil.test/a.webp\"", "\"art/a.webp\"", "\"../a.webp\"", "\"..\"", "\"a\\\\b.webp\"", "\"\"", "null")) {
            assertNull(image(rejected), rejected)
        }
        val noImage = ""","highlights":[{"title":"T","text":"X"}]"""
        assertNull(assertNotNull(parse(feed("1.4.0", version("1.4.0", noImage)))).versions.single().highlights.single().imageUrl)
    }

    @Test
    fun atMostTwoHighlightsAreKept() {
        val three = ""","highlights":[{"title":"1","text":"X"},{"title":"2","text":"X"},{"title":"3","text":"X"}]"""
        assertEquals(listOf("1", "2"), parse(feed("1.4.0", version("1.4.0", three)))?.versions?.single()?.highlights?.map { it.title })
    }

    @Test
    fun theReleaseToolsOutputVerifiesInTheJdkAndReadsWhole() {
        val bytes = resourceBytes("appupdate/make-feed-output/releases.json")
        val signature = resourceBytes("appupdate/make-feed-output/releases.json.sig").decodeToString()
        val public = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode("MCowBQYDK2VwAyEADi0Q0UbGVkp6QIiB7cLv2FgIc1Ne+eDuw5VDSaZcge8=")))
        assertTrue(Signature.getInstance("Ed25519").run { initVerify(public); update(bytes); verify(Base64.getDecoder().decode(signature)) })

        val feed = assertNotNull(parse(bytes.decodeToString()))
        assertEquals(AppVersion(1, 1, 0), feed.latest)
        assertEquals(listOf("1.1.0", "1.0.0"), feed.versions.map { it.version.toString() })
        assertEquals(PackageFormat.entries.toSet(), feed.packages.keys)
        assertEquals("https://github.com/hideouthq/hideout/releases/download/v1.1.0/hideout-1.1.0-1.x86_64.rpm", feed.packages[PackageFormat.Rpm]?.url)
        assertEquals(
            "https://github.com/hideouthq/hideout/releases/latest/download/1.1.0-updates@2x.webp",
            feed.versions.first().highlights.single().imageUrl,
        )
        assertEquals("Café names with accents no longer break the list.", feed.versions.first().fixed.single().text)
    }
}
