package org.umamo.reimport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The polling watcher over an in-memory file system under the test clock: a write, a temp-file-then-
 * rename replace, and a delete each reach the listener of the file they touch; a sibling's write does
 * not; watching a file is not a change; and a closed subscription hears nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingSourceWatcherTest {
	@Test
	fun changesToTheWatchedFileReachItsListenerAndNothingElseDoes() =
		runTest {
			val fileSystem = FakeFileSystem()
			val directory = "/art".toPath()
			val watched = directory / "art.psd"
			val sibling = directory / "other.psd"
			fileSystem.createDirectories(directory)
			fileSystem.write(watched) { writeUtf8("1") }
			fileSystem.write(sibling) { writeUtf8("1") }
			val watcher = PollingSourceWatcher(this, fileSystem, pollMillis = 100)
			val deliveries = ArrayList<String>()
			val handle = watcher.watch(watched.toString()) { path -> deliveries.add(path) }

			advanceTimeBy(250)
			assertTrue(deliveries.isEmpty(), "watching a file is not a change")

			// A write that grows the file.
			fileSystem.write(watched) { writeUtf8("22") }
			advanceTimeBy(100)
			assertEquals(listOf(watched.toString()), deliveries, "the listener hears its own path")

			// A sibling's write is not this file's change.
			fileSystem.write(sibling) { writeUtf8("22") }
			advanceTimeBy(300)
			assertEquals(1, deliveries.size, "a sibling file is nobody's change")

			// The save an art program performs: a temporary file renamed over the watched one.
			val temporary = directory / "art.psd.tmp"
			fileSystem.write(temporary) { writeUtf8("333") }
			fileSystem.atomicMove(temporary, watched)
			advanceTimeBy(100)
			assertEquals(2, deliveries.size, "a rename-replace is a change")

			// A delete is a change, and so is the file returning.
			fileSystem.delete(watched)
			advanceTimeBy(100)
			assertEquals(3, deliveries.size, "a delete is a change")
			fileSystem.write(watched) { writeUtf8("4444") }
			advanceTimeBy(100)
			assertEquals(4, deliveries.size, "a return is a change")

			// Closed: silence, and the poll loop is gone.
			handle.close()
			fileSystem.write(watched) { writeUtf8("55555") }
			advanceTimeBy(300)
			assertEquals(4, deliveries.size, "a closed subscription hears nothing")
			watcher.close()
		}

	@Test
	fun aUriGetsANoOpHandle() =
		runTest {
			val watcher = PollingSourceWatcher(this, FakeFileSystem(), pollMillis = 100)
			watcher.watch("content://documents/1") {}.close()
			advanceTimeBy(300)
			watcher.close()
		}
}