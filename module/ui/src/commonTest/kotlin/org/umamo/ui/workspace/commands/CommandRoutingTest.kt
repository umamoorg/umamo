package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.SpaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the one place a command asks "which area does the pointer mean".
 *
 * The rules these cover were previously five separate private helpers and a scatter of inline SpaceKind
 * comparisons, none of which any test could reach - they only ran inside a live composition.  Several
 * are asymmetric in ways that read like bugs and are not, so the asymmetries are asserted deliberately:
 * a hovered UV editor blocks a select tool outside Edit mode but still claims a transform, and the
 * kind-agnostic lookups stay kind-agnostic.
 */
class CommandRoutingTest {
	private val viewportArea = "viewport-1"
	private val uvArea = "uv-1"
	private val sheetArea = "sheet-1"

	private fun routing(hovered: HoveredSurface?): CommandRouting = CommandRouting { hovered }

	private fun meshDrawable(): Drawable =
		Drawable(
			id = DrawableId("a"),
			name = "a",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
			geometryGrid = null,
		)

	/**
	 * A session in [mode], with the one mesh drawable selected so Edit mode has something to edit.
	 *
	 * @param EditorMode mode The mode to leave the session in.
	 * @return EditorSession The session.
	 */
	private fun session(mode: EditorMode): EditorSession {
		val session =
			EditorSession(
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = emptyList(),
					drawables = listOf(meshDrawable()),
					rootChildren = emptyList(),
					rootPartId = null,
				),
			)
		val drawable = SelectionTarget.Drawable(DrawableId("a"))
		session.setSelection(Selection(setOf(drawable), drawable))
		session.setMode(mode)
		return session
	}

	/** areaOf names the hovered area only when that area hosts the asked-for kind. */
	@Test
	fun areaOfMatchesOnlyItsKind() {
		val overUv = routing(HoveredSurface(uvArea, SpaceKind.UvEditor))
		assertEquals(uvArea, overUv.areaOf(SpaceKind.UvEditor))
		assertNull(overUv.areaOf(SpaceKind.KeyformSheet), "a UV editor is not a sheet, even though both are hovered surfaces")
		assertNull(overUv.areaOf(SpaceKind.Viewport2D))
		assertNull(routing(null).areaOf(SpaceKind.UvEditor), "nothing hovered names no area")
	}

	/**
	 * hoveredAreaIdAnyKind stays kind-agnostic.  Narrowing it to one kind would look like a cleanup and
	 * would silently change Select Linked, which genuinely executes in a viewport AND in a UV editor.
	 */
	@Test
	fun hoveredAreaIdIsKindAgnostic() {
		assertEquals(uvArea, routing(HoveredSurface(uvArea, SpaceKind.UvEditor)).hoveredAreaIdAnyKind())
		assertEquals(sheetArea, routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)).hoveredAreaIdAnyKind())
		assertEquals("outliner-1", routing(HoveredSurface("outliner-1", SpaceKind.Outliner)).hoveredAreaIdAnyKind())
		assertNull(routing(null).hoveredAreaIdAnyKind())
	}

	/**
	 * Every space kind resolves, not just the three that happen to stamp the tracker today.
	 *
	 * Swept over [SpaceKind.entries] rather than a hand-picked sample so a tenth kind cannot be added
	 * without deciding what routing does with it: every kind names its own area and only its own, and
	 * exactly two - the 2D viewport and the UV editor - can host a transform.  The remaining seven resolve
	 * nothing, which is what makes a key pressed over a panel do nothing instead of acting elsewhere.
	 */
	@Test
	fun everySpaceKindResolves() {
		for (kind in SpaceKind.entries) {
			val areaId = "area-${kind.key}"
			val routing = routing(HoveredSurface(areaId, kind))

			assertEquals(areaId, routing.areaOf(kind), "${kind.name} names its own area")
			assertEquals(areaId, routing.hoveredAreaIdAnyKind(), "${kind.name} is reported kind-agnostically")
			for (other in SpaceKind.entries.filter { candidate -> candidate != kind }) {
				assertNull(routing.areaOf(other), "a hovered ${kind.name} must not answer as ${other.name}")
			}

			// Exactly two kinds can host a transform, and each hosts it in ITS OWN area; the other seven
			// resolve nothing rather than reaching back to a viewport the pointer has left.
			val expectedTarget =
				when (kind) {
					SpaceKind.UvEditor -> TransformTarget.Uv(areaId)
					SpaceKind.Viewport2D -> TransformTarget.Viewport(areaId)
					else -> null
				}
			assertEquals(expectedTarget, routing.transformTarget(), "${kind.name} transform target")
		}
	}

	/**
	 * A hovered space with no camera registered resolves no camera, so the view commands no-op rather than
	 * reaching into whichever viewport the pointer last visited.
	 *
	 * The lookup itself lives in ViewCommands (it needs the hub); what is pinned here is the input to it -
	 * that a panel hover reports the PANEL's area id, which no camera is registered under.
	 */
	@Test
	fun aPanelHoverNamesAnAreaThatOwnsNoCamera() {
		val overOutliner = routing(HoveredSurface("outliner-1", SpaceKind.Outliner))
		assertEquals("outliner-1", overOutliner.hovered()?.areaId)
		assertNull(overOutliner.areaOf(SpaceKind.Viewport2D), "the pointer is on a panel, not on a viewport")
		assertNull(overOutliner.areaOf(SpaceKind.KeyformSheet))
	}

	/** A hovered UV editor in Edit mode arms the select tool in itself, not in the pointer's old viewport. */
	@Test
	fun selectToolArmsInAHoveredUvEditorInEditMode() {
		assertEquals(uvArea, routing(HoveredSurface(uvArea, SpaceKind.UvEditor)).selectToolArea(session(EditorMode.Edit)))
	}

	/**
	 * In Object mode a hovered UV editor arms NOTHING - it must not fall back to the viewport.  The UV
	 * overlay only composes in Edit mode, and the session's arm is mode-agnostic, so a fallback would
	 * latch a tool on an area with nothing to drive or cancel it.
	 */
	@Test
	fun selectToolRefusesAHoveredUvEditorOutsideEditMode() {
		val routing = routing(HoveredSurface(uvArea, SpaceKind.UvEditor))
		assertNull(routing.selectToolArea(session(EditorMode.Object)), "Object mode over a UV editor arms nothing")
		assertNull(routing.selectToolArea(null), "no document arms nothing")
		// Not vacuous: the same routing DOES arm in Edit mode, so the nulls above are the mode gate talking.
		assertEquals(uvArea, routing.selectToolArea(session(EditorMode.Edit)))
	}

	/**
	 * A select tool arms in the hovered VIEWPORT and nowhere else.
	 *
	 * There is no "last viewport" fallback: a marquee is a pointer gesture, so arming one in an area the
	 * pointer is not over would latch a tool the user cannot drive, exactly as it would in a UV editor
	 * outside Edit mode.
	 */
	@Test
	fun selectToolArmsOnlyInAHoveredViewport() {
		val editing = session(EditorMode.Edit)
		assertEquals(viewportArea, routing(HoveredSurface(viewportArea, SpaceKind.Viewport2D)).selectToolArea(editing))
		assertNull(routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)).selectToolArea(editing))
		assertNull(routing(HoveredSurface("outliner-1", SpaceKind.Outliner)).selectToolArea(editing))
		assertNull(routing(null).selectToolArea(editing), "nothing hovered arms nothing")
	}

	/**
	 * A transform over a hovered UV editor targets the UV editor in EITHER mode: routing is session-blind
	 * here, and beginUvOperator does its own Edit-mode refusal.  The asymmetry with selectToolArea above
	 * is deliberate - both end in a refusal outside Edit mode, just at different layers.
	 */
	@Test
	fun transformTargetsAHoveredUvEditorInEitherMode() {
		assertEquals(TransformTarget.Uv(uvArea), routing(HoveredSurface(uvArea, SpaceKind.UvEditor)).transformTarget())
	}

	/**
	 * A transform runs in the hovered viewport, and in no viewport at all when the pointer is elsewhere.
	 *
	 * The hovered viewport's OWN id is what comes back - the property the retired second resolver could not
	 * guarantee, since it remembered the last viewport touched rather than the one under the pointer.
	 */
	@Test
	fun transformRunsOnlyWhereThePointerIs() {
		val secondViewport = "viewport-2"
		assertEquals(
			TransformTarget.Viewport(secondViewport),
			routing(HoveredSurface(secondViewport, SpaceKind.Viewport2D)).transformTarget(),
			"the hovered viewport, not whichever one was touched first",
		)
		assertNull(
			routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)).transformTarget(),
			"a keyform sheet hosts no transform, and must not hand the gesture to a viewport",
		)
		assertNull(routing(HoveredSurface("logs-1", SpaceKind.Logs)).transformTarget())
		assertNull(routing(null).transformTarget(), "nothing hovered is nowhere to run it")
	}

	/**
	 * The resolver is read on every call, never sampled once.  A routing instance outlives the document
	 * swaps and area edits that move the pointer, so a captured answer would go permanently stale.
	 */
	@Test
	fun theResolverIsReadPerCall() {
		var hovered: HoveredSurface? = HoveredSurface(uvArea, SpaceKind.UvEditor)
		val routing = CommandRouting { hovered }
		assertEquals(TransformTarget.Uv(uvArea), routing.transformTarget())

		hovered = HoveredSurface(viewportArea, SpaceKind.Viewport2D)
		assertEquals(TransformTarget.Viewport(viewportArea), routing.transformTarget(), "the second call saw the new pointer state")
	}
}
