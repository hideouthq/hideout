package gg.hideouthq.nativepackage

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalPathApi::class)
class NoticeIndexTest {
    private val index = readNoticeIndex(
        """
        # component	licence	OSI	notices	artifacts
        LWJGL	BSD-3-Clause	yes	lwjgl.txt	org.lwjgl:lwjgl:3.3.3,org.lwjgl:lwjgl-glfw:3.3.3
        Junrar	UnRAR licence	no	junrar_unrar.txt	com.github.junrar:junrar:8.1.2
        Google Sans Flex	SIL Open Font License 1.1	yes	google_sans_flex_ofl.txt	-
        """.trimIndent(),
    )
    private val repository = createTempDirectory("notice-index")
    private val own = repository / "project" / "build" / "tasks"

    @AfterTest
    fun cleanUp() = repository.deleteRecursively()

    private fun published(group: String, artifact: String, version: String, classifier: String? = null, pomGroup: String? = group, parentGroup: String? = null): Path {
        val folder = (repository / "m2" / group.replace('.', '/') / artifact / version).createDirectories()
        val parent = parentGroup?.let { "<parent><groupId>$it</groupId><artifactId>parent</artifactId><version>$version</version></parent>" }.orEmpty()
        val pom = "<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\">$parent" +
            "${pomGroup?.let { "<groupId>$it</groupId>" }.orEmpty()}<artifactId>$artifact</artifactId><version>$version</version></project>"
        (folder / "$artifact-$version.pom").writeText(pom)
        return (folder / "$artifact-$version${classifier?.let { "-$it" }.orEmpty()}.jar").also { it.writeText("jar") }
    }

    @Test
    fun anIndexLineNamesExactArtifacts() {
        assertEquals(
            setOf(Coordinate("org.lwjgl", "lwjgl", "3.3.3"), Coordinate("org.lwjgl", "lwjgl-glfw", "3.3.3"), Coordinate("com.github.junrar", "junrar", "8.1.2")),
            index,
        )
    }

    @Test
    fun aJarIsMappedByThePomBesideItAndClassifierFilesShareTheArtifact() {
        val jars = listOf(
            published("org.lwjgl", "lwjgl", "3.3.3"),
            published("org.lwjgl", "lwjgl", "3.3.3", classifier = "natives-linux"),
            published("org.lwjgl", "lwjgl", "3.3.3", classifier = "natives-windows"),
            published("org.lwjgl", "lwjgl", "3.3.3", classifier = "natives-windows-arm64"),
            published("org.lwjgl", "lwjgl-glfw", "3.3.3", classifier = "natives-linux", pomGroup = null, parentGroup = "org.lwjgl"),
            published("com.github.junrar", "junrar", "8.1.2"),
            (own / "_shared_jarJvm").createDirectories().resolve("shared-jvm.jar").also { it.writeText("own") },
        )
        assertEquals(emptyList(), unmappedJars(jars, own, index))
    }

    @Test
    fun anotherVersionGroupOrArtifactAndAJarWithoutAnAgreeingPomAreUnmapped() {
        val lookalike = published("evil.org.lwjgl", "lwjgl", "3.3.3")
        val jars = listOf(
            published("org.lwjgl", "lwjgl", "3.3.4"),
            published("org.other", "lwjgl", "3.3.3"),
            published("org.lwjgl", "lwjgl-openal", "3.3.3"),
            lookalike,
            published("com.github.junrar", "junrar", "8.1.2", pomGroup = "com.github.other"),
            (repository / "m2" / "org" / "lwjgl" / "lwjgl" / "3.3.3" / "lwjgl-3.3.3x.jar").also { it.parent.createDirectories(); it.writeText("jar") },
            (repository / "loose" / "x" / "own.jar").also { it.parent.createDirectories(); it.writeText("jar") },
        )
        assertEquals(jars, unmappedJars(jars, own, index), "a path that merely ends like a named group, as ${lookalike.parent} does, is not that group")
        val withoutPom = repository / "m2" / "org" / "lwjgl" / "lwjgl" / "3.3.3" / "lwjgl-3.3.3.jar"
        withoutPom.writeText("jar")
        assertEquals(listOf(withoutPom), unmappedJars(listOf(withoutPom), own, index), "an artifact without its POM is unresolved")
    }

    private val versionOutput =
        """
        /image/hideout-link: go1.27.1
        ${'\t'}path${'\t'}hideout/tools/link
        ${'\t'}mod${'\t'}hideout/tools/link${'\t'}(devel)${'\t'}
        ${'\t'}dep${'\t'}golang.org/x/sys${'\t'}v0.48.0${'\t'}h1:abc=
        ${'\t'}dep${'\t'}example.com/forked${'\t'}v1.0.0
        ${'\t'}=>${'\t'}example.com/fork${'\t'}v1.0.1${'\t'}h1:def=
        ${'\t'}build${'\t'}CGO_ENABLED=0
        """.trimIndent()

    @Test
    fun aGoBinaryIsTheStandardLibraryAndItsModulesWithReplacementsApplied() {
        assertEquals(
            listOf(Coordinate("go", "std", "go1.27.1"), Coordinate("go", "golang.org/x/sys", "v0.48.0"), Coordinate("go", "example.com/fork", "v1.0.1")),
            goModules(versionOutput),
        )
    }

    @Test
    fun aGoModuleWithNoLineStopsTheBuildNamingIt() {
        val index = (repository / "index.tsv").also {
            it.writeText("Go\tBSD\tyes\tgo.txt\tgo:std:go1.27.1\nx/sys\tBSD\tyes\tsys.txt\tgo:golang.org/x/sys:v0.48.0\nFork\tMIT\tyes\tfork.txt\tgo:example.com/fork:v1.0.0\n")
        }
        val failure = assertFailsWith<IllegalStateException> { requireLinkNotices(goModules(versionOutput), index) }
        assertContains(failure.message.orEmpty(), "go:example.com/fork:v1.0.1")
        index.writeText(index.toFile().readText().replace("fork:v1.0.0", "fork:v1.0.1"))
        requireLinkNotices(goModules(versionOutput), index)
    }

    @Test
    fun malformedOrRepeatedLinesAreRefused() {
        listOf(
            "A\tMIT\tyes\ta.txt",
            "A\tMIT\tyes\ta.txt\torg.example:a",
            "A\tMIT\tyes\ta.txt\torg.example:a:1\nB\tMIT\tyes\tb.txt\torg.example:a:1",
        ).forEach { text -> assertFailsWith<IllegalArgumentException>(text) { readNoticeIndex(text) } }
    }
}
