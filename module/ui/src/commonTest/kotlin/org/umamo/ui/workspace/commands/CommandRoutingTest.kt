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

	private fun routing(hovered: HoveredSurface?, viewport: String? = viewportArea): CommandRouting =
		CommandRouting({ hovered }, { viewport })

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
		// The fixture's viewport is live, so a fallback would have been visible rather than vacuously null.
		assertEquals(viewportArea, routing.viewportArea())
	}

	/** Every other hovered surface - and none at all - arms in the pointer's 2D viewport. */
	@Test
	fun selectToolFallsBackToTheActiveViewport() {
		val editing = session(EditorMode.Edit)
		assertEquals(viewportArea, routing(HoveredSurface(viewportArea, SpaceKind.Viewport2D)).selectToolArea(editing))
		assertEquals(viewportArea, routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)).selectToolArea(editing))
		assertEquals(viewportArea, routing(null).selectToolArea(editing))
		assertNull(routing(null, viewport = null).selectToolArea(editing), "no viewport ever touched arms nothing")
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

	/** Anything else resolves to the pointer's viewport, and nothing at all resolves to nothing. */
	@Test
	fun transformFallsBackToTheActiveViewport() {
		assertEquals(TransformTarget.Viewport(viewportArea), routing(HoveredSurface(viewportArea, SpaceKind.Viewport2D)).transformTarget())
		assertEquals(
			TransformTarget.Viewport(viewportArea),
			routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)).transformTarget(),
			"a keyform sheet has no transform of its own, so the gesture runs in the pointer's viewport",
		)
		assertNull(routing(null, viewport = null).transformTarget(), "no viewport and no UV editor is nowhere to run it")
	}

	/**
	 * Both resolvers are read on every call, never sampled once.  A routing instance outlives the document
	 * swaps that replace the render service, so a captured answer would go permanently stale.
	 */
	@Test
	fun resolversAreReadPerCall() {
		var hovered: HoveredSurface? = HoveredSurface(uvArea, SpaceKind.UvEditor)
		var viewport: String? = null
		val routing = CommandRouting({ hovered }, { viewport })
		assertEquals(TransformTarget.Uv(uvArea), routing.transformTarget())

		hovered = HoveredSurface(viewportArea, SpaceKind.Viewport2D)
		viewport = viewportArea
		assertEquals(TransformTarget.Viewport(viewportArea), routing.transformTarget(), "the second call saw the new pointer state")
	}
}
