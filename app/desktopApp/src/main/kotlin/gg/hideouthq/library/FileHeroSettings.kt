package gg.hideouthq.library

import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.HeroSettingsFiles
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Steam Cloud's copy is staged in `userdata/<account>/1422450`, outside the `remote` folder Steam uploads. */
class FileHeroSettings(
    private val steamInstalls: () -> List<SteamInstall> = ::steamInstalls,
    private val beforeReplace: (staged: Path) -> Unit = {},
) : HeroSettingsFiles {
    override suspend fun copies(root: GameRoot): List<HeroSettingsCopy> = withContext(Dispatchers.IO) {
        listOfNotNull(HeroSettingsCopy.Game, steamCloud(root, steamInstalls())?.let { HeroSettingsCopy.Cloud(it.account) })
    }

    override suspend fun read(root: GameRoot, copy: HeroSettingsCopy): FileRead = withContext(Dispatchers.IO) {
        try {
            val file = file(root, copy, create = false)
            val attributes = attributesOrNull(file) ?: return@withContext FileRead.Absent
            if (!attributes.isRegularFile) return@withContext FileRead.Unreadable("$file is not a plain file")
            readAtMost(file, MaxConfigBytes)?.let { FileRead.Present(it) } ?: FileRead.Unreadable("$file is larger than $MaxConfigBytes bytes")
        } catch (e: NoSuchFileException) {
            FileRead.Absent
        } catch (e: IOException) {
            FileRead.Unreadable(e.message ?: "$HeroSettingsName could not be read")
        }
    }

    override suspend fun write(root: GameRoot, copy: HeroSettingsCopy, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = file(root, copy, create = true)
        val staging = if (copy is HeroSettingsCopy.Cloud) userData(root, copy.account) else file.parent
        Files.newDirectoryStream(staging, "$HeroSettingsName$StagedInfix*").use { it.toList() }.forEach(Files::deleteIfExists)
        val staged = staging.resolve("$HeroSettingsName$StagedInfix${UUID.randomUUID()}")
        try {
            writeDurably(staged, bytes, StandardOpenOption.CREATE_NEW)
            beforeReplace(staged)
            retryingBusy { Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            syncFolderIfSupported(file.parent)
        } finally {
            retryingBusy { Files.deleteIfExists(staged) }
        }
    }

    override fun changes(root: GameRoot, copy: HeroSettingsCopy): Flow<Unit> = flow { emitAll(fileChanges(file(root, copy, create = false))) }.flowOn(Dispatchers.IO)

    private fun file(root: GameRoot, copy: HeroSettingsCopy, create: Boolean): Path = when (copy) {
        HeroSettingsCopy.Game -> gameFolder(root, SetFolder.GameSegments + "cfg", create).resolve(HeroSettingsName)
        is HeroSettingsCopy.Cloud -> listOf("remote", "cfg", HeroSettingsName).fold(userData(root, copy.account), Path::resolve)
    }

    private fun userData(root: GameRoot, account: SteamAccountId): Path =
        deadlockUserData(steamCloud(root, steamInstalls())?.steam ?: throw IOException("Steam and the account last signed in to it were not found"), account)

    private companion object {
        const val StagedInfix = ".hideout-next-"
    }
}
