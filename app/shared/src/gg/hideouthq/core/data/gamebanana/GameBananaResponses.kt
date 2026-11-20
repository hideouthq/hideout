package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.Requirement
import gg.hideouthq.core.network.LenientJson
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.core.network.asBooleanOrNull
import gg.hideouthq.core.network.asList
import gg.hideouthq.core.network.asLongOrNull
import gg.hideouthq.core.network.asObjectOrNull
import gg.hideouthq.core.network.asStringOrNull
import gg.hideouthq.core.network.decodeLenient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * GameBanana's JSON, turned into the types in Submission.kt. Its shapes drift: lists arrive as
 * objects keyed by id or as `[]` when empty, counts are left out when zero, and booleans come
 * quoted. The awkward fields are read as JSON elements and picked apart by hand.
 */

internal class IndexPage(val totalRecords: Int, val submissions: List<Submission>, val skipped: Int)

internal fun parseIndexPage(text: String): IndexPage {
    val page = decodeLenient(IndexPageDto.serializer(), text)
    if (page.metadata.recordCount < 0 || (page.metadata.recordCount > 0 && page.records.isEmpty())) {
        throw RemoteException(DataError.Unavailable, "Invalid listing count or empty page")
    }
    val submissions = page.records.mapNotNull(::submissionIn)
    return IndexPage(page.metadata.recordCount, submissions, page.records.size - submissions.size)
}

private fun submissionIn(record: JsonElement): Submission? = try {
    LenientJson.decodeFromJsonElement(SubmissionDto.serializer(), record).toSubmission()
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

internal class FeaturedPicks(val refs: List<SubmissionRef>, val submissions: List<Submission>)

internal fun parseFeatured(text: String): FeaturedPicks {
    val records = decodeLenient(IndexPageDto.serializer(), text).records
    val refs = records.mapNotNull { record ->
        val fields = record.asObjectOrNull() ?: return@mapNotNull null
        val kind = kindNamed(fields["_sModelName"].asStringOrNull()) ?: return@mapNotNull null
        fields["_idRow"].asLongOrNull()?.takeIf { it > 0 }?.let { SubmissionRef(kind, it) }
    }
    return FeaturedPicks(refs, records.mapNotNull(::submissionIn).filter { it.key in refs })
}

private fun kindNamed(apiName: String?): SubmissionKind? = SubmissionKind.entries.firstOrNull { it.apiName == apiName }

internal class CategoryLevel(val categories: List<SubmissionCategory>, val withChildren: List<Long>)

internal fun parseCategories(text: String, parentId: Long?, kind: SubmissionKind): CategoryLevel {
    val dtos = decodeLenient(ListSerializer(CategoryDto.serializer()), text)
    return CategoryLevel(
        categories = dtos.map { SubmissionCategory(it.id, it.name, parentId, kind, it.itemCount) },
        withChildren = dtos.filter { it.childCount > 0 }.map { it.id },
    )
}

internal fun parseFileFacts(text: String, kind: SubmissionKind, requestedIds: Set<Long>): Map<SubmissionRef, FactsAnswer> {
    val rows = decodeLenient(JsonElement.serializer(), text) as? JsonArray
        ?: throw RemoteException(DataError.Unavailable, "Invalid ${kind.apiName}/Multi response")
    val answers = requestedIds.associateTo(LinkedHashMap<SubmissionRef, FactsAnswer>()) { SubmissionRef(kind, it) to FactsAnswer.Missing }
    for (row in rows) {
        val fields = row as? JsonObject ?: continue
        val key = fields["_idRow"].asLongOrNull()?.takeIf { it in requestedIds }?.let { SubmissionRef(kind, it) } ?: continue
        answers[key] = if (answers[key] == FactsAnswer.Missing) factsIn(fields) else FactsAnswer.Unreadable
    }
    return answers
}

private fun factsIn(fields: JsonObject): FactsAnswer {
    val records = fileRecords(fields["_aFiles"])?.map { (key, file) -> key to (file as? JsonObject ?: return FactsAnswer.Unreadable) }
        ?: return FactsAnswer.Unreadable
    val downloads = fields["_nDownloadCount"].asLongOrNull() ?: 0
    if (downloads < 0) return FactsAnswer.Unreadable
    val files = records.map { it.second }.filter { archivedFlag(it) != true }
    val current = nonArchivedRecords(records)
    val options = current?.mapNotNull { (fileKey, file) -> fileOption(fileKey, file) }
    return FactsAnswer.Read(FileFacts(
        downloads = downloads.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        largestFileBytes = files.mapNotNull { it["_nFilesize"].asLongOrNull()?.takeIf { size -> size >= 0 } }.maxOrNull(),
        newestFileAt = files.mapNotNull { it["_tsDateAdded"].asLongOrNull()?.takeIf { date -> date > 0 } }.maxOrNull(),
        currentFile = current?.singleOrNull()?.let { (fileKey, file) -> fileIdentity(fileKey, file) },
        files = options.orEmpty(),
        filesComplete = current != null && options?.size == current.size,
    ))
}

private fun nonArchivedRecords(records: List<Pair<String?, JsonObject>>): List<Pair<String?, JsonObject>>? =
    records.filter { (_, file) -> !(archivedFlag(file) ?: return null) }

private fun fileIdentity(key: String?, file: JsonObject): SubmissionFile? {
    val field = file["_idRow"]?.let { it.asLongOrNull() ?: return null }
    val keyed = key?.let { if (FileIdKey.matches(it)) it.toLong() else return null }
    val id = field ?: keyed ?: return null
    if (id <= 0 || !idMatchesKey(key, keyed, id)) return null
    val md5 = when (val checksum = readMd5(file)) {
        Md5Field.Absent -> null
        is Md5Field.Value -> checksum.md5
        Md5Field.Invalid -> return null
    }
    return SubmissionFile(id, md5)
}

private fun fileOption(key: String?, file: JsonObject): SubmissionFileOption? {
    val identity = fileIdentity(key, file) ?: return null
    return SubmissionFileOption(
        id = identity.id,
        md5 = identity.md5,
        filename = file["_sFile"].asStringOrNull()?.takeIf { it.isNotBlank() },
        description = file["_sDescription"].asStringOrNull()?.takeIf { it.isNotBlank() },
        sizeBytes = file["_nFilesize"].asLongOrNull()?.takeIf { it >= 0 },
        downloads = file["_nDownloadCount"].asLongOrNull()?.takeIf { it >= 0 },
        addedAt = file["_tsDateAdded"].asLongOrNull()?.takeIf { it > 0 },
    )
}

private val FileIdKey = Regex("[1-9][0-9]{0,17}")

internal fun parseProfile(text: String, key: SubmissionRef): SubmissionProfile {
    val dto = decodeLenient(ProfileDto.serializer(), text)
    return SubmissionProfile(
        key = key,
        name = dto.name,
        profileUrl = dto.profileUrl,
        textHtml = dto.text,
        description = dto.description,
        version = dto.version,
        submitter = memberName(dto.submitter),
        credits = dto.credits.asList().mapNotNull { it.asObjectOrNull()?.toCreditGroup() },
        images = images(dto.previewMedia),
        hasUpdates = dto.hasUpdates.asBooleanOrNull() == true,
        requirements = requirements(dto.requirements),
    )
}

/**
 * Each entry is a list whose first item is a name and whose second is a link; how many items follow depends on the
 * endpoint.
 */
private fun requirements(field: JsonElement?): List<Requirement> = field.asList().mapNotNull { entry ->
    val items = entry.asList()
    val url = items.getOrNull(1).asStringOrNull()?.trim()?.takeIf { WebAddress.matches(it) }
    val name = items.getOrNull(0).asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: url ?: return@mapNotNull null
    Requirement(name, url, url?.let(GameBananaPage::matchEntire)?.let { match ->
        val kind = if (match.groupValues[1].equals("sounds", ignoreCase = true)) SubmissionKind.Sound else SubmissionKind.Mod
        SubmissionRef(kind, match.groupValues[2].toLong()).modId
    })
}

private val WebAddress = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

private val GameBananaPage = Regex("""https?://(?:www\.)?gamebanana\.com/(mods|sounds)/([1-9][0-9]{0,17})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)

internal fun parseUpdates(text: String): List<SubmissionUpdate> =
    decodeLenient(UpdatesDto.serializer(), text).records.mapNotNull { record ->
        val fields = record.asObjectOrNull() ?: return@mapNotNull null
        val name = fields["_sName"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val addedAt = fields["_tsDateAdded"].asLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        SubmissionUpdate(
            name = name,
            version = fields["_sVersion"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            addedAt = addedAt,
            changes = fields["_aChangeLog"].asList().mapNotNull { line ->
                val entry = line.asObjectOrNull() ?: return@mapNotNull null
                val change = entry["text"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                change to entry["cat"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            },
            text = fields["_sText"].asStringOrNull(),
            fileIds = (fields["_aFileRowIds"].asList().mapNotNull { it.asLongOrNull() } +
                fields["_aFiles"].asList().mapNotNull { it.asObjectOrNull()?.get("_idRow").asLongOrNull() }).toSet(),
        )
    }

@Serializable
private class UpdatesDto(@SerialName("_aRecords") val records: List<JsonElement> = emptyList())

@Serializable
private class IndexPageDto(
    @SerialName("_aMetadata") val metadata: MetadataDto,
    @SerialName("_aRecords") val records: List<JsonElement>,
)

@Serializable
private class MetadataDto(@SerialName("_nRecordCount") val recordCount: Int)

@Serializable
private class SubmissionDto(
    @SerialName("_idRow") val id: Long,
    @SerialName("_sModelName") val model: String,
    @SerialName("_sName") val name: String,
    @SerialName("_sProfileUrl") val profileUrl: String,
    @SerialName("_tsDateAdded") val addedAt: Long = 0,
    @SerialName("_tsDateUpdated") val updatedAt: Long? = null,
    @SerialName("_tsDateModified") val modifiedAt: Long? = null,
    @SerialName("_aTags") val tags: JsonElement? = null,
    @SerialName("_aPreviewMedia") val previewMedia: JsonElement? = null,
    @SerialName("_aSubmitter") val submitter: JsonElement? = null,
    @SerialName("_aRootCategory") val rootCategory: JsonElement? = null,
    @SerialName("_aSubCategory") val subCategory: JsonElement? = null,
    @SerialName("_bIsObsolete") val obsolete: Boolean = false,
    @SerialName("_sInitialVisibility") val visibility: String = "show",
    @SerialName("_bHasContentRatings") val contentRatings: Boolean,
    @SerialName("_nLikeCount") val likes: Int = 0,
) {
    fun toSubmission(): Submission {
        val kind = kindNamed(model) ?: throw SerializationException("Not a listed kind of submission: $model")
        val root = categoryId(rootCategory)
        return Submission(
            key = SubmissionRef(kind, id),
            name = name,
            profileUrl = profileUrl,
            submitter = memberName(submitter),
            categoryId = categoryId(subCategory) ?: root,
            rootCategoryId = root,
            tags = tags.asList().mapNotNull { it.asStringOrNull() ?: it.asObjectOrNull()?.get("_sValue").asStringOrNull() },
            images = images(previewMedia),
            audioUrl = previewMedia.asObjectOrNull()?.get("_aMetadata").asObjectOrNull()?.get("_sAudioUrl").asStringOrNull(),
            likes = likes,
            addedAt = addedAt,
            updatedAt = updatedAt?.takeIf { it > 0 },
            obsolete = obsolete,
            visibility = visibility,
            mature = contentRatings,
            modifiedAt = modifiedAt?.takeIf { it > 0 },
        )
    }
}

private fun memberName(member: JsonElement?): String? = member.asObjectOrNull()?.get("_sName").asStringOrNull()

/** A listing names a category without its id, by its page's URL (`.../mods/cats/33306`). */
private fun categoryId(category: JsonElement?): Long? =
    category.asObjectOrNull()?.get("_sProfileUrl").asStringOrNull()
        ?.substringAfterLast("/cats/", "")?.takeWhile { it.isDigit() }?.toLongOrNull()

@Serializable
private class CategoryDto(
    @SerialName("_idRow") val id: Long,
    @SerialName("_sName") val name: String,
    @SerialName("_nCategoryCount") val childCount: Int = 0,
    @SerialName("_nItemCount") val itemCount: Int? = null,
)

@Serializable
private class ProfileDto(
    @SerialName("_sName") val name: String,
    @SerialName("_sProfileUrl") val profileUrl: String,
    @SerialName("_sText") val text: String? = null,
    @SerialName("_sDescription") val description: String? = null,
    @SerialName("_sVersion") val version: String? = null,
    @SerialName("_aSubmitter") val submitter: JsonElement? = null,
    @SerialName("_aCredits") val credits: JsonElement? = null,
    @SerialName("_aPreviewMedia") val previewMedia: JsonElement? = null,
    @SerialName("_bHasUpdates") val hasUpdates: JsonElement? = null,
    @SerialName("_aRequirements") val requirements: JsonElement? = null,
)

/** Credit authors come as bare names with a role, or as GameBanana members (who may have none). */
private fun JsonObject.toCreditGroup(): CreditGroup = CreditGroup(
    name = get("_sGroupName").asStringOrNull(),
    authors = get("_aAuthors").asList().mapNotNull { author ->
        val fields = author.asObjectOrNull() ?: return@mapNotNull null
        val name = fields["_sName"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Credit(name, fields["_sRole"].asStringOrNull()?.takeIf { it.isNotBlank() })
    },
)

private fun images(previewMedia: JsonElement?): List<SubmissionImage> =
    previewMedia.asObjectOrNull()?.get("_aImages").asList().mapNotNull { image ->
        val fields = image.asObjectOrNull() ?: return@mapNotNull null
        val base = fields["_sBaseUrl"].asStringOrNull() ?: return@mapNotNull null
        val file = fields["_sFile"].asStringOrNull() ?: return@mapNotNull null
        SubmissionImage(base, file, fields["_sFile530"].asStringOrNull())
    }
