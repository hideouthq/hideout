package gg.hideouthq.feature.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import kotlinx.coroutines.CancellationException

@Stable
class FolderOpeningState(private val opener: FolderOpener) : FolderOpener {
    data class Request(val root: GameRoot, val folder: SetFolder) {
        val path: String
            get() {
                val separator = if ('\\' in root.path) "\\" else "/"
                return root.path.trimEnd('/', '\\') + separator + folder.segments.joinToString(separator)
            }
    }

    var failed: Request? by mutableStateOf(null)
        private set
    var opening by mutableStateOf(false)
        private set

    override suspend fun open(root: GameRoot, folder: SetFolder): Boolean {
        if (opening) return false
        opening = true
        return try {
            val opened = try {
                opener.open(root, folder)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            failed = if (opened) null else Request(root, folder)
            opened
        } finally {
            opening = false
        }
    }

    suspend fun retry() {
        failed?.let { open(it.root, it.folder) }
    }

    fun dismiss(request: Request) {
        if (failed == request) failed = null
    }
}
