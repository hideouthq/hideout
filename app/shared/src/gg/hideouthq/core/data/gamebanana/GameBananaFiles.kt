package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.Withdrawal
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.core.network.asBooleanOrNull
import gg.hideouthq.core.network.asLongOrNull
import gg.hideouthq.core.network.asStringOrNull
import gg.hideouthq.core.network.decodeLenient
import gg.hideouthq.core.network.getText
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.http.Url
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * [GameBananaClient] over GameBanana's v11 API: one submission's own page, asked every time an install starts, and
 * its search of Deadlock's mods' names.
 * A withdrawal counts only when GameBanana's flags say so; a missing file list without a flag is unknown, not empty.
 * A file, current or archived, is read only with a positive id that agrees with its key, a name, a positive size, an https download on
 * gamebanana.com, and an MD5 that is either absent or 32 hex digits.
 */
class GameBananaFiles(private val client: HttpClient) : GameBananaClient {
    override suspend fun files(submission: SubmissionRef): FileListing {
        val text = try {
            client.getText("${GameBananaApi.DefaultBaseUrl}/${submission.kind.apiName}/${submission.id}") { parameter("_csvProperties", Properties) }
        } catch (e: RemoteException) {
            return FileListing.Unavailable(e.message ?: "GameBanana did not answer")
        }
        return try {
            parseListing(decodeLenient(JsonElement.serializer(), text), submission.id)
        } catch (e: RemoteException) {
            FileListing.Unavailable(e.message ?: "GameBanana's answer could not be read")
        } catch (e: SerializationException) {
            FileListing.Unavailable("GameBanana's answer is not JSON")
        }
    }

    override suspend fun search(title: String): List<SubmissionRef>? = try {
        val text = client.getText("${GameBananaApi.DefaultBaseUrl}/Util/Search/Results") {
            parameter("_sModelName", SubmissionKind.Mod.apiName)
            parameter("_idGameRow", GameBananaApi.GameId)
            parameter("_sOrder", "best_match")
            parameter("_csvFields", "name")
            parameter("_sSearchString", title)
        }
        val records = (decodeLenient(JsonElement.serializer(), text) as? JsonObject)?.get("_aRecords") as? JsonArray
        records.orEmpty().mapNotNull { record -> (record as? JsonObject)?.get("_idRow").asLongOrNull()?.takeIf { it > 0 }?.let { SubmissionRef(SubmissionKind.Mod, it) } }
    } catch (e: RemoteException) {
        null
    } catch (e: SerializationException) {
        null
    }

    private fun parseListing(element: JsonElement, id: Long): FileListing {
        val fields = element as? JsonObject ?: return FileListing.Unavailable("GameBanana's answer is not an object")
        fields["_idRow"]?.let { if (it.asLongOrNull() != id) return FileListing.Unavailable("GameBanana answered for another submission") }
        when {
            fields["_bIsTrashed"].asBooleanOrNull() == true -> return FileListing.Withdrawn(Withdrawal.Trashed)
            fields["_bIsWithheld"].asBooleanOrNull() == true -> return FileListing.Withdrawn(Withdrawal.Withheld)
            fields["_bIsPrivate"].asBooleanOrNull() == true -> return FileListing.Withdrawn(Withdrawal.Private)
        }
        val title = fields["_sName"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return FileListing.Unavailable("GameBanana gave the submission no name")
        val container = fields["_aFiles"]
        val records = fileRecords(container) ?: return FileListing.Unavailable(
            if (container == null || container is JsonNull) "GameBanana listed no files and gave no reason" else "GameBanana's file list is not a list",
        )
        val (archived, current) = records.map { (key, value) ->
            val file = value as? JsonObject ?: return FileListing.Unavailable("a file in GameBanana's list is not an object")
            Triple(key, file, archivedFlag(file) ?: return FileListing.Unavailable("whether a file is current is unknown"))
        }.partition { it.third }
        val readable = current.mapNotNull { remoteFile(it.first, it.second) }
        return FileListing.Available(title, readable, complete = readable.size == current.size, archived = archived.mapNotNull { remoteFile(it.first, it.second) })
    }

    private fun remoteFile(key: String?, file: JsonObject): RemoteFile? {
        val id = file["_idRow"].asLongOrNull()?.takeIf { it > 0 } ?: return null
        if (!idMatchesKey(key, key?.toLongOrNull(), id)) return null
        val name = file["_sFile"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val size = file["_nFilesize"].asLongOrNull()?.takeIf { it > 0 } ?: return null
        val url = file["_sDownloadUrl"].asStringOrNull()?.takeIf(::isGameBananaDownload) ?: return null
        val md5 = when (val checksum = readMd5(file)) {
            Md5Field.Absent -> null
            is Md5Field.Value -> checksum.md5
            Md5Field.Invalid -> return null
        }
        return RemoteFile(
            id = id,
            fileName = name,
            sizeBytes = size,
            md5 = md5,
            description = file["_sDescription"].asStringOrNull()?.takeIf { it.isNotBlank() },
            downloads = file["_nDownloadCount"].asLongOrNull()?.coerceAtLeast(0) ?: 0,
            addedAt = file["_tsDateAdded"].asLongOrNull()?.coerceAtLeast(0) ?: 0,
            downloadUrl = url,
        )
    }

    private fun isGameBananaDownload(url: String): Boolean {
        val parsed = try {
            Url(url)
        } catch (e: Exception) {
            return false
        }
        val host = parsed.host.lowercase()
        return parsed.protocol.name == "https" && (host == Host || host.endsWith(".$Host"))
    }

    private companion object {
        const val Properties = "_idRow,_sName,_bIsPrivate,_bIsWithheld,_bIsTrashed,_aFiles"
        const val Host = "gamebanana.com"
    }
}
