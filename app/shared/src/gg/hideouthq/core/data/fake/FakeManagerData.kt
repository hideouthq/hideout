package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.BundleOpen
import gg.hideouthq.core.data.BundleVpk
import gg.hideouthq.core.data.GrimoireFiles
import gg.hideouthq.core.data.ManagerData
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.preview.sha256Hex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class ForeignSeed { None, Dmm, Grimoire, Bundle }

class FakeManagerData(private val seed: ForeignSeed) : ManagerData {
    override suspend fun dmmState(): ByteArray? = if (seed == ForeignSeed.Dmm) SampleForeign.dmmState else null

    override suspend fun grimoire(folder: String?): GrimoireFiles? =
        if (seed == ForeignSeed.Grimoire) GrimoireFiles(SampleForeign.grimoireMetadata, SampleForeign.grimoireProfiles) else null

    override suspend fun bundle(path: String): BundleOpen =
        if (seed == ForeignSeed.Bundle) BundleOpen.Found(SampleForeign.bundleManifest, SampleForeign.BundleFolder) else BundleOpen.Missing("there is no mod-interchange.json at $path")

    override suspend fun bundleVpk(folder: String, path: String): BundleVpk? {
        if (seed != ForeignSeed.Bundle || path != SampleForeign.BundledPath) return null
        val bytes = SampleForeign.bundled
        return BundleVpk(path, "$folder\\files\\0000-sunset\\sunset_dir.vpk", bytes.size.toLong(), sha256Hex(bytes), VpkFormat.read(bytes, bytes.size.toLong()))
    }

    fun layOut(files: InMemoryGameFiles, root: GameRoot) {
        val citadel = "game/citadel"
        when (seed) {
            ForeignSeed.None -> Unit
            ForeignSeed.Dmm -> {
                files.putBytes(root, "$citadel/addons/pak10_dir.vpk", SampleForeign.neonHaze)
                files.putBytes(root, "$citadel/addons/pak11_dir.vpk", SampleForeign.ironSights)
                files.putBytes(root, "$citadel/addons/700003_Ranked HUD.vpk", SampleForeign.rankedHud)
                files.putBytes(root, "$citadel/addons/.dmm.json", SampleForeign.dmmManifest)
                files.putBytes(root, "$citadel/addons/profile_ranked/pak01_dir.vpk", SampleForeign.ironSights)
                files.putBytes(root, "$citadel/addons/profile_ranked/.dmm.json", SampleForeign.dmmRankedManifest)
                files.putBytes(root, "$citadel/cfg/autoexec.cfg", SampleForeign.dmmAutoexec)
            }
            ForeignSeed.Grimoire -> {
                files.putBytes(root, "$citadel/grimoire/pak01_dir.vpk", SampleForeign.lockerCards)
                files.putBytes(root, "$citadel/grimoire/pak05_dir.vpk", SampleForeign.skyline)
                files.putBytes(root, "$citadel/addons/.disabled/night_market_dir.vpk", SampleForeign.nightMarket)
                files.putBytes(root, "$citadel/cfg/autoexec.cfg", SampleForeign.grimoireAutoexec)
            }
            ForeignSeed.Bundle -> files.putBytes(root, "$citadel/addons/pak12_dir.vpk", SampleForeign.bundled)
        }
    }
}

internal object SampleForeign {
    val neonHaze = FakeVpk.directory(seed = 100)
    val ironSights = FakeVpk.directory(seed = 101)
    val rankedHud = FakeVpk.directory(seed = 102)
    val lockerCards = FakeVpk.directory(seed = 110)
    val skyline = FakeVpk.directory(seed = 111)
    val nightMarket = FakeVpk.directory(seed = 112)
    val bundled = FakeVpk.directory(seed = 120)

    const val BundleFolder = """C:\Users\you\Downloads\deadlock-mods-export-20261021-101500"""
    const val BundledPath = "files/0000-sunset/sunset_dir.vpk"

    private fun mod(remoteId: Long, name: String, fileId: Long) =
        """{"remoteId":"$remoteId","name":"$name","installOrder":${remoteId - 700000},"selectedDownloads":[{"name":"$name.zip","url":"https://gamebanana.com/dl/$fileId","md5Checksum":"${"%032x".format(fileId)}"}]}"""

    val dmmState: ByteArray = run {
        val mods = listOf(mod(700_001, "Neon Haze", 1_800_001), mod(700_002, "Iron Sights", 1_800_002), mod(700_003, "Ranked HUD", 1_800_003))
        val state = """{"profiles":{"default":{"name":"Default","folderName":null,"mods":[${mods.joinToString(",")}]},
            |"ranked":{"name":"Ranked","folderName":"profile_ranked","mods":[${mods[1]}]}},"activeProfileId":"default","localMods":[]}""".trimMargin()
        buildJsonObject { put("local-config", JsonPrimitive("""{"state":$state,"version":32}""")) }.toString().encodeToByteArray()
    }

    val dmmManifest: ByteArray = """{"version":3,"mods":{
        |"700001":{"enabled":true,"order":null,"shard":1,"currentVpks":["pak10_dir.vpk"],"disabledVpks":[],"originalVpkNames":["neon_haze_dir.vpk"]},
        |"700002":{"enabled":true,"order":null,"shard":1,"currentVpks":["pak11_dir.vpk"],"disabledVpks":[],"originalVpkNames":["iron_sights_dir.vpk"]},
        |"700003":{"enabled":false,"order":null,"shard":1,"currentVpks":[],"disabledVpks":["700003_Ranked HUD.vpk"],"originalVpkNames":["Ranked HUD.vpk"]}}}""".trimMargin().encodeToByteArray()

    val dmmRankedManifest: ByteArray =
        """{"version":3,"mods":{"700002":{"enabled":true,"order":null,"shard":1,"currentVpks":["pak01_dir.vpk"],"disabledVpks":[],"originalVpkNames":["iron_sights_dir.vpk"]}}}""".encodeToByteArray()

    val dmmAutoexec: ByteArray = ("bind F5 kill\r\n// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===\r\ncitadel_crosshair_pip_gap \"-7\"\r\n" +
        "citadel_crosshair_color_g \"255\"\r\n// === End Crosshair Settings ===\r\n").encodeToByteArray()

    val grimoireMetadata: ByteArray =
        """{"skyline":{"sha256":"${sha256Hex(skyline)}","modName":"Skyline","gameBananaId":700010,"gameBananaFileId":1800010,"sourceSection":"Mod","lastPriority":5}}""".encodeToByteArray()

    val grimoireProfiles: ByteArray = """[{"id":"fun","name":"Fun","mods":[{"fileName":"pak05_dir.vpk","enabled":true,"sha256":"${sha256Hex(skyline)}"}]}]""".encodeToByteArray()

    val grimoireAutoexec: ByteArray = ("// === AUTOEXEC COMMANDS (Mod Manager) ===\nfps_max 240\n// === END COMMANDS ===\n" +
        "// === CROSSHAIR SETTINGS (Mod Manager) ===\ncitadel_crosshair_pip_gap 3\n// === END CROSSHAIR ===\n").encodeToByteArray()

    val bundleManifest: ByteArray = """{"format":"deadlock-mod-interchange","version":1,"mods":[
        |{"key":"gamebanana:mod:700020","name":"Sunset","enabled":true,"order":0,"origin":{"provider":"gamebanana","submissionId":"700020","fileId":1800020},
        |"files":[{"name":"sunset_dir.vpk","path":"$BundledPath","sha256":"${sha256Hex(bundled)}","size":${bundled.size}}]}],
        |"profiles":[{"key":"profile:fun","name":"Fun","active":false,"mods":[{"modKey":"gamebanana:mod:700020","enabled":true}]}]}""".trimMargin().encodeToByteArray()
}

internal const val GrimoireMount = "\t\t\tGame\t\t\t\tcitadel/grimoire\r\n"
