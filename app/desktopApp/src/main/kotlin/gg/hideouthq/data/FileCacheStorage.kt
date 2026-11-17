package gg.hideouthq.data

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.library.replaceAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileCacheStorage(private val directory: File) : CacheStorage {
    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        directory.resolve(name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        replaceAtomically(directory.resolve(name).toPath(), text.encodeToByteArray())
    }
}
