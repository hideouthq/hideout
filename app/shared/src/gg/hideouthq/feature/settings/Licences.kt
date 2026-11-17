package gg.hideouthq.feature.settings

import gg.hideouthq.resources.Res

internal data class LicenceNotice(val component: String, val licence: String, val osiApproved: Boolean, val files: List<String>)

internal fun readLicenceIndex(text: String): List<LicenceNotice> = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
    val columns = line.split('\t')
    LicenceNotice(columns[0], columns[1], columns[2] == "yes", columns[3].split(','))
}

internal suspend fun loadLicences(): List<LicenceNotice> = readLicenceIndex(Res.readBytes("$LicenceFolder/index.tsv").decodeToString())

internal suspend fun loadNotice(file: String): String = Res.readBytes("$LicenceFolder/$file").decodeToString()

private const val LicenceFolder = "files/licenses"
