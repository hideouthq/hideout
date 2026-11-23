package gg.hideouthq.core.customgames

enum class Listing { InviteOnly, Public }

/**
 * What a host wants beyond the mode and its picks: whether Servers lists the game, and which Steam friends get an
 * invite once it is ready. It is part of what the game is for, so it rides in [Role.Host] beside the choices, Play
 * again keeps it, and only [CustomGames] writes it.
 */
data class Outreach(val listing: Listing, val invitees: Set<SteamId>) {
    companion object {
        val Private = Outreach(Listing.InviteOnly, emptySet())
    }
}

/** A Steam account as its SteamID64, parsed wherever one enters. */
@JvmInline
value class SteamId private constructor(val value: String) {
    override fun toString() = "SteamId(redacted)"

    companion object {
        private val Shape = Regex("7656119[0-9]{10}")

        fun parse(text: String): SteamId? = if (Shape.matches(text)) SteamId(text) else null
    }
}
