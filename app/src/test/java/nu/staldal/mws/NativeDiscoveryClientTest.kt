package nu.staldal.mws

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeDiscoveryClientTest {
    private val doc = "https://site.example/page#fragment"

    private class Reply(
        url: String,
        private val code: Int = 200,
        private val body: ByteArray = byteArrayOf(1, 2),
        private val headers: Map<String, List<String>> = emptyMap(),
    ) : HttpURLConnection(URL(url)) {
        val request = mutableMapOf<String, String>()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderFields(): Map<String, List<String>> = headers
        override fun getHeaderField(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        override fun setRequestProperty(key: String, value: String) { request[key] = value }
    }

    private fun client(vararg replies: Reply, limits: NativeDiscoveryClient.Limits = NativeDiscoveryClient.Limits(),
        cookies: DiscoveryCookieAdapter? = null, enabled: Boolean = false,
        auth: DiscoveryBasicAuth? = null): NativeDiscoveryClient {
        val queue = ArrayDeque(replies.toList())
        return NativeDiscoveryClient(doc, "https://site.example", cookies, enabled, auth, limits,
            open = { queue.removeFirst() })
    }

    @Test fun manifestCorsAndCredentialsModes() {
        val cross = "https://other.example/manifest"
        val wildcard = Reply(cross, headers = mapOf("Access-Control-Allow-Origin" to listOf("*")))
        assertNotNull(client(wildcard).fetchManifest(cross, null))
        assertEquals("https://site.example", wildcard.request["Origin"])
        assertNull(client(Reply(cross, headers = mapOf("Access-Control-Allow-Origin" to listOf("*"))))
            .fetchManifest(cross, "use-credentials"))
        val allowed = Reply(cross, headers = mapOf("Access-Control-Allow-Origin" to listOf("https://site.example"),
            "Access-Control-Allow-Credentials" to listOf("true")))
        assertNotNull(client(allowed).fetchManifest(cross, "use-credentials"))
        assertNull(client(Reply(cross)).fetchManifest(cross, null))
        assertNotNull(client(Reply("https://site.example/manifest"))
            .fetchManifest("https://site.example/manifest", null))
    }

    @Test fun allIconClassesAndReferrers() {
        val manifest = Reply("https://site.example/manifest")
        val manifestIcon = Reply("https://site.example/m.png")
        val pageIcon = Reply("https://other.example/p.png")
        val favicon = Reply("https://site.example/favicon.ico")
        val c = client(manifest, manifestIcon, pageIcon, favicon)
        c.setReferrerPolicies("origin", "no-referrer")
        assertNotNull(c.fetchManifest("https://site.example/manifest", null))
        assertNotNull(c.fetch(IconCandidate("https://site.example/m.png", IconCandidate.Source.MANIFEST)))
        assertEquals("https://site.example/", manifestIcon.request["Referer"])
        assertNotNull(c.fetch(IconCandidate("https://other.example/p.png", IconCandidate.Source.PAGE)))
        assertNull(pageIcon.request["Referer"])
        assertNotNull(c.fetch(IconCandidate("https://site.example/favicon.ico", IconCandidate.Source.FAVICON)))
        assertEquals("https://site.example/", favicon.request["Referer"])
        assertNull(c.fetch(IconCandidate("http://site.example/favicon.ico", IconCandidate.Source.FAVICON)))
        assertNull(c.fetch(IconCandidate("https://other.example/favicon.ico", IconCandidate.Source.FAVICON)))
    }

    @Test fun redirectsRecheckHttpsAndStripCredentials() {
        val a = Reply("https://site.example/a", 302, headers = mapOf("Location" to listOf("https://other.example/b")))
        val b = Reply("https://other.example/b")
        val adapter = object : DiscoveryCookieAdapter {
            override fun requestCookie(context: DiscoveryCookieContext) = "session=secret"
            override fun receiveSetCookie(context: DiscoveryCookieContext, headers: List<String>) {}
        }
        val c = client(a, b, cookies = adapter, enabled = true,
            auth = DiscoveryBasicAuth { _, _ -> "user" to "pass" })
        assertNotNull(c.fetch(IconCandidate("https://site.example/a", IconCandidate.Source.PAGE)))
        assertEquals("session=secret", a.request["Cookie"])
        assertTrue(b.request["Cookie"].isNullOrEmpty())
        assertTrue(b.request["Authorization"].isNullOrEmpty())
        assertNull(client(Reply("https://site.example/a", 302,
            headers = mapOf("Location" to listOf("http://site.example/b"))))
            .fetch(IconCandidate("https://site.example/a", IconCandidate.Source.PAGE)))
        assertNull(client().fetchManifest("http://site.example/manifest", null))
    }

    @Test fun credentiallessDefaultAndBasicHook() {
        val first = Reply("https://site.example/a", 401,
            headers = mapOf("WWW-Authenticate" to listOf("Basic realm=\"private\""),
                "Set-Cookie" to listOf("a=b")))
        val second = Reply("https://site.example/a")
        var stored = false
        val adapter = object : DiscoveryCookieAdapter {
            override fun requestCookie(context: DiscoveryCookieContext) = "a=b"
            override fun receiveSetCookie(context: DiscoveryCookieContext, headers: List<String>) { stored = true }
        }
        val c = client(first, second, cookies = adapter,
            auth = DiscoveryBasicAuth { _, realm -> if (realm == "private") "user" to "pass" else null })
        assertTrue(c.authenticatedMetadataWarning)
        assertNotNull(c.fetch(IconCandidate("https://site.example/a", IconCandidate.Source.PAGE)))
        assertTrue(first.request["Cookie"].isNullOrEmpty())
        assertFalse(stored)
        assertTrue(second.request["Authorization"]!!.startsWith("Basic "))
    }

    @Test fun basicCredentialIsNotSentProactivelyToSameOriginRedirectTarget() {
        val first = Reply("https://site.example/a", 401,
            headers = mapOf("WWW-Authenticate" to listOf("Basic realm=\"private\"")))
        val retry = Reply("https://site.example/a", 302,
            headers = mapOf("Location" to listOf("/b")))
        val target = Reply("https://site.example/b")
        val c = client(first, retry, target, auth = DiscoveryBasicAuth { _, _ -> "user" to "pass" })
        assertNotNull(c.fetch(IconCandidate("https://site.example/a", IconCandidate.Source.PAGE)))
        assertTrue(retry.request["Authorization"]!!.startsWith("Basic "))
        assertTrue(target.request["Authorization"].isNullOrEmpty())
    }

    @Test fun verifiedFullContextCookiesFollowCredentialsMode() {
        val cross = "https://other.example/m"
        val received = mutableListOf<DiscoveryCookieContext>()
        val adapter = object : DiscoveryCookieAdapter {
            override fun requestCookie(context: DiscoveryCookieContext): String {
                received += context
                return "session=yes"
            }
            override fun receiveSetCookie(context: DiscoveryCookieContext, headers: List<String>) {
                if (headers.isNotEmpty()) received += context
            }
        }
        val include = Reply(cross, headers = mapOf(
            "Access-Control-Allow-Origin" to listOf("https://site.example"),
            "Access-Control-Allow-Credentials" to listOf("true"),
            "Set-Cookie" to listOf("session=renewed")))
        val c = client(include, cookies = adapter, enabled = true)
        assertFalse(c.authenticatedMetadataWarning)
        assertNotNull(c.fetchManifest(cross, "use-credentials"))
        assertEquals("session=yes", include.request["Cookie"])
        assertEquals(2, received.size)
        assertEquals(DiscoveryCookieContext.Credentials.INCLUDE, received.first().credentials)
        assertEquals("https://site.example", received.first().topLevelSite)
        assertEquals("https://site.example", received.first().partitionKey)

        val sameOriginOnly = Reply(cross, headers = mapOf("Access-Control-Allow-Origin" to listOf("*")))
        assertNotNull(client(sameOriginOnly, cookies = adapter, enabled = true)
            .fetchManifest(cross, null))
        assertTrue(sameOriginOnly.request["Cookie"].isNullOrEmpty())
    }

    @Test fun crossOriginManifestIconCannotRequestBasicCredential() {
        val cross = "https://other.example/manifest"
        val manifest = Reply(cross, headers = mapOf("Access-Control-Allow-Origin" to listOf("*")))
        val icon = Reply("https://other.example/icon", 401,
            headers = mapOf("WWW-Authenticate" to listOf("Basic realm=\"private\"")))
        var calls = 0
        val c = client(manifest, icon, auth = DiscoveryBasicAuth { _, _ ->
            calls++
            "user" to "pass"
        })
        assertNotNull(c.fetchManifest(cross, null))
        assertNull(c.fetch(IconCandidate("https://other.example/icon", IconCandidate.Source.MANIFEST)))
        assertEquals(0, calls)
        assertTrue(icon.request["Authorization"].isNullOrEmpty())
    }

    @Test fun corsIsCheckedAcrossRedirectChain() {
        val source = "https://other.example/manifest"
        val denied = Reply(source, 302, headers = mapOf("Location" to listOf("https://site.example/m")))
        assertNull(client(denied).fetchManifest(source, null))
        val allowed = Reply(source, 302, headers = mapOf("Location" to listOf("https://site.example/m"),
            "Access-Control-Allow-Origin" to listOf("*")))
        val final = Reply("https://site.example/m", headers = mapOf("Access-Control-Allow-Origin" to listOf("*")))
        assertEquals("https://site.example/m", client(allowed, final).fetchManifest(source, null)?.url)
    }

    @Test fun fallbackAndMetadataClientsShareAttemptBudget() {
        val budget = java.util.concurrent.atomic.AtomicLong(0)
        fun stage() = NativeDiscoveryClient(doc, "https://site.example",
            limits = NativeDiscoveryClient.Limits(attemptBytes = 3),
            open = { Reply("https://site.example/icon", body = byteArrayOf(1, 2)) },
            attemptBytes = budget)
        val candidate = IconCandidate("https://site.example/icon", IconCandidate.Source.PAGE)
        assertNotNull(stage().fetch(candidate))
        val next = stage()
        assertNull(next.fetch(candidate))
        assertTrue(next.limitsExceeded)
    }

    @Test fun retainedManifestUrlSuppliesIconRequestContext() {
        val c = client(Reply("https://site.example/icon", body = byteArrayOf(1)))
        c.retainManifestSource("https://site.example/manifest.json")
        assertNotNull(c.fetch(IconCandidate("https://site.example/icon", IconCandidate.Source.MANIFEST)))
    }

    @Test fun sizeAggregateRedirectAndCancellationLimits() {
        val small = NativeDiscoveryClient.Limits(manifestDecodedBytes = 2, iconEncodedBytes = 2,
            attemptBytes = 3, redirects = 0)
        assertNull(client(Reply("https://site.example/m", body = byteArrayOf(1, 2, 3)), limits = small)
            .fetchManifest("https://site.example/m", null))
        val c = client(Reply("https://site.example/a", body = byteArrayOf(1, 2)),
            Reply("https://site.example/b", body = byteArrayOf(1, 2)), limits = small)
        assertNotNull(c.fetch(IconCandidate("https://site.example/a", IconCandidate.Source.PAGE)))
        assertNull(c.fetch(IconCandidate("https://site.example/b", IconCandidate.Source.PAGE)))
        assertTrue(c.limitsExceeded)
        assertNull(client(Reply("https://site.example/a", 302,
            headers = mapOf("Location" to listOf("/b"))), limits = small)
            .fetch(IconCandidate("https://site.example/a", IconCandidate.Source.PAGE)))
        c.cancel()
        assertNull(c.fetch(IconCandidate("https://site.example/c", IconCandidate.Source.PAGE)))
    }

    @Test fun compressedBodyIsLimitedAfterDecodingAndCountedInAggregate() {
        val zipped = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(100) { 65 }) }
        }.toByteArray()
        val limits = NativeDiscoveryClient.Limits(manifestDecodedBytes = 100,
            attemptBytes = 100)
        val c = client(Reply("https://site.example/m", body = zipped,
            headers = mapOf("Content-Encoding" to listOf("gzip"))), limits = limits)
        assertEquals(100, c.fetchManifest("https://site.example/m", null)?.body?.size)
        val tooSmall = limits.copy(manifestDecodedBytes = 99)
        assertNull(client(Reply("https://site.example/m", body = zipped,
            headers = mapOf("Content-Encoding" to listOf("gzip"))), limits = tooSmall)
            .fetchManifest("https://site.example/m", null))
    }

    @Test fun resourceDeadlineStopsAResponse() {
        var clock = 0L
        val c = NativeDiscoveryClient(doc, "https://site.example",
            open = { Reply(it) }, nowMillis = { clock.also { clock += 5_000 } })
        assertNull(c.fetchManifest("https://site.example/m", null))
    }

    @Test fun cancellationDisconnectsBlockedResponse() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://site.example/m")) {
            override fun connect() {}
            override fun usingProxy() = false
            override fun disconnect() { released.countDown() }
            override fun getResponseCode(): Int {
                entered.countDown()
                released.await(2, TimeUnit.SECONDS)
                return 200
            }
        }
        val c = NativeDiscoveryClient(doc, "https://site.example", open = { connection })
        var response: DiscoveryResponse? = null
        val worker = Thread { response = c.fetchManifest("https://site.example/m", null) }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        c.cancel()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertNull(response)
    }

    @Test fun watchdogDisconnectsBlockedResponse() {
        val released = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://site.example/m")) {
            override fun connect() {}
            override fun usingProxy() = false
            override fun disconnect() { released.countDown() }
            override fun getResponseCode(): Int {
                released.await(2, TimeUnit.SECONDS)
                return 200
            }
        }
        val c = NativeDiscoveryClient(doc, "https://site.example",
            limits = NativeDiscoveryClient.Limits(resourceMillis = 50), open = { connection })
        assertNull(c.fetchManifest("https://site.example/m", null))
        assertTrue(released.count == 0L)
    }
}
