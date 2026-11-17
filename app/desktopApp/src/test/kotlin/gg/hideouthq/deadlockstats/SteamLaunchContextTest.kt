package gg.hideouthq.deadlockstats

import gg.hideouthq.library.SteamClient
import gg.hideouthq.library.SteamInstall
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SteamLaunchContextTest {
    @Test fun clientOverrideSelectsTheSameArchitectureAsTheSteamLauncher() {
        val root = Files.createTempDirectory("steam selection ")
        try {
            for (architecture in SteamArchitecture.entries) {
                val executable = root.resolve("${architecture.clientDirectory}/steam")
                Files.createDirectories(executable.parent)
                val header = ByteArray(20)
                byteArrayOf(127, 69, 76, 70).copyInto(header)
                header[4] = if (architecture == SteamArchitecture.I386) 1 else 2
                header[5] = 1
                header[18] = if (architecture == SteamArchitecture.I386) 3 else 62
                Files.write(executable, header)
            }
            val installs = listOf(SteamInstall(root.toString(), SteamClient.Desktop))
            fun selected(override: String?) = steamTrackingTargets(installs, null, override).single().architecture
            assertEquals(SteamArchitecture.I386, selected(null))
            for (override in listOf("steamrt64", "steamrt3c", "steamrt"))
                assertEquals(SteamArchitecture.X86_64, selected(override))
            Files.createFile(root.resolve(".steam-enable-steamrt64-client"))
            assertEquals(SteamArchitecture.X86_64, selected(null))
            for (override in listOf("ubuntu12_32", "scout", "ldlp"))
                assertEquals(SteamArchitecture.I386, selected(override))
            Files.delete(root.resolve("steamrt64/steam"))
            assertEquals(SteamArchitecture.X86_64, selected("steamrt64"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun packageCommandsPreservePathsAndArgumentsWithoutAHostShell() {
        val args = listOf("/home/a b/.hideout/probe", "a b", "\$value", "steam://test")
        assertEquals(args, SteamLaunchContext.Native.command(args))
        assertEquals(listOf("/nix/store/fhs/bin/steam-run") + args,
            SteamLaunchContext.Fhs(Path.of("/nix/store/fhs/bin/steam-run")).command(args))
        assertEquals(listOf("flatpak", "run", "--command=${args.first()}", "com.valvesoftware.Steam") + args.drop(1),
            SteamLaunchContext.Flatpak.command(args))
        assertFailsWith<IllegalStateException> { SteamLaunchContext.Snap.command(args) }
        assertFailsWith<IllegalArgumentException> { SteamLaunchContext.Fhs(null).command(args) }
    }

    @Test fun probeUsesTheSelectedClientEnvironmentAndReportsMissingComponents() {
        val root = Files.createTempDirectory("steam runtime ")
        try {
            val target = SteamInstallationTarget("fixture", SteamClient.Desktop, root, root,
                SteamArchitecture.X86_64, false, null)
            val bundle = target.storage.resolve("bundle")
            val error = assertFailsWith<IllegalArgumentException> { target.probeCommand(bundle) }
            assertEquals("missing_client_runtime", error.message)
            val runtime = root.resolve("steamrt64/pv-run.sh")
            Files.createDirectories(runtime.parent); Files.writeString(runtime, "#!/bin/sh\nexit 0\n")
            runtime.toFile().setExecutable(true)
            assertEquals(listOf(runtime.toString(), "--", bundle.resolve("bridge_probe").toString(),
                target.executable.toString(), target.module.toString(), bundle.resolve("libhideout_steam_bridge.so").toString()),
                target.probeCommand(bundle))
        } finally { root.toFile().deleteRecursively() }
    }
}
