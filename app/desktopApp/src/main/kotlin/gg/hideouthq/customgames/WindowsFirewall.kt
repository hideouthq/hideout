package gg.hideouthq.customgames

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinReg
import gg.hideouthq.core.customgames.FirewallDecision
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Windows Firewall as a standard user sees it: the rules in its registry key, which Users may read, the profiles of the
 * networks in use, and whether its question is on screen. As it asks about a program, Windows adds Block rules for its
 * TCP and UDP on the network's profile, and turns them to Allow if the player allows it, so a rule naming the program,
 * its UDP and every profile in use means it doesn't ask about that path again here.
 */
internal class WindowsFirewall(
    private val rules: () -> Collection<String>,
    /** Null when Windows can't say, and then a rule on any profile counts. */
    val profiles: () -> Set<FirewallProfile>?,
    val questionShown: () -> Boolean,
    private val environment: (String) -> String? = System::getenv,
) {
    fun decision(program: Path, on: Set<FirewallProfile>? = profiles()): FirewallDecision {
        val covered = rules().mapNotNull { coveredProfiles(it, program) }
        val decided = if (on == null) covered.isNotEmpty() else on.all { profile -> covered.any { profile in it } }
        return if (decided) FirewallDecision.Decided else FirewallDecision.Unknown
    }

    /**
     * The profiles on which [rule] answers for the server's UDP, or null if it doesn't. A rule reads as
     * `v2.10|Action=Block|Active=TRUE|Dir=In|Protocol=17|Profile=Private|App=C:\users\…\modlock-host.exe|Name=…|`,
     * with a field left out for any protocol or profile and `Profile` repeated for several.
     */
    private fun coveredProfiles(rule: String, program: Path): Set<FirewallProfile>? {
        val fields = rule.split('|').mapNotNull { field -> field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        fun all(key: String) = fields.filter { it.first == key }.map { it.second }
        fun one(key: String) = all(key).singleOrNull()
        val app = one("App")?.let { EnvironmentName.replace(it) { name -> environment(name.groupValues[1]) ?: name.value } }
        val udp = one("Protocol").let { it == null || it == UdpProtocol }
        if (one("Dir") != "In" || one("Active") != "TRUE" || !udp || !app.equals(program.toString(), ignoreCase = true)) return null
        val profiles = all("Profile")
        return if (profiles.isEmpty()) FirewallProfile.entries.toSet() else profiles.mapNotNull { name -> FirewallProfile.entries.firstOrNull { it.name == name } }.toSet()
    }

    companion object {
        val OfThisComputer = WindowsFirewall(::registryRules, ::connectedProfiles, ::questionOnScreen)
    }
}

internal enum class FirewallProfile { Domain, Private, Public }

private const val UdpProtocol = "17"

/** `Get-NetConnectionProfile` needs no administrator, and prints its categories by their names in every language. */
private fun connectedProfiles(): Set<FirewallProfile>? = try {
    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Get-NetConnectionProfile | ForEach-Object { [string]${'$'}_.NetworkCategory }")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    if (process.waitFor(ProfilesLimit.inWholeMilliseconds, TimeUnit.MILLISECONDS)) connectedProfilesOf(output.get()) else null.also { process.destroyForcibly() }
} catch (e: IOException) {
    null
}

/** Null when no network is in use or a category is one Hideout doesn't know. */
internal fun connectedProfilesOf(categories: String): Set<FirewallProfile>? {
    val profiles = categories.lines().map { it.trim() }.filter { it.isNotEmpty() }.map {
        when (it) {
            "DomainAuthenticated" -> FirewallProfile.Domain
            "Private" -> FirewallProfile.Private
            "Public" -> FirewallProfile.Public
            else -> return null
        }
    }
    return profiles.toSet().takeIf { it.isNotEmpty() }
}

private val ProfilesLimit = 10.seconds

private val EnvironmentName = Regex("%([^%]+)%")

private const val RulesKey = """SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters\FirewallPolicy\FirewallRules"""

private fun registryRules(): Collection<String> = try {
    Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, RulesKey).values.filterIsInstance<String>()
} catch (e: Win32Exception) {
    emptyList()
}

/** The question's window on Windows 11 (measured): titled "Windows Security", of this class, in `PickerHost.exe`. */
private const val QuestionClass = "Shell_SystemDialogProxy"

private fun questionOnScreen(): Boolean =
    generateSequence(User32.INSTANCE.FindWindowEx(null, null, QuestionClass, null)) { User32.INSTANCE.FindWindowEx(null, it, QuestionClass, null) }
        .any { User32.INSTANCE.IsWindowVisible(it) }
