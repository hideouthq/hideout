package gg.hideouthq.core.library

/** What taking over from another manager does with one of its folders under `game/citadel`. */
enum class RootRole {
    /** Its sets load while the folder is mounted: they are adopted, and the folder is unmounted. */
    AdoptOn,

    /** Its sets never load, since another manager parks disabled mods there: they are adopted off. */
    AdoptOff,

    /** Derived from other files, or kept for one server: the mount goes and the files stay where they are. */
    UnmountOnly,
}

enum class ForeignRoot(pattern: String, val role: RootRole, val managers: Set<Manager>) {
    Addons("addons", RootRole.AdoptOn, setOf(Manager.DeadlockModManager, Manager.Grimoire)),

    Overflow("addons[0-9]+", RootRole.AdoptOn, setOf(Manager.DeadlockModManager, Manager.Grimoire)),

    ProfileFolder("addons/profile_[^/]+", RootRole.AdoptOn, setOf(Manager.DeadlockModManager)),

    OverflowProfileFolder("addons[0-9]+/profile_[^/]+", RootRole.AdoptOn, setOf(Manager.DeadlockModManager)),

    Priority("grimoire", RootRole.AdoptOn, setOf(Manager.Grimoire)),

    Parking("addons/\\.disabled", RootRole.AdoptOff, setOf(Manager.Grimoire)),

    Localization("dmm_localization_[^/]+", RootRole.UnmountOnly, setOf(Manager.DeadlockModManager)),

    Server("addons/server_[^/]+", RootRole.UnmountOnly, setOf(Manager.DeadlockModManager)),
    ;

    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    companion object {
        /** The row [folder] falls under, or null when it is no other manager's folder. */
        fun of(folder: CitadelPath): ForeignRoot? = entries.firstOrNull { it.regex.matches(folder.value) }

        fun keptBy(manager: Manager): List<ForeignRoot> = entries.filter { manager in it.managers }
    }
}
