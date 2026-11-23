package gg.hideouthq.core.customgames.social

/** The mailbox's id for one invite it delivered, 32 hex digits. */
@JvmInline
value class InvitationId private constructor(val value: String) {
    companion object {
        private val Shape = Regex("[0-9a-f]{32}")

        fun parse(text: String): InvitationId? = if (Shape.matches(text)) InvitationId(text) else null
    }
}

/** The directory's public name for a listing: the first 16 hex digits of SHA-256 of the listing's token, so no reader can change it. */
@JvmInline
value class ListingId private constructor(val value: String) {
    companion object {
        private val Shape = Regex("[0-9a-f]{16}")

        fun parse(text: String): ListingId? = if (Shape.matches(text)) ListingId(text) else null
    }
}
