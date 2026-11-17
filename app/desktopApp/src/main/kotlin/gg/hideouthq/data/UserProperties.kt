package gg.hideouthq.data

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal const val UserPropertiesFile = "hideout.properties"

internal fun userOverride(property: String?, environment: String?, userFile: Path, key: String): String? =
    listOf(property, environment).firstOrNull { !it.isNullOrBlank() } ?: fileProperty(userFile, key)

private fun fileProperty(file: Path, key: String): String? {
    if (!Files.isRegularFile(file)) return null
    return try {
        Files.newBufferedReader(file).use { reader -> Properties().apply { load(reader) }.getProperty(key) }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
