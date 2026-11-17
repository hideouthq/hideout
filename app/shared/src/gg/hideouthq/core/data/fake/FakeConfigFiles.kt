package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.ConfigFiles
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot

class FakeConfigFiles(private val files: InMemoryGameFiles) : ConfigFiles {
    val edited = mutableListOf<String?>()
    val shown = mutableListOf<CitadelPath>()
    var opens = true

    override suspend fun edit(root: GameRoot, path: CitadelPath): Boolean {
        if (opens) edited += files.bytes(root, "game/citadel/${path.value}")?.decodeToString()
        return opens
    }

    override suspend fun show(root: GameRoot, path: CitadelPath): Boolean {
        if (opens) shown += path
        return opens
    }
}
