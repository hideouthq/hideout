package gg.hideouthq.core.autoexec

import gg.hideouthq.core.library.AutoexecTakeover

/**
 * One change to autoexec.cfg, as an intent rather than new bytes, so a save can make it again on the file as it is on
 * disk then: a line someone added meanwhile in another editor stays, and Hideout's change lands on top. Each edit is
 * a pure function of a document, and making it twice gives what making it once does.
 */
sealed interface AutoexecEdit {
    /** [document] with this edit, or null when it no longer fits it, as when its line is gone; the file then wins. */
    fun applyTo(document: AutoexecDocument): AutoexecDocument?

    data class SetValue(val ref: CommandRef, val value: String) : AutoexecEdit {
        override fun applyTo(document: AutoexecDocument): AutoexecDocument? = document.changed(ref) { command -> command.valued(value).takeIf { classify(it.text) is FreeKind.Command } ?: command }
    }

    data class SetOn(val ref: CommandRef, val on: Boolean) : AutoexecEdit {
        override fun applyTo(document: AutoexecDocument): AutoexecDocument? = document.changed(ref) { it.turned(on) }
    }

    data class Remove(val ref: CommandRef) : AutoexecEdit {
        override fun applyTo(document: AutoexecDocument): AutoexecDocument = document.indexOf(ref)?.let { document.withParts(document.parts.take(it) + document.parts.drop(it + 1)) } ?: document
    }

    data class RemoveAll(val name: String) : AutoexecEdit {
        override fun applyTo(document: AutoexecDocument): AutoexecDocument =
            document.withParts(document.parts.filterNot { (it as? Part.Free)?.command?.name.equals(name, ignoreCase = true) })
    }

    /**
     * The Add field and the library's +: `name value` after the last free command, else after the header. A line of
     * that name already there is turned on and given [value] instead.
     */
    data class Add(val name: String, val value: String) : AutoexecEdit {
        override fun applyTo(document: AutoexecDocument): AutoexecDocument {
            val named = document.parts.indexOfFirst { (it as? Part.Free)?.command?.name.equals(name, ignoreCase = true) }
            if (named >= 0) return document.changed(named) { it.turned(on = true).written(value) }
            return document.inserted(document.commandsEnd(), listOf(document.line(if (value.isEmpty()) name else "$name $value")))
        }

        companion object {
            fun parse(text: String): Add? {
                val command = CommandText.parse(text.trim())?.takeIf { it.on } ?: return null
                return Add(command.name, command.value)
            }
        }
    }
}

private fun AutoexecDocument.changed(ref: CommandRef, change: (CommandText) -> CommandText): AutoexecDocument? {
    indexOf(ref)?.let { return changed(it, change) }
    val made = CommandText.parse(ref.text)?.let(change)?.text ?: return null
    return takeIf { parts.any { (it as? Part.Free)?.line?.text == made } }
}

private fun AutoexecDocument.changed(index: Int, change: (CommandText) -> CommandText): AutoexecDocument {
    val free = parts[index] as Part.Free
    val next = change(checkNotNull(free.command)).text
    if (next == free.line.text) return this
    return withParts(parts.take(index) + Part.Free(free.line.edited(next)) + parts.drop(index + 1))
}

/**
 * An import's change to autoexec.cfg: with [AutoexecTakeover.Sections], each of those commands sections in the file
 * becomes plain lines in place; with [AutoexecTakeover.Lines], a bundle's commands go in as the user's own, each unless
 * a line reads so already. Null when nothing would change, so importing twice writes once.
 */
fun AutoexecDocument.takeOver(takeover: AutoexecTakeover): AutoexecDocument? {
    val next = when (takeover) {
        is AutoexecTakeover.Lines -> withLines(takeover.commands)
        is AutoexecTakeover.Sections -> unwrapped { it.markers in takeover.sections && it.markers.kind == SectionKind.Commands }
    }
    return next.takeUnless { it.render().contentEquals(render()) }
}

/**
 * One run of changed lines in the Text view: [removed], from line [at], became [added]. [above] and [below] are the
 * lines around it when it was made, so it lands only where they still are, never on a locked line. Lines count from
 * below the header, and [above] is null at the top, so the header the first save puts first moves no hunk; one that
 * would change the header has an [at] below 0 and never lands.
 */
data class TextHunk(val at: Int, val above: String?, val removed: List<String>, val added: List<String>, val below: String?) : AutoexecEdit {
    override fun applyTo(document: AutoexecDocument): AutoexecDocument? {
        if (at < 0) return null
        val parts = document.parts
        val header = if (parts.firstOrNull()?.isHeader == true) 1 else 0
        val owners = parts.flatMapIndexed { index, part -> List(part.lines.size) { index } }.drop(header)
        val texts = document.editorText.lines.drop(header)
        val end = at + removed.size
        if (end > texts.size || texts.subList(at, end) != removed || texts.getOrNull(at - 1) != above || texts.getOrNull(end) != below) return null
        val first = owners.getOrNull(at) ?: parts.size
        val insideAPart = removed.isEmpty() && at in 1 until texts.size && owners[at - 1] == owners[at]
        if (insideAPart || (at until end).any { parts[owners[it]] !is Part.Free }) return null
        val after = parts.drop(first + removed.size)
        val lines = added.mapIndexed { index, text -> if (index < removed.size) Part.Free((parts[first + index] as Part.Free).line.edited(text, document.eol)) else document.line(text) }
        return when {
            after.isNotEmpty() -> document.withParts(parts.take(first) + lines + after)
            removed.isEmpty() -> if (lines.isEmpty()) document else document.appended(lines)
            else -> document.withParts(parts.take(first) + lines.dropLast(1) + lines.takeLast(1).map { it.ending(parts.last().lines.last().end) })
        }
    }

    companion object {
        /** The one run of lines that differs between the Text view as [before] showed it and [after], typed; null when none does. */
        fun between(before: EditorText, after: String): TextHunk? {
            val old = before.lines
            val new = if (old.isEmpty() && after.isEmpty()) emptyList() else after.split('\n')
            if (old == new) return null
            val prefix = old.zip(new).takeWhile { (a, b) -> a == b }.size
            val suffix = old.drop(prefix).reversed().zip(new.drop(prefix).reversed()).takeWhile { (a, b) -> a == b }.size
            val header = if (before.locked.firstOrNull()?.owner == LockOwner.Header) 1 else 0
            return TextHunk(prefix - header, old.getOrNull(prefix - 1)?.takeIf { prefix > header }, old.subList(prefix, old.size - suffix), new.subList(prefix, new.size - suffix), old.getOrNull(old.size - suffix))
        }
    }
}

/** [edits] made in order on [document], each that no longer fits it left out: what a save writes, given the file as it is on disk. */
fun replay(document: AutoexecDocument, edits: List<AutoexecEdit>): AutoexecDocument = edits.fold(document) { current, edit -> edit.applyTo(current) ?: current }
