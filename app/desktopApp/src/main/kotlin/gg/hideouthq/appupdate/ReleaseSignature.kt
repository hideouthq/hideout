package gg.hideouthq.appupdate

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val ReleaseKey = "MCowBQYDK2VwAyEAKkjHdRhvoz4Mrt696uEkV5+XBfmnRU2amsyxY0GpwMo="

internal fun signatureCheck(publicKey: String): (ByteArray, String) -> Boolean {
    val key by lazy { KeyFactory.getInstance(Algorithm).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))) }
    return { bytes, signature ->
        Signature.getInstance(Algorithm).run {
            initVerify(key)
            update(bytes)
            verify(Base64.getDecoder().decode(signature))
        }
    }
}

private const val Algorithm = "Ed25519"
