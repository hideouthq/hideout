package gg.hideouthq

import gg.hideouthq.data.AppDirs
import java.util.concurrent.TimeUnit

/** Compose Desktop has no API for the system's "reduce motion" setting, so this reads it the way each desktop stores it. */
internal object SystemMotionPreference {
    private const val OverrideProperty = "hideout.reducedMotion"
    private const val CommandTimeoutSeconds = 2L

    fun prefersReducedMotion(): Boolean {
        System.getProperty(OverrideProperty)?.let { return it.toBoolean() }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return runCatching {
            when {
                "linux" in os -> kdeAnimationsOff() ?: gnomeAnimationsOff() ?: false
                OnWindows -> windowsAnimationsOff()
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun kdeAnimationsOff(): Boolean? {
        val file = AppDirs.configHome().resolve("kdeglobals").takeIf { it.isFile } ?: return null
        val factor = file.useLines { lines ->
            lines.map { it.trim() }.firstOrNull { it.startsWith("AnimationDurationFactor=") }?.substringAfter('=')
        } ?: return null
        return factor.toFloatOrNull() == 0f
    }

    private fun gnomeAnimationsOff(): Boolean? =
        run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")?.let { it == "false" }

    private fun windowsAnimationsOff(): Boolean =
        run("reg", "query", "HKCU\\Control Panel\\Desktop\\WindowMetrics", "/v", "MinAnimate")
            ?.lineSequence()?.firstOrNull { "MinAnimate" in it }?.trim()?.endsWith("0") == true

    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(CommandTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        return process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 }
    }
}
