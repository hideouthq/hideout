package gg.hideouthq.core.sharing

import gg.hideouthq.AppContainer
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallLinkTest {
    private val ivy = InstallLink.File(SubmissionRef(SubmissionKind.Mod, 691_863), 1_851_504)

    @Test
    fun everyFormOfAOneClickLinkReadsToItsFile() {
        listOf(
            "hideout:https://gamebanana.com/mmdl/1851504,Mod,691863",
            "hideout://https://gamebanana.com/mmdl/1851504,Mod,691863",
            "HIDEOUT:https://www.gamebanana.com/dl/1851504,mod,691863/",
            "hideout:http://gamebanana.com/mmdl/1851504,Mod,691863?src=1click",
            "hideout:https:/gamebanana.com/mmdl/1851504,Mod,691863",
            "hideout:https://gamebanana.com/mmdl/1851504%2CMod%2C691863",
            " hideout:https://gamebanana.com/mmdl/1851504,Mod,691863\n",
        ).forEach { assertEquals(ivy, InstallLink.parse(it), it) }
        assertEquals(InstallLink.File(SubmissionRef(SubmissionKind.Sound, 80_002), 77_001), InstallLink.parse("hideout:https://gamebanana.com/mmdl/77001,Sound,80002"))
    }

    @Test
    fun aLinkToAnythingButAModOrASoundHasNothingToInstallAndAnythingElseIsUnreadable() {
        listOf("Tool", "Wip", "Question").forEach { assertEquals(InstallLink.Refused.NotAMod, InstallLink.parse("hideout:https://gamebanana.com/mmdl/1851504,$it,12345"), it) }
        listOf(
            "hideout:https://gamebanana.com/mmdl/1851504",
            "hideout:https://gamebanana.com/mmdl/0,Mod,691863",
            "hideout:https://gamebanana.com/mmdl/abc,Mod,691863",
            "hideout:https://gamebanana.com/mods/691863",
            "hideout:https://example.com/mmdl/1851504,Mod,691863",
            "hideout:https://gamebanana.com.example/mmdl/1851504,Mod,691863",
        ).forEach { assertEquals(InstallLink.Refused.Unreadable, InstallLink.parse(it), it) }
    }

    @Test
    fun onlyAHideoutLinkCarryingAWebAddressIsOne() {
        listOf("hideout:https://gamebanana.com/mmdl/1851504,Mod,691863", "hideout://https://example.com/x", "Hideout:http://gamebanana.com/").forEach { assertTrue(InstallLink.isAppLink(it), it) }
        listOf("hideout://join/P95-MAN-26D", "hideout://p/K7Q2M9XD", "https://gamebanana.com/mmdl/1851504,Mod,691863", "deadlock-mod-manager:https://gamebanana.com/mmdl/1851504,Mod,691863")
            .forEach { assertFalse(InstallLink.isAppLink(it), it) }
    }

    @Test
    fun aOneClickLinkWaitsForTheShellAndReachesNeitherCustomGamesNorTheProfileImport() = runTest {
        val container = AppContainer.fake()
        val games = checkNotNull(container.customGames)
        container.openAppLink("hideout:https://gamebanana.com/mmdl/1851504,Tool,12345")
        container.openAppLink("hideout:https://gamebanana.com/mmdl/1851504,Mod,691863")
        assertEquals(ivy, container.installLinks.incoming.first(), "the newest link waits until the shell takes it")
        container.openAppLink("hideout:https://gamebanana.com/mmdl/garbage")
        assertEquals(InstallLink.Refused.Unreadable, container.installLinks.incoming.first())

        container.openAppLink("hideout://whatever")
        assertEquals(1L, games.lastLink.filterNotNull().first(), "custom games hears only of its own link")
        assertNull(withTimeoutOrNull(100) { container.profileImports.incoming.first() })
    }
}
