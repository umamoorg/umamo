package org.umamo.editor.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the update check's HTTP GET against a server on the loopback interface: the request carries the headers it
 * is given, a success and an error status both come back with their bodies - GitHub explains a rate limit in the
 * body of its 403 - and a request that gets no answer at all throws, which the check reports as unanswered.
 */
class HttpUpdateTransportTest {
	/**
	 * Runs [block] against a local server that answers every request with [status] and [body], and returns the
	 * request headers the server saw alongside the block's result.
	 *
	 * @param Int      status The status the server answers with.
	 * @param String   body   The body it answers with.
	 * @param Function block  Runs against the server's address.
	 * @return Pair The block's result, then the headers of the last request, by lower-case name.
	 */
	private fun <T> withServer(
		status: Int,
		body: String,
		block: (String) -> T,
	): Pair<T, Map<String, String>> {
		val seenHeaders = HashMap<String, String>()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			for ((name, values) in exchange.requestHeaders) {
				seenHeaders[name.lowercase()] = values.joinToString(",")
			}

			val bytes = body.toByteArray(Charsets.UTF_8)
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { output -> output.write(bytes) }
		}
		server.start()

		try {
			return block("http://127.0.0.1:${server.address.port}/repos/umamoorg/umamo/releases/latest") to seenHeaders
		} finally {
			server.stop(0)
		}
	}

	@Test
	fun theHeadersArriveAndTheBodyComesBack() {
		val (response, headers) =
			withServer(200, """{"tag_name":"v0.5.0"}""") { url ->
				runBlocking { HttpUpdateTransport().get(url, mapOf("User-Agent" to "Umamo/0.4.0", "Accept" to "application/vnd.github+json")) }
			}

		assertEquals(200, response.status)
		assertEquals("""{"tag_name":"v0.5.0"}""", response.body)
		assertEquals("Umamo/0.4.0", headers["user-agent"])
		assertEquals("application/vnd.github+json", headers["accept"])
	}

	@Test
	fun anErrorStatusComesBackWithItsBody() {
		for ((status, body) in listOf(404 to """{"message":"Not Found"}""", 403 to """{"message":"API rate limit exceeded"}""")) {
			val (response, _) = withServer(status, body) { url -> runBlocking { HttpUpdateTransport().get(url, emptyMap()) } }

			assertEquals(status, response.status)
			assertEquals(body, response.body)
		}
	}

	@Test
	fun theOverrideReplacesTheAddressAskedFor() {
		val (response, _) =
			withServer(200, "from the override") { url ->
				runBlocking { HttpUpdateTransport(overrideUrl = url).get("https://api.github.com/unused", emptyMap()) }
			}

		assertEquals("from the override", response.body)
	}

	@Test
	fun noAnswerThrows() {
		// A port that was free a moment ago: nothing listens on it, so the connection is refused.
		val closedPort = ServerSocket(0).use { socket -> socket.localPort }

		assertFailsWith<IOException> {
			runBlocking { HttpUpdateTransport().get("http://127.0.0.1:$closedPort/", emptyMap()) }
		}
	}
}