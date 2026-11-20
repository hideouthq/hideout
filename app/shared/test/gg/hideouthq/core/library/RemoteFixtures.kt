package gg.hideouthq.core.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

internal fun oneSetRecord(
    installId: String,
    gameRoot: GameRoot,
    source: InstalledSource,
    title: String,
    variant: String?,
    archive: ArchiveIdentity,
    number: Int?,
    files: List<SetFile>,
    version: VersionPosition? = null,
    originalName: String? = null,
    componentKey: String = "pak01_dir.vpk",
) = ManifestRecord(installId, gameRoot, title, placedAt(number), listOf(InstalledSet(source, originalName, variant, origin(source, archive), version, componentKey, files)))

internal fun placedAt(number: Int?): Placement = number?.let { Placement.On(Slot(it)) } ?: Placement.Off

internal fun directory(image: FileImage) = SetFile(VpkPart.Directory, image)

internal fun owned(part: VpkPart, bytes: ByteArray) = SetFile(part, Sha256.image(bytes))

internal fun ManifestRecord.offPath(part: VpkPart = VpkPart.Directory): String = placed(Placement.Off).let { off -> off.files.single { it.part == part }.relativePath(off) }

internal fun origin(source: InstalledSource, archive: ArchiveIdentity): SetOrigin =
    if (source is InstalledSource.Remote) SetOrigin.Downloaded(archive) else SetOrigin.Imported(FileImage(archive.sizeBytes, archive.sha256))

internal val ManifestRecord.only: InstalledSet get() = sets.single()

internal fun ManifestRecord.withOnly(change: InstalledSet.() -> InstalledSet): ManifestRecord = copy(sets = listOf(only.change()))

internal val ManifestRecord.number: Int get() = numbers.single().value

internal val ManifestRecord.source: InstalledSource get() = only.source

internal val ManifestRecord.archive: ArchiveIdentity get() = checkNotNull(only.download)

internal val ManifestRecord.variant: String? get() = only.variant

internal val ManifestRecord.version: VersionPosition? get() = only.version

internal val ManifestRecord.remoteIdentity: SubmissionIdentity
    get() = (source as InstalledSource.Remote).identity

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.changeFilesTo(library: ModLibrary, installId: String, vararg fileIds: Long): Boolean {
    val modId = checkNotNull((library.state.value.library as LibraryStatus.Ready).records.single { it.installId == installId }.submission).modId
    library.changeFiles(installId)
    runCurrent()
    val question = library.state.value.operations[modId]?.stage as? OperationStage.ChoosingFile ?: return false
    val accepted = library.answer(question.id, ChoiceAnswer.Files(fileIds.toSet(), question.profiles))
    runCurrent()
    return accepted
}
