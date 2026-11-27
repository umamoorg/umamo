package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins what each [SpaceScope] shape answers, including for a pointer that has touched nothing yet. */
class SpaceScopeTest {
	/** Everywhere applies to every space, and also before the pointer has touched any. */
	@Test
	fun everywhereAppliesToEverySpaceAndToNone() {
		for (kind in SpaceKind.entries) {
			assertTrue(SpaceScope.Everywhere.appliesIn(kind), "Everywhere must cover $kind")
		}

		assertTrue(SpaceScope.Everywhere.appliesIn(null), "a command that routes by nothing needs no surface")
	}

	/** A scoped command applies in exactly its listed spaces. */
	@Test
	fun aScopedCommandAppliesOnlyInItsSpaces() {
		val spaces = SpaceScope.of(SpaceKind.Viewport2D, SpaceKind.KeyformSheet)

		for (kind in SpaceKind.entries) {
			val expected = kind == SpaceKind.Viewport2D || kind == SpaceKind.KeyformSheet
			assertTrue(spaces.appliesIn(kind) == expected, "$kind should ${if (expected) "" else "not "}be covered")
		}
	}

	/**
	 * Before the pointer touches any surface a scoped command applies nowhere - its handler would resolve
	 * no area to act in, so listing it would offer a guaranteed no-op.
	 */
	@Test
	fun aScopedCommandAppliesNowhereBeforeAnySurfaceIsTouched() {
		assertFalse(SpaceScope.UvEditor.appliesIn(null))
		assertFalse(SpaceScope.WorkSurfaces.appliesIn(null))
	}

	/** The named sets cover what their names say. */
	@Test
	fun theNamedSetsCoverWhatTheyName() {
		assertTrue(SpaceScope.Viewport2D.appliesIn(SpaceKind.Viewport2D))
		assertFalse(SpaceScope.Viewport2D.appliesIn(SpaceKind.UvEditor))
		assertTrue(SpaceScope.UvEditor.appliesIn(SpaceKind.UvEditor))
		assertFalse(SpaceScope.UvEditor.appliesIn(SpaceKind.Viewport2D))
		assertTrue(SpaceScope.WorkSurfaces.appliesIn(SpaceKind.Viewport2D))
		assertTrue(SpaceScope.WorkSurfaces.appliesIn(SpaceKind.UvEditor))
		assertFalse(SpaceScope.WorkSurfaces.appliesIn(SpaceKind.Outliner))
	}

	/** The operation strip lives in exactly the work surfaces, so a space that joins them hosts the strip too. */
	@Test
	fun theOperationStripFollowsTheWorkSurfaces() {
		for (kind in SpaceKind.entries) {
			assertEquals(SpaceScope.WorkSurfaces.appliesIn(kind), kind.hostsOperationStrip, "$kind")
		}
	}

	/** A scope over no space is unrepresentable: it could never be shown, which is never what was meant. */
	@Test
	fun aScopeOverNoSpaceIsRejected() {
		assertFailsWith<IllegalArgumentException> { SpaceScope.Only(emptySet()) }
		assertFailsWith<IllegalArgumentException> { SpaceScope.of() }
	}
}