package gg.hideouthq.core.autoexec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandLibraryTest {
    @Test
    fun aCommandIsFoundByItsNameIgnoringCase() {
        assertTrue(CommandLibrary.has("FPS_MAX"))
        assertEquals(CommandCategory.HudAndUi, CommandLibrary.find("CL_ShowFPS")?.category)
    }

    @Test
    fun everySuggestedLineReadsAsTheCommandItAdds() {
        for (command in CommandLibrary.Commands) {
            val row = AutoexecDocument.parse("${command.name} ${command.suggested}".encodeToByteArray()).commands.single()
            assertEquals(command.name to command.suggested, row.ref.name to row.value)
            assertTrue(row.canTurnOff, command.name)
        }
    }
}
