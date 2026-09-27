package nu.staldal.mws

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.CookieHandler
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.MockResponse
import kotlin.concurrent.thread

/** Real Android URLConnection transport, including all four credentialless fetch classes. */
@RunWith(AndroidJUnit4::class)
class NativeMetadataInstrumentedTest {
    @Test fun allMetadataClassesIgnoreCookiesAndSetCookieAcrossRedirects() {
        assertNull("Ambient CookieHandler must be absent", CookieHandler.getDefault())
        LocalHttpsServer().use { server ->
            val requests = CopyOnWriteArrayList<Pair<String, String?>>()
            server.dispatch { request ->
                requests += request.path.orEmpty() to request.getHeader("Cookie")
                val response = MockResponse().setHeader("Set-Cookie", "metadata=should-not-persist; Secure; Path=/")
                if (request.path.orEmpty().startsWith("/redirect/"))
                    response.setResponseCode(302).setHeader("Location", request.path!!.removePrefix("/redirect"))
                else response.setBody("fixture")
            }
            val adapterCalls = AtomicInteger()
            val adapter = object : DiscoveryCookieAdapter {
                override fun requestCookie(context: DiscoveryCookieContext): String {
                    adapterCalls.incrementAndGet(); return "profile=should-not-send"
                }
                override fun receiveSetCookie(context: DiscoveryCookieContext, headers: List<String>) {
                    adapterCalls.incrementAndGet()
                }
            }
            val client = NativeDiscoveryClient(server.url.toString(), server.url.toString(),
                cookieAdapter = adapter, fullContextCookiesVerified = false,
                open = { url -> (URL(url).openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = server.client.sslSocketFactory
                } })
            assertTrue(client.authenticatedMetadataWarning)
            // Include mode must also use the mandatory credentialless fallback.
            for (credentials in listOf<String?>(null, "use-credentials")) {
                assertNotNull(client.fetchManifest(server.url.resolve("/redirect/manifest")!!.toString(), credentials))
                assertNotNull(client.fetch(IconCandidate(server.url.resolve("/redirect/manifest-icon")!!.toString(), IconCandidate.Source.MANIFEST)))
                assertNotNull(client.fetch(IconCandidate(server.url.resolve("/redirect/page-icon")!!.toString(), IconCandidate.Source.PAGE)))
                assertNotNull(client.fetch(IconCandidate(server.url.resolve("/favicon.ico")!!.toString(), IconCandidate.Source.FAVICON)))
            }
            assertEquals(14, requests.size)
            assertTrue("Cookies sent: $requests", requests.all { it.second.isNullOrEmpty() })
            assertEquals("Unqualified cookie adapter invoked", 0, adapterCalls.get())
        }
    }

    @Test fun crossOriginManifestRedirectUsesCorsAndCredentiallessFallback() {
        assertNull(CookieHandler.getDefault())
        LocalHttpsServer().use { source ->
            LocalHttpsServer().use { destination ->
                val origin = UrlPolicy.parseHttps(source.url.toString())!!.origin
                val cookies = CopyOnWriteArrayList<String>()
                val origins = CopyOnWriteArrayList<String>()
                source.dispatch { MockResponse().setResponseCode(302)
                    .setHeader("Location", destination.url.resolve("/manifest")!!.toString()) }
                destination.dispatch { request ->
                    cookies += request.getHeader("Cookie").orEmpty()
                    origins += request.getHeader("Origin").orEmpty()
                    MockResponse().setHeader("Access-Control-Allow-Origin", origin)
                        .setHeader("Access-Control-Allow-Credentials", "true")
                        .setHeader("Set-Cookie", "cross=ignored; Secure; Path=/").setBody("fixture")
                }
                val client = NativeDiscoveryClient(source.url.toString(), origin,
                    open = { url -> (URL(url).openConnection() as HttpsURLConnection).apply {
                        sslSocketFactory = source.client.sslSocketFactory
                    } })
                for (credentials in listOf<String?>(null, "use-credentials"))
                    assertNotNull(client.fetchManifest(source.url.resolve("/manifest")!!.toString(), credentials))
                assertEquals(listOf(origin, origin), origins)
                assertEquals(listOf("", ""), cookies)
                destination.dispatch { MockResponse().setBody("no CORS permission") }
                assertNull("Cross-origin manifest without ACAO must be rejected",
                    client.fetchManifest(source.url.resolve("/manifest")!!.toString(), null))
            }
        }
    }

    @Test fun metadataCleartextRedirectNeverOpensHttpDestination() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { listener ->
            val connections = AtomicInteger()
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    connections.incrementAndGet(); socket.close()
                }
            }
            LocalHttpsServer().use { server ->
                val httpsRequests = AtomicInteger()
                server.dispatch {
                    httpsRequests.incrementAndGet()
                    MockResponse().setResponseCode(307).setHeader("Location", "http://127.0.0.1:${listener.localPort}/leak")
                }
                val client = NativeDiscoveryClient(server.url.toString(), server.url.toString(),
                    open = { url -> (URL(url).openConnection() as HttpsURLConnection).apply {
                        sslSocketFactory = server.client.sslSocketFactory
                    } })
                assertNull(client.fetchManifest(server.url.resolve("/manifest")!!.toString(), null))
                client.retainManifestSource(server.url.resolve("/manifest")!!.toString())
                for (source in IconCandidate.Source.entries) {
                    val path = if (source == IconCandidate.Source.FAVICON) "/favicon.ico" else "/icon"
                    assertNull(client.fetch(IconCandidate(server.url.resolve(path)!!.toString(), source)))
                }
                assertEquals(4, httpsRequests.get())
                Thread.sleep(2000)
                assertEquals("Cleartext destination contacted", 0, connections.get())
            }
        }
    }
}
