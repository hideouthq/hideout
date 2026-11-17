package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.RemoteFile

internal object Ace {
    val ref = SubmissionRef(SubmissionKind.Mod, 626_469)
    val withBag = RemoteFile(1_648_841, "ace_infernus_ognb_db0f9.rar", 23_568_106, "6d906e126c8e5426e950be1810e793b8", null, 103_040, 1_773_614_606, "https://gamebanana.com/dl/1648841")
    val noBag = RemoteFile(1_648_840, "ace_nobag_infernus_ognb_5f09e.rar", 23_499_737, "1b6e4c6be05becbe97c6c0c460ffba70", "Ace without the backpack.", 66_118, 1_773_614_606, "https://gamebanana.com/dl/1648840")
    val older = RemoteFile(1_537_122, "ace_infernus_83fb6.zip", 35_589_754, "56bbcbbe6dd686fd820823f230c4b05c", null, 38_891, 1_760_259_833, "https://gamebanana.com/dl/1537122")
    val listing = FileListing.Available("Ace (One Piece) - Infernus", listOf(withBag, noBag), archived = listOf(older))

    val withBagVpk = FilePrint(79_968_324, 0x1CE55BABL)
    val noBagVpk = FilePrint(79_913_908, 0x7F53A936L)
    val olderVpk = FilePrint(77_346_236, 0x15F49FF1L)
    val indexes = mapOf(withBag.id to listOf(withBagVpk), noBag.id to listOf(noBagVpk), older.id to listOf(olderVpk))
}
