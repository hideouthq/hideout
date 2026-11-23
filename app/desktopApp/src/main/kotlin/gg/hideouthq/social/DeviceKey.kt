package gg.hideouthq.social

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECPrivateKey
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.util.Base64

/**
 * The site binds a Steam id's mailbox to the first device key that opens it, so the key must outlive updates: it lives
 * in the config folder, which the Windows installer leaves alone, unlike the data folder.
 */
class DeviceKey private constructor(private val key: PrivateKey, private val public: ByteArray) {
    val header: String = encoder.encodeToString(public)

    fun sign(message: ByteArray): String = encoder.encodeToString(Signature.getInstance(Algorithm).run {
        initSign(key)
        update(message)
        sign()
    })

    companion object {
        private const val Algorithm = "Ed25519"
        private const val Half = 32
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        fun load(file: Path): DeviceKey {
            read(file)?.let { return it }
            val (key, stored) = generate(SecureRandom())
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "device", ".tmp", *ownerOnly(file))
            try {
                Files.write(temporary, stored)
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
            return key
        }

        internal fun generate(random: SecureRandom): Pair<DeviceKey, ByteArray> {
            val pair = KeyPairGenerator.getInstance(Algorithm).apply { initialize(NamedParameterSpec.ED25519, random) }.generateKeyPair()
            val seed = (pair.private as EdECPrivateKey).bytes.orElseThrow()
            val public = pair.public.encoded.takeLast(Half).toByteArray()
            return DeviceKey(pair.private, public) to seed + public
        }

        private fun read(file: Path): DeviceKey? {
            val stored = try {
                Files.readAllBytes(file)
            } catch (e: IOException) {
                return null
            }
            if (stored.size != Half * 2) return null
            val seed = EdECPrivateKeySpec(NamedParameterSpec.ED25519, stored.copyOfRange(0, Half))
            return DeviceKey(KeyFactory.getInstance(Algorithm).generatePrivate(seed), stored.copyOfRange(Half, Half * 2))
        }

        private fun ownerOnly(file: Path) =
            if ("posix" in file.fileSystem.supportedFileAttributeViews()) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
    }
}
