package gg.hideouthq.core.data

import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder

/** The file at [path] under `<root>/game/citadel`: a name another manager wrote, or the Hideout name it becomes. */
data class CitadelFile(val root: GameRoot, val path: CitadelPath)

/** [CitadelFile.path] under the game root, as reports name files. */
val CitadelFile.gamePath: String get() = "${SetFolder.GameSegments.joinToString("/")}/${path.value}"

interface GameFiles : TwinLinks<CitadelFile> {
    companion object {
        val Unavailable: GameFiles = object : GameFiles {
            private val reason = "taking over another manager's files is not available here"

            override suspend fun link(existing: CitadelFile, created: CitadelFile): LinkResult = LinkResult.Failed(reason)

            override suspend fun inspect(entry: CitadelFile, witness: CitadelFile): EntryState = EntryState.Unreadable(reason)

            override suspend fun removeTwin(entry: CitadelFile, witness: CitadelFile, expected: FileImage): Removal = Removal.Failed(reason)

            override suspend fun removeExact(entry: CitadelFile, expected: FileImage): Removal = Removal.Failed(reason)
        }
    }
}
