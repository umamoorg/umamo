package org.umamo.ui.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the one property the host-to-shell open seam exists for: a request made before anything collects - a cold
 * launch's double-clicked file, which the OS delivers before the first composition - is held, not dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostOpenRequestsTest {
	/** Requests made before the shell exists reach it, in order, once it starts collecting. */
	@Test
	fun requestsMadeBeforeACollectorAreHeldInOrder() =
		runTest {
			val openRequests = HostOpenRequests()
			openRequests.request("/rigs/first.uma")
			openRequests.request("content://documents/second.uma")
			val opened = mutableListOf<String>()

			val collector = launch { openRequests.requests.collect { requestedPath -> opened += requestedPath } }
			runCurrent()

			assertEquals(listOf("/rigs/first.uma", "content://documents/second.uma"), opened)
			collector.cancelAndJoin()
		}

	/**
	 * A collector that went away - the shell recomposed under another document - does not take the seam with it:
	 * the next collector receives what is asked afterwards, and nothing is delivered twice.
	 */
	@Test
	fun aLaterCollectorReceivesLaterRequests() =
		runTest {
			val openRequests = HostOpenRequests()
			val openedByFirst = mutableListOf<String>()
			val first = launch { openRequests.requests.collect { requestedPath -> openedByFirst += requestedPath } }
			openRequests.request("/rigs/first.uma")
			runCurrent()
			first.cancelAndJoin()

			val openedBySecond = mutableListOf<String>()
			val second = launch { openRequests.requests.collect { requestedPath -> openedBySecond += requestedPath } }
			openRequests.request("/rigs/second.uma")
			runCurrent()

			assertEquals(listOf("/rigs/first.uma"), openedByFirst)
			assertEquals(listOf("/rigs/second.uma"), openedBySecond)
			second.cancelAndJoin()
		}
}