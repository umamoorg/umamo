package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An area's own shown or hidden choice for a piece of chrome, over the application's default. */
class ChromeVisibilityTest {
	/** With no choice of its own the area follows whatever the default is. */
	@Test
	fun anAreaWithNoChoiceFollowsTheDefault() {
		val visibility = ChromeVisibility()
		assertNull(visibility.choice)
		assertTrue(visibility.isShown(default = true))
		assertFalse(visibility.isShown(default = false))
	}

	/** A toggle flips what shows and makes that the area's own choice. */
	@Test
	fun aToggleMakesTheAreasOwnChoice() {
		val visibility = ChromeVisibility()
		visibility.toggle(default = false)
		assertEquals(true, visibility.choice)
		visibility.toggle(default = false)
		assertEquals(false, visibility.choice, "toggled back, the choice stays the area's even though it matches the default")
	}

	/** Once the area chose, a change of default does not flip it. */
	@Test
	fun aChoiceOutlastsAChangeOfDefault() {
		val visibility = ChromeVisibility()
		visibility.toggle(default = true)
		assertFalse(visibility.isShown(default = true))
		assertFalse(visibility.isShown(default = false))
	}
}