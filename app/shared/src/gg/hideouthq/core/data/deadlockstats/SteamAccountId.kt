package gg.hideouthq.core.data.deadlockstats

/**
 * A Deadlock player's 32-bit Steam account id — what deadlock-api keys players by, and what a
 * profile picks when the user supplies their own public id. Not a SteamID64: Steam's 64-bit ids
 * are `accountId + 76561197960265728`, and this type converts between the two so a player can be
 * selected by either without either end of the app mixing the ranges up.
 */
@JvmInline
value class SteamAccountId private constructor(val value: Long) {
    fun toSteamId64(): Long = value + SteamId64Base

    override fun toString(): String = value.toString()

    companion object {
        private const val MaxAccountId = 4_294_967_295L
        private const val SteamId64Base = 76561197960265728L

        /** Null when [value] does not fit in an unsigned 32-bit account id. */
        fun ofAccountId(value: Long): SteamAccountId? = value.takeIf { it in 0..MaxAccountId }?.let(::SteamAccountId)

        /** Converts a 64-bit SteamID to its account id. Null when it is not an individual account in range. */
        fun ofSteamId64(steamId64: Long): SteamAccountId? = ofAccountId(steamId64 - SteamId64Base)
    }
}
