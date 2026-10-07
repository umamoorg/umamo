package org.umamo.edit

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.SessionTestModels.model
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The request buses as reached on the session: every request lands on its own flow carrying the payload the
 * command handed in, with the dispatch-time area riding along where the request carries one.  The session
 * reaches the bus by delegation, so these pin the wiring the overlay collectors depend on.  Each test
 * subscribes before it requests: a SharedFlow with no replay drops a tryEmit nobody is collecting.
 */
class SessionRequestsTest {
	@Test
	fun aSnapRequestCarriesItsKindAndArea() =
		runTest {
			val session = EditorSession(model())
			val received = mutableListOf<SnapRequest>()
			backgroundScope.launch { session.snapRequests.collect { request -> received += request } }
			runCurrent() // let the collector subscribe before the request
			session.requestSnap(SnapKind.CursorToSelected, "area-a")
			session.requestSnap(SnapKind.SelectionToGrid, null)
			runCurrent() // deliver the emissions
			assertEquals(listOf(SnapRequest(SnapKind.CursorToSelected, "area-a"), SnapRequest(SnapKind.SelectionToGrid, null)), received)
		}

	@Test
	fun aSelectLinkedRequestCarriesItsVariantAndArea() =
		runTest {
			val session = EditorSession(model())
			val received = mutableListOf<SelectLinkedRequest>()
			backgroundScope.launch { session.selectLinkedRequests.collect { request -> received += request } }
			runCurrent()
			session.requestSelectLinked(fromSelection = true, areaId = "area-b")
			runCurrent()
			assertEquals(listOf(SelectLinkedRequest(fromSelection = true, areaId = "area-b")), received)
		}

	@Test
	fun theUvRequestsPassTheirPayloadsThrough() =
		runTest {
			val session = EditorSession(model())
			val snaps = mutableListOf<UvSnapRequest>()
			val mirrors = mutableListOf<UvMirrorRequest>()
			val pages = mutableListOf<UvPageRequest>()
			backgroundScope.launch { session.uvSnapRequests.collect { request -> snaps += request } }
			backgroundScope.launch { session.uvMirrorRequests.collect { request -> mirrors += request } }
			backgroundScope.launch { session.uvPageRequests.collect { request -> pages += request } }
			runCurrent()
			session.requestUvSnap(UvSnapRequest(UvSnapKind.SelectionToPixels, "uv-a"))
			session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = "uv-a"))
			session.requestUvPage(UvPageRequest(UvPageKind.NextPage, "uv-a"))
			runCurrent()
			assertEquals(listOf(UvSnapRequest(UvSnapKind.SelectionToPixels, "uv-a")), snaps)
			assertEquals(listOf(UvMirrorRequest(mirrorU = true, areaId = "uv-a")), mirrors)
			assertEquals(listOf(UvPageRequest(UvPageKind.NextPage, "uv-a")), pages)
		}

	@Test
	fun theAreaOnlyRequestsCarryTheArea() =
		runTest {
			val session = EditorSession(model())
			val switches = mutableListOf<String?>()
			val rips = mutableListOf<String?>()
			backgroundScope.launch { session.switchObjectRequests.collect { areaId -> switches += areaId } }
			backgroundScope.launch { session.ripRequests.collect { areaId -> rips += areaId } }
			runCurrent()
			session.requestSwitchObjectUnderCursor("viewport-a")
			session.requestRip(null)
			runCurrent()
			assertEquals(listOf<String?>("viewport-a"), switches)
			assertEquals(listOf<String?>(null), rips, "a request with no area still fires, for the collectors to ignore")
		}

	@Test
	fun theConfirmAndCancelRequestsFireOnce() =
		runTest {
			val session = EditorSession(model())
			var confirms = 0
			var cancels = 0
			backgroundScope.launch { session.meshConfirmRequests.collect { confirms += 1 } }
			backgroundScope.launch { session.meshGestureCancelRequests.collect { cancels += 1 } }
			runCurrent()
			session.requestMeshConfirm()
			session.requestMeshGestureCancel()
			runCurrent()
			assertEquals(1, confirms)
			assertEquals(1, cancels)
		}
}