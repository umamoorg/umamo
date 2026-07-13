package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins the AreaScope.spaceState contract: one instance per key per scope (the header slot and the
 * body must observe the same object), created lazily, and independent across scopes (two areas
 * hosting the same space never share state).
 */
class AreaScopeTest {
	/** Repeated requests under one key return the same instance; the factory runs once. */
	@Test
	fun sameKeyReturnsSameInstance() {
		val scope = AreaScope(areaId = "area-1")
		var factoryRuns = 0
		val firstRequest =
			scope.spaceState("outliner.view") {
				factoryRuns++
				Any()
			}
		val secondRequest =
			scope.spaceState("outliner.view") {
				factoryRuns++
				Any()
			}
		assertSame(firstRequest, secondRequest)
		assertEquals(1, factoryRuns)
	}

	/** Different keys park different objects. */
	@Test
	fun differentKeysAreIndependent() {
		val scope = AreaScope(areaId = "area-1")
		val outlinerState = scope.spaceState("outliner.view") { Any() }
		val otherState = scope.spaceState("uv.view") { Any() }
		assertNotSame(outlinerState, otherState)
	}

	/** Two scopes (two areas) never share state under the same key. */
	@Test
	fun scopesAreIndependent() {
		val firstScope = AreaScope(areaId = "area-1")
		val secondScope = AreaScope(areaId = "area-2")
		val firstState = firstScope.spaceState("outliner.view") { Any() }
		val secondState = secondScope.spaceState("outliner.view") { Any() }
		assertNotSame(firstState, secondState)
	}
}
