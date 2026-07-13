package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the modal-transform infrastructure: the axis-locked scale, the pivot-group builders
 * (shared anchor vs per-island Individual Origins), and the session's axis-constraint / pivot / cursor
 * / pie latches (toggle semantics, the Rotate exclusion, and the clear-on-latch discipline).
 */
class TransformStateTest {
	/** scaleVerticesAxis scales each axis independently about the pivot. */
	@Test
	fun scaleVerticesAxisScalesPerAxis() {
		val positions = floatArrayOf(2f, 3f, 4f, 5f)
		val scaled = MeshTransforms.scaleVerticesAxis(positions, setOf(0, 1), factorX = 2f, factorY = 1f, pivotX = 1f, pivotY = 1f)
		assertEquals(listOf(3f, 3f, 7f, 5f), scaled.toList(), "x doubles about the pivot, y is untouched")
		assertEquals(listOf(2f, 3f, 4f, 5f), positions.toList(), "the input array is never mutated")
	}

	/** sharedGroup wraps every covered vertex about one anchor; islandGroups split by connectivity. */
	@Test
	fun pivotGroupBuilders() {
		val shared = TransformPivots.sharedGroup(setOf(0, 1, 2), pivotX = 5f, pivotY = 6f)
		assertEquals(1, shared.size)
		assertEquals(setOf(0, 1, 2), shared.single().vertexIndices)
		assertEquals(5f, shared.single().pivotX)

		// Two disconnected triangles: vertices 0-2 at x=0..2, vertices 3-5 at x=10..12 (y = 0).
		val positions = floatArrayOf(0f, 0f, 2f, 0f, 1f, 2f, 10f, 0f, 12f, 0f, 11f, 2f)
		val triangles = intArrayOf(0, 1, 2, 3, 4, 5)
		val islands = TransformPivots.islandGroups(positions, setOf(0, 1, 2, 3, 4, 5), triangles)
		assertEquals(2, islands.size, "two components make two islands")
		val leftIsland = islands.first { group -> 0 in group.vertexIndices }
		assertEquals(setOf(0, 1, 2), leftIsland.vertexIndices)
		assertEquals(1f, leftIsland.pivotX, 1e-4f, "each island pivots about its own centroid")
	}

	private fun meshedSession(): EditorSession {
		val mesh = DrawableMesh(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), FloatArray(6), intArrayOf(0, 1, 2))
		val drawable =
			Drawable(
				id = DrawableId("d"),
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = mesh,
				keyforms = null,
			)
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = listOf(drawable),
				rootChildren = emptyList(),
				rootPartId = null,
			)
		return EditorSession(model)
	}

	/** The axis constraint toggles only while a Grab / Scale is live, never for Rotate, and clears with the operator. */
	@Test
	fun axisConstraintTogglesOnlyDuringGrabOrScale() {
		val session = meshedSession()
		val target = SelectionTarget.Drawable(DrawableId("d"))
		session.setSelection(Selection(setOf(target), target))

		// Idle: nothing to lock.
		session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
		assertNull(session.axisConstraint.value, "no constraint arms while idle")

		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
		assertEquals(TransformAxisConstraint.AxisX, session.axisConstraint.value)
		session.toggleAxisConstraint(TransformAxisConstraint.AxisZ)
		assertEquals(TransformAxisConstraint.AxisZ, session.axisConstraint.value, "the other axis switches the lock")
		session.toggleAxisConstraint(TransformAxisConstraint.AxisZ)
		assertNull(session.axisConstraint.value, "the same axis releases the lock")

		session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
		session.clearObjectOperator()
		assertNull(session.axisConstraint.value, "clearing the operator clears the lock")

		// Rotate has no 2D axis to lock.
		session.beginObjectOperator(MeshOperatorKind.Rotate, "area-test")
		session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
		assertNull(session.axisConstraint.value, "Rotate refuses the lock")
	}

	/** The cursor, pivot mode, and pie latches hold their values; undo clears the transient pie latch. */
	@Test
	fun cursorPivotAndPieState() {
		val session = meshedSession()
		assertNull(session.cursor2d.value, "no cursor before placement")
		session.setCursor2d(12f, 34f)
		assertEquals(Cursor2d(12f, 34f), session.cursor2d.value)

		assertEquals(TransformPivotMode.MedianPoint, session.pivotMode.value, "median is the default pivot")
		session.setPivotMode(TransformPivotMode.Cursor)
		assertEquals(TransformPivotMode.Cursor, session.pivotMode.value)

		session.openPieMenu(PieMenuKind.Snap)
		assertEquals(PieMenuKind.Snap, session.activePieMenu.value)
		session.closePieMenu()
		assertNull(session.activePieMenu.value)

		// A restore (undo) drops the pie latch but keeps the cursor and pivot mode (persistent editor state).
		val target = SelectionTarget.Drawable(DrawableId("d"))
		session.setSelection(Selection(setOf(target), target))
		session.openPieMenu(PieMenuKind.PivotMode)
		session.undo()
		assertNull(session.activePieMenu.value, "restore closes an open pie")
		assertEquals(Cursor2d(12f, 34f), session.cursor2d.value, "the cursor survives undo")
		assertEquals(TransformPivotMode.Cursor, session.pivotMode.value, "the pivot mode survives undo")
		assertTrue(session.canRedo.value)
	}

	/** Proportional editing toggles off/on remembering its configuration; falloff selection enables it. */
	@Test
	fun proportionalEditToggleAndConfiguration() {
		val session = meshedSession()
		assertNull(session.proportionalEdit.value, "proportional editing starts off")

		session.toggleProportionalEdit()
		val enabled = session.proportionalEdit.value
		assertEquals(ProportionalFalloff.Smooth, enabled?.falloff, "smooth is the default falloff")
		assertEquals(DEFAULT_PROPORTIONAL_RADIUS_WORLD, enabled?.radiusWorld, "the default radius applies on first enable")
		assertEquals("notice.proportional.on", session.notice.value?.messageKey, "the toggle confirms with a notice")

		// Reconfigure, toggle off, toggle back on: the configuration is remembered.
		session.setProportionalFalloff(ProportionalFalloff.Sharp)
		session.setProportionalRadius(500f)
		session.toggleProportionalEdit()
		assertNull(session.proportionalEdit.value, "the second toggle disables")
		session.toggleProportionalEdit()
		assertEquals(ProportionalEditState(ProportionalFalloff.Sharp, 500f), session.proportionalEdit.value, "the configuration survives an off/on cycle")

		// The radius clamps to its bounds; a falloff pick while off re-enables.
		session.setProportionalRadius(0f)
		assertEquals(MIN_PROPORTIONAL_RADIUS_WORLD, session.proportionalEdit.value?.radiusWorld, "the radius clamps at the minimum")
		session.toggleProportionalEdit()
		session.setProportionalFalloff(ProportionalFalloff.Linear)
		assertEquals(ProportionalFalloff.Linear, session.proportionalEdit.value?.falloff, "picking a falloff while off enables proportional editing")
	}

	/** The rotate accumulator crosses the atan2 branch cut, reverses through zero, and multi-turns. */
	@Test
	fun rotationAngleTrackerUnwrapsAndAccumulates() {
		val pi = kotlin.math.PI.toFloat()

		// Start right AT the branch cut (a far pivot's typical start) and sweep across it: the raw
		// difference would jump ~2*pi, the tracker advances by the small increments actually travelled.
		val tracker = RotationAngleTracker()
		assertEquals(0f, tracker.advance(pi - 0.1f), 1e-5f, "the first sample anchors at zero")
		assertEquals(0.15f, tracker.advance(-pi + 0.05f), 1e-5f, "crossing the cut adds the short arc, not ~2*pi")
		assertEquals(-0.05f, tracker.advance(pi - 0.15f), 1e-5f, "reversing walks back through zero into the other sign")
		assertEquals(-0.05f, tracker.advance(pi - 0.15f), 1e-5f, "the same sample twice adds nothing (re-derives are safe)")

		// A full circle in quarter steps accumulates ~2*pi (a raw subtraction could never exceed pi).
		val multiTurn = RotationAngleTracker()
		multiTurn.advance(0f)
		multiTurn.advance(pi / 2)
		multiTurn.advance(pi)
		multiTurn.advance(-pi / 2)
		assertEquals(2 * pi, multiTurn.advance(0f), 1e-4f, "a full sweep accumulates a whole turn")

		assertEquals(0f, wrapAngle(2 * pi), 1e-5f, "wrapAngle folds a whole turn to zero")
		assertEquals(-pi + 0.2f, wrapAngle(pi + 0.2f), 1e-5f, "wrapAngle folds past +pi into the negative side")
	}

	/** Connected Only toggles with a notice, enabling proportional editing when it was off. */
	@Test
	fun proportionalConnectedOnlyToggle() {
		val session = meshedSession()
		session.toggleProportionalConnected()
		assertEquals(true, session.proportionalEdit.value?.connectedOnly, "toggling while off enables proportional editing, connected on")
		assertEquals("notice.proportional.connected.on", session.notice.value?.messageKey, "the toggle confirms with a notice")
		session.toggleProportionalConnected()
		assertEquals(false, session.proportionalEdit.value?.connectedOnly, "the second toggle turns connected off")
		assertTrue(session.proportionalEdit.value != null, "proportional editing itself stays on")

		// Off/on cycles remember the flag with the rest of the configuration.
		session.toggleProportionalConnected()
		session.toggleProportionalEdit()
		session.toggleProportionalEdit()
		assertEquals(true, session.proportionalEdit.value?.connectedOnly, "connected only survives an off/on cycle")
	}

	/** A suppressed latch (the duplicate / rip auto-grab) opts the gesture out of proportional editing. */
	@Test
	fun suppressedMeshOperatorLatch() {
		val session = meshedSession()
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)))

		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test", suppressProportional = true)
		assertTrue(session.activeMeshOperatorSuppressesProportional, "the suppressed latch publishes its flag")

		// Clearing the operator resets the flag; a plain latch never inherits it.
		session.clearMeshOperator()
		assertTrue(!session.activeMeshOperatorSuppressesProportional, "clearing the operator resets the suppression")
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test")
		assertTrue(!session.activeMeshOperatorSuppressesProportional, "a plain latch is never suppressed")
	}

	/** Operator latches record the initiating area; re-latching moves ownership atomically. */
	@Test
	fun operatorLatchesCarryTheInitiatingArea() {
		val session = meshedSession()
		val target = SelectionTarget.Drawable(DrawableId("d"))
		session.setSelection(Selection(setOf(target), target))

		session.beginObjectOperator(MeshOperatorKind.Grab, "area-a")
		assertEquals(ActiveOperator(MeshOperatorKind.Grab, "area-a"), session.activeObjectOperator.value)
		session.beginObjectOperator(MeshOperatorKind.Scale, "area-b")
		assertEquals(ActiveOperator(MeshOperatorKind.Scale, "area-b"), session.activeObjectOperator.value, "re-latching replaces both the kind and the owner")
		session.clearObjectOperator()

		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)))
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-a")
		assertEquals(ActiveOperator(MeshOperatorKind.Grab, "area-a"), session.activeMeshOperator.value)
	}

	/** The armed select tools carry their arming area; a resize keeps it, a re-arm moves it. */
	@Test
	fun selectToolsCarryTheArmingArea() {
		val session = meshedSession()
		session.beginBoxSelect("area-a")
		assertEquals(ActiveSelectTool.BoxArmed("area-a"), session.activeSelectTool.value)

		session.beginCircleSelect("area-a")
		val armed = session.activeSelectTool.value as ActiveSelectTool.Circle
		assertEquals("area-a", armed.areaId)

		session.setCircleRadius(armed.radiusPx + 10f)
		val resized = session.activeSelectTool.value as ActiveSelectTool.Circle
		assertEquals("area-a", resized.areaId, "a resize keeps the brush in its arming area")

		// Re-arming from another viewport moves the tool there, restoring the remembered radius.
		session.beginCircleSelect("area-b")
		val rearmed = session.activeSelectTool.value as ActiveSelectTool.Circle
		assertEquals("area-b", rearmed.areaId)
		assertEquals(resized.radiusPx, rearmed.radiusPx, "the remembered radius survives the re-arm")
	}

	/** Mutual exclusion crosses areas: a latch in one viewport drops a tool armed in another. */
	@Test
	fun latchingDropsToolsArmedInOtherAreas() {
		val session = meshedSession()
		val target = SelectionTarget.Drawable(DrawableId("d"))
		session.setSelection(Selection(setOf(target), target))

		session.beginCircleSelect("area-a")
		session.beginObjectOperator(MeshOperatorKind.Grab, "area-b")
		assertNull(session.activeSelectTool.value, "latching an operator drops the other area's armed tool")
		assertEquals("area-b", session.activeObjectOperator.value?.areaId)

		// A restore (undo) clears the area-carrying latch entirely, whoever owned it.
		session.undo()
		assertNull(session.activeObjectOperator.value, "restore clears the latched operator")
	}

	/** snapToGrid rounds relative to the world origin, so a snap lands on the origin-anchored grid lines. */
	@Test
	fun snapToGridRoundsRelativeToOrigin() {
		// Origin 0: plain rounding to the nearest multiple of the step.
		assertEquals(100f, snapToGrid(120f, origin = 0f, step = 100f), "120 -> nearest 100")
		assertEquals(200f, snapToGrid(160f, origin = 0f, step = 100f), "160 -> nearest 200")
		// Offset origin (the canvas-center case): the lattice shifts with it, so 120 snaps to origin + 100.
		assertEquals(150f, snapToGrid(120f, origin = 50f, step = 100f), "grid at 50,150,... -> 120 snaps to 150")
		assertEquals(50f, snapToGrid(60f, origin = 50f, step = 100f), "60 snaps to the origin line at 50")
	}

	/** GridConfig.snapStep is the finest visible spacing: scale divided by the subdivision count. */
	@Test
	fun gridConfigSnapStepIsScaleOverSubdivisions() {
		assertEquals(10f, GridConfig(scale = 100f, subdivisions = 10).snapStep, "100 / 10 = 10")
		assertEquals(25f, GridConfig(scale = 100f, subdivisions = 4).snapStep, "100 / 4 = 25")
		// A defaulted config keeps the built-in fallbacks (SNAP_GRID_WORLD_UNITS / DEFAULT_GRID_SUBDIVISIONS).
		assertEquals(SNAP_GRID_WORLD_UNITS / DEFAULT_GRID_SUBDIVISIONS, GridConfig().snapStep, "default step")
		// subdivisions is clamped to at least 1, so a degenerate 0 never divides by zero.
		assertEquals(100f, GridConfig(scale = 100f, subdivisions = 0).snapStep, "0 subdivisions clamps to 1")
	}

	/** setGridConfig publishes the new geometry on the session's gridConfig flow (transient, not snapshotted). */
	@Test
	fun setGridConfigPublishesAndIsNotUndoable() {
		val session = meshedSession()
		assertEquals(GridConfig(), session.gridConfig.value, "starts at the default grid")
		session.setGridConfig(GridConfig(scale = 50f, subdivisions = 5))
		assertEquals(GridConfig(scale = 50f, subdivisions = 5), session.gridConfig.value, "the new config publishes")
		assertTrue(!session.canUndo.value, "a grid-config change is transient, never an undo step")
	}
}
