package gg.hideouthq.nativepackage

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun writeAppProperties(id: String, version: String, @Output outputDir: Path) {
    val content = "version=$version\n"
    val file = outputDir / (id.replace('.', '/') + "/app.properties")
    if (!file.exists() || file.readText() != content) {
        file.parent.createDirectories()
        file.writeText(content)
    }
}
