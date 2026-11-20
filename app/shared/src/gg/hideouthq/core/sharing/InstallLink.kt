package gg.hideouthq.core.sharing

import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** `urlScheme` in the desktop app's `module.yaml` has to say the same. */
private const val AppScheme = "hideout:"

/**
 * GameBanana's 1-click install link, as its install button hands it to the app it names:
 * `hideout:https://gamebanana.com/mmdl/<file id>,<item type>,<item id>`.
 */
sealed interface InstallLink {
    data class File(val submission: SubmissionRef, val fileId: Long) : InstallLink

    /** Nothing to install: the link names an item that is no mod or sound, such as a tool, or can't be read. */
    enum class Refused : InstallLink { NotAMod, Unreadable }

    companion object {
        private val OneClick = Regex("""https?:/{1,2}(?:www\.)?gamebanana\.com/(?:mmdl|dl)/([1-9][0-9]{0,17}),([a-z]+),([1-9][0-9]{0,17})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)

        /** Whether a launch's `hideout:` argument carries a web address, as GameBanana's links do, readable or not. */
        fun isAppLink(argument: String): Boolean = address(argument) != null

        /** Reads `hideout:` or `hideout://` before the GameBanana address, whose path may be `/mmdl/` or `/dl/`. */
        fun parse(argument: String): InstallLink {
            val address = address(argument)?.replace("%2C", ",", ignoreCase = true) ?: return Refused.Unreadable
            val (fileId, type, itemId) = OneClick.matchEntire(address)?.destructured ?: return Refused.Unreadable
            val kind = SubmissionKind.entries.firstOrNull { it.apiName.equals(type, ignoreCase = true) } ?: return Refused.NotAMod
            return File(SubmissionRef(kind, itemId.toLong()), fileId.toLong())
        }

        private fun address(argument: String): String? {
            val text = argument.trim()
            if (!text.startsWith(AppScheme, ignoreCase = true)) return null
            return text.drop(AppScheme.length).removePrefix("//").takeIf { it.startsWith("http", ignoreCase = true) }
        }
    }
}

/** GameBanana's 1-click links on their way to the shell, which takes them once it shows; a newer one replaces one not yet taken. */
class InstallLinks {
    private val requests = Channel<InstallLink>(Channel.CONFLATED)

    fun open(link: InstallLink) {
        requests.trySend(link)
    }

    val incoming: Flow<InstallLink> = requests.receiveAsFlow()
}
