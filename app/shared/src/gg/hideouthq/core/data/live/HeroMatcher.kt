package gg.hideouthq.core.data.live

import gg.hideouthq.core.model.Hero

internal class HeroMatcher(heroes: List<Hero>) {
    private val byName: Map<String, String> = heroes.associate { words(it.name).joinToString(" ") to it.id }
    private val phrases: List<Pair<List<String>, String>> = heroes.map { words(it.name) to it.id }

    fun named(label: String): String? = byName[words(label).joinToString(" ")]

    fun mentionedIn(text: String): String? {
        val textWords = Word.findAll(text.lowercase()).map { it.value }.toList()
        return phrases.filter { (phrase, _) -> textWords.containsRun(phrase) }.map { it.second }.distinct().singleOrNull()
    }

    private fun words(name: String): List<String> {
        val all = Word.findAll(name.lowercase()).map { it.value }.toList()
        return if (all.size > 1 && all.first() == "the") all.drop(1) else all
    }

    private fun List<String>.containsRun(run: List<String>): Boolean =
        run.isNotEmpty() && (0..size - run.size).any { start -> run.indices.all { this[start + it] == run[it] } }

    private companion object {
        val Word = Regex("[a-z0-9]+")
    }
}
