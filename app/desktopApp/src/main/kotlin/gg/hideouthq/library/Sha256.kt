package gg.hideouthq.library

import gg.hideouthq.OnWindows
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.lang.ref.Cleaner
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.security.MessageDigest

/**
 * A SHA-256 digest from the operating system's crypto library, libcrypto on Linux and CNG on Windows, which use the
 * CPU's SHA instructions. The image is compiled for x86-64-v2, which has none, so the JDK's own is far slower. Where
 * the library does not load, this is the JDK's.
 */
internal fun sha256(library: Sha256Library? = Sha256Library.Os): MessageDigest =
    library?.let(::LibraryDigest) ?: MessageDigest.getInstance("SHA-256")

/** Reads the channel to its end into each of [digests] and returns how many bytes it read. */
internal fun ReadableByteChannel.digestRest(vararg digests: MessageDigest): Long {
    val buffer = ByteBuffer.allocate(ReadBytes)
    var size = 0L
    while (read(buffer.clear()) >= 0) {
        for (digest in digests) digest.update(buffer.array(), 0, buffer.position())
        size += buffer.position()
    }
    return size
}

/** SHA-256 contexts held in native memory. Each call fails with [IllegalStateException] when the library refuses. */
internal interface Sha256Library {
    /** A context ready for input, which [close] frees. */
    fun open(): Pointer

    fun update(context: Pointer, bytes: Pointer, length: Int)

    /** Writes the 32-byte digest to [digest] and readies [context] for new input. */
    fun finish(context: Pointer, digest: Pointer)

    fun close(context: Pointer)

    companion object {
        val Os: Sha256Library? by lazy { load(OnWindows) }

        /** The system's library, or null where it does not load or does not answer SHA-256's published test. */
        fun load(windows: Boolean, libcrypto: List<String> = Libcrypto.Names): Sha256Library? {
            val candidates = if (windows) sequenceOf({ Cng(NativeLibrary.getInstance("bcrypt")) }) else libcrypto.asSequence().map { { Libcrypto(NativeLibrary.getInstance(it)) } }
            return candidates.firstNotNullOfOrNull { open ->
                try {
                    open().takeIf { LibraryDigest(it).digest(Abc).contentEquals(AbcDigest) }
                } catch (_: Throwable) {
                    null
                }
            }
        }

        private val Abc = "abc".encodeToByteArray()
        private val AbcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray()
    }
}

private class Libcrypto(library: NativeLibrary) : Sha256Library {
    private val newContext = library.getFunction("EVP_MD_CTX_new")
    private val freeContext = library.getFunction("EVP_MD_CTX_free")
    private val init = library.getFunction("EVP_DigestInit_ex")
    private val update = library.getFunction("EVP_DigestUpdate")
    private val final = library.getFunction("EVP_DigestFinal_ex")
    private val method = checkNotNull(library.getFunction("EVP_sha256").invokePointer(emptyArray())) { "libcrypto has no SHA-256" }

    override fun open(): Pointer = checkNotNull(newContext.invokePointer(emptyArray())) { "libcrypto made no context" }.also(::start)

    override fun update(context: Pointer, bytes: Pointer, length: Int) = ok(update.invokeInt(arrayOf(context, bytes, length.toLong())))

    override fun finish(context: Pointer, digest: Pointer) {
        ok(final.invokeInt(arrayOf(context, digest, null)))
        start(context)
    }

    override fun close(context: Pointer) = freeContext.invokeVoid(arrayOf(context))

    private fun start(context: Pointer) = ok(init.invokeInt(arrayOf(context, method, null)))

    private fun ok(result: Int) = check(result == 1) { "libcrypto refused SHA-256" }

    companion object {
        val Names = listOf("libcrypto.so.3", "libcrypto.so.1.1")
    }
}

/** A reusable hash restarts at each finish, so a context never has to be made again. */
private class Cng(library: NativeLibrary) : Sha256Library {
    private val createHash = library.getFunction("BCryptCreateHash")
    private val hashData = library.getFunction("BCryptHashData")
    private val finishHash = library.getFunction("BCryptFinishHash")
    private val destroyHash = library.getFunction("BCryptDestroyHash")
    private val algorithm: Pointer = Memory(Native.POINTER_SIZE.toLong()).use { handle ->
        val name = Memory(16).apply { setWideString(0, "SHA256") }
        ok(library.getFunction("BCryptOpenAlgorithmProvider").invokeInt(arrayOf(handle, name, null, HashReusable)))
        handle.getPointer(0)
    }

    override fun open(): Pointer = Memory(Native.POINTER_SIZE.toLong()).use { handle ->
        ok(createHash.invokeInt(arrayOf(algorithm, handle, null, 0, null, 0, HashReusable)))
        handle.getPointer(0)
    }

    override fun update(context: Pointer, bytes: Pointer, length: Int) = ok(hashData.invokeInt(arrayOf(context, bytes, length, 0)))

    override fun finish(context: Pointer, digest: Pointer) = ok(finishHash.invokeInt(arrayOf(context, digest, DigestBytes, 0)))

    override fun close(context: Pointer) {
        destroyHash.invokeInt(arrayOf(context))
    }

    private fun ok(status: Int) = check(status == 0) { "CNG refused SHA-256 with status 0x${status.toUInt().toString(16)}" }

    private companion object {
        const val HashReusable = 0x20
    }
}

/**
 * Gathers input in native memory and hands the library a large block at a time, so a stream's small reads cost one
 * native call per [PendingBytes] instead of one each.
 */
private class LibraryDigest(private val library: Sha256Library) : MessageDigest("SHA-256") {
    private val context = library.open()
    private val digest = Memory(DigestBytes.toLong())
    private val pending by lazy { Memory(PendingBytes.toLong()) }
    private var filled = 0

    init {
        // Copied out so the cleanup does not hold this digest and keep it from ever being collected.
        val context = context
        val library = library
        Contexts.register(this) { library.close(context) }
    }

    override fun engineGetDigestLength(): Int = DigestBytes

    override fun engineUpdate(input: Byte) = engineUpdate(byteArrayOf(input), 0, 1)

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        var at = offset
        val end = offset + len
        while (at < end) {
            val taken = minOf(end - at, PendingBytes - filled)
            pending.write(filled.toLong(), input, at, taken)
            filled += taken
            at += taken
            if (filled == PendingBytes) flush()
        }
    }

    override fun engineDigest(): ByteArray {
        flush()
        library.finish(context, digest)
        return digest.getByteArray(0, DigestBytes)
    }

    override fun engineReset() {
        filled = 0
        library.finish(context, digest)
    }

    private fun flush() {
        if (filled > 0) library.update(context, pending, filled)
        filled = 0
    }
}

private val Contexts: Cleaner = Cleaner.create()

private const val DigestBytes = 32
private const val PendingBytes = 1 shl 18
private const val ReadBytes = 1 shl 16
