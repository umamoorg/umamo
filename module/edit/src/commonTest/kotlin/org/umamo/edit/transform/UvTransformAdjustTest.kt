package org.umamo.edit.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.SessionTestModels.meshModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for UvTransformAdjust.kt: what a UV transform's adjusted proportional rows hand back to the session.
 */
class UvTransformAdjustTest {
	/** A state carrying a texel radius turns proportional editing on with the session's remembered world radius. */
	@Test
	fun aTexelRadiusStateKeepsTheRememberedWorldRadius() {
		val session = EditorSession(meshModel())
		session.setProportionalRadius(345f)
		assertNull(session.proportionalEdit.value, "precondition: proportional editing is off")

		session.setProportionalEditKeepingRadius(ProportionalEditState(ProportionalFalloff.Linear, 12f))

		val state = assertNotNull(session.proportionalEdit.value)
		assertEquals(ProportionalFalloff.Linear, state.falloff)
		assertEquals(345f, state.radiusWorld, "the texel radius never becomes the world radius")
	}

	/** A null state turns proportional editing off and leaves the remembered world radius alone. */
	@Test
	fun aNullStateTurnsProportionalEditingOff() {
		val session = EditorSession(meshModel())
		session.setProportionalRadius(345f)
		session.toggleProportionalEdit()
		assertNotNull(session.proportionalEdit.value, "precondition: proportional editing is on")

		session.setProportionalEditKeepingRadius(null)

		assertNull(session.proportionalEdit.value)
		assertEquals(345f, session.proportionalSettings.value.radiusWorld)
	}
}