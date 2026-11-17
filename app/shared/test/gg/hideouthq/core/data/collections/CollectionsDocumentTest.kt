package gg.hideouthq.core.data.collections

import gg.hideouthq.core.model.CollectionLook
import gg.hideouthq.core.model.Glow
import gg.hideouthq.core.model.MonthDay
import gg.hideouthq.core.model.Rgb
import gg.hideouthq.core.model.Season
import gg.hideouthq.core.model.SkinCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollectionsDocumentTest {
    @Test
    fun aPublishedDocumentGivesItsCollectionsInOrderWithTheirLooksAndCatalogueModIds() {
        val halloween = CollectionLook(
            Rgb(0x2A1530), Rgb(0x0D080E), Rgb(0xF2A65A), listOf(Glow(Rgb(0xF2A65A), 0.45f, 0.8f, 0.18f, 0.38f), Glow(Rgb(0x7A3FA0), 0.35f, 0.25f, 0.3f, 0.45f)),
            arch = false, rim = listOf(Rgb(0xF2A65A)),
        )
        val gothic = CollectionLook(Rgb(0x2E0C18), Rgb(0x0B0507), Rgb(0xE0506D), listOf(Glow(Rgb(0xC23A55), 0.45f, 0.5f, 0.22f, 0.3f)), arch = true, rim = listOf(Rgb(0xFF7A93)))
        val winter = CollectionLook(Rgb(0x1A2C3E), Rgb(0x080D13), Rgb(0x8FD8E6), emptyList(), arch = false, rim = listOf(Rgb(0xFF4FD8), Rgb(0x4FF0FF)))
        assertEquals(
            listOf(
                SkinCollection("halloween", "Halloween", Season(MonthDay(10, 1), MonthDay(11, 7)), halloween, listOf("mod-724122"), listOf("mod-724122", "mod-671574")),
                SkinCollection("gothic", "Gothic", null, gothic, emptyList(), listOf("mod-730390")),
                SkinCollection("winter", "Winter", Season(MonthDay(12, 1), MonthDay(1, 7)), winter, emptyList(), listOf("mod-1")),
            ),
            decodeCollections(Good),
        )
    }

    @Test
    fun entriesForAnythingButAModAreLeftOut() {
        val members = """[{"model": "Sound", "id": 5}, {"model": "Mod", "id": 7}, {"model": "Wip", "id": 9}]"""
        val neon = decodeCollections(document(collection("neon", members = members, cover = members))).single()
        assertEquals(listOf("mod-7"), neon.members)
        assertEquals(listOf("mod-7"), neon.cover)
    }

    @Test
    fun aMalformedDocumentIsRejectedWhole() {
        for (bad in listOf(
            "{\"schema\": 1, \"collections\": [",
            Good.replace("\"schema\": 1", "\"schema\": 2"),
            Good.replace("\"title\": \"Gothic\"", "\"title\": 7"),
            Good.replace("\"title\": \"Gothic\"", "\"title\": \"Goth\\u0007ic\""),
            Good.replace("\"until\": \"11-07\"", "\"until\": \"11-31\""),
            Good.replace("\"until\": \"11-07\"", "\"until\": \"Nov 7\""),
            Good.replace("\"id\": 730390", "\"id\": 0"),
            Good.replace(", \"members\": [{\"model\": \"Mod\", \"id\": 730390}]", ""),
            Good.replace(GothicLook, ""),
            Good.replace("\"top\": \"#2E0C18\"", "\"top\": \"#2E0C1\""),
            Good.replace("\"alpha\": 0.45, \"x\": 0.5", "\"alpha\": 1.5, \"x\": 0.5"),
            Good.replace("\"rim\": [\"#FF7A93\"]", "\"rim\": []"),
        )) assertFailsWith<IllegalArgumentException>(bad) { decodeCollections(bad) }
    }

    private companion object {
        const val GothicLook =
            """"look": {"top": "#2E0C18", "bottom": "#0B0507", "main": "#E0506D", "glows": [{"color": "#C23A55", "alpha": 0.45, "x": 0.5, "y": 0.22, "r": 0.3}], "arch": true, "rim": ["#FF7A93"]}, """

        val Good = """
            {"schema": 1, "builtAt": "2026-10-28T21:00:00Z", "model": "LiquidAI/d1-3B@051bcc4",
             "collections": [
               {"id": "halloween", "title": "Halloween", "season": {"from": "10-01", "until": "11-07"},
                "look": {"top": "#2A1530", "bottom": "#0D080E", "main": "#F2A65A", "arch": false, "rim": ["#F2A65A"],
                         "glows": [{"color": "#F2A65A", "alpha": 0.45, "x": 0.8, "y": 0.18, "r": 0.38}, {"color": "#7A3FA0", "alpha": 0.35, "x": 0.25, "y": 0.3, "r": 0.45}]},
                "cover": [{"model": "Mod", "id": 724122}], "members": [{"model": "Mod", "id": 724122}, {"model": "Mod", "id": 671574}]},
               {"id": "gothic", "title": "Gothic", $GothicLook"cover": [], "members": [{"model": "Mod", "id": 730390}]},
               {"id": "winter", "title": "Winter", "season": {"from": "12-01", "until": "01-07"},
                "look": {"top": "#1A2C3E", "bottom": "#080D13", "main": "#8FD8E6", "glows": [], "arch": false, "rim": ["#FF4FD8", "#4FF0FF"]},
                "cover": [], "members": [{"model": "Mod", "id": 1}]}
             ]}
        """.trimIndent()

        fun document(vararg collections: String) = """{"schema": 1, "collections": [${collections.joinToString()}]}"""

        fun collection(id: String, members: String, cover: String) =
            """{"id": "$id", "title": "$id", "look": {"top": "#000000", "bottom": "#000000", "main": "#FFFFFF", "glows": [], "arch": false, "rim": ["#FFFFFF"]}, "cover": $cover, "members": $members}"""
    }
}
