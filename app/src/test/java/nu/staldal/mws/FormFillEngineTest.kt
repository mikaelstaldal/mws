package nu.staldal.mws

import android.webkit.WebView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FormFillEngineTest {
    private class Bridge : FormFillBridge {
        override val supported = true
        val registrations = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()
        val listeners = mutableListOf<(String, String, Boolean, (String) -> Unit) -> Unit>()
        var script = ""
        override fun add(name: String, origin: String, receive: (String, String, Boolean, (String) -> Unit) -> Unit) {
            registrations += name to origin; listeners += receive
        }
        override fun remove(name: String) { removed += name }
        override fun inject(script: String) { this.script = script }
        fun nonce(): String = Regex("\\\"mws_[a-z0-9]+\\\", \\\"([^\\\"]+)\\\"").find(script)!!.groupValues[1]
        fun ready(nonce: String = nonce()) = JSONObject().put("type", "ready").put("nonce", nonce).toString()
    }
    @Test fun transportRotatesRejectsBoundariesAndRepliesOnceUsingExactUrl() {
        val bridge = Bridge()
        var fetched: FormFillState.Document? = null
        var answer: ((MyPassClient.Outcome) -> Unit)? = null
        var requests = 0
        val engine = FormFillEngine(WebView(RuntimeEnvironment.getApplication()), { true }, { document, callback ->
            fetched = document; answer = callback; requests++
        }, {}, bridge)
        val url = "https://example.com:8443/login?return=%2F#form"
        engine.navigate(url)
        engine.started(url)
        engine.commit(url)
        assertEquals("https://example.com:8443", bridge.registrations.last().second)
        val receive = bridge.listeners.last()
        val replies = mutableListOf<String>()
        val reply: (String) -> Unit = { replies += it }
        receive(bridge.ready(), "https://example.com:8443", false, reply)
        receive(bridge.ready(), "https://other.example", true, reply)
        receive(bridge.ready("wrong"), "https://example.com:8443", true, reply)
        assertEquals(0, requests)
        receive(bridge.ready(), "https://example.com:8443", true, reply)
        receive(bridge.ready(), "https://example.com:8443", true, reply)
        assertEquals(1, requests); assertEquals(url, fetched!!.url)
        val credential = MyPassClient.Outcome.Credential("a\"b".toCharArray(), "secret".toCharArray())
        answer!!(credential); answer!!(credential)
        assertEquals(1, replies.size)
        assertEquals("secret", JSONObject(replies.single()).getString("password"))
        assertFalse(bridge.script.contains("secret"))
        val oldName = bridge.registrations.last().first
        engine.navigate(url); engine.started(url); engine.commit(url)
        assertNotEquals(oldName, bridge.registrations.last().first)
        assertTrue(oldName in bridge.removed)
        receive(bridge.ready(), "https://example.com:8443", true, reply)
        assertEquals(1, requests)
        engine.close()
    }
    @Test fun failureBeforeCommitAndDisabledFeatureNeverBootstrap() {
        val bridge = Bridge()
        var enabled = true
        val engine = FormFillEngine(WebView(RuntimeEnvironment.getApplication()), { enabled }, { _, _ -> fail("No request expected") }, {}, bridge)
        engine.navigate("https://example.com/login")
        engine.started("https://example.com/login")
        engine.rejectDocument()
        engine.commit("https://example.com/login")
        assertEquals("", bridge.script)
        enabled = false
        engine.navigate("https://example.com/login")
        engine.commit("https://example.com/login")
        assertEquals("", bridge.script)
        engine.close()
    }

    @Test fun lateCredentialsAfterNavigationAndUnavailableResultsDoNotFill() {
        val bridge = Bridge()
        var answer: ((MyPassClient.Outcome) -> Unit)? = null
        val engine = FormFillEngine(WebView(RuntimeEnvironment.getApplication()), { true }, { _, callback -> answer = callback }, {}, bridge)
        val url = "https://example.com/login"
        engine.navigate(url); engine.started(url); engine.commit(url)
        val replies = mutableListOf<String>()
        bridge.listeners.last()(bridge.ready(), "https://example.com", true) { replies += it }
        engine.navigate(url)
        answer!!(MyPassClient.Outcome.Credential("u".toCharArray(), "p".toCharArray()))
        assertTrue(replies.isEmpty())
        engine.started(url); engine.commit(url)
        bridge.listeners.last()(bridge.ready(), "https://example.com", true) { replies += it }
        answer!!(MyPassClient.Outcome.Unavailable)
        assertFalse(JSONObject(replies.single()).has("password"))
        engine.close()
    }
}
