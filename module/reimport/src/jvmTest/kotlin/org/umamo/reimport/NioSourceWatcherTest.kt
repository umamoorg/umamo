package org.umamo.reimport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The java.nio watcher over a real temporary directory: a write, a temp-file-then-rename replace, and
 * a delete each reach the listener of the file they touch, a sibling's write does not, and closing
 * the subscription stops delivery.  The platform service may poll (macOS), so every wait is generous.
 */
class NioSourceWatcherTest {
	private suspend fun awaitDelivery(deliveries: List<String>, count: Int) {
		withTimeout(15_000) {
			while (deliveries.size < count) {
				delay(50)
			}
		}
	}

	@Test
	fun changesToTheWatchedFileReachItsListenerAndNothingElseDoes() =
		runBlocking {
			val directory = Files.createTempDirectory("umamo-watch")
			val watched = directory.resolve("art.psd")
			val sibling = directory.resolve("other.psd")
			Files.write(watched, byteArrayOf(1))
			Files.write(sibling, byteArrayOf(1))
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val watcher = NioSourceWatcher(scope)
			val deliveries = CopyOnWriteArrayList<String>()
			val handle = watcher.watch(watched.toString()) { path -> deliveries.add(path) }
			try {
				// The service needs a moment to arm on platforms that poll.
				delay(500)
				Files.write(watched, byteArrayOf(2))
				awaitDelivery(deliveries, 1)
				assertEquals(watched.toString(), deliveries.first(), "the listener hears its own path")

				// A sibling's write is not this file's change.
				val before = deliveries.size
				Files.write(sibling, byteArrayOf(2))
				delay(1_500)
				assertEquals(before, deliveries.size, "a sibling file is filtered out")

				// The save an art program performs: a temporary file renamed over the watched one.
				val temporary: Path = directory.resolve("art.psd.tmp")
				Files.write(temporary, byteArrayOf(3))
				Files.move(temporary, watched, StandardCopyOption.REPLACE_EXISTING)
				awaitDelivery(deliveries, before + 1)

				// A delete is a change too.
				val beforeDelete = deliveries.size
				Files.delete(watched)
				awaitDelivery(deliveries, beforeDelete + 1)

				// Closed: silence.
				handle.close()
				val afterClose = deliveries.size
				Files.write(watched, byteArrayOf(4))
				delay(1_500)
				assertEquals(afterClose, deliveries.size, "a closed subscription hears nothing")
			} finally {
				watcher.close()
				scope.cancel()
				runCatching { Files.deleteIfExists(watched) }
				runCatching { Files.deleteIfExists(sibling) }
				runCatching { Files.deleteIfExists(directory) }
			}
		}

	@Test
	fun aUriAndARootPathGetNoOpHandles() {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val watcher = NioSourceWatcher(scope)
		try {
			watcher.watch("content://documents/1") {}.close()
			watcher.watch("/") {}.close()
			assertTrue(true, "neither registration throws")
		} finally {
			watcher.close()
			scope.cancel()
		}
	}
}