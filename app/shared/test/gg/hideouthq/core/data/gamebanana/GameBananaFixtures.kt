package gg.hideouthq.core.data.gamebanana

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

internal fun jsonClient(body: (HttpRequestData) -> String?): HttpClient = HttpClient(MockEngine { request ->
    body(request)?.let { respond(it, headers = headersOf(HttpHeaders.ContentType, "application/json")) } ?: respond("", HttpStatusCode.ServiceUnavailable)
})

internal object GameBananaFixtures {
    const val IndexPage = """{"_aMetadata":{"_nRecordCount":4473,"_bIsComplete":false,"_nPerpage":50},"_aRecords":[
{"_idRow":555341,"_sModelName":"Mod","_sName":"Red Geist","_sProfileUrl":"https://gamebanana.com/mods/555341","_tsDateAdded":1731465235,"_tsDateModified":1776101275,"_tsDateUpdated":1732398701,"_aTags":[],"_aPreviewMedia":{"_aImages":[{"_sType":"screenshot","_sBaseUrl":"https://images.gamebanana.com/img/ss/mods","_sFile":"67424fe17e6b1.jpg","_sFile530":"530-90_67424fe17e6b1.jpg","_sFile100":"100-90_67424fe17e6b1.jpg"},{"_sType":"screenshot","_sBaseUrl":"https://images.gamebanana.com/img/ss/mods","_sFile":"67424fe13f5de.jpg","_sFile100":"100-90_67424fe13f5de.jpg"}]},"_aSubmitter":{"_idRow":3945488,"_sName":"christinespoiled"},"_aRootCategory":{"_sName":"Skins","_sProfileUrl":"https://gamebanana.com/mods/cats/33295","_sIconUrl":""},"_aSubCategory":{"_sName":"Lady Geist","_sProfileUrl":"https://gamebanana.com/mods/cats/33315","_sIconUrl":""},"_bIsObsolete":true,"_sInitialVisibility":"show","_bHasContentRatings":false,"_nLikeCount":42,"_sVersion":"0.2"},
{"_idRow":77726,"_sModelName":"Sound","_sName":"Valorant Reaver killsounds","_sProfileUrl":"https://gamebanana.com/sounds/77726","_tsDateAdded":1734853879,"_tsDateModified":1757373072,"_aTags":[{"_sValue":"Victor"}],"_aPreviewMedia":{"_aMetadata":{"_sAudioUrl":"https://files.gamebanana.com/preview/sounds/reaverkill5.mp3"}},"_aSubmitter":[],"_aRootCategory":{"_sName":"Killsounds","_sProfileUrl":"https://gamebanana.com/sounds/cats/5843","_sIconUrl":""},"_bIsObsolete":"false","_sInitialVisibility":"warn","_bHasContentRatings":true},
{"_idRow":12,"_sModelName":"News","_sName":"Patch notes","_sProfileUrl":"https://gamebanana.com/news/12"},
{"_sName":"No id at all"}
]}"""

    const val FileFacts = """[
{"_idRow":540476,"_nDownloadCount":"338","_aFiles":[],"_bIsNsfw":"true"},
{"_idRow":540475,"_nDownloadCount":4736,"_aFiles":{"1288317":{"_idRow":1288317,"_nFilesize":146145,"_tsDateAdded":1727438075,"_bIsArchived":false},"1282198":{"_idRow":1282198,"_nFilesize":91705,"_tsDateAdded":1726570502,"_bIsArchived":false},"816748":{"_idRow":816748,"_nFilesize":46352827,"_tsDateAdded":1791000000,"_bIsArchived":true}},"_bIsNsfw":false}
]"""

    const val FileFactsWithChecksums = """[{"_idRow":540475,"_nDownloadCount":4741,"_aFiles":[{"_idRow":1288317,"_sFile":"deer_s_post_processing.zip","_nFilesize":146145,"_tsDateAdded":1727438075,"_bIsArchived":false,"_sMd5Checksum":"ab965746d197e296c253fb552dc1fd47"},{"_idRow":1282198,"_sFile":"deer_post_processing_mod.zip","_nFilesize":91705,"_tsDateAdded":1726570502,"_bIsArchived":false,"_sMd5Checksum":"8797b6cdb331371ef015e78a9f79bad9"}],"_bIsNsfw":false},{"_idRow":555341,"_nDownloadCount":4932,"_aFiles":[{"_idRow":1328678,"_sFile":"redgeist.zip","_nFilesize":12797496,"_tsDateAdded":1732398791,"_bIsArchived":false,"_sMd5Checksum":"77b555b9ce0e99b7f30483be456432d4"}],"_bIsNsfw":false}]"""

    const val Profile = """{"_idRow":723542,"_sName":"Bride of Victor | Lady Geist","_sProfileUrl":"https://gamebanana.com/mods/723542","_sVersion":"1.0","_nStatus":"0",
"_sText":"Victor Bride skin for Lady Geist<br><br>Model by <a href=\"https://x.com/Nobel_via\">Nobel</a>","_sDescription":null,
"_aSubmitter":{"_idRow":4577138,"_sName":"Nobel_"},
"_aCredits":[{"_sGroupName":"Mod Authors","_aAuthors":[{"_sRole":"Concept Art","_sName":"Human1","_sUrl":"https://x.com/anthrospray"},{"_sRole":"3D Art","_sName":"Nobel","_sUrl":"https://x.com/Nobel_via"}]},{"_sGroupName":"Key Authors","_aAuthors":{"0":{"_idRow":1735263,"_sName":"Deer_uwu","_sProfileUrl":"https://gamebanana.com/members/1735263"}}}],
"_aPreviewMedia":{"_aImages":[{"_sBaseUrl":"https://images.gamebanana.com/img/ss/mods","_sFile":"6ac11043bd7f1.jpg","_sFile530":"530-90_6ac11043bd7f1.jpg"},{"_sBaseUrl":"https://images.gamebanana.com/img/ss/mods","_sFile":"6ac1141148b02.jpg"}]},
"_aContentRatings":null}"""

    const val RootCategories = """[{"_idRow":33295,"_sName":"Skins","_nItemCount":3013,"_nCategoryCount":44},{"_idRow":37225,"_sName":"Maps","_nItemCount":39,"_nCategoryCount":0}]"""

    const val Featured = """{"_aMetadata":{"_nRecordCount":1005,"_nPerpage":15},"_aRecords":[{"_idRow":724369,"_sModelName":"Mod"},{"_idRow":901,"_sModelName":"News"},{"_idRow":77008,"_sModelName":"Sound"}]}"""
}
