package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.network.RemoteException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameBananaResponsesTest {
    @Test
    fun listingRecordsBecomeSubmissionsAndUnreadableOnesAreCounted() {
        val page = parseIndexPage(GameBananaFixtures.IndexPage)
        assertEquals(4473, page.totalRecords)
        assertEquals(2, page.skipped)

        val skin = page.submissions[0]
        assertEquals(SubmissionRef(SubmissionKind.Mod, 555341), skin.key)
        assertEquals("mod-555341", skin.key.modId)
        assertEquals(33315L, skin.categoryId)
        assertEquals(33295L, skin.rootCategoryId)
        assertEquals("christinespoiled", skin.submitter)
        assertEquals(
            listOf("https://images.gamebanana.com/img/ss/mods/530-90_67424fe17e6b1.jpg", "https://images.gamebanana.com/img/ss/mods/67424fe13f5de.jpg"),
            skin.images.map { it.cardUrl },
        )
        assertEquals(42, skin.likes)
        assertEquals(1732398701L, skin.updatedAt)
        assertEquals(1776101275L, skin.modifiedAt)
        assertEquals(true, skin.obsolete)

        val sound = page.submissions[1]
        assertEquals("sound-77726", sound.key.modId)
        assertEquals(5843L, sound.categoryId)
        assertEquals(listOf("Victor"), sound.tags)
        assertEquals("https://files.gamebanana.com/preview/sounds/reaverkill5.mp3", sound.audioUrl)
        assertNull(sound.submitter)
        assertEquals(0, sound.likes)
        assertNull(sound.updatedAt)
        assertEquals(1757373072L, sound.modifiedAt)
        assertEquals("warn", sound.visibility)
        assertTrue(sound.mature, "the listing says what is mature")
        assertFalse(skin.mature)
        val unrated = """{"_aMetadata":{"_nRecordCount":1},"_aRecords":[{"_idRow":1,"_sModelName":"Mod","_sName":"Mod 1","_sProfileUrl":"https://gamebanana.com/mods/1"}]}"""
        assertEquals(1, parseIndexPage(unrated).skipped, "without its content-rating flag a record is unreadable, never taken as safe")
    }

    @Test
    fun multiRowsUseIdentityRatherThanPositionAndAnOmittedRecordIsMissingNotEmpty() {
        val batch = listOf(540475L, 99999999L, 540476L).map { SubmissionRef(SubmissionKind.Mod, it) }
        val facts = parseFileFacts(GameBananaFixtures.FileFacts, SubmissionKind.Mod, batch.map { it.id }.toSet())
        assertEquals(batch.toSet(), facts.keys)
        assertEquals(
            FileFacts(
                downloads = 4736, largestFileBytes = 146145, newestFileAt = 1727438075,
                files = listOf(SubmissionFileOption(1288317, null, null, null, 146145, addedAt = 1727438075), SubmissionFileOption(1282198, null, null, null, 91705, addedAt = 1726570502)),
                filesComplete = true,
            ),
            facts.read(batch[0]),
        )
        assertEquals(FactsAnswer.Missing, facts[batch[1]])
        assertNull(facts.getValue(batch[1]).facts)
        assertEquals(FileFacts(downloads = 338, largestFileBytes = null, newestFileAt = null, filesComplete = true), facts.read(batch[2]), "an empty list in a readable row is known to be empty")
    }

    @Test
    fun aFileIdentityComesFromItsFieldOrItsKeyAndNeverFromAGuessWhileTheCatalogueFiguresStay() {
        val md5 = "ab965746d197e296c253fb552dc1fd47"
        for ((files, identity) in listOf(
            """{"1288317":{"_idRow":"1288317","_sMd5Checksum":"AB965746D197E296C253FB552DC1FD47"}}""" to SubmissionFile(1288317, md5),
            """{"1288317":{"_nFilesize":5}}""" to SubmissionFile(1288317),
            """[{"_idRow":7,"_sMd5Checksum":""}]""" to SubmissionFile(7),
            """[{"_idRow":7,"_bIsArchived":false,"_sMd5Checksum":null}]""" to SubmissionFile(7),
            """[{"_idRow":7,"_bIsArchived":"false","_sMd5Checksum":"$md5"}]""" to SubmissionFile(7, md5),
            """{"1":{"_idRow":1,"_bIsArchived":true},"2":{"_idRow":2,"_bIsArchived":false}}""" to SubmissionFile(2),
            """[{"_idRow":7,"_bIsArchived":"TRUE"},{"_idRow":8,"_bIsArchived":"False"}]""" to SubmissionFile(8),
        )) assertEquals(identity, facts(files).currentFile, files)
        for (files in listOf(
            "[]",
            """[{"_idRow":1},{"_idRow":2}]""",
            """{"1288317":{"_idRow":1288318}}""",
            """{"files":{"_idRow":1288317}}""",
            """{"01288317":{"_idRow":1288317}}""",
            """[{"_nFilesize":5}]""",
            """[{"_idRow":0}]""",
            """[{"_idRow":"x1"}]""",
            """[{"_idRow":null}]""",
            """[{"_idRow":1,"_sMd5Checksum":"not-an-md5"}]""",
            """[{"_idRow":1,"_sMd5Checksum":"ab965746d197e296c253fb552dc1fd4"}]""",
            """[{"_idRow":7,"_sMd5Checksum":{"md5":"$md5"}}]""",
            """[{"_idRow":7,"_sMd5Checksum":["$md5"]}]""",
            """[{"_idRow":7,"_sMd5Checksum":true}]""",
            """[{"_idRow":7,"_sMd5Checksum":12345678901234567890123456789012}]""",
            """[{"_idRow":7,"_bIsArchived":"maybe"}]""",
            """[{"_idRow":7,"_bIsArchived":1}]""",
            """[{"_idRow":7,"_bIsArchived":null}]""",
            """[{"_idRow":7,"_bIsArchived":{}}]""",
            """[{"_idRow":7,"_bIsArchived":[false]}]""",
            """{"6":{"_idRow":6,"_bIsArchived":"unknown"},"7":{"_idRow":7,"_bIsArchived":false}}""",
            """{"6":{"_idRow":6,"_bIsArchived":true},"7":{"_idRow":7,"_bIsArchived":"unknown"}}""",
        )) {
            val facts = facts(files)
            assertNull(facts.currentFile, files)
            assertEquals(5, facts.downloads, files)
        }
    }

    @Test
    fun theFileListIsEveryCurrentFileWithATrustedIdentityAndSaysWhetherThatIsAll() {
        val checksums = parseFileFacts(GameBananaFixtures.FileFactsWithChecksums, SubmissionKind.Mod, setOf(540475L, 555341L))
        val two = checksums.read(SubmissionRef(SubmissionKind.Mod, 540475))
        assertNull(two.currentFile, "two current files are ambiguous")
        assertEquals(
            listOf(
                SubmissionFileOption(1288317, "ab965746d197e296c253fb552dc1fd47", "deer_s_post_processing.zip", null, 146145, addedAt = 1727438075),
                SubmissionFileOption(1282198, "8797b6cdb331371ef015e78a9f79bad9", "deer_post_processing_mod.zip", null, 91705, addedAt = 1726570502),
            ),
            two.files,
            "both stay offered even though neither is a safe default",
        )
        val one = checksums.read(SubmissionRef(SubmissionKind.Mod, 555341))
        assertEquals(SubmissionFile(1328678, "77b555b9ce0e99b7f30483be456432d4"), one.currentFile)
        assertEquals(listOf(SubmissionFileOption(1328678, "77b555b9ce0e99b7f30483be456432d4", "redgeist.zip", null, 12797496, addedAt = 1732398791)), one.files)

        val counted = facts("""[{"_idRow":1,"_sFile":"a.zip","_nDownloadCount":30,"_tsDateAdded":100},{"_idRow":2,"_sFile":"b.zip","_nDownloadCount":-1}]""")
        assertEquals(listOf(30L, null), counted.files.map { it.downloads }, "each file's own download count, as the install dialog orders them")

        val archived = facts("""[{"_idRow":1,"_sFile":"a.zip","_sDescription":"First cut","_sMd5Checksum":"ab965746d197e296c253fb552dc1fd47"},{"_idRow":2,"_bIsArchived":true,"_sMd5Checksum":"bad"}]""")
        assertEquals(listOf(SubmissionFileOption(1, "ab965746d197e296c253fb552dc1fd47", "a.zip", "First cut")), archived.files, "the archived file is left out")
        assertTrue(archived.filesComplete, "and does not count")
        val untrusted = facts("""[{"_idRow":1,"_sFile":"a.zip"},{"_idRow":2,"_sFile":"b.zip","_sMd5Checksum":"not-an-md5"}]""")
        assertEquals(listOf(SubmissionFileOption(1, null, "a.zip", null)), untrusted.files, "a file whose identity cannot be trusted is left out, not guessed")
        assertFalse(untrusted.filesComplete)
        assertEquals(
            FileFacts(downloads = 5, largestFileBytes = 10, newestFileAt = 100),
            facts("""[{"_idRow":7,"_nFilesize":10,"_tsDateAdded":100,"_bIsArchived":"maybe"},{"_idRow":8}]"""),
            "with a file's archive state unknown nothing is safe to offer and the list is incomplete",
        )
    }

    @Test
    fun aRefusedMultiRequestFails() {
        val failure = assertFailsWith<RemoteException> {
            parseFileFacts("""{"_sErrorCode":"INPUT_ERRORS","_aErrors":[]}""", SubmissionKind.Mod, setOf(1L))
        }
        assertEquals(DataError.Unavailable, failure.error)
    }

    @Test
    fun oneOddMultiRowLeavesOnlyItsOwnSubmissionUnreadable() {
        val facts = parseFileFacts(
            """[
                {"_idRow":9,"_aFiles":[]}, [1,2,3], {"_aFiles":[]},
                {"_idRow":1,"_aFiles":[]},
                {"_idRow":2},
                {"_idRow":3,"_aFiles":"broken"},
                {"_idRow":4,"_aFiles":null},
                {"_idRow":5,"_aFiles":[5]},
                {"_idRow":6,"_aFiles":[],"_nDownloadCount":-1},
                {"_idRow":7,"_aFiles":[]}, {"_idRow":7,"_aFiles":[]}
            ]""",
            SubmissionKind.Sound,
            (1L..7L).toSet(),
        )
        val unreadable = (2L..7L).associate { SubmissionRef(SubmissionKind.Sound, it) to FactsAnswer.Unreadable }
        assertEquals(
            mapOf(SubmissionRef(SubmissionKind.Sound, 1) to FactsAnswer.Read(FileFacts(0, null, null, filesComplete = true))) + unreadable,
            facts,
            "a row for an id nobody asked for, or for none, is passed over",
        )
    }

    @Test
    fun profilesReadBothShapesOfCredit() {
        val profile = parseProfile(GameBananaFixtures.Profile, SubmissionRef(SubmissionKind.Mod, 723542))
        assertEquals("1.0", profile.version)
        assertEquals("Nobel_", profile.submitter)
        assertEquals(
            listOf(
                CreditGroup("Mod Authors", listOf(Credit("Human1", "Concept Art"), Credit("Nobel", "3D Art"))),
                CreditGroup("Key Authors", listOf(Credit("Deer_uwu", null))),
            ),
            profile.credits,
        )
        assertEquals(2, profile.images.size)
    }

    @Test
    fun categoryLevelsNameTheirParentAndWhichHaveChildren() {
        val level = parseCategories(GameBananaFixtures.RootCategories, parentId = null, SubmissionKind.Mod)
        assertEquals(listOf(SubmissionCategory(33295, "Skins", null, SubmissionKind.Mod, 3013), SubmissionCategory(37225, "Maps", null, SubmissionKind.Mod, 39)), level.categories)
        assertEquals(listOf(33295L), level.withChildren)
    }

    @Test
    fun featuredKeepsOnlySubmissions() {
        assertEquals(
            listOf(SubmissionRef(SubmissionKind.Mod, 724369), SubmissionRef(SubmissionKind.Sound, 77008)),
            parseFeatured(GameBananaFixtures.Featured).refs,
        )
    }

    @Test
    fun anErrorEnvelopeIsNotAnEmptyListing() {
        assertFailsWith<RemoteException> { parseIndexPage("""{"_sErrorCode":"INPUT_ERRORS"}""") }
    }

    @Test
    fun anEmptyPageWithAPositiveCountIsRejected() {
        assertFailsWith<RemoteException> { parseIndexPage("""{"_aMetadata":{"_nRecordCount":50},"_aRecords":[]}""") }
        assertEquals(0, parseIndexPage("""{"_aMetadata":{"_nRecordCount":0},"_aRecords":[]}""").totalRecords)
    }

    @Test
    fun anUpdateKeepsItsChangelogItsFilesAndItsNotesAndOneWithoutANameOrDateIsLeftOut() {
        val updates = parseUpdates(
            """{"_aMetadata":{"_nRecordCount":3},"_aRecords":[
                {"_sName":"Wolf form fixes","_sVersion":" 1.2 ","_tsDateAdded":1791100000,"_aChangeLog":[{"text":"No more T-pose","cat":"Bugfix"},{"text":" ","cat":"Addition"}],"_aFileRowIds":[1510001],"_aFiles":[{"_idRow":1510002}],"_sText":"<p>Notes</p>"},
                {"_sName":"","_tsDateAdded":1791000000},
                {"_sName":"No date"}
            ]}""",
        )
        val update = updates.single()
        assertEquals("Wolf form fixes", update.name)
        assertEquals("1.2", update.version)
        assertEquals(1791100000, update.addedAt)
        assertEquals(listOf("No more T-pose" to "Bugfix"), update.changes)
        assertEquals(setOf(1510001L, 1510002L), update.fileIds)
        assertEquals("<p>Notes</p>", update.text)
    }

    @Test
    fun aPageSaysWhetherItHasUpdates() {
        val base = """"_sName":"Mod","_sProfileUrl":"https://gamebanana.com/mods/1""""
        val key = SubmissionRef(SubmissionKind.Mod, 1)
        assertEquals(true, parseProfile("""{$base,"_bHasUpdates":true}""", key).hasUpdates)
        assertEquals(false, parseProfile("""{$base}""", key).hasUpdates)
    }

    private fun facts(files: String): FileFacts =
        parseFileFacts("""[{"_idRow":1,"_nDownloadCount":5,"_aFiles":$files}]""", SubmissionKind.Mod, setOf(1L)).values.single().read()

    private fun FactsAnswer.read(): FileFacts = assertIs<FactsAnswer.Read>(this).facts

    private fun Map<SubmissionRef, FactsAnswer>.read(key: SubmissionRef): FileFacts = getValue(key).read()
}
