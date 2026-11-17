package gg.hideouthq.core.sharing

import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.offeredFirst
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

private const val Version: Byte = 1
private const val MaxNameBytes = 100
private const val MaxMods = 500
private const val MaxPins = 16
private const val MaxNumber = (1L shl 53) - 1
private const val VarintBits = 7
private const val VarintMore = 0x80
private const val VarintValue = 0x7F

/** One GameBanana mod a shared profile turns on, and the [files] of it to install when they are not the ones Install takes unasked. */
data class SharedMod(val submission: Long, val files: Set<Long> = emptySet()) {
    init {
        require(submission in 1..MaxNumber) { "a submission is a GameBanana id" }
        require(files.size <= MaxPins && files.all { it in 1..MaxNumber }) { "a mod pins at most $MaxPins GameBanana files" }
    }

    val ref: SubmissionRef get() = SubmissionRef(SubmissionKind.Mod, submission)
}

/**
 * A profile as a link carries it: its name and the GameBanana mods it turns on, winner first. Its [bytes] are what the
 * site keeps under a short code and what a long link carries whole: a version byte, the name's UTF-8 length as a varint
 * and its bytes, then each mod as its varint submission id, a varint count of pinned files and their varint ids.
 */
data class SharedProfile(val name: String, val mods: List<SharedMod>) {
    init {
        require(name.isNotEmpty() && name == name.trim() && name.none { it.isISOControl() }) { "a profile name is trimmed and printable" }
        require(name.encodeToByteArray().size <= MaxNameBytes) { "a shared name is at most $MaxNameBytes bytes" }
        require(mods.size in 1..MaxMods && mods.distinctBy { it.submission }.size == mods.size) { "a shared profile names 1 to $MaxMods mods, each once" }
    }

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val name = name.encodeToByteArray()
        out.write(Version.toInt())
        out.varint(name.size.toLong())
        out.write(name)
        for (mod in mods) {
            out.varint(mod.submission)
            out.varint(mod.files.size.toLong())
            mod.files.forEach { out.varint(it) }
        }
        return out.toByteArray()
    }

    /** [bytes] as base64url without padding, as a long link carries them. */
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes())

    companion object {
        /** The profile [bytes] hold, or null for anything but exactly what [SharedProfile.bytes] writes. */
        fun read(bytes: ByteArray): SharedProfile? {
            val input = Reader(bytes)
            if (input.byte() != Version.toInt()) return null
            val length = input.varint()?.takeIf { it in 1..MaxNameBytes }?.toInt() ?: return null
            val name = input.utf8(length)?.takeIf { it.isNotEmpty() && it == it.trim() && it.none(Char::isISOControl) } ?: return null
            val mods = mutableListOf<SharedMod>()
            while (!input.done) {
                if (mods.size == MaxMods) return null
                val submission = input.varint()?.takeIf { it > 0 } ?: return null
                val count = input.varint()?.takeIf { it <= MaxPins }?.toInt() ?: return null
                val files = (1..count).map { input.varint()?.takeIf { it > 0 } ?: return null }
                if (files.distinct().size != files.size) return null
                mods += SharedMod(submission, files.toSet())
            }
            if (mods.isEmpty() || mods.distinctBy { it.submission }.size != mods.size) return null
            return SharedProfile(name, mods)
        }

        /** The profile base64url [encoded], with or without padding, holds; null for anything else. */
        fun read(encoded: String): SharedProfile? {
            val bytes = try {
                Base64.getUrlDecoder().decode(encoded.trimEnd('='))
            } catch (e: IllegalArgumentException) {
                return null
            }
            return read(bytes)
        }
    }
}

/** What sharing a profile gives: [profile], null when it turns on no mod a link can carry, and how many mods it turns on were [leftOut]. */
data class ProfileShare(val profile: SharedProfile?, val leftOut: Int)

/**
 * This profile as a link carries it: each mod it turns on that came from a GameBanana mod's files, in its load order,
 * winner first. Mods added from a file, adopted ones Hideout couldn't match, and GameBanana sounds are [ProfileShare.leftOut],
 * and so is any mod past the link's limit. [catalogue] is what the catalogue knows of a mod by its id, which decides
 * whether its files need pinning.
 */
fun Profile.share(records: List<ManifestRecord>, catalogue: (String) -> Mod?): ProfileShare {
    val byId = records.associateBy { it.installId }
    val on = orders.values.flatten().filter { it in enabled }.mapNotNull(byId::get)
    val mods = LinkedHashMap<Long, SharedMod>()
    var leftOut = 0
    for (record in on) {
        val sources = record.sets.map { it.source as? InstalledSource.Remote }
        val ref = record.submission?.takeIf { it.kind == SubmissionKind.Mod && sources.none { source -> source == null } }
        when {
            ref == null || mods.size == MaxMods && ref.id !in mods -> leftOut++
            ref.id !in mods -> mods[ref.id] = SharedMod(ref.id, pins(sources.mapNotNullTo(LinkedHashSet()) { it?.identity?.fileId }, catalogue(ref.modId)))
        }
    }
    val profile = mods.values.toList().takeIf { it.isNotEmpty() }?.let { SharedProfile(sharedName(name), it) }
    return ProfileShare(profile, leftOut)
}

/**
 * The files to pin so a friend gets [installed]: none when they are the one Install takes unasked, or when none of them
 * is still offered, so the friend gets what Install takes; otherwise the ones still offered. With no catalogue entry to
 * tell, every one of them.
 */
private fun pins(installed: Set<Long>, mod: Mod?): Set<Long> {
    val offered = mod?.previewVariants.orEmpty()
    if (offered.isEmpty()) return installed.take(MaxPins).toSet()
    val current = installed.filterTo(LinkedHashSet()) { id -> offered.any { it.fileId == id } }
    return if (current.isEmpty() || current == setOfNotNull(offered.offeredFirst()?.fileId)) emptySet() else current.take(MaxPins).toSet()
}

/** [name] cut, whole characters at a time, to the bytes a link has room for. */
private fun sharedName(name: String): String {
    var bytes = 0
    var end = 0
    while (end < name.length) {
        val next = name.offsetByCodePoints(end, 1)
        bytes += name.substring(end, next).encodeToByteArray().size
        if (bytes > MaxNameBytes) break
        end = next
    }
    return name.substring(0, end).trim()
}

private fun ByteArrayOutputStream.varint(value: Long) {
    var rest = value
    while (rest >= VarintMore) {
        write((rest and VarintValue.toLong()).toInt() or VarintMore)
        rest = rest ushr VarintBits
    }
    write(rest.toInt())
}

private class Reader(private val bytes: ByteArray) {
    private var at = 0
    val done: Boolean get() = at == bytes.size

    fun byte(): Int? = if (done) null else bytes[at++].toInt() and 0xFF

    /** A minimal LEB128 varint of at most 2^53-1, or null for a cut, overlong or larger one. */
    fun varint(): Long? {
        var value = 0L
        var shift = 0
        while (true) {
            val next = byte() ?: return null
            if (next == 0 && shift > 0) return null
            value = value or ((next and VarintValue).toLong() shl shift)
            if (value > MaxNumber) return null
            if (next and VarintMore == 0) return value
            shift += VarintBits
            if (shift > 53) return null
        }
    }

    fun utf8(length: Int): String? {
        if (bytes.size - at < length) return null
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, at, length)).toString().also { at += length }
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
