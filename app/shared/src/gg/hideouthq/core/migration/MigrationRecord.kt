package gg.hideouthq.core.migration

import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.ImportSummary
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.MigrationId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one import keeps in Hideout's data folder, written before it changes anything: with the copies beside it and
 * every adopted file's old path and bytes, the import can be undone by hand. A record with no [outcome] is an import
 * that did not end, which runs again at the next start.
 */
data class MigrationRecord(
    val id: MigrationId,
    val source: ImportSource,
    val choice: ImportChoice,
    val root: GameRoot,
    /** Epoch milliseconds. */
    val startedAt: Long,
    /** The game files copied beside the record, by path under `game/citadel`, with the bytes each held. */
    val copies: Map<CitadelPath, FileImage>,
    /**
     * Every file the import takes over, or removes as another copy of those bytes, where it was and its bytes. The name
     * that holds them now is in `library.json`, found by the same bytes, so a removed copy needs no copy of its own here.
     */
    val adopted: Map<CitadelPath, FileImage> = emptyMap(),
    val outcome: RecordOutcome? = null,
)

sealed interface RecordOutcome {
    data class Finished(val summary: ImportSummary) : RecordOutcome

    /** It stopped for [reason]; whatever it committed stays, and importing again finishes it. */
    data class Stopped(val reason: String) : RecordOutcome
}

/** The record as JSON, strict both ways: a record that does not decode is never run again. */
object MigrationRecordCodec {
    private val json = Json { explicitNulls = false; prettyPrint = true }

    fun encode(record: MigrationRecord): ByteArray = json.encodeToString(RecordDto.serializer(), record.toDto()).encodeToByteArray()

    fun decode(bytes: ByteArray): MigrationRecord? = try {
        json.decodeFromString(RecordDto.serializer(), bytes.decodeToString()).toDomain()
    } catch (e: IllegalArgumentException) {
        null
    }
}

@Serializable
private data class RecordDto(
    val id: String,
    val source: SourceDto,
    val choice: ChoiceDto,
    val root: String,
    val startedAt: Long,
    val copies: List<FileDto>,
    val adopted: List<FileDto>,
    val outcome: OutcomeDto? = null,
)

@Serializable
private data class SourceDto(val manager: Manager? = null, val dataFolder: String? = null, val bundle: String? = null)

@Serializable
private data class ChoiceDto(val mods: ModsChoice, val crosshair: Boolean, val commands: Boolean)

@Serializable
private data class FileDto(val path: String, val sizeBytes: Long, val sha256: String)

@Serializable
private sealed interface OutcomeDto

@Serializable
@SerialName("Finished")
private data class FinishedDto(val on: Int, val off: Int, val profiles: List<String>, val crosshair: Boolean, val commands: Int) : OutcomeDto

@Serializable
@SerialName("Stopped")
private data class StoppedDto(val reason: String) : OutcomeDto

private fun MigrationRecord.toDto() = RecordDto(
    id = id.value,
    source = when (source) {
        is ImportSource.Installed -> SourceDto(manager = source.manager, dataFolder = source.dataFolder)
        is ImportSource.Bundle -> SourceDto(bundle = source.folder)
    },
    choice = ChoiceDto(choice.mods, choice.crosshair, choice.commands),
    root = root.path,
    startedAt = startedAt,
    copies = copies.map { (path, image) -> FileDto(path.value, image.sizeBytes, image.sha256) },
    adopted = adopted.map { (path, image) -> FileDto(path.value, image.sizeBytes, image.sha256) },
    outcome = when (val ended = outcome) {
        null -> null
        is RecordOutcome.Finished -> with(ended.summary) { FinishedDto(on, off, profiles, crosshair, commands) }
        is RecordOutcome.Stopped -> StoppedDto(ended.reason)
    },
)

private fun RecordDto.toDomain(): MigrationRecord {
    val source = when {
        source.manager != null && source.bundle == null -> ImportSource.Installed(source.manager, source.dataFolder)
        source.bundle != null && source.manager == null && source.dataFolder == null -> ImportSource.Bundle(source.bundle)
        else -> throw IllegalArgumentException("a record names one source")
    }
    fun List<FileDto>.files() = associate { CitadelPath(it.path) to FileImage(it.sizeBytes, it.sha256) }.also { require(it.size == size) { "a file is listed once" } }
    return MigrationRecord(
        MigrationId(id), source, ImportChoice(choice.mods, choice.crosshair, choice.commands), GameRoot(root), startedAt, copies.files(), adopted.files(),
        when (val ended = outcome) {
            null -> null
            is FinishedDto -> RecordOutcome.Finished(ImportSummary(ended.on, ended.off, ended.profiles, ended.crosshair, ended.commands))
            is StoppedDto -> RecordOutcome.Stopped(ended.reason)
        },
    )
}
