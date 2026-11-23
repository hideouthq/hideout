package gg.hideouthq.feature.customgames

import androidx.compose.runtime.Composable
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Copy as the tables choose it, before it is read: the app's screens read it with [text] and the in-game UI with [load],
 * so a change of words lands in both. An argument that is itself a [Phrase] is read first.
 */
sealed interface Phrase {
    data class Text(val resource: StringResource, val args: List<Any> = emptyList()) : Phrase

    data class Count(val resource: PluralStringResource, val quantity: Int, val args: List<Any> = emptyList()) : Phrase

    data class Plain(val text: String) : Phrase

    /** Read one after another with a space between. */
    data class Joined(val parts: List<Phrase>) : Phrase
}

fun phrase(resource: StringResource, vararg args: Any): Phrase = Phrase.Text(resource, args.toList())

@Composable
fun Phrase.text(): String = when (this) {
    is Phrase.Text -> stringResource(resource, *args.map { it.argText() }.toTypedArray())
    is Phrase.Count -> pluralStringResource(resource, quantity, *args.map { it.argText() }.toTypedArray())
    is Phrase.Plain -> text
    is Phrase.Joined -> parts.map { it.text() }.joinToString(" ")
}

/** Outside Compose. [environment] says the language, and reading the system's for each phrase would ask the desktop for its theme each time. */
suspend fun Phrase.load(environment: ResourceEnvironment): String = when (this) {
    is Phrase.Text -> getString(environment, resource, *args.map { it.argLoaded(environment) }.toTypedArray())
    is Phrase.Count -> getPluralString(environment, resource, quantity, *args.map { it.argLoaded(environment) }.toTypedArray())
    is Phrase.Plain -> text
    is Phrase.Joined -> parts.map { it.load(environment) }.joinToString(" ")
}

@Composable
private fun Any.argText(): Any = (this as? Phrase)?.text() ?: this

private suspend fun Any.argLoaded(environment: ResourceEnvironment): Any = (this as? Phrase)?.load(environment) ?: this

/** [named] with the host's name in it, or [unnamed] when Steam gave none. */
fun namedPhrase(name: DisplayName?, named: StringResource, unnamed: StringResource): Phrase = name?.let { phrase(named, it.value) } ?: phrase(unnamed)

val ModeFace.namePhrase: Phrase get() = title?.let(Phrase::Plain) ?: phrase(Res.string.cg_unknown_mode)
