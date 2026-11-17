package gg.hideouthq.core.customgames

import java.util.Base64

private const val CodeAlphabet = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val CodeLength = 9
private const val CodeGroup = 3
private const val CodeSymbol = "[$CodeAlphabet]"
private val InviteShape = Regex("""\b$CodeSymbol{3}-?$CodeSymbol{3}-?$CodeSymbol{3}\b|tc[A-Za-z0-9_-]{16,}""")

fun redactInvites(text: String): String = text.replace(InviteShape, "[invite]")

/**
 * What a host tells a friend's Hideout over the link before the friend says yes. [content] says where the guest's game
 * content comes from. [choices] are the picks the server runs with, and [capacity] the match size the host's listing
 * gives them, which a guest reads here rather than from its own listing, which may be another version.
 */
data class InviteOffer(val host: DisplayName?, val mode: ModeId, val content: OfferContent, val choices: Choices, val capacity: Int) {
    val encoded: String
        get() {
            val content = when (content) {
                is OfferContent.Feed -> content.client?.hex.orEmpty()
                is OfferContent.Host -> "$HostContent${content.pack.sha256.hex}:${content.pack.bytes}"
            }
            val text = listOf(mode.value, content, choices.args.joinToString(PickSeparator), capacity.toString(), host?.value.orEmpty())
                .joinToString(OfferSeparator.toString())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())
        }

    companion object {
        private const val OfferSeparator = '\n'
        private const val PickSeparator = ","
        private const val HostContent = "host:"

        /** The one place an offer is made, and the server's `--arg`s come from it too, so the two can't disagree. [shared] is a folder mode's pack. */
        fun of(mode: Mode, choices: Choices, me: LocalPlayer, shared: SharedPack? = null): InviteOffer =
            InviteOffer(me.name, mode.id, shared?.let(OfferContent::Host) ?: OfferContent.Feed(mode.clientDigest), choices, mode.listing.capacity(choices))

        fun decode(encoded: String): InviteOffer? {
            val text = try {
                Base64.getUrlDecoder().decode(encoded).decodeToString()
            } catch (e: IllegalArgumentException) {
                return null
            }
            val lines = text.split(OfferSeparator)
            if (lines.size != 5) return null
            val (mode, from, picks, capacity, host) = lines
            val id = ModeId.parse(mode) ?: return null
            val content = contentIn(from) ?: return null
            val pairs = if (picks.isEmpty()) emptyList() else picks.split(PickSeparator).map { pick ->
                val (key, value) = pick.split('=').takeIf { it.size == 2 } ?: return null
                (SettingKey.parse(key) ?: return null) to (SettingValue.parse(value) ?: return null)
            }
            if (pairs.distinctBy { it.first }.size != pairs.size) return null
            val choices = Choices.of(pairs.toMap()) ?: return null
            val size = capacity.toIntOrNull()?.takeIf { it in 1..MaxPlayers && it.toString() == capacity } ?: return null
            val name = if (host.isEmpty()) null else DisplayName.parse(host)?.takeIf { it.value == host } ?: return null
            return InviteOffer(name, id, content, choices, size)
        }

        private fun contentIn(text: String): OfferContent? {
            if (text.isEmpty()) return OfferContent.Feed(null)
            if (!text.startsWith(HostContent)) return OfferContent.Feed(Sha256.parse(text) ?: return null)
            val (digest, size) = text.removePrefix(HostContent).split(':').takeIf { it.size == 2 } ?: return null
            val bytes = size.toLongOrNull()?.takeIf { it in 1..MaxArtifactBytes && it.toString() == size } ?: return null
            return OfferContent.Host(SharedPack(Sha256.parse(digest) ?: return null, bytes))
        }
    }
}

sealed interface OfferContent {
    /** The guest looks [client] up in its own signed feed, so nothing here makes it fetch what its feed doesn't name. Null plays with stock assets. */
    data class Feed(val client: Sha256?) : OfferContent

    /** A mode author's build, which the host's helper serves over the link and the guest's helper fetches and checks. */
    data class Host(val pack: SharedPack) : OfferContent
}

/** The zip of a folder mode's content that the host shares, named by its digest and size. */
data class SharedPack(val sha256: Sha256, val bytes: Long)

/**
 * A code a friend reads out or pastes, or a link that carries it, such as `P95-MAN-26D`. The first eight characters are
 * the secret both helpers derive the link's keys from, and the last names the host's relay region. [value] is the bare
 * nine characters the helper takes. Parsed at the boundary, so a bad one never reaches the helper.
 */
class InviteCode private constructor(val value: String) {
    val shown: String get() = value.chunked(CodeGroup).joinToString("-")

    /** The code stays in the fragment, which a browser never sends, so the site and its logs don't see it. */
    fun webLink(site: SiteOrigin): String = "${site.value}/join#$shown"

    override fun equals(other: Any?): Boolean = other is InviteCode && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "InviteCode(redacted)"

    companion object {
        private const val MaxPastedLength = 4096
        private const val ZeroWidth = "­​‌‍‎‏⁠﻿"

        /** `urlScheme` in the desktop app's `module.yaml` has to say the same. */
        private const val AppLinkScheme = "hideout:"
        private const val AppLinkPrefix = "hideout://join/"

        /** Whether a launch's argument came through the registered scheme, readable or not. */
        fun isAppLink(argument: String): Boolean = argument.startsWith(AppLinkScheme, ignoreCase = true)

        /**
         * Reads a bare code, a `hideout://join/<code>` link or a `https://<site>/join#<code>` link, in any case, with or
         * without dashes and spaces.
         */
        fun parse(pasted: String): InviteCode? {
            if (pasted.length > MaxPastedLength) return null
            val code = codeIn(pasted.filterNot { it.isWhitespace() || it in ZeroWidth }).replace("-", "").uppercase()
            return code.takeIf { it.length == CodeLength && it.all(CodeAlphabet::contains) }?.let(::InviteCode)
        }

        private fun codeIn(text: String): String = when {
            text.startsWith(AppLinkPrefix, ignoreCase = true) -> text.drop(AppLinkPrefix.length).removeSuffix("/")
            text.startsWith("https://", ignoreCase = true) ->
                if (text.substringBefore('#').removeSuffix("/").endsWith("/join", ignoreCase = true)) text.substringAfter('#', "") else ""
            else -> text
        }
    }
}

data class Invite(val code: InviteCode, val offer: InviteOffer)
