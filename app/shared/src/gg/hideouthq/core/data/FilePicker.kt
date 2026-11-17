package gg.hideouthq.core.data

fun interface FilePicker {
    suspend fun pick(title: String): LocalFile?

    companion object {
        val Unavailable = FilePicker { null }
    }
}

fun LocalFile.isSupportedImport(): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("zip", "7z", "rar", "vpk")

/** Named as one file of an archive split into several: `mod.7z.001`, `mod.zip.002`, `mod.z01` or `mod.r00`. */
fun LocalFile.isSplitArchivePart(): Boolean = SplitPart.matches(name.lowercase())

private val SplitPart = Regex(".+\\.((7z|zip|rar)\\.\\d{3}|z\\d{2}|r\\d{2})")
