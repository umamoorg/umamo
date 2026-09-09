package org.umamo.reimport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.umamo.runtime.model.ArtSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reload policy under a test clock: settling, the hash check that drops a no-op save, the idle
 * gate, the three modes, a missing and returning file, the open-time staleness check, and how a
 * finished reload feeds back.  The watcher is a fake that fires on demand; the disk is a map.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceWatchCoordinatorTest {
	private val fileA = ArtSourceId("art-0")
	private val fileB = ArtSourceId("art-1")

	/** A watcher the test fires by hand, recording who watches what. */
	private class FakeWatcher : SourceWatcher {
		val listeners = LinkedHashMap<String, MutableList<SourceWatcher.ChangeListener>>()

		override fun watch(path: String, listener: SourceWatcher.ChangeListener): AutoCloseable {
			listeners.getOrPut(path) { ArrayList() }.add(listener)
			return AutoCloseable { listeners[path]?.remove(listener) }
		}

		fun fire(path: String) {
			listeners[path].orEmpty().toList().forEach { listener -> listener.onChanged(path) }
		}
	}

	/** The disk: a hash per path, or null for a file that cannot be read; absent means missing. */
	private class Disk {
		val hashByPath = HashMap<String, String?>()

		fun hashOf(path: String): String? = hashByPath[path]

		fun exists(path: String): Boolean = hashByPath.containsKey(path)
	}

	/**
	 * The coordinator over the TEST scope itself (this coroutines-test version does not advance
	 * backgroundScope work on advanceUntilIdle), so every test ends by calling [finish].
	 */
	private class Harness(scope: TestScope, val mode: () -> WatchMode, val idle: () -> Boolean = { true }) {
		val watcher = FakeWatcher()
		val disk = Disk()
		val events = ArrayList<SourceWatchEvent>()
		val coordinator =
			SourceWatchCoordinator(
				scope = scope,
				watcher = watcher,
				hashOf = { path -> disk.hashOf(path) },
				exists = { path -> disk.exists(path) },
				isIdle = idle,
				mode = mode,
				settleMillis = 100,
				idlePollMillis = 10,
			)
		private val collector: Job = scope.launch { coordinator.events.collect { event -> events.add(event) } }

		fun finish() {
			coordinator.close()
			collector.cancel()
		}
	}

	private fun TestScope.harness(mode: WatchMode = WatchMode.Auto, idle: () -> Boolean = { true }): Harness = Harness(this, { mode }, idle)

	@Test
	fun aSettledChangeReloadsOnceAndANoOpSaveIsDropped() =
		runTest {
			val harness = harness()
			harness.disk.hashByPath["/a.psd"] = "h1"
			harness.coordinator.track(listOf(WatchedSource(fileA, "/a.psd", "h1")))
			advanceUntilIdle()
			assertTrue(harness.events.isEmpty(), "a file matching its record is quiet at open")

			// A save that changed nothing: the hash is the record's.
			harness.watcher.fire("/a.psd")
			advanceTimeBy(200)
			advanceUntilIdle()
			assertTrue(harness.events.isEmpty(), "the same hash is dropped")
			assertTrue(harness.coordinator.pending.value.isEmpty())

			// A real change, written in two bursts inside one settle window.
			harness.disk.hashByPath["/a.psd"] = "h2"
			harness.watcher.fire("/a.psd")
			advanceTimeBy(50)
			harness.watcher.fire("/a.psd")
			advanceTimeBy(50)
			assertTrue(harness.events.isEmpty(), "the second event restarted the settle")
			advanceTimeBy(100)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.ReloadDue(setOf(fileA))), harness.events)
			assertEquals(setOf(fileA), harness.coordinator.pending.value)

			// The reload landed: the model's record caught up, and tracking it clears the debt.
			harness.coordinator.reloadFinished(setOf(fileA), WatchedReloadResult.Applied)
			harness.coordinator.track(listOf(WatchedSource(fileA, "/a.psd", "h2")))
			advanceUntilIdle()
			assertTrue(harness.coordinator.pending.value.isEmpty())
			harness.finish()
		}

	@Test
	fun anUnreadableFileWaitsAndAMissingOneIsReported() =
		runTest {
			val harness = harness()
			harness.disk.hashByPath["/a.psd"] = "h1"
			harness.coordinator.track(listOf(WatchedSource(fileA, "/a.psd", "h1")))
			advanceUntilIdle()

			// Mid-write: present but unreadable for two windows, then done.
			harness.disk.hashByPath["/a.psd"] = null
			harness.watcher.fire("/a.psd")
			advanceTimeBy(250)
			assertTrue(harness.events.isEmpty(), "an unreadable file is waited for")
			harness.disk.hashByPath["/a.psd"] = "h2"
			advanceTimeBy(100)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.ReloadDue(setOf(fileA))), harness.events)
			harness.events.clear()

			// Deleted: reported once, the pending debt dropped, the presence serial bumped.
			val serialBefore = harness.coordinator.serial.value
			harness.disk.hashByPath.remove("/a.psd")
			harness.watcher.fire("/a.psd")
			advanceTimeBy(100)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.Missing(fileA)), harness.events)
			assertTrue(harness.coordinator.pending.value.isEmpty())
			assertEquals(serialBefore + 1, harness.coordinator.serial.value)
			harness.events.clear()

			// Back, with new content: a change, and the serial moves again.
			harness.disk.hashByPath["/a.psd"] = "h3"
			harness.watcher.fire("/a.psd")
			advanceTimeBy(100)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.ReloadDue(setOf(fileA))), harness.events)
			assertEquals(serialBefore + 2, harness.coordinator.serial.value)
			harness.finish()
		}

	@Test
	fun autoWaitsForTheEditorToGoIdleAndGathersWhatArrivedMeanwhile() =
		runTest {
			var idle = false
			val harness = harness(idle = { idle })
			harness.disk.hashByPath["/a.psd"] = "h1"
			harness.disk.hashByPath["/b.psd"] = "k1"
			harness.coordinator.track(listOf(WatchedSource(fileA, "/a.psd", "h1"), WatchedSource(fileB, "/b.psd", "k1")))
			advanceUntilIdle()
			harness.disk.hashByPath["/a.psd"] = "h2"
			harness.watcher.fire("/a.psd")
			advanceTimeBy(500)
			assertTrue(harness.events.isEmpty(), "nothing fires while a gesture is in flight")
			harness.disk.hashByPath["/b.psd"] = "k2"
			harness.watcher.fire("/b.psd")
			advanceTimeBy(500)
			idle = true
			advanceTimeBy(20)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.ReloadDue(setOf(fileA, fileB))), harness.events, "one reload for both once idle")
			harness.finish()
		}

	@Test
	fun notifyReportsAndOffTracksNothing() =
		runTest {
			val notify = harness(WatchMode.Notify)
			notify.disk.hashByPath["/a.psd"] = "h1"
			notify.coordinator.track(listOf(WatchedSource(fileA, "/a.psd", "h1")))
			advanceUntilIdle()
			notify.disk.hashByPath["/a.psd"] = "h2"
			notify.watcher.fire("/a.psd")
			advanceTimeBy(100)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.ChangedOnDisk(setOf(fileA))), notify.events)
			assertEquals(setOf(fileA), notify.coordinator.pending.value, "the debt stays until Reload is pressed")

			val off = harness(WatchMode.Off)
			off.disk.hashByPath["/a.psd"] = "h1"
			off.coordinator.track(listOf(WatchedSource(fileA, "/a.psd", "h0")))
			advanceUntilIdle()
			assertTrue(off.watcher.listeners.values.all { listeners -> listeners.isEmpty() }, "off watches nothing")
			assertTrue(off.events.isEmpty())
			notify.finish()
			off.finish()
		}

	@Test
	fun aFileEditedWhileClosedIsStaleAtOpenAndAFinishedReloadFeedsBack() =
		runTest {
			val harness = harness()
			harness.disk.hashByPath["/a.psd"] = "h9"
			harness.coordinator.track(listOf(WatchedSource(fileA, "/a.psd", "h1")))
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.StaleAtOpen(setOf(fileA))), harness.events, "reported, never auto-reloaded")
			assertEquals(setOf(fileA), harness.coordinator.pending.value)
			harness.events.clear()

			// A reload that found nothing to change acknowledges the disk hash: no further report for it.
			harness.coordinator.reloadFinished(setOf(fileA), WatchedReloadResult.NothingChanged)
			assertTrue(harness.coordinator.pending.value.isEmpty())
			harness.watcher.fire("/a.psd")
			advanceTimeBy(100)
			advanceUntilIdle()
			assertTrue(harness.events.isEmpty(), "the acknowledged hash is dropped")

			// A superseded reload is tried again after a settle.
			harness.disk.hashByPath["/a.psd"] = "h10"
			harness.watcher.fire("/a.psd")
			advanceTimeBy(100)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.ReloadDue(setOf(fileA))), harness.events)
			harness.events.clear()
			harness.coordinator.reloadFinished(setOf(fileA), WatchedReloadResult.Superseded)
			advanceTimeBy(100)
			advanceUntilIdle()
			assertEquals(listOf<SourceWatchEvent>(SourceWatchEvent.ReloadDue(setOf(fileA))), harness.events, "asked again")

			// Untracked files stop; the coordinator closes clean.
			harness.coordinator.track(emptyList())
			assertTrue(harness.watcher.listeners.values.all { listeners -> listeners.isEmpty() })
			assertTrue(harness.coordinator.pending.value.isEmpty())
			harness.finish()
		}

	@Test
	fun theModeKeyRoundTripsAndAStaleValueFallsBack() {
		for (mode in WatchMode.entries) {
			assertEquals(mode, WatchMode.fromKey(mode.key))
		}
		assertEquals(WatchMode.Default, WatchMode.fromKey("not-a-mode"))
		assertEquals(WatchMode.Default, WatchMode.fromKey(null))
	}
}