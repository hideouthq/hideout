package gg.hideouthq.deadlockstats

import gg.hideouthq.library.SteamClient
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class SteamRuntimeServiceTest {
    @Test fun snapProbeRequiresSelectedAncestryAndTheSameBusOwner() {
        val home = Files.createTempDirectory("snap-runtime-service-")
        try {
            val root = home.resolve("Steam"); val proc = home.resolve("proc")
            val target = SteamInstallationTarget("snap", SteamClient.Snap, root, root, SteamArchitecture.I386,
                false, null, SteamLaunchContext.Snap)
            Files.createDirectories(target.executable.parent); Files.writeString(target.executable, "fixture")
            val service = home.resolve("steam-runtime-launcher-service"); Files.writeString(service, "fixture")
            fun process(pid: Int, parent: Int, exe: java.nio.file.Path, command: String) {
                val path = proc.resolve(pid.toString()); Files.createDirectories(path)
                Files.createSymbolicLink(path.resolve("exe"), exe)
                Files.writeString(path.resolve("status"), "PPid:\t$parent\n")
                Files.writeString(path.resolve("stat"), "$pid (process) S " + List(18) { "0" }.joinToString(" ") + " 123\n")
                Files.writeString(path.resolve("cmdline"), command)
            }
            process(100, 1, target.executable, "steam\u0000")
            process(200, 100, service, "steam-runtime-launcher-service\u0000--alongside-steam\u0000")
            val runner = root.resolve("ubuntu12_32/steam-runtime/run.sh")
            Files.createDirectories(runner.parent)
            fun reply(pid: Int) {
                Files.writeString(runner, "#!/bin/sh\ncase \"\$6\" in\n" +
                    "org.freedesktop.DBus.GetNameOwner) printf '   :1.9\\n';;\n" +
                    "org.freedesktop.DBus.GetConnectionUnixProcessID) [ \"\$7\" = string::1.9 ] || exit 1; printf '   uint32 $pid\\n';;\n" +
                    "*) exit 1;;\nesac\n")
                runner.toFile().setExecutable(true)
            }
            reply(200)
            val command = target.runningSnapCommand(listOf("/private/probe", "argument with spaces"), proc)
            assertEquals(listOf(runner.toString(), "steam-runtime-launch-client",
                "--bus-name=:1.9", "--", "/private/probe", "argument with spaces"), command)
            reply(201)
            assertEquals("runtime_service_identity_changed", assertFailsWith<IllegalArgumentException> {
                target.runningSnapCommand(listOf("probe"), proc)
            }.message)
            reply(200)
            Files.writeString(proc.resolve("200/status"), "PPid:\t1\n")
            assertEquals("snap_runtime_service_missing_or_ambiguous", assertFailsWith<IllegalArgumentException> {
                target.runningSnapCommand(listOf("probe"), proc)
            }.message)
        } finally { home.toFile().deleteRecursively() }
    }
}
