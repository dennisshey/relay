package com.sidephone.aviary.transport.signal

import android.content.Context
import com.sidephone.aviary.R
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Signal's chat service is served by Signal's own private CA, not a public one,
 * so the default Android trust store rejects it. We trust exactly that CA (from
 * the bundled whisper.store, the same file Signal-Android ships) and nothing else
 * — a self-contained equivalent of Signal's certificate pinning.
 */
object SignalTrust {

    @Volatile private var cached: Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager>? = null

    private fun trust(context: Context): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager> {
        cached?.let { return it }
        val keyStore = KeyStore.getInstance("BKS", BouncyCastleProvider())
        context.resources.openRawResource(R.raw.whisper_store).use { input ->
            keyStore.load(input, "whisper".toCharArray())
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return (sslContext.socketFactory to trustManager).also { cached = it }
    }

    /**
     * NOTE: no OkHttp pingInterval. Signal's servers don't answer WebSocket ping
     * frames, so an OkHttp ping would time out and tear the socket down after 30s
     * (breaking provisioning QR validity and receive). Long-lived sockets keep
     * themselves alive with Signal's own application-level /v1/keepalive request.
     */
    fun okHttpClient(context: Context): OkHttpClient {
        val (factory, trustManager) = trust(context)
        return OkHttpClient.Builder()
            .sslSocketFactory(factory, trustManager)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
