package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.Withdrawal
import gg.hideouthq.core.library.Ace
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameBananaFilesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)

    private val liveIvy = """{"_idRow":628739,"_bIsPrivate":false,"_bIsWithheld":false,"_sName":"Bubblegum ivy V2","_bIsTrashed":false,
        "_aFiles":[{"_idRow":1691864,"_sFile":"bg_ivy.zip","_nFilesize":23159953,"_tsDateAdded":1777795519,"_nDownloadCount":1999,
        "_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/1691864","_sMd5Checksum":"ae386fa2a8adb7bc07d17d47195a3c25","_sAnalysisState":"done",
        "_sAnalysisResult":"ok","_sAvState":"done","_sAvResult":"clean","_bIsArchived":false,"_bHasContents":true}]}"""

    private fun answering(status: HttpStatusCode = HttpStatusCode.OK, body: () -> String): Pair<GameBananaFiles, MutableList<String>> {
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += "${request.url.encodedPath}?${request.url.parameters["_csvProperties"]}"
            if (status == HttpStatusCode.OK) respond(body(), headers = json) else respondError(status)
        }
        return GameBananaFiles(HttpClient(engine)) to asked
    }

    private fun file(fields: String) = """{"_idRow":628739,"_sName":"Ivy","_bIsTrashed":false,"_bIsWithheld":false,"_bIsPrivate":false,"_aFiles":[$fields]}"""

    private fun entry(id: Long = 1, md5: String? = "\"ae386fa2a8adb7bc07d17d47195a3c25\"", url: String = "https:\\/\\/gamebanana.com\\/dl\\/$id", extra: String = "") =
        """{"_idRow":$id,"_sFile":"f$id.zip","_nFilesize":10,"_nDownloadCount":3,"_tsDateAdded":5,"_sDownloadUrl":"$url","_sMd5Checksum":$md5$extra}"""

    @Test
    fun theLiveAnswerBecomesTheExactFileToDownload() = runTest {
        val (client, asked) = answering { liveIvy }
        assertEquals(
            FileListing.Available(
                "Bubblegum ivy V2",
                listOf(RemoteFile(1_691_864, "bg_ivy.zip", 23_159_953, "ae386fa2a8adb7bc07d17d47195a3c25", null, 1_999, 1_777_795_519, "https://gamebanana.com/dl/1691864")),
            ),
            client.files(ivy),
        )
        assertEquals(listOf("/apiv11/Mod/628739?_idRow,_sName,_bIsPrivate,_bIsWithheld,_bIsTrashed,_aFiles"), asked)
        val (sounds, soundAsked) = answering { liveIvy.replace("628739", "7") }
        sounds.files(SubmissionRef(SubmissionKind.Sound, 7))
        assertEquals("/apiv11/Sound/7", soundAsked.single().substringBefore('?'))
    }

    @Test
    fun aTrashedSubmissionIsWithdrawnButAnUnexplainedNullIsUnknown() = runTest {
        val trashed = """{"_idRow":628739,"_bIsPrivate":false,"_bIsWithheld":false,"_sName":"Wraith: Klukai","_bIsTrashed":true,"_aFiles":null}"""
        assertEquals(FileListing.Withdrawn(Withdrawal.Trashed), answering { trashed }.first.files(ivy))
        assertEquals(FileListing.Withdrawn(Withdrawal.Withheld), answering { trashed.replace("\"_bIsTrashed\":true", "\"_bIsTrashed\":false").replace("\"_bIsWithheld\":false", "\"_bIsWithheld\":true") }.first.files(ivy))
        assertIs<FileListing.Unavailable>(answering { trashed.replace("\"_bIsTrashed\":true", "\"_bIsTrashed\":false") }.first.files(ivy))
    }

    @Test
    fun aMissingMd5IsAbsentAndABadOneDropsOnlyThatFile() = runTest {
        val listing = answering { file(entry(1, md5 = "null") + "," + entry(2, md5 = "\"AE386FA2A8ADB7BC07D17D47195A3C25\"") + "," + entry(3, md5 = "\"nope\"")) }.first.files(ivy)
        val available = assertIs<FileListing.Available>(listing)
        assertEquals(listOf(1L to null, 2L to "ae386fa2a8adb7bc07d17d47195a3c25"), available.files.map { it.id to it.md5 })
        assertFalse(available.complete, "a current file it could not read is left out, so the list is not all there")
        assertTrue(assertIs<FileListing.Available>(answering { file(entry(1) + "," + entry(2, extra = ",\"_bIsArchived\":true")) }.first.files(ivy)).complete)
    }

    @Test
    fun archivedFilesAreKeptApartAndFilesFromElsewhereAreNotOffered() = runTest {
        val listing = answering {
            file(
                listOf(
                    entry(1, extra = ",\"_bIsArchived\":true"),
                    entry(2, url = "http:\\/\\/gamebanana.com\\/dl\\/2"),
                    entry(3, url = "https:\\/\\/gamebanana.com.evil.test\\/dl\\/3"),
                    entry(4, url = "https:\\/\\/files.gamebanana.com\\/mods\\/f4.zip", extra = ",\"_sDescription\":\"Pink\""),
                ).joinToString(","),
            )
        }.first.files(ivy)
        assertEquals(listOf(4L to "Pink"), assertIs<FileListing.Available>(listing).files.map { it.id to it.description })
        assertEquals(listOf(1L), assertIs<FileListing.Available>(listing).archived.map { it.id })
        assertFalse(assertIs<FileListing.Available>(listing).complete, "files from elsewhere are current files it cannot offer")
        val unknownArchive = answering { file(entry(1, extra = ",\"_bIsArchived\":\"maybe\"")) }.first.files(ivy)
        assertIs<FileListing.Unavailable>(unknownArchive, "which file is current is unknown")
        val ace = """{"_idRow":626469,"_sName":"Ace (One Piece) - Infernus","_bIsTrashed":false,"_bIsWithheld":false,"_bIsPrivate":false,"_aFiles":[
            {"_idRow":1648841,"_sFile":"ace_infernus_ognb_db0f9.rar","_nFilesize":23568106,"_tsDateAdded":1773614606,"_nDownloadCount":103040,
            "_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/1648841","_sMd5Checksum":"6d906e126c8e5426e950be1810e793b8","_bIsArchived":false},
            {"_idRow":1648840,"_sFile":"ace_nobag_infernus_ognb_5f09e.rar","_nFilesize":23499737,"_tsDateAdded":1773614606,"_nDownloadCount":66118,
            "_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/1648840","_sMd5Checksum":"1b6e4c6be05becbe97c6c0c460ffba70","_bIsArchived":false,"_sDescription":"Ace without the backpack."},
            {"_idRow":1537122,"_sFile":"ace_infernus_83fb6.zip","_nFilesize":35589754,"_tsDateAdded":1760259833,"_nDownloadCount":38891,
            "_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/1537122","_sMd5Checksum":"56bbcbbe6dd686fd820823f230c4b05c","_bIsArchived":true}]}"""
        assertEquals(Ace.listing, answering { ace }.first.files(Ace.ref), "Ace's archived ZIP kept apart from its two RARs")
    }

    @Test
    fun keyedFileObjectsMustAgreeWithTheirIds() = runTest {
        val keyed = """{"_idRow":628739,"_sName":"Ivy","_aFiles":{"5":${entry(5)},"6":${entry(7)}}}"""
        val listed = assertIs<FileListing.Available>(answering { keyed }.first.files(ivy))
        assertEquals(listOf(5L), listed.files.map { it.id })
        assertFalse(listed.complete)
    }

    @Test
    fun aSearchFindsDeadlocksModsByTitleBestFirst() = runTest {
        val answer = """{"_aMetadata":{"_nRecordCount":757,"_nPerpage":15,"_bIsComplete":false},"_aRecords":[
            {"_idRow":626469,"_sModelName":"Mod","_sName":"Ace (One Piece) - Infernus","_aGame":{"_idRow":20948,"_sName":"Deadlock"}},
            {"_idRow":582035,"_sModelName":"Mod","_sName":"Portgas D Ace Infernus","_aGame":{"_idRow":20948,"_sName":"Deadlock"}},
            {"_idRow":709065,"_sModelName":"Mod","_sName":"Garp (One Piece) as Abrams","_aGame":{"_idRow":20948,"_sName":"Deadlock"}}]}"""
        val asked = mutableListOf<Url>()
        val client = GameBananaFiles(HttpClient(MockEngine { request -> asked += request.url; respond(answer, headers = json) }))
        assertEquals(listOf(626_469L, 582_035L, 709_065L).map { SubmissionRef(SubmissionKind.Mod, it) }, client.search("Ace (One Piece) - Infernus"))
        val url = asked.single()
        assertEquals("/apiv11/Util/Search/Results", url.encodedPath)
        assertEquals(
            mapOf("_sModelName" to "Mod", "_idGameRow" to "20948", "_sOrder" to "best_match", "_csvFields" to "name", "_sSearchString" to "Ace (One Piece) - Infernus"),
            url.parameters.names().associateWith { url.parameters[it] },
        )
        assertNull(answering(HttpStatusCode.InternalServerError) { "" }.first.search("Ace"), "no answer is not an answer of nothing")
    }

    @Test
    fun errorsAndAnswersForAnotherSubmissionAreUnavailable() = runTest {
        assertIs<FileListing.Unavailable>(answering(HttpStatusCode.InternalServerError) { "" }.first.files(ivy))
        assertIs<FileListing.Unavailable>(answering(HttpStatusCode.NotFound) { "" }.first.files(ivy))
        assertIs<FileListing.Unavailable>(answering { "<html>" }.first.files(ivy))
        assertIs<FileListing.Unavailable>(answering { liveIvy.replace("\"_idRow\":628739", "\"_idRow\":1") }.first.files(ivy))
        assertIs<FileListing.Unavailable>(answering { liveIvy.replace("\"_sName\":\"Bubblegum ivy V2\",", "") }.first.files(ivy))
    }
}
