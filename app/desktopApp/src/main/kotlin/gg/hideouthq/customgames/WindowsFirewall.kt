package gg.hideouthq.customgames

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinReg
import gg.hideouthq.core.customgames.FirewallDecision
import java.nio.file.Path

/**
 * Windows Firewall as a standard user sees it: the rules in its registry key, which Users may read, and whether its
 * question is on screen. Windows adds Block rules for a program the moment it asks about it, and turns them to Allow
 * if the player allows it, so a rule naming the program means it never asks about that path again.
 */
internal class WindowsFirewall(
    private val rules: () -> Collection<String>,
    val questionShown: () -> Boolean,
    private val environment: (String) -> String? = System::getenv,
) {
    fun decision(program: Path): FirewallDecision =
        if (rules().any { namesProgram(it, program) }) FirewallDecision.Decided else FirewallDecision.Unknown

    /** A rule reads as `v2.10|Action=Block|Active=TRUE|Dir=In|Protocol=17|Profile=Private|App=C:\users\…\modlock-host.exe|Name=…|`. */
    private fun namesProgram(rule: String, program: Path): Boolean {
        val fields = rule.split('|').mapNotNull { field -> field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val app = fields["App"]?.let { EnvironmentName.replace(it) { name -> environment(name.groupValues[1]) ?: name.value } }
        return fields["Dir"] == "In" && fields["Active"] == "TRUE" && app.equals(program.toString(), ignoreCase = true)
    }

    companion object {
        val OfThisComputer = WindowsFirewall(::registryRules, ::questionOnScreen)
    }
}

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
