package gg.hideouthq.deadlockstats

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SteamProcessResolverTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("steam-process-namespace-")
        val exe = root.resolve("steam")
        val proc = Files.createDirectory(root.resolve("proc"))
        val resolver = SteamProcessResolver(proc)
        init { Files.write(exe, ByteArray(20).also { it[0]=127;it[1]=69;it[2]=76;it[3]=70;it[4]=2;it[5]=1;it[18]=62 }) }
        fun process(host: Long, namespace: Long, start: Long, binary: Path = exe): Path = Files.createDirectories(proc.resolve(host.toString())).also {
            Files.createSymbolicLink(it.resolve("exe"), binary)
            Files.writeString(it.resolve("status"), "Name:\tsteam\nNSpid:\t$host\t$namespace\n")
            start(it, start)
        }
        fun start(process: Path, value: Long) { Files.writeString(process.resolve("stat"), "${process.fileName} (steam (runtime)) S " + List(18) { "0" }.joinToString(" ") + " $value 0\n") }
        override fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun namespacePidResolvesOnlyTheSelectedExecutableAndHostIdentity() {
        Fixture().use { f ->
            f.process(101, 7, 1234)
            val other = f.root.resolve("other");Files.copy(f.exe, other)
            f.process(102, 7, 1234, other)
            assertEquals(SteamProcessIdentity(101, "1234"), f.resolver.resolve(7, f.exe, SteamArchitecture.X86_64))
            assertNull(f.resolver.resolve(101, f.exe, SteamArchitecture.X86_64), "the host PID is not the announced namespace PID")
            assertNull(f.resolver.resolve(7, f.exe, SteamArchitecture.I386))
            f.process(103, 7, 5678)
            assertNull(f.resolver.resolve(7, f.exe, SteamArchitecture.X86_64), "two matching namespace PIDs are ambiguous")
        }
    }

    @Test fun processReuseDuringValidationCannotBeAdmitted() {
        Fixture().use { f ->
            val process = f.process(101, 7, 1234)
            assertNull(f.resolver.resolve(7, f.exe, SteamArchitecture.X86_64) { f.start(process, 5678);true })
            assertEquals(SteamProcessIdentity(101, "5678"), f.resolver.resolve(7, f.exe, SteamArchitecture.X86_64))
        }
    }

    @Test fun inaccessibleMatchingProcessIsReportedWithoutAdmittingIt() {
        Fixture().use { f ->
            f.process(101, 7, 1234)
            assertFailsWith<SteamProcessMetadataUnavailable> {
                f.resolver.resolve(7, f.exe, SteamArchitecture.X86_64) {
                    throw java.nio.file.AccessDeniedException(it.resolve("maps").toString())
                }
            }
            assertNull(f.resolver.resolve(8, f.exe, SteamArchitecture.X86_64) {
                error("unrelated processes must not be inspected")
            })
        }
    }
}
