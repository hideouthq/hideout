package gg.hideouthq.core.library

import kotlin.jvm.JvmInline

@JvmInline
value class ProfileId(val value: String) {
    init { require(value.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) }
}

enum class ProfileColour { Sage, Blue, Clay, Mauve, Sand, Rose }

/**
 * One profile: the mods it turns on and, for each game with installed mods, all of them, on or off, winner first. While
 * it is active the ones it turns on load in that order, and each one it leaves off keeps its place there for when it is
 * turned on. It never names files, numbers or steps.
 */
data class Profile(val id: ProfileId, val name: String, val colour: ProfileColour, val enabled: Set<String>, val orders: Map<GameRoot, List<String>>) {
    init {
        require(name.isNotBlank() && name == name.trim() && name.none { it.isISOControl() })
        require(orders.values.all { ids -> ids.isNotEmpty() && ids.distinct().size == ids.size && ids.none { it.isBlank() } }) { "an order names each set of its game once" }
        require(orders.values.flatten().let { it.distinct().size == it.size }) { "a set loads in one game" }
        require(orders.values.flatten().containsAll(enabled)) { "a profile turns on only mods it orders" }
    }
}

/** Why [name], trimmed, cannot name a profile beside [profiles], [except] being the one renamed; null when it can. */
fun profileNameProblem(name: String, profiles: List<Profile>, except: ProfileId? = null): LibraryProblem? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() || trimmed.any { it.isISOControl() } -> LibraryProblem.InvalidProfileName
        profiles.any { it.id != except && it.name.equals(trimmed, ignoreCase = true) } -> LibraryProblem.ProfileNameTaken
        else -> null
    }
}

/** [base], or "[base] 2", "[base] 3" and so on: the first that no profile in [profiles] is called, whatever its case. */
fun freeProfileName(base: String, profiles: List<Profile>): String =
    (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base $it" }).first { profileNameProblem(it, profiles) == null }

/** Every profile, each ordering the same mods in the same games, and which one is active. */
data class ProfileBook(val active: ProfileId, val profiles: List<Profile>) {
    init {
        require(profiles.isNotEmpty())
        require(profiles.map { it.id }.distinct().size == profiles.size)
        require(profiles.map { it.name.lowercase() }.distinct().size == profiles.size)
        require(profiles.any { it.id == active })
        require(profiles.map { profile -> profile.orders.mapValues { (_, ids) -> ids.toSet() } }.distinct().size == 1) { "every profile orders the same mods" }
    }

    val activeProfile: Profile get() = profiles.single { it.id == active }

    /** The first colour no profile uses, so a new profile looks unlike the rest; the first colour once all are used. */
    val nextColour: ProfileColour get() = ProfileColour.entries.firstOrNull { colour -> profiles.none { it.colour == colour } } ?: ProfileColour.entries.first()

    internal val installed: Set<String> get() = activeProfile.orders.values.flatten().toSet()

    internal fun membership(id: ProfileId, installIds: Set<String>, enabled: Boolean): ProfileBook = copy(profiles = profiles.map {
        if (it.id != id) it else it.copy(enabled = if (enabled) it.enabled + installIds else it.enabled - installIds)
    })

    /** The active profile loading [root]'s mods in [order]; every other profile keeps its own. */
    internal fun withOrder(root: GameRoot, order: List<String>): ProfileBook =
        copy(profiles = profiles.map { if (it.id == active) it.copy(orders = it.orders + (root to order)) else it })

    /** [record] first in every profile's order of its game, where a new mod goes, and on in [members]. */
    internal fun withInstalled(record: ManifestRecord, members: Set<ProfileId>): ProfileBook {
        val root = record.gameRoot
        val placed = copy(profiles = profiles.map { profile ->
            profile.copy(orders = profile.orders + (root to listOf(record.installId) + profile.orders[root].orEmpty().filter { it != record.installId }))
        })
        return members.fold(placed) { book, id -> book.membership(id, setOf(record.installId), true) }
    }

    internal fun without(installIds: Set<String>): ProfileBook = copy(profiles = profiles.map { profile ->
        profile.copy(enabled = profile.enabled - installIds, orders = profile.orders.mapValues { (_, ids) -> ids - installIds }.filterValues { it.isNotEmpty() })
    })

    /** Whether [this] is [target] but for the mods in [edited]: what each profile turns on, in what order, and which is active. */
    internal fun reaches(target: ProfileBook, edited: Set<String>): Boolean = without(edited) == target.without(edited)

    /**
     * [this] as the files can follow it while the mods the user edited stay where [records] have them: the active
     * profile turns each of them on exactly when it is on in [records], and each one that is on takes the place its
     * number gives it among the rest. Every other mod keeps its place, so the book [reaches] [this] but for those mods.
     */
    internal fun holding(records: List<ManifestRecord>): ProfileBook {
        val edited = records.filter { it.edited }.mapTo(HashSet()) { it.installId }
        val byId = records.associateBy { it.installId }
        val active = activeProfile
        val orders = active.orders.mapValues { (_, ids) ->
            val order = ids.filter { it !in edited }.toMutableList()
            for (id in ids.filter { it in edited }) {
                val number = byId.getValue(id).numbers.firstOrNull()
                val at = if (number == null) minOf(ids.indexOf(id), order.size)
                else order.indexOfFirst { other -> byId[other]?.numbers?.firstOrNull()?.let { it > number } == true }.takeIf { it >= 0 } ?: order.size
                order.add(at, id)
            }
            order
        }
        val enabled = active.enabled - edited + records.filter { it.edited && it.enabled }.map { it.installId }
        return copy(profiles = profiles.map { if (it.id == active.id) it.copy(enabled = enabled, orders = orders) else it })
    }

    /** Whether every profile orders exactly the mods of [records], each in its own game. */
    internal fun covers(records: List<ManifestRecord>): Boolean =
        activeProfile.orders.mapValues { (_, ids) -> ids.toSet() } == records.groupBy { it.gameRoot }.mapValues { (_, mods) -> mods.map { it.installId }.toSet() }

    /**
     * Whether the files are where the active profile wants them: it orders exactly [records], turns on exactly the ones
     * that are on, and orders those as their numbers load them. Where it puts the ones that are off is its own.
     */
    internal fun follows(records: List<ManifestRecord>): Boolean =
        covers(records) && activeProfile.enabled == records.filter { it.enabled }.mapTo(HashSet()) { it.installId } &&
            records.groupBy { it.gameRoot }.all { (root, held) -> activeProfile.onOrder(root) == onLoadOrder(held) }

    companion object {
        const val MainName = "Main"

        /** One profile, Main, that [records] follow: the mods that are on in the order their numbers load them, then the ones that are off as listed. */
        fun main(records: List<ManifestRecord> = emptyList()): ProfileBook {
            val id = ProfileId("main")
            val orders = records.groupBy { it.gameRoot }.mapValues { (_, held) -> fileOrder(held) }
            return ProfileBook(id, listOf(Profile(id, MainName, ProfileColour.Sage, records.filter { it.enabled }.map { it.installId }.toSet(), orders)))
        }
    }
}

/** The book the user last saved, [target], which the files follow once Deadlock is known closed and do not match yet. Only the library makes one. */
@ConsistentCopyVisibility
data class SavedChoices internal constructor(val target: ProfileBook)

sealed interface ProfileSeed {
    data object Empty : ProfileSeed
    data class Copy(val id: ProfileId) : ProfileSeed
}

data class NewProfile(val name: String, val colour: ProfileColour, val seed: ProfileSeed, val activate: Boolean = true)
data class SwitchSummary(val profile: ProfileId, val off: Int, val on: Int)

sealed interface Undoable {
    /** [switchedTo] names the profile made active in its place, when it was the active one. */
    data class Deleted(val name: String, val switchedTo: String?) : Undoable
    /** [count] mods turned on, or off, together, in [profile] when that is not the active one. */
    data class Turned(val enabled: Boolean, val count: Int, val profile: String?) : Undoable
    /** [installIds] uninstalled together; [title] is the one mod's, when there is one. */
    data class Uninstalled(val installIds: Set<String>, val title: String?) : Undoable
}

class UndoToken internal constructor(val undoes: Undoable)

sealed interface MutationResult<out T> {
    data class Done<T>(val value: T) : MutationResult<T>
    data class Refused(val problem: LibraryProblem) : MutationResult<Nothing>
}
