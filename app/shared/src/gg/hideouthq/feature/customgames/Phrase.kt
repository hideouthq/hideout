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

/** Copy chosen before it is read, so the app's screens ([text]) and the in-game UI ([load]) say the same words. */
sealed interface Phrase {
    data class Text(val resource: StringResource, val args: List<Any> = emptyList()) : Phrase

    data class Count(val resource: PluralStringResource, val quantity: Int, val args: List<Any> = emptyList()) : Phrase

    data class Plain(val text: String) : Phrase

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

suspend fun Phrase.load(environment: ResourceEnvironment): String = when (this) {
    is Phrase.Text -> getString(environment, resource, *args.map { it.argLoaded(environment) }.toTypedArray())
    is Phrase.Count -> getPluralString(environment, resource, quantity, *args.map { it.argLoaded(environment) }.toTypedArray())
    is Phrase.Plain -> text
    is Phrase.Joined -> parts.map { it.load(environment) }.joinToString(" ")
}

@Composable
private fun Any.argText(): Any = (this as? Phrase)?.text() ?: this

private suspend fun Any.argLoaded(environment: ResourceEnvironment): Any = (this as? Phrase)?.load(environment) ?: this

fun namedPhrase(name: DisplayName?, named: StringResource, unnamed: StringResource): Phrase = name?.let { phrase(named, it.value) } ?: phrase(unnamed)

val ModeFace.namePhrase: Phrase get() = title?.let(Phrase::Plain) ?: phrase(Res.string.cg_unknown_mode)
