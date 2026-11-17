package gg.hideouthq.core.library

/** The pakNN an author named a part's directory file with, which authors use to say which part wins; null for any other name. */
fun authorNumber(key: String): Int? = PakNumbers.mounted(key.substringAfterLast('/'))?.value

/**
 * An archive's [sets] as its question offers them: lowest author number first, then in archive order, and each group
 * of parts that change the same files kept together where its first part falls.
 */
internal fun setOptions(sets: List<VpkSet<StagedVpk>>): List<SetOption> {
    val options = sets.map { set ->
        SetOption(set.key, set.members.map { it.vpk.path }, set.sizeBytes, (set.members.first().vpk.resources as? ResourceIndex.Ready)?.files.orEmpty())
    }.sortedBy { authorNumber(it.key) ?: Int.MAX_VALUE }
    val groups = pickOneGroups(options)
    val byKey = options.associateBy { it.key }
    return options.flatMap { option ->
        val group = groups.firstOrNull { option.key in it } ?: return@flatMap listOf(option)
        if (group.first() == option.key) group.map(byKey::getValue) else emptyList()
    }
}

/** The parts of [options] that are [alternatives] to each other, by key in [options]' order, in groups of two or more: of each, one is kept. */
fun pickOneGroups(options: List<SetOption>): List<List<String>> {
    val linked = options.indices.associateWith { mutableSetOf<Int>() }
    for (a in options.indices) for (b in a + 1..options.lastIndex) {
        if (alternatives(options[a].files, options[b].files)) {
            linked.getValue(a) += b
            linked.getValue(b) += a
        }
    }
    val placed = BooleanArray(options.size)
    return options.indices.mapNotNull { start ->
        if (placed[start]) return@mapNotNull null
        val group = sortedSetOf(start)
        val queue = ArrayDeque(listOf(start))
        placed[start] = true
        while (queue.isNotEmpty()) linked.getValue(queue.removeFirst()).filterNot { placed[it] }.forEach { placed[it] = true; group += it; queue += it }
        group.map { options[it].key }.takeIf { it.size > 1 }
    }
}

/** What starts ticked: every part outside a pick-one group, add-ons too, and of each group the [kept] parts in it, else its first. */
internal fun preselect(options: List<SetOption>, kept: Collection<String>): List<String> {
    val groups = pickOneGroups(options)
    return options.map { it.key }.filter { key ->
        val group = groups.firstOrNull { key in it } ?: return@filter true
        if (group.any { it in kept }) key in kept else key == group.first()
    }
}

/** One file's parts in a mod, winner first, and the lowest number their author gave them. */
internal class FileParts(val fileId: Long?, val parts: List<SetPlan>) {
    val number: Int? = parts.mapNotNull { authorNumber(it.componentKey) }.minOrNull()
}

/**
 * A mod's parts in the order they win, each file's kept together: [current] the files it has, in its order, and
 * [incoming] those new to it. Each new file goes before the first file the mod has whose author number is higher, new
 * files lowest number first and then in [incoming]'s order.
 */
internal fun arrangeParts(current: List<FileParts>, incoming: List<FileParts>): List<SetPlan> {
    val waiting = ArrayDeque(incoming.sortedBy { it.number ?: Int.MAX_VALUE })
    return buildList {
        for (file in current) {
            while (waiting.isNotEmpty() && (waiting.first().number ?: Int.MAX_VALUE) < (file.number ?: Int.MAX_VALUE)) addAll(waiting.removeFirst().parts)
            addAll(file.parts)
        }
        waiting.forEach { addAll(it.parts) }
    }
}
