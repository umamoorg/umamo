package org.umamo.ui.action

import org.umamo.ui.workspace.SpaceKind
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins what each [CommandSpaces] shape answers, including for a pointer that has touched nothing yet. */
class CommandSpacesTest {
	/** Everywhere applies to every space, and also before the pointer has touched any. */
	@Test
	fun everywhereAppliesToEverySpaceAndToNone() {
		for (kind in SpaceKind.entries) {
			assertTrue(CommandSpaces.Everywhere.appliesIn(kind), "Everywhere must cover $kind")
		}
		assertTrue(CommandSpaces.Everywhere.appliesIn(null), "a command that routes by nothing needs no surface")
	}

	/** A scoped command applies in exactly its listed spaces. */
	@Test
	fun aScopedCommandAppliesOnlyInItsSpaces() {
		val spaces = CommandSpaces.of(SpaceKind.Viewport2D, SpaceKind.KeyformSheet)
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
		assertFalse(CommandSpaces.UvEditor.appliesIn(null))
		assertFalse(CommandSpaces.WorkSurfaces.appliesIn(null))
	}

	/** The named sets cover what their names say. */
	@Test
	fun theNamedSetsCoverWhatTheyName() {
		assertTrue(CommandSpaces.Viewport2D.appliesIn(SpaceKind.Viewport2D))
		assertFalse(CommandSpaces.Viewport2D.appliesIn(SpaceKind.UvEditor))
		assertTrue(CommandSpaces.UvEditor.appliesIn(SpaceKind.UvEditor))
		assertFalse(CommandSpaces.UvEditor.appliesIn(SpaceKind.Viewport2D))
		assertTrue(CommandSpaces.WorkSurfaces.appliesIn(SpaceKind.Viewport2D))
		assertTrue(CommandSpaces.WorkSurfaces.appliesIn(SpaceKind.UvEditor))
		assertFalse(CommandSpaces.WorkSurfaces.appliesIn(SpaceKind.Outliner))
	}

	/** A scope over no space is unrepresentable: it could never be shown, which is never what was meant. */
	@Test
	fun aScopeOverNoSpaceIsRejected() {
		assertFailsWith<IllegalArgumentException> { CommandSpaces.Only(emptySet()) }
		assertFailsWith<IllegalArgumentException> { CommandSpaces.of() }
	}
}