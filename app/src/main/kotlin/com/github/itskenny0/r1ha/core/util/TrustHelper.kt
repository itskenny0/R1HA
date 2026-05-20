package com.github.itskenny0.r1ha.core.util

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Fixes "Unacceptable certificate: CN=ISRG Root X1" on Android < 7.1 (API < 25).
 *
 * Older OS trust stores shipped before September 2021 don't include the ISRG Root X1
 * certificate that Let's Encrypt switched to as its root.
 *
 * IMPORTANT: This uses a DELEGATION pattern — the system TrustManager is kept intact
 * and consulted first. We never try to copy its certificates (Android's
 * TrustManagerImpl.getAcceptedIssuers() deliberately returns an empty array, so
 * trying to build a new KeyStore from it produces a store with zero system CAs).
 *
 * On Android ≥ 7.1 (API 25) ISRG Root X1 is trusted natively; this helper is a
 * no-op for those devices (system check passes, fallback never runs).
 *
 * The embedded cert is the publicly-trusted ISRG Root X1 from
 * https://letsencrypt.org/certs/isrgrootx1.pem
 */
object TrustHelper {

    // ISRG Root X1 — valid until 2035-06-04.
    private const val ISRG_ROOT_X1_PEM = """
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoBggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
"""

    /**
     * Returns an [X509TrustManager] that delegates to the platform trust store first,
     * and falls back to trusting ISRG Root X1 if the system rejects the chain.
     *
     * Critically, this does NOT copy [X509TrustManager.getAcceptedIssuers] from the
     * system manager — Android returns an empty array from that method deliberately,
     * and copying it would produce a broken trust store containing zero system CAs.
     */
    fun buildTrustManager(): Pair<SSLContext, X509TrustManager> {
        val cf = CertificateFactory.getInstance("X.509")
        val isrgCert = cf.generateCertificate(
            ByteArrayInputStream(ISRG_ROOT_X1_PEM.trimIndent().toByteArray()),
        ) as X509Certificate

        // Build a small KeyStore / TrustManager for ISRG Root X1 only.
        val extraKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("isrg_root_x1", isrgCert)
        }
        val extraTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        extraTmf.init(extraKeyStore)
        val extraTm = extraTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        // System TrustManager — keep it fully intact. Never copy getAcceptedIssuers()
        // because Android's TrustManagerImpl returns an empty array from that method.
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        val systemTm = systemTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        // Delegating TrustManager: system first, ISRG Root X1 fallback.
        val mergedTm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                systemTm.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (primary: CertificateException) {
                    // System rejected — try our ISRG Root X1 fallback (for Android < 7.1).
                    try {
                        extraTm.checkServerTrusted(chain, authType)
                    } catch (_: CertificateException) {
                        throw primary // re-throw the original system error
                    }
                }
            }

            // Return empty — consistent with Android's own behaviour and avoids
            // the pitfall of returning a stale snapshot of system CAs.
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(mergedTm), null) }
        return sslContext to mergedTm
    }

    /**
     * Convenience — apply the merged trust config to an [OkHttpClient.Builder].
     * Safe to call on all API levels; on Android ≥ 7.1 the system check always
     * passes and the fallback never runs.
     */
    fun OkHttpClient.Builder.trustingIsrgRootX1(): OkHttpClient.Builder {
        val (ssl, tm) = buildTrustManager()
        return sslSocketFactory(ssl.socketFactory, tm)
    }
}

