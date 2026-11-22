package gg.hideouthq.library

import com.sun.jna.Pointer
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Each SHA-256 the app finished, with how many bytes went into it and which of the app's functions asked, gathered by
 * standing in for the operating system's library that [sha256] hands every digest to.
 */
internal class HashedFiles private constructor() {
    class Hash(val bytes: Long, val by: String, val at: Long = System.nanoTime())

    val hashes = ConcurrentLinkedQueue<Hash>()

    fun of(bytes: Long, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): Int = hashes.count { it.bytes == bytes && it.at in from..to }

    fun by(bytes: Long, from: Long, to: Long): Map<String, Int> = hashes.filter { it.bytes == bytes && it.at in from..to }.groupingBy { it.by }.eachCount()

    fun bytesOver(least: Long, from: Long, to: Long): Long = hashes.filter { it.bytes >= least && it.at in from..to }.sumOf { it.bytes }

    fun report(): String = hashes.filter { it.bytes >= 1_000 }.joinToString("\n", "hashes:\n") { "${it.bytes} by ${it.by}" }

    private class Counting(private val real: Sha256Library, private val into: HashedFiles) : Sha256Library {
        private val fed = ConcurrentHashMap<Long, Long>()

        override fun open(): Pointer = real.open()

        override fun update(context: Pointer, bytes: Pointer, length: Int) {
            fed.merge(Pointer.nativeValue(context), length.toLong(), Long::plus)
            real.update(context, bytes, length)
        }

        override fun finish(context: Pointer, digest: Pointer) {
            val bytes = fed.remove(Pointer.nativeValue(context)) ?: 0L
            val by = Throwable().stackTrace.filter { it.className.startsWith("gg.hideouthq") && "Sha256Kt" !in it.className && "LibraryDigest" !in it.className && "HashedFiles" !in it.className }
                .take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            into.hashes += Hash(bytes, by)
            real.finish(context, digest)
        }

        override fun close(context: Pointer) = real.close(context)
    }

    companion object {
        /** Counts the hashes [block] makes. Needs the system's library, which every machine the tests run on has. */
        fun during(block: (HashedFiles) -> Unit): HashedFiles {
            val counted = HashedFiles()
            val field = (Sha256Library.Companion::class.java.declaredFields + Sha256Library::class.java.declaredFields).first { it.name == "Os\$delegate" }.apply { isAccessible = true }
            val holder = field.get(if (Modifier.isStatic(field.modifiers)) null else Sha256Library.Companion) as Lazy<*>
            val real = checkNotNull(holder.value as Sha256Library?) { "the system's SHA-256 did not load" }
            val value = holder.javaClass.getDeclaredField("_value").apply { isAccessible = true }
            synchronized(HashedFiles) {
                value.set(holder, Counting(real, counted))
                try {
                    block(counted)
                } finally {
                    value.set(holder, real)
                }
            }
            return counted
        }
    }
}
