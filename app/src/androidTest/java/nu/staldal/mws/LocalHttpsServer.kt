package nu.staldal.mws

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** A controllable HTTPS origin for instrumented tests. No production trust settings are changed. */
class LocalHttpsServer : Closeable {
    private val certificate = HeldCertificate.decode(
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("test-server.pem").bufferedReader().use { it.readText() }
    )
    private val testCa = InstrumentationRegistry.getInstrumentation().targetContext.resources
        .openRawResource(R.raw.test_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate, testCa)
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(testCa)
        .build()
    private val server = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), false)
        start()
    }

    val url get() = server.url("/")
    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    /** Replaces the response queue with [handler], which runs on the server thread. */
    fun dispatch(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    override fun close() {
        server.shutdown()
    }
}
