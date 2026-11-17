package gg.hideouthq.deadlockstats

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SteamBridgeInstallerTest {
    @Test fun automaticGameDiscoverySelectsTheUniqueOwnerAndKeepsMultipleGamesAmbiguous(): Unit = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            fun game(f: Fixture): gg.hideouthq.core.library.GameRoot {
                val folder = f.steam.resolve("steamapps/common/Deadlock")
                Files.createDirectories(folder.resolve("game/citadel"))
                Files.writeString(folder.resolve("game/citadel/gameinfo.gi"), "GameInfo {}")
                Files.writeString(f.steam.resolve("steamapps/appmanifest_1422450.acf"),
                    "\"AppState\" { \"appid\" \"1422450\" \"installdir\" \"Deadlock\" }")
                return gg.hideouthq.core.library.GameRoot(folder.toRealPath().toString())
            }
            fun install(f: Fixture) = gg.hideouthq.library.SteamInstall(f.steam.toAbsolutePath().toString(), gg.hideouthq.library.SteamClient.Desktop)
            val chosen = game(first)
            val installs = listOf(install(first), install(second))
            val automatic = discoverSteamTrackingTargets(installs, null)
            assertEquals(first.steam.toRealPath(), automatic.single { it.ownsGame }.root)
            assertEquals(chosen.path, automatic.first().game)
            game(second)
            assertTrue(discoverSteamTrackingTargets(installs, null).none { it.ownsGame })
            assertEquals(first.steam.toRealPath(), discoverSteamTrackingTargets(installs, chosen).single { it.ownsGame }.root)
        } }
    }

    @Test fun runtimeFailuresAndMalformedProbeResponsesAreDistinct(): Unit = runBlocking {
        Fixture().use { f ->
            val runner = f.steam.resolve("ubuntu12_32/steam-runtime/run.sh")
            Files.createDirectories(runner.parent)
            fun run(body: String): SteamBridgeReport {
                Files.writeString(runner, "#!/bin/sh\n$body\n")
                runner.toFile().setExecutable(true)
                return probeSteamBridge(f.target(), f.steam.toAbsolutePath())
            }
            assertEquals("runtime_command_failed", run("exit 1").reason)
            assertEquals("invalid_probe_response", run("echo broken; exit 0").reason)
            assertEquals("invalid_probe_response", run("echo '{\"version\":1,\"compatible\":true,\"reason\":\"compatible\"}'; exit 1").reason)
            assertEquals("map_layout_drift", run("echo '{\"version\":1,\"compatible\":false,\"reason\":\"map_layout_drift\"}'; exit 1").reason)
            assertTrue(run("echo '{\"version\":1,\"compatible\":true,\"reason\":\"compatible\"}'").compatible)
        }
    }

    @Test fun aCompatibleArchitectureUpdateRefreshesTheOwnedInstallation(): Unit = runBlocking {
        Fixture().use { f ->
            var target = f.target()
            Files.writeString(f.apps.resolve("steam.desktop"), f.original)
            Files.writeString(f.auto.resolve("steam.desktop"), f.original)
            val installer = LinuxSteamTrackingInstaller({ listOf(target) }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { _, _ -> SteamBridgeReport(1, true, "compatible") })
            assertTrue(installer.install().installed)
            val before = Files.readString(f.apps.resolve("steam.desktop"))
            target = target.copy(architecture = SteamArchitecture.X86_64)
            assertTrue(installer.inspect().let { it.supported && it.installed })
            val after = Files.readString(f.apps.resolve("steam.desktop"))
            assertNotEquals(before, after)
            assertEquals(2, after.lineSequence().count { "launch.sh\" --" in it }, "Each desktop action is wrapped once")
            installer.uninstall()
            assertEquals(f.original, Files.readString(f.apps.resolve("steam.desktop")))
            assertEquals(f.original, Files.readString(f.auto.resolve("steam.desktop")))
        }
    }

    @Test fun anIncompatibleUpdateAndFinalProbeDriftKeepThePreviousInstallation(): Unit = runBlocking {
        Fixture().use { f ->
            var target = f.target()
            var compatible = true
            var finalDrift = false
            var probes = 0
            val installer = LinuxSteamTrackingInstaller({ listOf(target) }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { _, _ -> SteamBridgeReport(1, compatible && (!finalDrift || ++probes == 1), "client_changed") })
            installer.install()
            val receipt = f.data.resolve("steam-bridge-install/receipt.json")
            val beforeReceipt = Files.readString(receipt)
            val beforeLauncher = Files.readString(f.apps.resolve("steam.desktop"))
            target = target.copy(architecture = SteamArchitecture.X86_64)
            compatible = false
            assertTrue(installer.inspect().let { !it.supported && it.installed })
            assertEquals(beforeReceipt, Files.readString(receipt))
            assertEquals(beforeLauncher, Files.readString(f.apps.resolve("steam.desktop")))
            compatible = true
            finalDrift = true
            assertFailsWith<IllegalStateException> { installer.inspect() }
            assertEquals(beforeReceipt, Files.readString(receipt))
            assertEquals(beforeLauncher, Files.readString(f.apps.resolve("steam.desktop")))
            finalDrift = false
            installer.uninstall()
        }
    }

    @Test fun anArchitectureUpdatePreservesAUserEditedLauncher(): Unit = runBlocking {
        Fixture().use { f ->
            var target = f.target()
            val installer = LinuxSteamTrackingInstaller({ listOf(target) }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { _, _ -> SteamBridgeReport(1, true, "compatible") })
            installer.install()
            val desktop = f.apps.resolve("steam.desktop")
            val edited = Files.readString(desktop) + "# user edit\n"
            Files.writeString(desktop, edited)
            target = target.copy(architecture = SteamArchitecture.X86_64)
            val status = installer.inspect()
            assertTrue(status.installed)
            assertFalse(status.supported)
            assertContains(status.reason!!, "needs repair")
            assertEquals(edited, Files.readString(desktop))
            installer.uninstall()
            assertEquals(edited, Files.readString(desktop))
        }
    }

    @Test fun anUpdatingExecutableKeepsTheSelectionAndOwnedLaunchers(): Unit = runBlocking {
        Fixture().use { f ->
            val installs = listOf(gg.hideouthq.library.SteamInstall(f.steam.toAbsolutePath().toString(),
                gg.hideouthq.library.SteamClient.Desktop))
            val discover = { steamTrackingTargets(installs, null, null) }
            val installer = LinuxSteamTrackingInstaller({ discover() }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { target, _ ->
                    val compatible = SteamArchitecture.read(target.executable) == target.architecture
                    SteamBridgeReport(1, compatible, if (compatible) "compatible" else "unsupported_target")
                })
            val target = discover().single()
            installer.select(target.id)
            assertTrue(installer.install().installed)
            val desktop = Files.readString(f.apps.resolve("steam.desktop"))
            val updating = target.executable.resolveSibling("steam.updating")
            Files.move(target.executable, updating)
            val status = installer.inspect()
            assertEquals(target.id, status.selectedId)
            assertTrue(status.installed)
            assertFalse(status.supported)
            assertEquals(desktop, Files.readString(f.apps.resolve("steam.desktop")))
            Files.move(updating, target.executable)
            assertTrue(installer.inspect().let { it.supported && it.installed })
            installer.uninstall()
        }
    }

    @Test fun aFailedAutostartWriteRestoresTheAlreadyWrittenDesktop(): Unit = runBlocking {
        Fixture().use { f ->
            val auto = f.auto.resolve("steam.desktop")
            Files.writeString(auto, f.original)
            Files.setPosixFilePermissions(f.auto, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
            try {
                assertFailsWith<java.io.IOException> { f.installer().install() }
                assertFalse(Files.exists(f.apps.resolve("steam.desktop")))
                assertFalse(Files.exists(f.data.resolve("steam-bridge-install/receipt.json")))
                assertEquals(f.original, Files.readString(auto))
            } finally {
                Files.setPosixFilePermissions(f.auto, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    @Test fun incompatibleFinalProbeLeavesExistingLaunchersAlone(): Unit = runBlocking {
        Fixture().use { f ->
            Files.writeString(f.apps.resolve("steam.desktop"), f.original)
            var probes = 0
            val installer = LinuxSteamTrackingInstaller({ listOf(f.target()) }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { _, _ -> if (++probes == 1) SteamBridgeReport(1, true, "compatible")
                    else SteamBridgeReport(1, false, "client_changed") })
            assertFailsWith<IllegalStateException> { installer.install() }
            assertEquals(2, probes)
            assertEquals(f.original, Files.readString(f.apps.resolve("steam.desktop")))
            assertFalse(Files.exists(f.data.resolve("steam-bridge-install/receipt.json")))
        }
    }

    @Test fun explicitSelectionCannotOverrideTheUniqueGameOwner(): Unit = runBlocking {
        Fixture().use { f ->
            val owner = f.target()
            val other = owner.copy(id = "unrelated", ownsGame = false)
            val installer = LinuxSteamTrackingInstaller({ listOf(owner, other) }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { _, _ -> SteamBridgeReport(1, true, "compatible") })
            installer.install()
            val before = Files.readString(f.apps.resolve("steam.desktop"))
            assertFailsWith<IllegalArgumentException> { installer.select(other.id) }
            assertTrue(installer.inspect().installed)
            assertEquals(before, Files.readString(f.apps.resolve("steam.desktop")))
            installer.uninstall()
        }
    }

    @Test fun desktopDiscoveryIncludesUserFlatpakExportsWhenTheSessionDoesNot() {
        Fixture().use { f ->
            val exported = f.root.resolve(".local/share/flatpak/exports/share/applications/com.valvesoftware.Steam.desktop")
            Files.createDirectories(exported.parent)
            Files.writeString(exported, f.original)
            assertEquals(exported.toAbsolutePath(), defaultSteamDesktop("com.valvesoftware.Steam.desktop", f.root.toAbsolutePath(), f.root.resolve("empty-system").toAbsolutePath().toString()))
        }
    }

    @Test fun ambiguousOwnershipRequiresAChoiceAndSwitchingRestoresOwnedEdits(): Unit = runBlocking {
        Fixture().use { f ->
            val first = f.target().copy(id = "first", ownsGame = true)
            val second = first.copy(id = "second")
            val installer = LinuxSteamTrackingInstaller({ listOf(first, second) }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { _, _ -> SteamBridgeReport(1, true, "compatible") })
            assertNull(installer.inspect().selectedId)
            assertFalse(installer.install().supported)
            assertFalse(Files.exists(f.apps.resolve("steam.desktop")))
            assertEquals("first", installer.select("first").selectedId)
            assertTrue(installer.install().installed)
            assertTrue(Files.exists(f.apps.resolve("steam.desktop")))
            assertEquals("second", installer.select("second").selectedId)
            assertFalse(Files.exists(f.apps.resolve("steam.desktop")))
            assertFalse(installer.inspect().installed)
            assertTrue(installer.install().installed)
            installer.uninstall()
        }
    }

    @Test fun mappedDeviceUsesBtrfsSuperblockAndTheContainingMount() {
        val mounts = listOf(
            "42 1 0:35 /@ / rw - btrfs /dev/disk rw,subvolid=256",
            "62 42 0:35 /@home /home rw - btrfs /dev/disk rw,subvolid=257",
            "63 62 0:53 / /home/test\\040files rw - tmpfs tmpfs rw",
            "64 42 8:1 / /other rw - ext4 /dev/other rw",
        )
        assertEquals(0L to 35L, linuxMappedDevice(Path.of("/home/user/Steam/steam"), 56, mounts))
        assertEquals(0L to 53L, linuxMappedDevice(Path.of("/home/test files/steam"), 53, mounts))
        assertEquals(8L to 1L, linuxMappedDevice(Path.of("/other/steam"), 0x801, mounts))
        assertEquals(0L to 35L, linuxMappedDevice(Path.of("/home/test files-other/steam"), 56, mounts))
        assertEquals(0L to 56L, linuxMappedDevice(Path.of("/home/user/steam"), 56, emptyList()))
        assertEquals(8L to 2L, linuxMappedDevice(Path.of("/home/user/steam"), 0x802,
            listOf(mounts[0], "62 42 8:2 / /home rw - ext4 /dev/home rw")))
    }

    private class Fixture : AutoCloseable {
        init {
            assumeTrue(System.getProperty("os.name").lowercase().contains("linux") &&
                System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64", "x86", "i386", "i686"))
        }
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/steam-installer-tests")), "fixture-")
        val steam = root.resolve("Steam")
        val data = root.resolve("data")
        val apps = root.resolve("applications")
        val auto = root.resolve("autostart")
        val system = root.resolve("system-steam.desktop")
        val original = "[Desktop Entry]\nName=Steam\nExec=env TEST=value /usr/bin/steam -silent %U\n[Desktop Action Store]\nExec=steam steam://store\n"
        init {
            val binary = ByteArray(20).also { it[0]=127; it[1]=69; it[2]=76; it[3]=70; it[4]=1; it[5]=1; it[18]=3 }
            Files.createDirectories(steam.resolve("ubuntu12_32"))
            Files.write(steam.resolve("ubuntu12_32/steam"), binary)
            Files.write(steam.resolve("ubuntu12_32/steamclient.so"), binary)
            Files.writeString(system, original)
            Files.createDirectories(apps); Files.createDirectories(auto)
        }
        fun target() = SteamInstallationTarget("fixture", gg.hideouthq.library.SteamClient.Desktop,
            steam.toAbsolutePath(), steam.toAbsolutePath(), SteamArchitecture.I386, true, null)
        fun installer(resource: ((String) -> ByteArray?)? = null): LinuxSteamTrackingInstaller {
            val reader: (String) -> ByteArray? = resource ?: { name -> javaClass.getResourceAsStream("/steam-bridge/linux-i386/$name")?.use { it.readBytes() } }
            return LinuxSteamTrackingInstaller({ listOf(target()) }, "Linux", data, apps, auto, system, { _, name -> reader(name) }, { _, _ -> SteamBridgeReport(1, true, "compatible") })
        }
        override fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun installIsIdempotentAndRestoresDesktopAndAutostartExactly(): Unit = runBlocking {
        Fixture().use { f ->
            Files.writeString(f.apps.resolve("steam.desktop"), f.original)
            Files.writeString(f.auto.resolve("steam.desktop"), "[Desktop Entry]\nExec=steam -silent\nHidden=false\n")
            val autoBefore = Files.readString(f.auto.resolve("steam.desktop"))
            val installer = f.installer()
            assertTrue(installer.inspect().supported)
            val firstInstall = installer.install()
            assertTrue(firstInstall.installed)
            assertNull(firstInstall.reason, "a successful install should report no reason, not the restart hint")
            val installed = Files.readString(f.apps.resolve("steam.desktop"))
            assertTrue(installed.contains(" -- env TEST=value /usr/bin/steam -silent %U"))
            assertTrue(installer.install().installed)
            assertEquals(installed, Files.readString(f.apps.resolve("steam.desktop")))
            assertNull(installer.inspect().reason, "a successful inspect should report no reason, not the restart hint")
            installer.uninstall()
            assertEquals(f.original, Files.readString(f.apps.resolve("steam.desktop")))
            assertEquals(autoBefore, Files.readString(f.auto.resolve("steam.desktop")))
            assertFalse(installer.inspect().installed)
        }
    }

    @Test fun aNewOverrideIsRemovedAndPackageEntryIsNeverChanged(): Unit = runBlocking {
        Fixture().use { f ->
            val installer=f.installer(); installer.install(); installer.uninstall()
            assertFalse(Files.exists(f.apps.resolve("steam.desktop")))
            assertEquals(f.original, Files.readString(f.system))
        }
    }

    @Test fun disableKeepsALauncherEditedSinceEnableRestoresTheOtherAndEnableWorksAgain(): Unit = runBlocking {
        Fixture().use { f ->
            val autostart = f.auto.resolve("steam.desktop")
            val autoBefore = "[Desktop Entry]\nExec=steam -silent\nHidden=false\n"
            Files.writeString(autostart, autoBefore)
            val installer = f.installer()
            installer.install()
            val target = f.apps.resolve("steam.desktop")
            val edited = Files.readString(target) + "# user customization\n"
            Files.writeString(target, edited)

            val disabled = installer.uninstall()

            assertEquals(edited, Files.readString(target), "the user's edit survives")
            assertEquals(autoBefore, Files.readString(autostart), "the launcher nobody touched is restored")
            assertTrue(disabled.supported)
            assertFalse(disabled.installed)
            assertTrue(installer.install().installed, "tracking can be enabled again")
        }
    }

    @Test fun launcherOnlyChangesProduceANewBundleIdentity(): Unit = runBlocking {
        Fixture().use { f ->
            val manifest = javaClass.getResourceAsStream("/steam-bridge/linux-i386/SHA256SUMS")!!.use { it.readBytes() }
            val names = manifest.decodeToString().lines().filter { it.isNotBlank() }.map { it.substringAfter("  ") }
            val assets = names.associateWith { name -> javaClass.getResourceAsStream("/steam-bridge/linux-i386/$name")!!.use { it.readBytes() } }.toMutableMap()
            assets["SHA256SUMS"] = manifest
            val first = f.installer { assets[it] }
            first.install()
            fun bundle(): String = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(f.data.resolve("steam-bridge-install/receipt.json")))
                .let { (it as kotlinx.serialization.json.JsonObject).getValue("bundle").toString().trim('"') }
            val before = bundle()
            val library = SteamBridgeCompatibility.hash(Path.of(before).resolve("libhideout_steam_bridge.so"))
            assets["launch.sh"] = assets.getValue("launch.sh") + "\n# launcher fixture update\n".toByteArray()
            assets["SHA256SUMS"] = names.joinToString("\n", postfix = "\n") { name ->
                java.security.MessageDigest.getInstance("SHA-256").digest(assets.getValue(name)).toHexString() + "  " + name
            }.toByteArray()
            val updated = f.installer { assets[it] }
            assertTrue(updated.inspect().installed, "An enabled installation follows a verified bundle update")
            assertNotEquals(before, bundle())
            assertTrue(Files.exists(Path.of(before).resolve("disabled")))
            assertEquals(library, SteamBridgeCompatibility.hash(Path.of(bundle()).resolve("libhideout_steam_bridge.so")))
            updated.uninstall()
        }
    }

    @Test fun uninstallPreservesAReplacementSymlinkAndChangedPermissions(): Unit = runBlocking {
        Fixture().use { f ->
            val installer = f.installer()
            Files.writeString(f.auto.resolve("steam.desktop"), f.original)
            installer.install()
            val desktop = f.apps.resolve("steam.desktop")
            val userFile = f.root.resolve("user-steam.desktop")
            Files.move(desktop, userFile)
            Files.createSymbolicLink(desktop, userFile.toAbsolutePath())
            val auto = f.auto.resolve("steam.desktop")
            val autoText = Files.readString(auto)
            val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(auto, permissions)
            installer.uninstall()
            assertTrue(Files.isSymbolicLink(desktop))
            assertTrue(Files.exists(userFile))
            assertEquals(autoText, Files.readString(auto))
            assertEquals(permissions, Files.getPosixFilePermissions(auto))
        }
    }

    @Test fun structuralFailureIsReportedWithoutChangingLaunchers(): Unit = runBlocking {
        Fixture().use { f ->
            val installer = LinuxSteamTrackingInstaller({ listOf(f.target()) }, "Linux", f.data, f.apps, f.auto, f.system,
                probe = { _, _ -> SteamBridgeReport(1, false, "map_layout_drift") })
            val status = installer.install()
            assertFalse(status.supported)
            assertContains(status.reason!!, "map_layout_drift")
            assertFalse(Files.exists(f.apps.resolve("steam.desktop")))
            assertEquals(f.original, Files.readString(f.system))
        }
    }

    @Test fun aBridgeThatCannotLoadAndAnUnsupportedRuntimeLeaveLaunchersAlone(): Unit = runBlocking {
        Fixture().use { f ->
            val installer=f.installer { name -> if(name=="libhideout_steam_bridge.so") byteArrayOf(0)
                else javaClass.getResourceAsStream("/steam-bridge/linux-i386/$name")?.use { it.readBytes() } }
            assertFailsWith<IllegalStateException> { installer.install() }
            assertFalse(Files.exists(f.apps.resolve("steam.desktop")))
            assertTrue(steamTrackingTargets(listOf(gg.hideouthq.library.SteamInstall(f.steam.toAbsolutePath().toString(), gg.hideouthq.library.SteamClient.Desktop)), null).isNotEmpty())
        }
    }
}
