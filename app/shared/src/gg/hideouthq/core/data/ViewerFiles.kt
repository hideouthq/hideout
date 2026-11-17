package gg.hideouthq.core.data

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Files the 3D viewer reads and writes outside the game: the user's own backdrop, copied into Hideout's data folder,
 * and pictures of the stage, saved where the user chooses.
 */
interface ViewerFiles {
    /** Asks for a PNG, JPG or WebP picture and copies it into Hideout's data folder; null when none was chosen. */
    suspend fun importBackdrop(title: String): String?

    /** A backdrop [importBackdrop] copied, or null once it is gone. */
    suspend fun readBackdrop(path: String): ByteArray?

    /**
     * Asks where to save [image] as a PNG, offering Pictures/Hideout/[fileName], and saves it there; null when the user
     * cancels. A file already there is replaced only once the user confirms it. A picture that cannot be written throws.
     */
    suspend fun savePicture(image: ImageBitmap, title: String, fileName: String): String?

    /** Shows a saved picture in the file manager without blocking the UI; throws if the handoff fails. */
    suspend fun reveal(path: String)

    object Unavailable : ViewerFiles {
        override suspend fun importBackdrop(title: String): String? = null
        override suspend fun readBackdrop(path: String): ByteArray? = null
        override suspend fun savePicture(image: ImageBitmap, title: String, fileName: String): String? = null
        override suspend fun reveal(path: String) = Unit
    }
}
