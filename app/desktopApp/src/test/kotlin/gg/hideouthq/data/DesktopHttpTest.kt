package gg.hideouthq.data

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopHttpTest {
    private val bundled = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
    private val chain = arrayOf(bundled.first())

    @Test
    fun aChainOnlyTheSecondStoreTrustsIsTrusted() {
        val asked = mutableListOf<String>()

        EitherTrust(listOf(Store("bundled", trusts = false, asked = asked), Store("windows", trusts = true, asked = asked))).checkServerTrusted(chain, "RSA")

        assertEquals(listOf("bundled", "windows"), asked)
    }

    @Test
    fun aChainNeitherStoreTrustsIsRefusedWithTheFirstStoresReason() {
        val refusal = assertFailsWith<CertificateException> {
            EitherTrust(listOf(Store("bundled", trusts = false), Store("windows", trusts = false))).checkServerTrusted(chain, "RSA")
        }

        assertEquals("bundled refuses", refusal.message)
    }

    private class Store(
        private val name: String,
        private val trusts: Boolean = true,
        private val asked: MutableList<String> = mutableListOf(),
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check()

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check()

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun check() {
            asked += name
            if (!trusts) throw CertificateException("$name refuses")
        }
    }
}
