package gg.hideouthq.core.data

/**
 * Named text files that outlive the app, such as the catalogue snapshot. The platform backs it
 * with real files; a write replaces the whole file or nothing, so a crash cannot leave half of one.
 */
interface CacheStorage {
    /** The file's text, or null if there is none. */
    suspend fun read(name: String): String?

    suspend fun write(name: String, text: String)
}
