package gg.hideouthq.nativepackage

import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal data class Coordinate(val group: String, val artifact: String, val version: String) {
    override fun toString() = "$group:$artifact:$version"
}

/**
 * The artifact a JAR in a Maven repository is, or one of whose classifier files it is, read from the POM published
 * beside it. Null when there is no POM, or when the POM and the JAR's folders disagree: a path alone cannot tell the
 * group `androidx.lifecycle` from `org.jetbrains.androidx.lifecycle`.
 */
internal fun coordinateOf(jar: Path): Coordinate? {
    val version = jar.parent ?: return null
    val artifact = version.parent ?: return null
    val stem = "${artifact.name}-${version.name}"
    if (jar.name != "$stem.jar" && !(jar.name.startsWith("$stem-") && jar.name.endsWith(".jar"))) return null
    val pom = jar.resolveSibling("$stem.pom").takeIf { it.isRegularFile() } ?: return null
    val project = pom.inputStream().use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }.documentElement
    val parent = project.element("parent")
    val group = project.text("groupId") ?: parent?.text("groupId") ?: return null
    val agrees = project.text("artifactId") == artifact.name && (project.text("version") ?: parent?.text("version")) == version.name &&
        version.endsWith(Path.of(group.replace('.', '/'), artifact.name, version.name))
    return Coordinate(group, artifact.name, version.name).takeIf { agrees }
}

private fun Element.element(tag: String): Element? =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == tag }

private fun Element.text(tag: String): String? = element(tag)?.textContent?.trim()

internal fun readNoticeIndex(text: String): Set<Coordinate> {
    val artifacts = text.lines().withIndex().filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }.flatMap { (index, line) ->
        val columns = line.split('\t')
        require(columns.size == NoticeColumns) { "Notice index line ${index + 1} has ${columns.size} columns instead of $NoticeColumns" }
        if (columns.last() == "-") emptyList() else columns.last().split(',').map { coordinate(it, index + 1) }
    }
    val repeated = artifacts.groupBy { it }.filterValues { it.size > 1 }.keys
    require(repeated.isEmpty()) { "The notice index names ${repeated.joinToString()} more than once" }
    return artifacts.toSet()
}

private fun coordinate(text: String, line: Int): Coordinate {
    val parts = text.split(':')
    require(parts.size == 3 && parts.all { it.isNotBlank() && it.none(Char::isWhitespace) }) { "Notice index line $line names '$text' instead of group:artifact:version" }
    return Coordinate(parts[0], parts[1], parts[2])
}

internal fun unmappedJars(jars: List<Path>, ownBuild: Path, named: Set<Coordinate>): List<Path> {
    val own = ownBuild.toAbsolutePath().normalize()
    return jars.map { it.toAbsolutePath().normalize() }.distinct().filterNot { jar -> jar.parent?.parent == own || coordinateOf(jar) in named }
}

internal fun goModules(versionOutput: String): List<Coordinate> {
    val lines = versionOutput.lines()
    val toolchain = lines.first().substringAfter(": ").trim()
    val modules = mutableListOf(Coordinate(GoGroup, "std", toolchain))
    for (line in lines.drop(1)) {
        val columns = line.trim().split('\t')
        when (columns.first()) {
            "dep" -> modules += Coordinate(GoGroup, columns[1], columns[2])
            "=>" -> modules[modules.lastIndex] = Coordinate(GoGroup, columns[1], columns[2])
        }
    }
    return modules
}

private const val GoGroup = "go"
private const val NoticeColumns = 5
