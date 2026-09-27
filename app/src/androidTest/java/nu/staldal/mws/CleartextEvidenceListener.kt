package nu.staldal.mws

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Counts bytes without retaining request contents; each connection has an independent reader. */
internal class CleartextEvidenceListener : Closeable {
    private val server = ServerSocket(0)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val bytes = AtomicInteger()
    val errors = AtomicInteger()
    val url get() = "http://127.0.0.1:${server.localPort}/leak"
    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                if (server.isClosed) { socket.close(); sockets.remove(socket); break }
                thread(isDaemon = true) {
                    try {
                        socket.use {
                            socket.soTimeout = 2000
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = socket.getInputStream().read(buffer)
                                if (count < 0) break
                                bytes.addAndGet(count)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Idle speculative connection.
                    } catch (_: Exception) {
                        if (!server.isClosed) errors.incrementAndGet()
                    } finally { sockets.remove(socket) }
                }
            }
        }
    }
    fun calibrate() {
        Socket("127.0.0.1", server.localPort).use { it.getOutputStream().write(1) }
        val end = android.os.SystemClock.uptimeMillis() + 5000
        while (bytes.get() == 0 && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(20)
        org.junit.Assert.assertEquals("Listener calibration", 1, bytes.getAndAdd(-1))
    }
    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
