package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.FirewallDecision
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

private val Server = Path.of("""C:\Users\nori\AppData\Local\gg.hideouthq.Hideout\customgames-server\modlock-host.exe""")

class WindowsFirewallTest {
    private fun decision(vararg rules: String, on: Set<FirewallProfile>? = setOf(FirewallProfile.Private)) =
        WindowsFirewall({ rules.toList() }, { on }, { false }, mapOf("LOCALAPPDATA" to """C:\Users\nori\AppData\Local""")::get).decision(Server)

    @Test
    fun theBlockRulesWindowsAddsAsItAsksDecideIt() {
        val asked = """v2.10|Action=Block|Active=TRUE|Dir=In|Protocol=17|Profile=Private|App=C:\users\nori\appdata\local\gg.hideouthq.hideout\customgames-server\modlock-host.exe|Name=modlock-host|Desc=modlock-host|"""
        assertEquals(FirewallDecision.Decided, decision(asked))
        assertEquals(FirewallDecision.Decided, decision(asked.replace("Action=Block", "Action=Allow")), "and so do the ones an Allow leaves")
    }

    @Test
    fun aRuleForAnotherProgramOrWayOrOneTurnedOffDecidesNothing() {
        val rule = """v2.33|Action=Block|Active=TRUE|Dir=In|App=$Server|Name=Game server|"""
        assertEquals(FirewallDecision.Unknown, decision())
        assertEquals(FirewallDecision.Unknown, decision(rule.replace("Dir=In", "Dir=Out")))
        assertEquals(FirewallDecision.Unknown, decision(rule.replace("Active=TRUE", "Active=FALSE")))
        assertEquals(FirewallDecision.Unknown, decision(rule.replace("customgames-server", """customgames\8c0a74b2""")))
    }

    @Test
    fun aRuleNamingAFolderByItsVariableCounts() {
        assertEquals(FirewallDecision.Decided, decision("""v2.33|Action=Allow|Active=TRUE|Dir=In|App=%LOCALAPPDATA%\gg.hideouthq.Hideout\customgames-server\modlock-host.exe|"""))
    }

    @Test
    fun onlyARuleForTheServersUdpOnTheNetworkInUseDecidesIt() {
        val rule = """v2.10|Action=Allow|Active=TRUE|Dir=In|Protocol=17|Profile=Private|App=$Server|Name=modlock-host|"""
        assertEquals(FirewallDecision.Unknown, decision(rule.replace("Protocol=17", "Protocol=6")), "the server's port is UDP")
        assertEquals(FirewallDecision.Unknown, decision(rule, on = setOf(FirewallProfile.Public)), "Windows asks again on a public network")
        assertEquals(FirewallDecision.Unknown, decision(rule, on = setOf(FirewallProfile.Private, FirewallProfile.Public)), "every network in use needs a rule")
        assertEquals(FirewallDecision.Decided, decision(rule, rule.replace("Profile=Private", "Profile=Public"), on = setOf(FirewallProfile.Private, FirewallProfile.Public)))
        assertEquals(FirewallDecision.Decided, decision(rule.replace("Profile=Private", "Profile=Domain|Profile=Public"), on = setOf(FirewallProfile.Public)))
        assertEquals(FirewallDecision.Decided, decision(rule.replace("Protocol=17|Profile=Private|", ""), on = setOf(FirewallProfile.Public)), "a rule with neither covers all")
        assertEquals(FirewallDecision.Decided, decision(rule.replace("Profile=Private", "Profile=Public"), on = null), "any network, when Hideout can't read which is in use")
    }

    @Test
    fun theNetworksInUseReadFromWindowsCategories() {
        assertEquals(setOf(FirewallProfile.Private, FirewallProfile.Domain), connectedProfilesOf("Private\r\nDomainAuthenticated\r\n"))
        assertEquals(null, connectedProfilesOf(""), "no network in use says nothing of the next")
        assertEquals(null, connectedProfilesOf("Something new\r\n"))
    }
}
