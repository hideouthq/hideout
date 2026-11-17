package gg.hideouthq.core.sharing

import gg.hideouthq.core.customgames.SiteOrigin

private const val CodeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private val CodeLengths = 8..12
private const val MaxPastedLength = 16_384
private const val ZeroWidth = "­​‌‍‎‏⁠﻿"

/** `urlScheme` in the desktop app's `module.yaml` has to say the same. */
private const val AppCodePrefix = "hideout://p/"
private const val AppWholePrefix = "hideout://profile/"

/**
 * The short code the site keeps a shared profile under, such as `K7Q2M9XD`: Crockford base32, uppercase. Read the way
 * people type it, in any case, with dashes and spaces, and with I and L as 1 and O as 0.
 */
@JvmInline
value class ShareCode private constructor(val value: String) {
    fun webLink(site: SiteOrigin): String = "${site.value}/p/$value"

    companion object {
        fun parse(text: String): ShareCode? {
            val code = text.filterNot { it.isWhitespace() || it == '-' || it in ZeroWidth }.uppercase()
                .map { when (it) { 'I', 'L' -> '1'; 'O' -> '0'; else -> it } }.joinToString("")
            return code.takeIf { it.length in CodeLengths && it.all(CodeAlphabet::contains) }?.let(::ShareCode)
        }
    }
}

/** What a pasted or clicked profile link holds: a [Code] the site looks up, or the [Whole] profile. */
sealed interface ProfileLink {
    data class Code(val code: ShareCode) : ProfileLink
    data class Whole(val profile: SharedProfile) : ProfileLink

    companion object {
        /** Whether a launch's `hideout:` argument is for the profile import rather than a custom game, readable or not. */
        fun isAppLink(argument: String): Boolean =
            argument.startsWith(AppCodePrefix, ignoreCase = true) || argument.startsWith(AppWholePrefix, ignoreCase = true)

        /**
         * Reads a bare code or payload, `hideout://p/<code>`, `hideout://profile/<payload>`, `https://<site>/p/<code>`
         * or `https://<site>/profile#<payload>`. Any site's link reads, because the code or the payload alone decides
         * the profile. A text that is both a code and a payload is a code, since people type codes.
         */
        fun parse(pasted: String): ProfileLink? {
            if (pasted.length > MaxPastedLength) return null
            val text = pasted.filterNot { it.isWhitespace() || it in ZeroWidth }
            return when {
                text.startsWith(AppCodePrefix, ignoreCase = true) -> code(text.drop(AppCodePrefix.length).removeSuffix("/"))
                text.startsWith(AppWholePrefix, ignoreCase = true) -> whole(text.drop(AppWholePrefix.length).removeSuffix("/"))
                text.startsWith("https://", ignoreCase = true) -> {
                    val path = text.drop("https://".length).substringBefore('#').substringBefore('?').removeSuffix("/").split('/')
                    when {
                        path.size == 3 && path[1].equals("p", ignoreCase = true) -> code(path[2])
                        path.size == 2 && path[1].equals("profile", ignoreCase = true) -> whole(text.substringAfter('#', ""))
                        else -> null
                    }
                }
                else -> code(text) ?: whole(text)
            }
        }

        private fun code(text: String): ProfileLink? = ShareCode.parse(text)?.let(::Code)

        private fun whole(text: String): ProfileLink? = SharedProfile.read(text)?.let(::Whole)
    }
}

/** The link that carries [this] whole, for when the site can't keep it: the site's page, or without a site the app's own link. */
fun SharedProfile.longLink(site: SiteOrigin?): String = site?.let { "${it.value}/profile#$encoded" } ?: "$AppWholePrefix$encoded"
