package gg.hideouthq.tools

import gg.hideouthq.deadlockstats.DesktopStatsPlatform
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    require(args.size == 1 && args[0] in setOf("status", "install", "uninstall")) {
        "Usage: SteamBridgeInstallCheck status|install|uninstall"
    }
    DesktopStatsPlatform("Hideout/1.0 SteamBridgeInstallCheck").use { platform ->
        val installer = platform.steamTrackingInstaller
        val status = when (args[0]) {
            "install" -> installer.install()
            "uninstall" -> installer.uninstall()
            else -> installer.inspect()
        }
        println("supported=${status.supported} installed=${status.installed} reason=${status.reason}")
        if (args[0] == "install") check(status.supported && status.installed) { status.reason.orEmpty() }
    }
}
