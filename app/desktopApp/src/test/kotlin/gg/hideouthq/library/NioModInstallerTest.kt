package gg.hideouthq.library

import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.StageResult
import gg.hideouthq.core.data.UnpackResult
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ArchiveNote
import gg.hideouthq.core.library.ArchiveRejection
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.RejectionReason
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.SetProblem
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.StagedVpk
import gg.hideouthq.core.library.UnpackLimits
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.core.library.groupVpkSets
import gg.hideouthq.library.archive.ArchiveReaders
import gg.hideouthq.library.archive.Deadline
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class NioModInstallerTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "install-tests")), "home").toRealPath()
    private val staging = home.resolve("data/staging")
    private val installer = NioModInstaller(staging)
    private val root = GameRoot(home.resolve("Deadlock").toString())
    private val addons = home.resolve("Deadlock/game/citadel/addons")

    private val dir = FakeVpk.directory(embeddedBytes = 8, chunkReads = mapOf(0 to 50L), seed = 1)
    private val chunk = ByteArray(50) { 7 }
    private val single = FakeVpk.directory(embeddedBytes = 64, seed = 2)

    init {
        home.resolve("Deadlock/game/citadel").createDirectories()
    }

    private class Entry(val name: String, val bytes: ByteArray = ByteArray(0), val link: Boolean = false, val directory: Boolean = false)

    private fun zip(name: String, vararg entries: Entry): ArchiveIdentity {
        val file = home.resolve(name)
        ZipArchiveOutputStream(file.toFile()).use { out ->
            for (entry in entries) {
                val zipEntry = ZipArchiveEntry(entry.name)
                if (entry.link) zipEntry.unixMode = UnixStat.LINK_FLAG or UnixStat.DEFAULT_LINK_PERM
                out.putArchiveEntry(zipEntry)
                out.write(entry.bytes)
                out.closeArchiveEntry()
            }
        }
        return identity(file)
    }

    private fun sevenZ(name: String, vararg entries: Entry): ArchiveIdentity {
        val file = home.resolve(name)
        SevenZOutputFile(file.toFile()).use { out ->
            for (entry in entries) {
                val sevenZEntry = SevenZArchiveEntry()
                sevenZEntry.name = entry.name
                sevenZEntry.isDirectory = entry.directory
                if (entry.link) {
                    sevenZEntry.hasWindowsAttributes = true
                    sevenZEntry.windowsAttributes = 0x8000 or ((UnixStat.LINK_FLAG or UnixStat.DEFAULT_LINK_PERM) shl 16)
                }
                out.putArchiveEntry(sevenZEntry)
                if (!entry.directory) out.write(entry.bytes)
                out.closeArchiveEntry()
            }
        }
        return identity(file)
    }

    private fun identity(file: Path) = ArchiveIdentity(file.toString(), Files.size(file), sha256Hex(file.readBytes()))

    private fun unpack(archive: ArchiveIdentity, limits: UnpackLimits = UnpackLimits()) = runBlocking { installer.unpack(archive, limits) }

    private fun names(folder: Path = addons) = Files.list(folder).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }

    private fun stagingFiles(): List<String> = if (staging.exists()) Files.walk(staging).use { paths -> paths.filter(Files::isRegularFile).map { staging.relativize(it).toString() }.toList() } else emptyList()

    @Test
    fun aBareVpkOrChunkIsItsOwnArchiveUnderTheNameItWasGiven() = runBlocking {
        val download = home.resolve("Gojo full.vpk").apply { writeBytes(single) }
        val unpacked = assertIs<UnpackResult.Unpacked>(installer.unpack(identity(download), UnpackLimits()))
        assertEquals(listOf("Gojo full.vpk" to sha256Hex(single)), unpacked.vpks.map { it.path to it.sha256 })
        assertEquals(1, groupVpkSets(unpacked.vpks).sets.size)
        installer.discard(unpacked.staging)
        assertContentEquals(single, download.readBytes())
        assertEquals(emptyList(), stagingFiles())

        val directory = home.resolve("directory-copy.vpk").apply { writeBytes(dir) }
        val missing = groupVpkSets(assertIs<UnpackResult.Unpacked>(installer.unpack(identity(directory), UnpackLimits(), "skin_dir.vpk")).vpks)
        assertEquals(SetProblem.MissingChunks(listOf("skin_000.vpk")), missing.rejected.single().problem)
        val chunkFile = home.resolve("chunk-copy.vpk").apply { writeBytes(chunk) }
        assertEquals(SetProblem.Orphan, groupVpkSets(assertIs<UnpackResult.Unpacked>(installer.unpack(identity(chunkFile), UnpackLimits(), "skin_000.vpk")).vpks).rejected.single().problem)
    }

    @Test
    fun aZipsVpksAreExtractedUnderGeneratedNamesAndNothingElseIsWritten() {
        val archive = zip("set.zip", Entry("Ivy/ivy_dir.vpk", dir), Entry("readme.txt", "hi".encodeToByteArray()), Entry("Ivy/ivy_000.vpk", chunk))
        val unpacked = assertIs<UnpackResult.Unpacked>(unpack(archive))
        assertEquals(listOf("Ivy/ivy_dir.vpk" to sha256Hex(dir), "Ivy/ivy_000.vpk" to sha256Hex(chunk)), unpacked.vpks.map { it.path to it.sha256 })
        assertEquals(setOf("${unpacked.staging.value}/0.vpk", "${unpacked.staging.value}/2.vpk"), stagingFiles().toSet())
        assertIs<VpkRead.Parsed>(unpacked.vpks[0].directory)
        assertEquals(listOf("Ivy/ivy_dir.vpk"), groupVpkSets(unpacked.vpks).sets.map { it.key })
        assertEquals(listOf(ArchiveNote("readme.txt", "hi")), unpacked.notes, "the readme is read, never written")
    }

    @Test
    fun aNoteIsReadOnlyAsFarAsTheLimitAndSaysItWasCut() {
        val long = "Name the skin pak02 and this pak01. ".repeat(40).encodeToByteArray()
        val unpacked = assertIs<UnpackResult.Unpacked>(unpack(sevenZ("long.7z", Entry("README.md", long), Entry("pak01_dir.vpk", single)), UnpackLimits(maxNoteBytes = 100)))
        assertEquals(listOf(ArchiveNote("README.md", long.decodeToString(0, 100).trim() + "…")), unpacked.notes)
        assertEquals(listOf("${unpacked.staging.value}/1.vpk"), stagingFiles())
    }

    @Test
    fun anUnpackWhoseResultIsLostToACancelledCallerLeavesNoStagingFolder() {
        val archive = zip("set.zip", Entry("Set/x_dir.vpk", dir), Entry("Set/x_000.vpk", chunk))
        val tasks = LinkedBlockingQueue<Runnable>()
        val caller = Executor { tasks.put(it) }.asCoroutineDispatcher()
        // An extraction that ends before its caller has suspended hands the result straight back, with nothing to cancel.
        val callerSuspended = CountDownLatch(1)
        val installer = NioModInstaller(staging, clock = { callerSuspended.await(20, TimeUnit.SECONDS); System.nanoTime() })
        var result: UnpackResult? = null
        val job = CoroutineScope(caller).launch { result = installer.unpack(archive, UnpackLimits()) }
        assertNotNull(tasks.poll(20, TimeUnit.SECONDS)).run()
        callerSuspended.countDown()

        val resumption = assertNotNull(tasks.poll(20, TimeUnit.SECONDS), "the extraction finished on the IO threads and asked to resume its caller")
        assertEquals(2, stagingFiles().size, "both VPKs were extracted into a staging folder")
        job.cancel()
        resumption.run()
        while (!job.isCompleted) tasks.poll(20, TimeUnit.SECONDS)?.run()

        assertTrue(job.isCancelled)
        assertNull(result, "the caller never saw the result")
        assertEquals(emptyList(), Files.list(staging).use { it.toList() }, "the folder no caller owns is deleted")
    }

    @Test
    fun aSevenZipIsDecodedInOrderIntoTheSameShape() {
        val archive = sevenZ("set.7z", Entry("notes", directory = true), Entry("notes/a.txt", ByteArray(5000) { 1 }), Entry("pak01_dir.vpk", single))
        val unpacked = assertIs<UnpackResult.Unpacked>(unpack(archive))
        assertEquals(listOf("pak01_dir.vpk" to sha256Hex(single)), unpacked.vpks.map { it.path to it.sha256 })
        assertEquals(listOf("${unpacked.staging.value}/2.vpk"), stagingFiles())
    }

    @Test
    fun aSevenZipWhoseFilesNeedAPasswordIsRefusedAsEncryptedThoughItsListIsOpen() {
        val bytes = Base64.getDecoder().decode(
            "N3q8ryccAASVkDc8zAAAAAAAAAAUAAAAAAAAADHd6RKLzw8b8sfDM+mWLA1xsOolCs4s4HzlIpkvjkaKnb1/hHrnQSK3EZ4SxGA3eEl7ot/fBaW2mltT/xL+7WOOj8353D3o94+RBAJxpEgOhUajDuAAeAB0XQAAgTMHrg/SwbJbFBEU5CbPQMriRwxX3Cdstl+ThcT7H2OEtcqlWIHebzifsRzlGsQnFIvjiSPF9J4HuGbR4VPMIpLndCNz3e1UW2rcv5b6F0csC3LGCg2WSk4QcbTgiX8ic/0OzPgNSoW7L1XHj5B1A/52AAAXBlABCXwABwsBAAEhIQEYDHkAAA==",
        )
        val archive = identity(home.resolve("locked.7z").apply { writeBytes(bytes) })
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.Encrypted, null)), unpack(archive))
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun aVolumeOfASplitArchiveIsRefusedAsOneByItsNameOrItsEndRecord() = runBlocking {
        val first = sevenZ("whole.7z", Entry("pak01_dir.vpk", single))
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.SplitVolume, null)), installer.unpack(first, UnpackLimits(), "mod.7z.001"))
        val end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).putInt(0x06054b50).putShort(2).putShort(2).putShort(1).putShort(1).putInt(81).putInt(95402).putShort(0).array()
        val last = identity(home.resolve("copy-2").apply { writeBytes(ByteArray(300) { 9 } + end) })
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.SplitVolume, null)), installer.unpack(last, UnpackLimits(), "mod.zip"))
        val junk = identity(home.resolve("copy-3").apply { writeBytes(ByteArray(300) { 9 }) })
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.UnsupportedFormat, null)), installer.unpack(junk, UnpackLimits(), "mod.zip"))
    }

    @Test
    fun unsafeArchivesAreRefusedBeforeAnythingIsWritten() {
        for (name in listOf("../pak01_dir.vpk", "..\\..\\pak01_dir.vpk")) {
            val unpacked = assertIs<UnpackResult.Unpacked>(unpack(zip("slip.zip", Entry(name, single))), name)
            assertEquals(listOf("${unpacked.staging.value}/0.vpk"), stagingFiles(), "$name is only a label: its bytes stay in staging")
            runBlocking { installer.discard(unpacked.staging) }
        }
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.Link, "pak01_dir.vpk")), unpack(zip("link.zip", Entry("pak01_dir.vpk", "/etc/passwd".encodeToByteArray(), link = true))))
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.Link, "x.vpk")), unpack(sevenZ("link.7z", Entry("pak01_dir.vpk", single), Entry("x.vpk", "/etc".encodeToByteArray(), link = true))))
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.DuplicateName, "MOD/PAK01_DIR.VPK")), unpack(zip("case.zip", Entry("mod/pak01_dir.vpk", single), Entry("MOD/PAK01_DIR.VPK", single))))
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.TooLarge, "pak01_dir.vpk")), unpack(zip("big.zip", Entry("pak01_dir.vpk", single)), UnpackLimits(maxVpkBytes = 10)))
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.UnsupportedFormat, null)), unpack(identity(home.resolve("junk.zip").also { it.writeBytes(ByteArray(64) { 9 }) })))
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.NoVpk, null)), unpack(zip("none.zip", Entry("readme.txt", ByteArray(3)))))
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun anEntryThatDecodesToMoreThanItDeclaredIsRefusedAndItsBytesAreRemoved() {
        val archive = zip("lie.zip", Entry("pak01_dir.vpk", ByteArray(100_000)))
        val file = Path.of(archive.path)
        val bytes = file.readBytes()
        val central = (bytes.size - 4 downTo 0).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4B.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[central + 24] = 10
        bytes[central + 25] = 0
        bytes[central + 26] = 0
        bytes[central + 27] = 0
        file.writeBytes(bytes)
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.SizeMismatch, "pak01_dir.vpk")), unpack(identity(file)))
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun declaredTotalsAreCappedBeforeDecoding() {
        val archive = sevenZ("solid.7z", Entry("big.bin", ByteArray(4000) { 2 }), Entry("pak01_dir.vpk", single))
        val declared = 4000L + single.size
        assertIs<UnpackResult.Unpacked>(unpack(archive, UnpackLimits(maxTotalBytes = declared)))
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.TooLarge, "pak01_dir.vpk")), unpack(archive, UnpackLimits(maxTotalBytes = declared - 1)))
    }

    @Test
    fun entriesNobodyWantsAreStillDecodedThroughTheCountedSinkWhenTheFormatCannotSkipThem() {
        fun drained(archive: ArchiveIdentity): Long {
            var counted = 0L
            ArchiveReaders.open(Path.of(archive.path), archive.fileName, Deadline(System.nanoTime() + 1.hours.inWholeNanoseconds)).use { reader ->
                reader.decode(open = { null }, drain = {
                    object : java.io.OutputStream() {
                        override fun write(b: Int) { counted++ }
                        override fun write(b: ByteArray, off: Int, len: Int) { counted += len }
                    }
                })
            }
            return counted
        }
        assertEquals(4000L + single.size, drained(sevenZ("drain.7z", Entry("big.bin", ByteArray(4000) { 2 }), Entry("pak01_dir.vpk", single))))
        assertEquals(0L, drained(zip("drain.zip", Entry("big.bin", ByteArray(4000) { 2 }), Entry("pak01_dir.vpk", single))), "a ZIP entry is jumped over, not decoded")
    }

    @Test
    fun discardingStagingRemovesLinksNotWhatTheyPointTo() = runBlocking {
        val outside = home.resolve("outside.txt").also { it.writeBytes("keep".encodeToByteArray()) }
        val unpacked = assertIs<UnpackResult.Unpacked>(unpack(zip("one.zip", Entry("pak01_dir.vpk", single))))
        Files.createSymbolicLink(staging.resolve(unpacked.staging.value).resolve("evil"), outside)
        installer.discardAllStaging()
        assertEquals(emptyList(), stagingFiles())
        assertContentEquals("keep".encodeToByteArray(), outside.readBytes())
    }

    private fun stagedSingle(): StagedVpk = assertIs<UnpackResult.Unpacked>(unpack(zip("s.zip", Entry("pak01_dir.vpk", single)))).vpks.single()

    @Test
    fun aStagedCopyIsVerifiedAndPublishedWithoutReplacingAnything() = runBlocking {
        val vpk = stagedSingle()
        assertEquals(StageResult.Written(Sha256.image(single)), installer.stage(root, SetFolder.Addons, vpk, "pak04_dir.vpk.hideout-a1"))
        assertEquals(PublishResult.Published, installer.publish(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"))
        assertContentEquals(single, addons.resolve("pak04_dir.vpk").readBytes())
        assertTrue(Files.isSameFile(addons.resolve("pak04_dir.vpk"), addons.resolve("pak04_dir.vpk.hideout-a1")))

        val foreign = "someone's".encodeToByteArray()
        addons.resolve("pak05_dir.vpk").writeBytes(foreign)
        installer.stage(root, SetFolder.Addons, vpk, "pak05_dir.vpk.hideout-a2")
        assertEquals(PublishResult.Taken, installer.publish(root, SetFolder.Addons, "pak05_dir.vpk.hideout-a2", "pak05_dir.vpk"))
        assertContentEquals(foreign, addons.resolve("pak05_dir.vpk").readBytes())
    }

    @Test
    fun onlyGeneratedNamesAreAccepted() = runBlocking {
        val vpk = stagedSingle()
        assertIs<StageResult.Failed>(installer.stage(root, SetFolder.Addons, vpk, "../../escape.vpk"))
        assertIs<StageResult.Failed>(installer.stage(root, SetFolder.Addons, vpk, "pak04_dir.vpk"))
        assertIs<PublishResult.Failed>(installer.publish(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak05_dir.vpk"))
        assertIs<Removal.Failed>(installer.removeExact(GameEntry(root, SetFolder.Addons, "someone_elses.vpk"), FileImage(0, "a".repeat(64))))
        assertTrue(!addons.resolve("../../escape.vpk").normalize().exists())
    }

    @Test
    fun aLinkedAddonsFolderIsNeverWrittenThrough() = runBlocking {
        val elsewhere = home.resolve("elsewhere").createDirectories()
        Files.createSymbolicLink(addons, elsewhere)
        assertIs<StageResult.Failed>(installer.stage(root, SetFolder.Addons, stagedSingle(), "pak04_dir.vpk.hideout-a1"))
        assertEquals(emptyList(), Files.list(elsewhere).use { it.toList() })
    }

    @Test
    fun aHardLinkedCopyIsTheSameFileAsItsFinalName() = runBlocking {
        installer.stage(root, SetFolder.Addons, stagedSingle(), "pak04_dir.vpk.hideout-a1")
        Files.createLink(addons.resolve("pak04_dir.vpk"), addons.resolve("pak04_dir.vpk.hideout-a1"))
        assertTrue(installer.sameFile(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"))
        addons.resolve("pak06_dir.vpk").writeBytes(single)
        installer.stage(root, SetFolder.Addons, stagedSingle(), "pak06_dir.vpk.hideout-a2")
        assertFalse(installer.sameFile(root, SetFolder.Addons, "pak06_dir.vpk.hideout-a2", "pak06_dir.vpk"), "same bytes are not the same file")
    }

    @Test
    fun onADriveWithoutHardLinksAPublishedCopyAndAMovedOneCountAsOneFileWithTheirSourceWhileTheyHoldItsBytes() = runBlocking {
        val refusing = NioModInstaller(staging, linker = Linker { link, _ -> throw FileSystemException(link.toString(), null, "Operation not permitted") })
        refusing.stage(root, SetFolder.Addons, stagedSingle(), "pak04_dir.vpk.hideout-a1")
        assertEquals(PublishResult.Published, refusing.publish(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"))
        assertNotEquals(Files.readAttributes(addons.resolve("pak04_dir.vpk"), BasicFileAttributes::class.java).fileKey(), Files.readAttributes(addons.resolve("pak04_dir.vpk.hideout-a1"), BasicFileAttributes::class.java).fileKey(), "a copy")
        assertTrue(refusing.sameFile(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"))
        assertEquals(PublishResult.Taken, refusing.publish(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"), "never over a name")
        assertEquals(setOf("pak04_dir.vpk", "pak04_dir.vpk.hideout-a1"), names(), "and no copy is left under a name of its own")

        val on = GameEntry(root, SetFolder.Addons, "pak04_dir.vpk")
        val moved = GameEntry(root, off, first)
        val image = Sha256.image(single)
        assertEquals(LinkResult.Linked, refusing.link(on, moved))
        assertEquals(EntryState.Present(image, twin = true), refusing.inspect(moved, on))
        assertEquals(Removal.Removed, refusing.removeTwin(on, moved, image), "turned off: the bytes live on in the off folder")
        assertContentEquals(single, parked.resolve(first).readBytes())

        addons.resolve("pak05_dir.vpk").writeBytes(single + byteArrayOf(1))
        assertIs<Removal.Kept>(refusing.removeTwin(GameEntry(root, SetFolder.Addons, "pak05_dir.vpk"), moved, image), "other bytes are another file")
        assertEquals(EntryState.Present(Sha256.image(single + byteArrayOf(1)), twin = false), refusing.inspect(GameEntry(root, SetFolder.Addons, "pak05_dir.vpk"), moved))
    }

    @Test
    fun removalLeavesChangedFilesAndLinksInPlace() = runBlocking {
        addons.createDirectories()
        val image = Sha256.image(single)
        addons.resolve("pak04_dir.vpk").writeBytes(single)
        addons.resolve("pak05_dir.vpk").writeBytes(single + byteArrayOf(1))
        Files.createSymbolicLink(addons.resolve("pak06_dir.vpk"), addons.resolve("pak04_dir.vpk"))
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, SetFolder.Addons, "pak04_dir.vpk"), image))
        assertIs<Removal.Kept>(installer.removeExact(GameEntry(root, SetFolder.Addons, "pak05_dir.vpk"), image))
        assertIs<Removal.Kept>(installer.removeExact(GameEntry(root, SetFolder.Addons, "pak06_dir.vpk"), image))
        assertEquals(Removal.Absent, installer.removeExact(GameEntry(root, SetFolder.Addons, "pak07_dir.vpk"), image))
        assertEquals(setOf("pak05_dir.vpk", "pak06_dir.vpk"), names())
    }

    @Test
    fun aPublishedNameIsASecondNameOfItsVerifiedCopy() = runBlocking {
        installer.stage(root, SetFolder.Addons, stagedSingle(), "pak04_dir.vpk.hideout-a1")
        assertEquals(PublishResult.Published, installer.publish(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"))
        assertTrue(Files.isSameFile(addons.resolve("pak04_dir.vpk"), addons.resolve("pak04_dir.vpk.hideout-a1")), "the copy stays until the ledger releases it")
        assertEquals(Removal.Removed, installer.removeTwin(GameEntry(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1"), GameEntry(root, SetFolder.Addons, "pak04_dir.vpk"), Sha256.image(single)), "the copy goes as a second name of it")
        assertContentEquals(single, addons.resolve("pak04_dir.vpk").readBytes())
        assertEquals(setOf("pak04_dir.vpk"), names())
    }

    @Test
    fun aStagedCopyIsKnownFromItsReadBackUnderItsPublishedNameUntilItIsWrittenAgain() = runBlocking<Unit> {
        installer.stage(root, SetFolder.Addons, stagedSingle(), "pak04_dir.vpk.hideout-a1")
        assertEquals(PublishResult.Published, installer.publish(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"))
        assertTrue(installer.sameFile(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1", "pak04_dir.vpk"))
        val published = addons.resolve("pak04_dir.vpk")
        val seen = FileObservation.Present(single.size.toLong(), sha256Hex(single))

        assumeTrue("permissions are POSIX here", "posix" in published.fileSystem.supportedFileAttributeViews())
        Files.setPosixFilePermissions(published, emptySet())
        assumeFalse("this user can read any file", Files.isReadable(published))
        assertEquals(seen, NioGameInventory.file(root, "game/citadel/addons/pak04_dir.vpk"), "known from the read back, so not opened")
        val entry = GameEntry(root, SetFolder.Addons, "pak04_dir.vpk")
        assertEquals(EntryState.Present(Sha256.image(single), twin = true), installer.inspect(entry, entry))

        Files.setPosixFilePermissions(published, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        val edited = ByteArray(single.size) { 9 }
        published.writeBytes(edited)
        assertEquals(FileObservation.Present(edited.size.toLong(), sha256Hex(edited)), NioGameInventory.file(root, "game/citadel/addons/pak04_dir.vpk"), "a write after the read back is seen")
        assertIs<Removal.Kept>(installer.removeTwin(GameEntry(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1"), entry, Sha256.image(single)))
    }

    @Test
    fun aTemporaryIsRemovedOnlyWhenNothingCanBeLost() = runBlocking {
        addons.createDirectories()
        val sentinel = "not the copy Hideout wrote".encodeToByteArray()
        val prefix = single.copyOf(single.size / 2)
        addons.resolve("pak04_dir.vpk.hideout-a1").writeBytes(sentinel)
        addons.resolve("pak05_dir.vpk.hideout-a1").writeBytes(prefix)
        addons.resolve("pak06_dir.vpk.hideout-a1").createDirectories()
        Files.createSymbolicLink(addons.resolve("pak07_dir.vpk.hideout-a1"), addons.resolve("pak08_dir.vpk.hideout-a1"))
        addons.resolve("pak08_dir.vpk.hideout-a1").writeBytes(single)
        val image = Sha256.image(single)
        assertIs<Removal.Kept>(installer.removeExact(GameEntry(root, SetFolder.Addons, "pak04_dir.vpk.hideout-a1"), image))
        assertIs<Removal.Kept>(installer.removeExact(GameEntry(root, SetFolder.Addons, "pak05_dir.vpk.hideout-a1"), image))
        assertIs<Removal.Kept>(installer.removeExact(GameEntry(root, SetFolder.Addons, "pak06_dir.vpk.hideout-a1"), image))
        assertIs<Removal.Kept>(installer.removeExact(GameEntry(root, SetFolder.Addons, "pak07_dir.vpk.hideout-a1"), image))
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, SetFolder.Addons, "pak08_dir.vpk.hideout-a1"), image))
        assertContentEquals(sentinel, addons.resolve("pak04_dir.vpk.hideout-a1").readBytes())
        assertContentEquals(prefix, addons.resolve("pak05_dir.vpk.hideout-a1").readBytes())
        assertEquals(setOf("pak04_dir.vpk.hideout-a1", "pak05_dir.vpk.hideout-a1", "pak06_dir.vpk.hideout-a1", "pak07_dir.vpk.hideout-a1"), names())
    }

    private val off = SetFolder.Off(SubmissionRef(SubmissionKind.Mod, 628_739))
    private val parked = home.resolve("Deadlock/game/citadel/hideout_off/mod-628739")

    private val first = "set-3fa94c0d12be_dir.vpk"
    private val second = "set-0b1e7a9c55d2_dir.vpk"

    @Test
    fun anOffSetIsStagedAndPublishedInItsOwnFolderAndNeverInAddons() = runBlocking {
        val vpk = stagedSingle()
        assertEquals(StageResult.Written(Sha256.image(single)), installer.stage(root, off, vpk, "$first.hideout-a1"))
        assertEquals(PublishResult.Published, installer.publish(root, off, "$first.hideout-a1", first))
        assertTrue(Files.isSameFile(parked.resolve(first), parked.resolve("$first.hideout-a1")))
        assertTrue(installer.sameFile(root, off, "$first.hideout-a1", first))
        assertTrue(!addons.exists(), "placing an off set does not even create addons")

        val foreign = "someone's".encodeToByteArray()
        parked.resolve(second).writeBytes(foreign)
        installer.stage(root, off, vpk, "$second.hideout-a2")
        assertEquals(PublishResult.Taken, installer.publish(root, off, "$second.hideout-a2", second))
        assertContentEquals(foreign, parked.resolve(second).readBytes())

        val image = Sha256.image(single)
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, off, "$first.hideout-a1"), image))
        assertEquals(Removal.Absent, installer.removeExact(GameEntry(root, SetFolder.Addons, "pak04_dir.vpk"), image), "addons is never searched for an off set's files")
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, off, first), image))
        assertEquals(setOf(second, "$second.hideout-a2"), names(parked))
    }

    @Test
    fun theRemovalThatEmptiesAnOffFolderTakesTheFolderAndNothingElse() = runBlocking {
        val image = Sha256.image(single)
        installer.stage(root, off, stagedSingle(), "$first.hideout-a1")
        assertEquals(PublishResult.Published, installer.publish(root, off, "$first.hideout-a1", first))
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, off, "$first.hideout-a1"), image))
        assertTrue(parked.exists(), "a folder that still holds a file stays")
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, off, first), image))
        assertTrue(!parked.exists(), "the removal that empties it takes the folder")
        assertTrue(parked.parent.exists(), "hideout_off itself stays")

        installer.stage(root, off, stagedSingle(), "$first.hideout-a2")
        installer.publish(root, off, "$first.hideout-a2", first)
        val anchor = GameEntry(root, off, "$first.hideout-t1")
        assertEquals(LinkResult.Linked, installer.link(GameEntry(root, off, first), anchor))
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, off, "$first.hideout-a2"), image))
        assertEquals(Removal.Removed, installer.removeTwin(GameEntry(root, off, first), anchor, image))
        assertEquals(LinkResult.Linked, installer.link(anchor, GameEntry(root, SetFolder.Addons, "pak04_dir.vpk")))
        assertEquals(Removal.Removed, installer.removeTwin(anchor, GameEntry(root, SetFolder.Addons, "pak04_dir.vpk"), image))
        assertTrue(!parked.exists(), "a moved set's last anchor takes the folder too")

        installer.stage(root, SetFolder.Addons, stagedSingle(), "pak05_dir.vpk.hideout-a3")
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, SetFolder.Addons, "pak05_dir.vpk.hideout-a3"), image))
        assertEquals(Removal.Removed, installer.removeExact(GameEntry(root, SetFolder.Addons, "pak04_dir.vpk"), image))
        assertTrue(addons.exists(), "addons stays however empty")
    }

    @Test
    fun eachFolderTakesOnlyItsOwnKindOfNameAndTemporary() = runBlocking {
        val vpk = stagedSingle()
        val image = Sha256.image(single)
        assertIs<StageResult.Failed>(installer.stage(root, off, vpk, "pak04_dir.vpk.hideout-a1"), "a pak name in an off folder")
        assertIs<StageResult.Failed>(installer.stage(root, SetFolder.Addons, vpk, "$first.hideout-a1"), "an off name in addons")
        assertEquals(StageResult.Written(image), installer.stage(root, off, vpk, "$first.hideout-a1"))
        assertIs<PublishResult.Failed>(installer.publish(root, off, "$first.hideout-a1", "pak04_dir.vpk"))
        assertIs<PublishResult.Failed>(installer.publish(root, SetFolder.Addons, "$first.hideout-a1", first))
        assertEquals(PublishResult.Published, installer.publish(root, off, "$first.hideout-a1", first))
        assertFailsWith<IllegalArgumentException> { installer.sameFile(root, SetFolder.Addons, "$first.hideout-a1", first) }
        assertIs<Removal.Failed>(installer.removeExact(GameEntry(root, SetFolder.Addons, first), image))
        assertIs<Removal.Failed>(installer.removeExact(GameEntry(root, SetFolder.Addons, "$first.hideout-a1"), image))
        assertTrue(!addons.exists(), "addons is never made for an off name")
        assertEquals(setOf(first, "$first.hideout-a1"), names(parked))
    }

    @Test
    fun aLinkedOffFolderIsNeverWrittenThroughAndOnlyStagingCreatesFolders() = runBlocking {
        assertIs<PublishResult.Failed>(installer.publish(root, off, "$first.hideout-a1", first), "publishing never creates the folder")
        assertTrue(!parked.parent.exists())

        val elsewhere = home.resolve("elsewhere").createDirectories()
        Files.createSymbolicLink(parked.parent, elsewhere)
        assertIs<StageResult.Failed>(installer.stage(root, off, stagedSingle(), "$first.hideout-a1"))
        assertEquals(emptyList(), Files.list(elsewhere).use { it.toList() })
        Files.delete(parked.parent)

        parked.parent.createDirectories()
        Files.createSymbolicLink(parked, elsewhere)
        assertIs<StageResult.Failed>(installer.stage(root, off, stagedSingle(), "$first.hideout-a1"))
        assertEquals(emptyList(), Files.list(elsewhere).use { it.toList() })
    }

    @Test
    fun aGameWithoutItsCitadelFolderIsNeverCreated() = runBlocking {
        val missing = GameRoot(home.resolve("NotDeadlock").toString())
        assertIs<StageResult.Failed>(installer.stage(missing, off, stagedSingle(), "$first.hideout-a1"))
        assertIs<StageResult.Failed>(installer.stage(missing, SetFolder.Addons, stagedSingle(), "pak04_dir.vpk.hideout-a1"))
        assertTrue(!home.resolve("NotDeadlock").exists())
    }

    private fun xz(content: ByteArray, dictionaryProperty: Int? = null): ByteArray {
        val bytes = ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options().apply { dictSize = 1 shl 20 }).use { it.write(content) } }.toByteArray()
        if (dictionaryProperty != null) {
            val block = StreamHeaderBytes
            val size = ((bytes[block].toInt() and 0xFF) + 1) * 4
            check(bytes[block + 1].toInt() == 0 && bytes[block + 2].toInt() == LzmaTwoFilter && bytes[block + 3].toInt() == 1) { "one LZMA2 filter and no sizes in the block header" }
            bytes[block + 4] = dictionaryProperty.toByte()
            val crc = CRC32().apply { update(bytes, block, size - 4) }.value
            ByteBuffer.wrap(bytes, block + size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
        }
        return bytes
    }

    private fun zipWithMethod(name: String, entry: String, method: Int, payload: ByteArray, content: ByteArray): ArchiveIdentity {
        val crc = CRC32().apply { update(content) }.value.toInt()
        val entryName = entry.encodeToByteArray()
        fun header(signature: Int, central: Boolean) = ByteBuffer.allocate((if (central) 46 else 30) + entryName.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(signature)
            if (central) putShort(ZipVersion)
            putShort(ZipVersion).putShort(0).putShort(method.toShort()).putShort(0).putShort(DosDate)
            putInt(crc).putInt(payload.size).putInt(content.size).putShort(entryName.size.toShort()).putShort(0)
            if (central) putShort(0).putShort(0).putShort(0).putInt(0).putInt(0)
            put(entryName)
        }.array()
        val local = header(0x04034b50, central = false)
        val central = header(0x02014b50, central = true)
        val end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x06054b50).putShort(0).putShort(0).putShort(1).putShort(1).putInt(central.size).putInt(local.size + payload.size).putShort(0).array()
        val file = home.resolve(name)
        file.writeBytes(local + payload + central + end)
        return identity(file)
    }

    @Test
    fun aZipXzEntryWithinTheDictionaryLimitDecodes() {
        val unpacked = assertIs<UnpackResult.Unpacked>(unpack(zipWithMethod("xz.zip", "pak01_dir.vpk", ZipXz, xz(single), single)))
        assertEquals(listOf("pak01_dir.vpk" to sha256Hex(single)), unpacked.vpks.map { it.path to it.sha256 })
    }

    @Test
    fun aZipXzEntryDeclaringAnOversizedDictionaryIsRefusedBeforeItIsDecoded() {
        val archive = zipWithMethod("huge-dictionary.zip", "pak01_dir.vpk", ZipXz, xz(single, dictionaryProperty = LargestXzDictionary), single)
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.DictionaryTooLarge, "pak01_dir.vpk")), unpack(archive))
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun aZipEntryInAMethodWithoutABoundedDecoderIsRefused() {
        val archive = zipWithMethod("zstd.zip", "pak01_dir.vpk", ZipZstd, ByteArray(64) { 1 }, single)
        assertEquals(UnpackResult.Rejected(ArchiveRejection(RejectionReason.UnsupportedMethod, "pak01_dir.vpk")), unpack(archive))
        assertEquals(emptyList(), stagingFiles())
    }

    @Test
    fun everyDeadlineWhileAZipOrSevenZipIsReadRefusesIt() {
        val zipped = zip("timed.zip", Entry("Ivy/ivy_dir.vpk", dir), Entry("readme.txt", ByteArray(70_000) { 3 }), Entry("Ivy/ivy_000.vpk", chunk))
        assertEquals(listOf("Ivy/ivy_dir.vpk" to sha256Hex(dir), "Ivy/ivy_000.vpk" to sha256Hex(chunk)), assertEveryDeadlineIsHonoured(staging, zipped))
        val sevenZipped = sevenZ("timed.7z", Entry("notes/a.txt", ByteArray(70_000) { 1 }), Entry("pak01_dir.vpk", single))
        assertEquals(listOf("pak01_dir.vpk" to sha256Hex(single)), assertEveryDeadlineIsHonoured(staging, sevenZipped))
    }

    private companion object {
        const val StreamHeaderBytes = 12
        const val LzmaTwoFilter = 0x21
        const val LargestXzDictionary = 37
        const val ZipVersion: Short = 63
        const val DosDate: Short = 0x21
        const val ZipXz = 95
        const val ZipZstd = 93
    }
}
