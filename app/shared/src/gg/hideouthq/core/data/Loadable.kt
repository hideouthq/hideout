package gg.hideouthq.core.data

/** A value that comes from a remote source: still on its way, here, or not coming. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<out T>(val value: T) : Loadable<T>

    data class Failed(val error: DataError) : Loadable<Nothing>
}

/** Why data could not be loaded, in terms a screen can act on. */
enum class DataError {
    /** The source could not be reached; retrying later may help. */
    Offline,

    /** The source answered but could not serve the data (an error or a response we cannot read). */
    Unavailable,

    /** The source has no such item. */
    NotFound,
}
