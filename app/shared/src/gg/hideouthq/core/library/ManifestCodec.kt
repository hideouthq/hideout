package gg.hideouthq.core.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Turns [LibraryManifest] into the JSON [gg.hideouthq.core.data.LibraryStore] implementations persist, and back.
 * Pure: no file access, so the format can be tested without a disk. Decoding is all-or-nothing and strict: every
 * non-null field is required (an empty library says `"records":[]`), unknown or misspelled fields fail, and every
 * value must pass the domain's own checks. One bad record never silently drops itself while the rest of the file is
 * kept, which would adopt less than Hideout actually owns. A repair choice of a mod no longer installed is dropped.
 * Only [ManifestSchemaVersion] decodes; any other schema version is [ManifestDecode.Unsupported], left on disk exactly
 * as found.
 */
object ManifestCodec {
    /** The largest manifest, in UTF-8 bytes, that is written or trusted. */
    const val MaxBytes: Int = 8 * 1024 * 1024

    private val json = Json { explicitNulls = false }

    fun encode(manifest: LibraryManifest): String = json.encodeToString(manifest.toDto())

    fun decode(text: String): ManifestDecode = try {
        val element = json.parseToJsonElement(text)
        val version = ((element as? JsonObject)?.get("schemaVersion") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        when (version) {
            null -> ManifestDecode.Malformed("has no schema version")
            ManifestSchemaVersion -> ManifestDecode.Ok(json.decodeFromJsonElement(ManifestFileDto.serializer(), element).toDomain())
            else -> ManifestDecode.Unsupported(version)
        }
    } catch (e: SerializationException) {
        ManifestDecode.Malformed(e.message ?: "could not be parsed as JSON")
    } catch (e: IllegalArgumentException) {
        ManifestDecode.Malformed(e.message ?: "failed validation")
    }
}

sealed interface ManifestDecode {
    data class Ok(val manifest: LibraryManifest) : ManifestDecode
    data class Unsupported(val schemaVersion: Int) : ManifestDecode
    data class Malformed(val reason: String) : ManifestDecode
}

@Serializable
private data class ManifestFileDto(
    val schemaVersion: Int,
    val records: List<RecordDto>,
    val downloads: List<DownloadDto>,
    val gameConfigs: List<ReceiptDto>,
    val pending: PendingDto? = null,
    val profiles: BookDto,
    val savedChoices: SavedChoicesDto? = null,
    val originals: List<OriginalDto>,
    val repairChoices: List<RepairChoiceDto>,
)

@Serializable
private data class OriginalDto(val id: String, val installId: String, val set: SetSourceDto, val files: List<FileDto>)

@Serializable
private data class SetSourceDto(
    val submissionKind: SubmissionKind? = null,
    val submissionId: Long? = null,
    val fileId: Long? = null,
    val fileMd5: String? = null,
    val localId: String? = null,
    val localName: String? = null,
    val componentKey: String,
)

@Serializable
private data class RepairChoiceDto(val installId: String, val choice: RepairChoice)

@Serializable
private data class DerivationDto(
    val original: String,
    val originalFiles: List<ImageDto>,
    val rules: List<RuleDto>,
    val codec: String,
    val context: String,
    val resources: Int,
    val edits: Int,
)

@Serializable
private data class RuleDto(val id: String, val revision: Int)

@Serializable
private data class SavedChoicesDto(val profiles: BookDto)

@Serializable
private data class OrderDto(val gameRoot: String, val ids: List<String>)

@Serializable
private data class ProfileDto(val id: String, val name: String, val colour: ProfileColour, val enabled: List<String>, val orders: List<OrderDto>)

@Serializable
private data class BookDto(val active: String, val profiles: List<ProfileDto>)

private fun ProfileBook.toDto() = BookDto(active.value, profiles.map { profile ->
    ProfileDto(profile.id.value, profile.name, profile.colour, profile.enabled.sorted(), profile.orders.entries.sortedBy { it.key.path }.map { (root, ids) -> OrderDto(root.path, ids) })
})

private fun ProfileDto.toDomain() = Profile(ProfileId(id), name, colour, enabled.toSet(), orders.associate { GameRoot(it.gameRoot) to it.ids })

private fun BookDto.toDomain() = ProfileBook(ProfileId(active), profiles.map { it.toDomain() })

@Serializable
@SerialName("Replace")
private data class PendingReplaceDto(
    val source: RecordDto,
    val target: RecordDto,
    val token: String,
    val step: ReplaceStep,
    val kept: List<String>,
    val found: List<FoundDto>? = null,
    val aside: String? = null,
) : PendingDto

@Serializable
private data class FoundDto(val name: String, val image: ImageDto? = null)

@Serializable
private data class DownloadDto(
    val submissionKind: SubmissionKind,
    val submissionId: Long,
    val fileId: Long,
    val fileMd5: String?,
    val archivePath: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val retention: DownloadRetention,
)

@Serializable
private data class ImageDto(val sizeBytes: Long, val sha256: String)

@Serializable
private data class ReceiptDto(val gameRoot: String, val written: ImageDto?, val intent: LoadingIntent, val customGames: Boolean = false)

@Serializable
private sealed interface PendingDto

@Serializable
@SerialName("Install")
private data class PendingInstallDto(val record: RecordDto, val step: StepDto, val published: List<String>, val profiles: List<String>) : PendingDto

@Serializable
@SerialName("ConfigEdit")
private data class PendingConfigDto(
    val gameRoot: String,
    val target: ConfigTargetDto,
    val intent: LoadingIntent? = null,
    val customGames: Boolean = false,
    val token: String,
    val from: ImageDto? = null,
    val to: ImageDto,
) : PendingDto

private enum class ConfigTargetDto { GameInfo, Autoexec }

@Serializable
@SerialName("Relocate")
private data class PendingRelocateDto(val source: RecordDto, val target: RecordDto, val token: String, val step: RelocateStep) : PendingDto

@Serializable
@SerialName("Uninstall")
private data class PendingUninstallDto(
    val record: RecordDto,
    val token: String,
    val step: RemovalStepDto,
    val files: List<FileOutcomeDto>,
    val archives: List<String>,
    val archiveOutcomes: List<FileOutcomeDto>,
) : PendingDto

@Serializable
@SerialName("ArchiveRemoval")
private data class PendingArchiveRemovalDto(
    val download: DownloadDto,
    val installedInto: String?,
    val token: String,
    val step: RemovalStepDto,
    val outcome: OutcomeDto?,
) : PendingDto

@Serializable
@SerialName("Arrange")
private data class PendingArrangeDto(val token: String, val moves: List<MoveDto>, val after: BookDto, val step: MoveStep) : PendingDto

@Serializable
private data class MoveDto(val source: RecordDto, val placement: PlacementDto, val order: List<String>)

@Serializable
@SerialName("Adopt")
private data class PendingAdoptDto(
    val gameRoot: String,
    val migration: String,
    val adopted: List<AdoptionDto>,
    val book: BookDto,
    val target: BookDto,
    val step: AdoptStep,
) : PendingDto

@Serializable
@SerialName("Revert")
private data class PendingRevertDto(
    val gameRoot: String,
    val source: List<RecordDto>,
    val target: List<RecordDto>,
    val token: String,
    val step: ReplaceStep,
    val book: BookDto,
    val choices: SavedChoicesDto? = null,
    val originals: List<OriginalDto>,
    val registered: List<String>,
    val repairChoices: List<RepairChoiceDto>,
    val absent: List<String>,
    val missing: List<String>,
) : PendingDto

@Serializable
private data class AdoptionDto(val record: RecordDto, val sources: List<String>)

private enum class RemovalStepDto { Anchoring, Releasing, Settled }

private enum class OutcomeKind { Removed, Absent, Preserved }

@Serializable
private data class OutcomeDto(val kind: OutcomeKind, val reason: String?)

@Serializable
private data class FileOutcomeDto(val name: String, val kind: OutcomeKind, val reason: String?)

private enum class StepDto { Staging, Publishing, Releasing, RollingBack }

@Serializable
private data class RecordDto(
    val installId: String,
    val gameRoot: String,
    val title: String,
    val placement: PlacementDto,
    val sets: List<SetDto>,
    val reviewed: List<Long>? = null,
    val declined: Map<Long, List<String>>? = null,
    val keptBeside: List<String>? = null,
    val unasked: Boolean? = null,
)

@Serializable
private sealed interface PlacementDto

@Serializable
@SerialName("On")
private data class OnDto(val first: Int) : PlacementDto

@Serializable
@SerialName("Off")
private data object OffDto : PlacementDto

@Serializable
private data class SetDto(
    val submissionKind: SubmissionKind? = null,
    val submissionId: Long? = null,
    val fileId: Long? = null,
    val fileMd5: String? = null,
    val fileName: String? = null,
    val localId: String? = null,
    val localName: String? = null,
    val variant: String? = null,
    val origin: OriginDto,
    val versionIndex: Int? = null,
    val versionCount: Int? = null,
    val files: List<FileDto>,
    val componentKey: String,
    val derivation: DerivationDto? = null,
    val edits: List<FileDto>? = null,
)

@Serializable
private data class FileDto(val part: String, val sizeBytes: Long, val sha256: String)

private fun SetFile.toDto() = FileDto(part.label, image.sizeBytes, image.sha256)

private fun FileDto.toDomain() = SetFile(requireNotNull(VpkPart.parse(part)) { "\"$part\" is not a part of a set" }, FileImage(sizeBytes, sha256))

@Serializable
private sealed interface OriginDto

@Serializable
@SerialName("Downloaded")
private data class DownloadedDto(val archivePath: String, val archiveSizeBytes: Long, val archiveSha256: String) : OriginDto

@Serializable
@SerialName("Imported")
private data class ImportedDto(val sizeBytes: Long, val sha256: String) : OriginDto

@Serializable
@SerialName("Adopted")
private data class AdoptedDto(val migration: String) : OriginDto

private fun SetOrigin.toDto(): OriginDto = when (this) {
    is SetOrigin.Downloaded -> DownloadedDto(archive.path, archive.sizeBytes, archive.sha256)
    is SetOrigin.Imported -> ImportedDto(image.sizeBytes, image.sha256)
    is SetOrigin.Adopted -> AdoptedDto(migration.value)
}

private fun OriginDto.toDomain(): SetOrigin = when (this) {
    is DownloadedDto -> SetOrigin.Downloaded(ArchiveIdentity(archivePath, archiveSizeBytes, archiveSha256))
    is ImportedDto -> SetOrigin.Imported(FileImage(sizeBytes, sha256))
    is AdoptedDto -> SetOrigin.Adopted(MigrationId(migration))
}

private fun LibraryManifest.toDto() = ManifestFileDto(
    schemaVersion = ManifestSchemaVersion,
    originals = originals.map { it.toDto() },
    repairChoices = repairChoices.toDto(),
    profiles = profiles.toDto(),
    savedChoices = savedChoices?.toDto(),
    records = records.map { it.toDto() },
    downloads = downloads.map { it.toDto() },
    gameConfigs = gameConfigs.map { ReceiptDto(it.gameRoot.path, it.written?.toDto(), it.lines.intent, it.lines.customGames) },
    pending = when (val change = pending) {
        null -> null
        is PendingChange.Replace -> PendingReplaceDto(
            source = change.source.toDto(),
            target = change.target.toDto(),
            token = change.token.value,
            step = change.step,
            kept = change.source.sets.map { it.key }.filter { it in change.kept }.map { it.value },
            found = change.found.entries.sortedBy { it.key }.map { FoundDto(it.key, it.value?.toDto()) }.ifEmpty { null },
            aside = change.aside,
        )
        is PendingChange.Install -> PendingInstallDto(
            profiles = change.profiles.map { it.value }.sorted(),
            record = change.record.toDto(),
            step = when (change.step) {
                InstallStep.Staging -> StepDto.Staging
                InstallStep.Publishing -> StepDto.Publishing
                InstallStep.Releasing -> StepDto.Releasing
                is InstallStep.RollingBack -> StepDto.RollingBack
            },
            published = (change.step as? InstallStep.RollingBack)?.published.orEmpty().sorted(),
        )
        is PendingChange.ConfigEdit -> when (val target = change.target) {
            is ConfigTarget.GameInfo -> PendingConfigDto(change.gameRoot.path, ConfigTargetDto.GameInfo, target.lines.intent, target.lines.customGames, change.token, change.from?.toDto(), change.to.toDto())
            ConfigTarget.Autoexec -> PendingConfigDto(change.gameRoot.path, ConfigTargetDto.Autoexec, null, false, change.token, change.from?.toDto(), change.to.toDto())
        }
        is PendingChange.Relocate -> PendingRelocateDto(change.source.toDto(), change.target.toDto(), change.token.value, change.step)
        is PendingChange.Uninstall -> {
            val settled = change.step as? UninstallStep.Settled
            PendingUninstallDto(
                record = change.record.toDto(),
                token = change.token.value,
                step = when (change.step) {
                    UninstallStep.Anchoring -> RemovalStepDto.Anchoring
                    UninstallStep.Releasing -> RemovalStepDto.Releasing
                    is UninstallStep.Settled -> RemovalStepDto.Settled
                },
                files = change.record.files.mapNotNull { file -> settled?.files?.get(file.name)?.toDto()?.let { FileOutcomeDto(file.name, it.kind, it.reason) } },
                archives = change.archives.map { it.path },
                archiveOutcomes = change.archives.mapNotNull { archive -> settled?.archives?.get(archive.path)?.toDto()?.let { FileOutcomeDto(archive.path, it.kind, it.reason) } },
            )
        }
        is PendingChange.ArchiveRemoval -> PendingArchiveRemovalDto(
            download = change.download.toDto(),
            installedInto = change.installedInto,
            token = change.token.value,
            step = when (change.step) {
                ArchiveStep.Anchoring -> RemovalStepDto.Anchoring
                ArchiveStep.Releasing -> RemovalStepDto.Releasing
                is ArchiveStep.Settled -> RemovalStepDto.Settled
            },
            outcome = (change.step as? ArchiveStep.Settled)?.outcome?.toDto(),
        )
        is PendingChange.Arrange -> PendingArrangeDto(change.token.value, change.moves.map { MoveDto(it.source.toDto(), it.target.placement.toDto(), it.target.sets.map { set -> set.key.value }) }, change.after.toDto(), change.step)
        is PendingChange.Adopt -> with(change.plan) {
            PendingAdoptDto(gameRoot.path, migration.value, adopted.map { AdoptionDto(it.record.toDto(), it.sources.map { path -> path.value }) }, book.toDto(), target.toDto(), change.step)
        }
        is PendingChange.Revert -> PendingRevertDto(
            gameRoot = change.gameRoot.path,
            source = change.source.map { it.toDto() },
            target = change.target.map { it.toDto() },
            token = change.token.value,
            step = change.step,
            book = change.book.toDto(),
            choices = change.choices?.toDto(),
            originals = change.originals.map { it.toDto() },
            registered = change.registered.sorted(),
            repairChoices = change.repairChoices.toDto(),
            absent = change.absent.sorted(),
            missing = change.missing.sorted(),
        )
    },
)

private fun OwnedDownload.toDto() =
    DownloadDto(source.kind, source.submissionId, source.fileId, source.fileMd5, archive.path, archive.sizeBytes, archive.sha256, retention)

private fun RemovalOutcome.toDto() = when (this) {
    RemovalOutcome.Removed -> OutcomeDto(OutcomeKind.Removed, null)
    RemovalOutcome.Absent -> OutcomeDto(OutcomeKind.Absent, null)
    is RemovalOutcome.Preserved -> OutcomeDto(OutcomeKind.Preserved, reason)
}

private fun outcome(kind: OutcomeKind, reason: String?): RemovalOutcome = when (kind) {
    OutcomeKind.Removed -> RemovalOutcome.Removed
    OutcomeKind.Absent -> RemovalOutcome.Absent
    OutcomeKind.Preserved -> RemovalOutcome.Preserved(requireNotNull(reason) { "a preserved file says why" })
}

private fun FileImage.toDto() = ImageDto(sizeBytes, sha256)

private fun ImageDto.toDomain() = FileImage(sizeBytes, sha256)

private fun RetainedOriginal.toDto() =
    OriginalDto(id, installId, sourceDto(source, componentKey), files.map { it.toDto() })

private fun OriginalDto.toDomain() = RetainedOriginal(
    id, installId, source(set.submissionKind, set.submissionId, set.fileId, set.fileMd5, set.localId, set.localName), set.componentKey,
    files.map { it.toDomain() },
)

private fun Placement.toDto(): PlacementDto = when (this) {
    is Placement.On -> OnDto(first.value)
    Placement.Off -> OffDto
}

private fun PlacementDto.toDomain(): Placement = when (this) {
    is OnDto -> Placement.On(Slot(first))
    OffDto -> Placement.Off
}

private fun Map<String, RepairChoice>.toDto() = entries.sortedBy { it.key }.map { RepairChoiceDto(it.key, it.value) }

private fun SavedChoices.toDto() = SavedChoicesDto(target.toDto())

private fun SavedChoicesDto.toDomain() = SavedChoices(profiles.toDomain())

private fun ManifestRecord.toDto() = RecordDto(
    installId = installId, gameRoot = gameRoot.path, title = title, placement = placement.toDto(), sets = sets.map { it.toDto() }, reviewed = reviewed?.sorted(),
    declined = declined.takeIf { it.isNotEmpty() }?.toSortedMap()?.mapValues { it.value.sorted() }, keptBeside = keptBeside.takeIf { it.isNotEmpty() }?.sorted(),
    unasked = unasked.takeIf { it },
)

private fun sourceDto(source: InstalledSource, componentKey: String): SetSourceDto {
    val remote = (source as? InstalledSource.Remote)?.identity
    val local = source as? InstalledSource.Local
    return SetSourceDto(source.submission?.kind, source.submission?.id, remote?.fileId, remote?.fileMd5, local?.id, local?.fileName, componentKey)
}

private fun source(kind: SubmissionKind?, submissionId: Long?, fileId: Long?, fileMd5: String?, localId: String?, localName: String?): InstalledSource = if (localId != null) {
    require(fileId == null && fileMd5 == null && (kind == null) == (submissionId == null))
    InstalledSource.Local(localId, requireNotNull(localName), kind?.let { SubmissionRef(it, requireNotNull(submissionId)) })
} else {
    require(localName == null)
    InstalledSource.Remote(SubmissionIdentity(requireNotNull(kind), requireNotNull(submissionId), requireNotNull(fileId), fileMd5))
}

private fun SetDerivation.toDto() = DerivationDto(original, originalFiles.map { it.toDto() }, rules.map { RuleDto(it.id, it.revision) }, codec, context, resources, edits)

private fun DerivationDto.toDomain() = SetDerivation(original, originalFiles.map { it.toDomain() }, rules.map { RuleIdentity(it.id, it.revision) }, codec, context, resources, edits)

private fun InstalledSet.toDto(): SetDto {
    val from = sourceDto(source, componentKey)
    return SetDto(
        submissionKind = from.submissionKind,
        submissionId = from.submissionId,
        fileId = from.fileId,
        fileMd5 = from.fileMd5,
        fileName = originalName,
        localId = from.localId,
        localName = from.localName,
        variant = variant,
        origin = origin.toDto(),
        versionIndex = version?.index,
        versionCount = version?.count,
        files = files.map { it.toDto() },
        componentKey = componentKey,
        derivation = derivation?.toDto(),
        edits = edits.takeIf { it.isNotEmpty() }?.entries?.sortedBy { it.key }?.map { (part, image) -> SetFile(part, image).toDto() },
    )
}

private fun ManifestFileDto.toDomain(): LibraryManifest {
    val records = records.map { it.toDomain() }
    val installed = records.map { it.installId }.toSet()
    return LibraryManifest(
        originals = originals.map { it.toDomain() },
        repairChoices = repairChoices.filter { it.installId in installed }.associate { it.installId to it.choice },
        profiles = profiles.toDomain(),
        savedChoices = savedChoices?.toDomain(),
        records = records,
        downloads = downloads.map { it.toDomain() },
        gameConfigs = gameConfigs.map { GameConfigReceipt(GameRoot(it.gameRoot), it.written?.toDomain(), linesOf(it.intent, it.customGames)) },
        pending = when (val change = pending) {
            null -> null
            is PendingReplaceDto -> PendingChange.Replace(
                change.source.toDomain(), change.target.toDomain(), change.kept.mapTo(HashSet(), ::SetKey),
                ChangeToken(change.token), change.step, change.found.orEmpty().associate { it.name to it.image?.toDomain() }, change.aside,
            )
            is PendingInstallDto -> PendingChange.Install(
                record = change.record.toDomain(),
                profiles = change.profiles.map { ProfileId(it) }.toSet(),
                step = when (change.step) {
                    StepDto.Staging -> InstallStep.Staging
                    StepDto.Publishing -> InstallStep.Publishing
                    StepDto.Releasing -> InstallStep.Releasing
                    StepDto.RollingBack -> InstallStep.RollingBack(change.published.toSet())
                },
            )
            is PendingConfigDto -> {
                val target = when (change.target) {
                    ConfigTargetDto.GameInfo -> ConfigTarget.GameInfo(linesOf(requireNotNull(change.intent) { "a gameinfo.gi edit carries out an intent" }, change.customGames))
                    ConfigTargetDto.Autoexec -> ConfigTarget.Autoexec
                }
                PendingChange.ConfigEdit(GameRoot(change.gameRoot), target, change.token, change.from?.toDomain(), change.to.toDomain())
            }
            is PendingRelocateDto -> PendingChange.Relocate(change.source.toDomain(), ChangeToken(change.token), change.step, change.target.toDomain())
            is PendingUninstallDto -> change.toDomain()
            is PendingArchiveRemovalDto -> {
                val step = when (change.step) {
                    RemovalStepDto.Anchoring -> ArchiveStep.Anchoring
                    RemovalStepDto.Releasing -> ArchiveStep.Releasing
                    RemovalStepDto.Settled -> ArchiveStep.Settled(requireNotNull(change.outcome) { "a settled removal has an outcome" }.let { outcome(it.kind, it.reason) })
                }
                PendingChange.ArchiveRemoval(change.download.toDomain(), change.installedInto, ChangeToken(change.token), step)
            }
            is PendingArrangeDto -> PendingChange.Arrange(ChangeToken(change.token), change.moves.map { it.toDomain() }, change.after.toDomain(), change.step)
            is PendingAdoptDto -> {
                require(isPlainAbsolutePath(change.gameRoot)) { "an adoption names a game root" }
                val adopted = change.adopted.map { adoption -> Adoption(adoption.record.toDomain(), adoption.sources.map(::CitadelPath)) }
                PendingChange.Adopt(AdoptionPlan(GameRoot(change.gameRoot), MigrationId(change.migration), adopted, change.book.toDomain(), change.target.toDomain()), change.step)
            }
            is PendingRevertDto -> {
                require(isPlainAbsolutePath(change.gameRoot)) { "a restore names a game root" }
                PendingChange.Revert(
                    GameRoot(change.gameRoot), change.source.map { it.toDomain() }, change.target.map { it.toDomain() }, ChangeToken(change.token), change.step,
                    change.book.toDomain(), change.choices?.toDomain(), change.originals.map { it.toDomain() }, change.registered.toSet(),
                    change.repairChoices.associate { it.installId to it.choice }, change.absent.toSet(), change.missing.toSet(),
                )
            }
        },
    )
}

private fun PendingUninstallDto.toDomain(): PendingChange.Uninstall {
    val record = record.toDomain()
    val fileOutcomes = files.associate { it.name to outcome(it.kind, it.reason) }
    val archives = archives.map { path -> requireNotNull(record.sets.mapNotNull { it.download }.firstOrNull { it.path == path }) { "only the mod's own downloads can go with it" } }
    val archiveOutcomes = archiveOutcomes.associate { it.name to outcome(it.kind, it.reason) }
    val step = when (step) {
        RemovalStepDto.Anchoring -> UninstallStep.Anchoring
        RemovalStepDto.Releasing -> UninstallStep.Releasing
        RemovalStepDto.Settled -> UninstallStep.Settled(fileOutcomes, archiveOutcomes)
    }
    return PendingChange.Uninstall(record, archives, ChangeToken(token), step)
}

private fun DownloadDto.toDomain(): OwnedDownload =
    OwnedDownload(SubmissionIdentity(submissionKind, submissionId, fileId, fileMd5), ArchiveIdentity(archivePath, archiveSizeBytes, archiveSha256), retention)

private fun RecordDto.toDomain(): ManifestRecord =
    ManifestRecord(
        installId = installId, gameRoot = GameRoot(gameRoot), title = title, placement = placement.toDomain(), sets = sets.map { it.toDomain() }, reviewed = reviewed?.toSet(),
        declined = declined.orEmpty().mapValues { it.value.toSet() }, keptBeside = keptBeside.orEmpty().toSet(), unasked = unasked == true,
    )

private fun MoveDto.toDomain(): Move {
    val record = source.toDomain()
    return Move(record, record.placed(placement.toDomain(), order.map { record.set(SetKey(it)) }))
}

private fun SetDto.toDomain(): InstalledSet = InstalledSet(
    source = source(submissionKind, submissionId, fileId, fileMd5, localId, localName),
    originalName = fileName,
    variant = variant,
    origin = origin.toDomain(),
    version = if (versionIndex == null && versionCount == null) {
        null
    } else {
        VersionPosition(requireNotNull(versionIndex) { "a version has an index" }, requireNotNull(versionCount) { "a version has a count" })
    },
    componentKey = componentKey,
    files = files.map { it.toDomain() },
    derivation = derivation?.toDomain(),
    edits = edits.orEmpty().map { it.toDomain() }.also { require(it.map { edit -> edit.part }.distinct().size == it.size) { "a part is edited once" } }.associate { it.part to it.image },
)

private val HideoutLines.intent: LoadingIntent get() = if (mods) LoadingIntent.Modded else LoadingIntent.Vanilla

private fun linesOf(intent: LoadingIntent, customGames: Boolean) = HideoutLines(mods = intent == LoadingIntent.Modded, customGames = customGames)
