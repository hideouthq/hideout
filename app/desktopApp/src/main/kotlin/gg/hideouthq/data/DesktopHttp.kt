package gg.hideouthq.data

import gg.hideouthq.OnWindows
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * On Windows it also trusts the Windows certificate store, where antivirus web scanners and company proxies install the
 * roots they sign with; the bundled store stays first, because Windows fetches public roots only as it needs them, so
 * its store can lack one.
 */
fun desktopEngine(): HttpClientEngine = OkHttp.create {
    windowsTrust?.let { (context, trust) -> config { sslSocketFactory(context.socketFactory, trust) } }
}

private val windowsTrust: Pair<SSLContext, X509TrustManager>? by lazy {
    if (!OnWindows) return@lazy null
    val windows = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
    val trust = EitherTrust(listOf(trustIn(null), trustIn(windows)))
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) } to trust
}

/** The default trust manager over [store], or over the bundled `cacerts` when null. */
private fun trustIn(store: KeyStore?): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().first()

internal class EitherTrust(private val managers: List<X509TrustManager>) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = anyOf { it.checkClientTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = anyOf { it.checkServerTrusted(chain, authType) }

    override fun getAcceptedIssuers(): Array<X509Certificate> = managers.flatMap { it.acceptedIssuers.asList() }.toTypedArray()

    private fun anyOf(check: (X509TrustManager) -> Unit) {
        val refusals = managers.map { manager ->
            try {
                check(manager)
                return
            } catch (refusal: CertificateException) {
                refusal
            }
        }
        throw refusals.first()
    }
}
