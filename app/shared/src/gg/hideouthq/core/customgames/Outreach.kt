package gg.hideouthq.core.customgames

enum class Listing { InviteOnly, Public }

/** Rides in [Role.Host] beside the choices so Play again keeps it. Only [CustomGames] writes it. */
data class Outreach(val listing: Listing, val invitees: Set<SteamId>) {
    companion object {
        val Private = Outreach(Listing.InviteOnly, emptySet())
    }
}

@JvmInline
value class SteamId private constructor(val value: String) {
    override fun toString() = "SteamId(redacted)"

    companion object {
        private val Shape = Regex("7656119[0-9]{10}")

        fun parse(text: String): SteamId? = if (Shape.matches(text)) SteamId(text) else null
    }
}
