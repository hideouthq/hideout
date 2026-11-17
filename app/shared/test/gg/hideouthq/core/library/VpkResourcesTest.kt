package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VpkResourcesTest {
    @Test
    fun versionOneAndTwoReadNestedRootAndExtensionlessResourcesIncludingPreloadAndChunks() {
        for (version in listOf(1, 2)) {
            val bytes = resourceVpk(listOf("Models\\Ivy/body.vmdl_c", "readme.txt", "LICENSE", "sounds/ivy.vsnd_c"), version)
            assertEquals(setOf("models/ivy/body.vmdl_c", "readme.txt", "license", "sounds/ivy.vsnd_c"),
                assertIs<ResourceIndex.Ready>(VpkFormat.resources(bytes, bytes.size.toLong())).files.keys)
            assertIs<VpkRead.Parsed>(VpkFormat.read(bytes, bytes.size.toLong()))
        }
    }

    @Test
    fun eachResourceCarriesTheCrcItsTreeRecords() {
        val bytes = resourceVpk(listOf("models/ivy.vmdl_c", "sounds/ivy.vsnd_c"), crcs = listOf(0xDEADBEEF, 7))
        assertEquals(mapOf("models/ivy.vmdl_c" to 0xDEADBEEF, "sounds/ivy.vsnd_c" to 7L), assertIs<ResourceIndex.Ready>(VpkFormat.resources(bytes, bytes.size.toLong())).files)
    }

    @Test
    fun repeatedEntriesCountOnceWithoutChangingInstallationValidation() {
        val bytes = resourceVpk(listOf("models/ivy.vmdl_c", "models/ivy.vmdl_c", "models/IVY.vmdl_c"), crcs = listOf(1, 2, 3))
        assertEquals(mapOf("models/ivy.vmdl_c" to 1L), assertIs<ResourceIndex.Ready>(VpkFormat.resources(bytes, bytes.size.toLong())).files, "the first entry of a path is its")
        assertIs<VpkRead.Parsed>(VpkFormat.read(bytes, bytes.size.toLong()))
    }

    @Test
    fun resourceNameFailuresDoNotTightenInstallationValidation() {
        val control = resourceVpk(listOf("a\nb.txt"))
        assertIs<ResourceIndex.Unavailable>(VpkFormat.resources(control, control.size.toLong()))
        assertIs<VpkRead.Parsed>(VpkFormat.read(control, control.size.toLong()))
        val invalidUtf8 = resourceVpk(listOf("name.txt")).also { bytes ->
            val name = "name".encodeToByteArray()
            val at = bytes.indices.first { i -> i + name.size <= bytes.size && bytes.copyOfRange(i, i + name.size).contentEquals(name) }
            bytes[at] = 0xFF.toByte()
        }
        assertIs<ResourceIndex.Unavailable>(VpkFormat.resources(invalidUtf8, invalidUtf8.size.toLong()))
        assertIs<VpkRead.Parsed>(VpkFormat.read(invalidUtf8, invalidUtf8.size.toLong()))
    }

    @Test
    fun shortMalformedOrUnsupportedTreesHaveNoPartialIndex() {
        val bytes = resourceVpk(listOf("models/ivy.vmdl_c", "sounds/ivy.vsnd_c"))
        assertIs<ResourceIndex.Unavailable>(VpkFormat.resources(bytes.copyOf(bytes.size - 4), bytes.size.toLong()))
        assertIs<ResourceIndex.Unavailable>(VpkFormat.resources(ByteArray(10), 10))
        val unsupported = bytes.copyOf().also { it[4] = 3 }
        assertIs<ResourceIndex.Unavailable>(VpkFormat.resources(unsupported, unsupported.size.toLong()))
    }
}

internal fun resourceVpk(paths: List<String>, version: Int = 2, crcs: List<Long> = emptyList()): ByteArray {
    class Bytes {
        val bytes = mutableListOf<Byte>()
        fun number(value: Long, size: Int) { repeat(size) { bytes += (value ushr (8 * it)).toByte() } }
        fun number(value: Int, size: Int) = number(value.toLong() and 0xFFFFFFFFL, size)
        fun text(value: String) { bytes += value.encodeToByteArray().toList(); bytes += 0 }
    }
    val tree = Bytes()
    paths.withIndex().groupBy { it.value.substringAfterLast('.', " ") }.forEach { (extension, entries) ->
        tree.text(extension)
        entries.groupBy { it.value.substringBeforeLast('/', " ") }.forEach { (folder, grouped) ->
            tree.text(folder)
            grouped.forEach { (index, path) ->
                tree.text(path.substringAfterLast('/').let { if (extension == " ") it else it.substringBeforeLast('.') })
                tree.number(crcs.getOrElse(index) { 0 }, 4)
                tree.number(2, 2)
                tree.number(0, 2)
                tree.number(0, 4)
                tree.number(8, 4)
                tree.number(0xFFFF, 2)
                tree.number(0x1234, 2)
            }
            tree.text("")
        }
        tree.text("")
    }
    tree.text("")
    val header = Bytes()
    header.number(0x55AA1234, 4)
    header.number(version, 4)
    header.number(tree.bytes.size, 4)
    if (version == 2) repeat(4) { header.number(0, 4) }
    return (header.bytes + tree.bytes).toByteArray()
}
