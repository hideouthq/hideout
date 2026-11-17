package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecLines
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed interface BundleRead {
    data class Read(val library: ForeignLibrary) : BundleRead

    /** Not a JSON object whose `format` is `deadlock-mod-interchange` and whose `version` is 1. */
    data object Refused : BundleRead
}

object BundleReader {
    private const val Format = "deadlock-mod-interchange"
    private val SubmissionId = Regex("[1-9][0-9]{0,17}")
    private val CrosshairConvar = Regex("citadel_crosshair_[A-Za-z0-9_]+")

    fun read(manifest: ByteArray): BundleRead {
        val document = parseJson(manifest).obj?.takeIf { it["format"].text == Format && it["version"].number == 1.0 } ?: return BundleRead.Refused

        val mods = document["mods"].items.mapNotNull { it.obj?.let(::mod) }.distinctBy { it.key }
        val keys = mods.map { it.key }.toSet()
        val profiles = keyed(document["profiles"]).map { (key, profile) ->
            profile to ForeignProfile(
                key = ProfileKey(key),
                name = profile["name"].words ?: key,
                folder = null,
                members = profile["mods"].items.mapNotNull { it.obj }.filter { it["enabled"].isTrue }.sortedWith(compareBy(nullsLast()) { it["order"].number })
                    .mapNotNull { it["modKey"].words?.let(::ModKey) }.filter { it in keys }.distinct().map(ProfileMember::Mod),
            )
        }
        val active = profiles.firstOrNull { (profile, _) -> profile["active"].isTrue }
        val crosshairs = keyed(document["crosshairs"])
        val crosshair = active?.first?.get("crosshairKey").words?.let { key -> crosshairs.firstOrNull { it.first == key } }
            ?: crosshairs.firstOrNull { (_, crosshair) -> crosshair["active"].isTrue }
        return BundleRead.Read(
            ForeignLibrary(
                mods = mods,
                profiles = profiles.map { it.second },
                activeProfile = active?.second?.key,
                autoexec = AutoexecLines(
                    crosshair = crosshair?.second?.get("convars").obj.orEmpty().mapNotNull { (name, value) -> convarLine(name, value) },
                    commands = active?.first?.get("autoexec").items.mapNotNull { line -> line.text?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } },
                ),
                footprint = Footprint.None,
            ),
        )
    }

    private fun keyed(section: JsonElement?): List<Pair<String, JsonObject>> =
        section.items.mapNotNull { entry -> entry.obj?.let { it["key"].words?.to(it) } }.distinctBy { it.first }

    private fun mod(entry: JsonObject): ForeignMod? {
        val key = entry["key"].words ?: return null
        val identity = identity(entry["origin"].obj ?: return null) ?: return null
        val files = entry["files"].items.mapNotNull { file -> file.obj?.takeUnless { it["selected"].isFalse }?.let(::file) }
        return ForeignMod(
            key = ModKey(key),
            name = entry["name"].words,
            identity = identity,
            matchers = files.mapNotNull { it.image?.sha256 }.distinct().map(SetMatcher::Hash) + SetMatcher.Bundled(files, entry["enabled"].isTrue),
            position = entry["order"].number?.toInt() ?: 0,
        )
    }

    private fun identity(origin: JsonObject): ModIdentity? = when (origin["provider"].text) {
        "local" -> ModIdentity.None
        "gamebanana" -> (origin["submissionId"] as? JsonPrimitive)?.content?.takeIf(SubmissionId::matches)?.toLong()?.let { id ->
            ModIdentity.of(SubmissionRef(if (origin["submissionType"].text == "sound") SubmissionKind.Sound else SubmissionKind.Mod, id), origin["fileId"].whole?.takeIf { it > 0 })
        }
        else -> null
    }

    private fun file(entry: JsonObject): BundledFile? {
        val name = entry["name"].words ?: return null
        val path = entry["path"].words?.let(::insideBundle) ?: return null
        val size = entry["size"].whole?.takeIf { it >= 0 }
        val sha256 = entry["sha256"].sha256
        return BundledFile(path, name, if (size != null && sha256 != null) FileImage(size, sha256) else null)
    }

    private fun insideBundle(path: String): String? {
        if (path.startsWith('/') || path.startsWith('\\') || (path.length >= 2 && path[1] == ':') || path.any(Char::isISOControl)) return null
        val segments = path.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        return segments.takeIf { it.isNotEmpty() && ".." !in it }?.joinToString("/")
    }

    private fun convarLine(name: String, value: JsonElement): String? {
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return null
        return if (CrosshairConvar.matches(name) && text.none { it == '"' || it.isISOControl() }) "$name \"$text\"" else null
    }
}
