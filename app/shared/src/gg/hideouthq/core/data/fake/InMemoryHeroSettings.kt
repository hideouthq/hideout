package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.HeroSettingsFiles
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

class InMemoryHeroSettings(files: Map<HeroSettingsCopy, ByteArray> = emptyMap(), var account: SteamAccountId? = Account) : HeroSettingsFiles {
    private val files = files.mapValues { it.value.copyOf() }.toMutableMap()
    private val changed = MutableSharedFlow<HeroSettingsCopy>(extraBufferCapacity = 64)
    var failure: String? = null
    var held: CompletableDeferred<Unit>? = null
    val unwatchable: MutableSet<HeroSettingsCopy> = mutableSetOf()
    var writes = 0
        private set

    fun file(copy: HeroSettingsCopy): ByteArray? = files[copy]?.copyOf()

    fun put(copy: HeroSettingsCopy, bytes: ByteArray) {
        files[copy] = bytes.copyOf()
        changed.tryEmit(copy)
    }

    override suspend fun copies(root: GameRoot): List<HeroSettingsCopy> = listOfNotNull(HeroSettingsCopy.Game, account?.let(HeroSettingsCopy::Cloud))

    override suspend fun read(root: GameRoot, copy: HeroSettingsCopy): FileRead = files[copy]?.let { FileRead.Present(it.copyOf()) } ?: FileRead.Absent

    override suspend fun write(root: GameRoot, copy: HeroSettingsCopy, bytes: ByteArray) {
        held?.await()
        failure?.let { error(it) }
        check(copy in copies(root)) { "$copy was not found" }
        writes++
        put(copy, bytes)
    }

    override fun changes(root: GameRoot, copy: HeroSettingsCopy): Flow<Unit> = flow {
        if (copy in unwatchable) throw IOException("the folder of $copy is not there")
        emitAll(changed.filter { it == copy }.map { })
    }

    companion object {
        val Account: SteamAccountId = checkNotNull(SteamAccountId.ofAccountId(39734274))

        val Cloud: HeroSettingsCopy.Cloud = HeroSettingsCopy.Cloud(Account)
    }
}
