package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.GameLocator
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.FolderProblem
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.model.AppAbout
import java.util.concurrent.ConcurrentHashMap

enum class SampleGame {
    Found,
    NotFound,
    WrongFolder,
}

internal val SampleAbout = AppAbout(version = "sample")

internal object SampleFolders {
    const val Steam = """C:\Program Files (x86)\Steam\steamapps\common\Deadlock"""
    const val Downloads = """C:\Users\you\AppData\Local\Hideout\downloads"""
    const val Elsewhere = """D:\Games\Deadlock"""
    const val Game = """D:\Games\Deadlock\game"""
    const val Citadel = """D:\Games\Deadlock\game\citadel"""
    const val SteamLibrary = """D:\SteamLibrary"""

    val all: Map<String, FolderProblem?> = mapOf(Steam to null, Elsewhere to null, SteamLibrary to FolderProblem.NotDeadlock)

    val inside: Map<String, String> = mapOf(Game to Elsewhere, Citadel to Elsewhere)
}

internal class FakeGameLocator(
    private val steam: List<String>,
    private val folders: Map<String, FolderProblem?> = SampleFolders.all,
) : GameLocator {
    override val separator: Char = '\\'

    override suspend fun detect(): List<GameRoot> = steam.map(::GameRoot)

    override suspend fun check(path: String): FolderCheck {
        val folder = SampleFolders.inside[path] ?: path
        return if (folder in folders) folders[folder]?.let { FolderCheck.Invalid(path, it) } ?: FolderCheck.Valid(GameRoot(folder)) else FolderCheck.Invalid(path, FolderProblem.Unavailable)
    }
}

class MemoryStorage(initial: Map<String, String> = emptyMap()) : CacheStorage {
    private val texts = ConcurrentHashMap(initial)

    override suspend fun read(name: String): String? = texts[name]

    override suspend fun write(name: String, text: String) {
        texts[name] = text
    }
}
