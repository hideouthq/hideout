package gg.hideouthq.library

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadNameTest {
    @Test
    fun aNameIsItsLastSegmentWithoutWhatNoSystemAllows() {
        val names = mapOf(
            "bg_ivy.zip" to "bg_ivy.zip",
            "skins/ivy\\bg_ivy.zip" to "bg_ivy.zip",
            " ..bg?ivy*.zip.. " to "bgivy.zip",
            "a<b>c:d\"e|f\u0007.7z" to "abcdef.7z",
            "" to "download",
            " . " to "download",
            "???" to "download",
            "日本語 スキン.rar" to "日本語 スキン.rar",
        )
        for ((given, published) in names) assertEquals(published, downloadName(given), given)
    }

    @Test
    fun aWindowsDeviceNameIsMarkedInAnyCaseWithOrWithoutAnExtension() {
        val names = mapOf(
            "CON" to "_CON",
            "con.zip" to "_con.zip",
            "Nul.tar.gz" to "_Nul.tar.gz",
            "prn.7z" to "_prn.7z",
            "AUX .zip" to "_AUX .zip",
            "COM0.rar" to "_COM0.rar",
            "com9" to "_com9",
            "LPT1.zip" to "_LPT1.zip",
            "lpt9.vpk" to "_lpt9.vpk",
            "COM¹.zip" to "_COM¹.zip",
            "CON." to "_CON",
            "CONSOLE.zip" to "CONSOLE.zip",
            "COM10.zip" to "COM10.zip",
            "LPT.zip" to "LPT.zip",
            "my con.zip" to "my con.zip",
            "nul-skin.zip" to "nul-skin.zip",
        )
        for ((given, published) in names) assertEquals(published, downloadName(given), given)
    }

    @Test
    fun aLongNameIsCutByItsUtf8BytesBetweenCodePointsAndKeepsItsExtension() {
        val names = mapOf(
            "長".repeat(300) + ".zip" to "長".repeat(57) + ".zip",
            "🙂".repeat(100) + ".zip" to "🙂".repeat(43) + ".zip",
            "é".repeat(100) + ".zip" to "é".repeat(86) + ".zip",
            "a".repeat(300) to "a".repeat(176),
            "a".repeat(171) + "   b.zip" to "a".repeat(171) + ".zip",
            "x".repeat(200) + "." + "y".repeat(40) to "x".repeat(176),
            "CON." + "a".repeat(300) to "_CON." + "a".repeat(171),
            "長".repeat(57) + ".zip" to "長".repeat(57) + ".zip",
        )
        for ((given, published) in names) assertEquals(published, downloadName(given), given)
    }
}
