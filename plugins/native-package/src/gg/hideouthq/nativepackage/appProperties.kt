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

internal const val PostHogKeyVariable = "HIDEOUT_BUILD_POSTHOG_KEY"

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun writeAppProperties(id: String, version: String, @Output outputDir: Path) =
    writeAppPropertiesFile(id, version, outputDir, System::getenv)

internal fun writeAppPropertiesFile(id: String, version: String, outputDir: Path, environment: (String) -> String?) {
    val key = environment(PostHogKeyVariable)?.trim()?.ifEmpty { null }
    check(key == null || key.matches(Regex("phc_[A-Za-z0-9]+"))) { "$PostHogKeyVariable must be a PostHog project capture key" }
    val content = buildString {
        append("version=$version\n")
        if (key != null) append("posthog.projectKey=$key\n")
    }
    val file = outputDir / (id.replace('.', '/') + "/app.properties")
    if (!file.exists() || file.readText() != content) {
        file.parent.createDirectories()
        file.writeText(content)
    }
}
