package org.umamo.ui.workspace

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.edit.Cursor2d
import org.umamo.edit.DEFAULT_PROPORTIONAL_EDIT_STATE
import org.umamo.edit.EditorMode
import org.umamo.edit.GridConfig
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.ParameterSelection
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SessionViewState
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.UvCursor
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.initialLiveParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the editor entry's session block (docs/format/UMA.md §7.4): what a session saves comes back, a session at
 * its defaults writes nothing but nulls, the pose lists only the parameters away from their defaults and is
 * clamped as it loads, and a member of the wrong shape is skipped rather than thrown over.
 */
class SessionStateJsonTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val model =
		PuppetModel(
			parameters = listOf(Parameter(angleX, "Angle X", -30f, 30f, 0f), Parameter(angleY, "Angle Y", -30f, 30f, 5f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	private val defaultPose = mapOf(angleX to 0f, angleY to 5f)

	@Test
	fun aSessionAtItsDefaultsWritesOnlyNulls() {
		val tree = sessionStateJson(SessionViewState(proportionalSettings = DEFAULT_PROPORTIONAL_EDIT_STATE), defaultPose, model)

		assertTrue(tree.values.all { value -> value is JsonNull }, "nothing deviates: $tree")
		assertEquals(SessionViewState(), sessionViewStateOf(JsonObject(emptyMap())), "and an empty block reads as the defaults")
		assertNull(sessionViewStateOf(null), "while no block at all is no saved state")
	}

	@Test
	fun everyMemberRoundTrips() {
		val saved =
			SessionViewState(
				selection =
					Selection(
						linkedSetOf(SelectionTarget.Drawable(DrawableId("ArtMesh12")), SelectionTarget.Part(PartId("face")), SelectionTarget.Deformer(DeformerId("warp:1"))),
						SelectionTarget.Part(PartId("face")),
					),
				parameterSelection = ParameterSelection(linkedSetOf(angleX, angleY), angleY),
				mode = EditorMode.Edit,
				selectMode = MeshSelectMode.Edge,
				cursor2d = Cursor2d(12.5f, -8f),
				uvCursor = UvCursor(0.25f, 0.75f),
				pivotMode = TransformPivotMode.IndividualOrigins,
				proportionalEnabled = true,
				proportionalSettings = ProportionalEditState(ProportionalFalloff.Linear, 64f, connectedOnly = true),
				gridConfig = GridConfig(50f, 4),
			)

		val reopened = sessionViewStateOf(sessionStateJson(saved, defaultPose, model))

		assertEquals(saved, reopened)
	}

	/** An id holding a colon survives: the reference splits on its FIRST colon only. */
	@Test
	fun anObjectReferenceKeepsAnIdWithAColon() {
		val target = SelectionTarget.Deformer(DeformerId("art-0/lyid:12~1"))

		assertEquals(target, selectionTargetOf(objectReferenceOf(target)))
		assertNull(selectionTargetOf("glue:3"), "a reference of no known kind names nothing")
	}

	/** A disabled proportional configuration away from the default is still saved: a toggle brings it back. */
	@Test
	fun aDisabledProportionalConfigurationIsSaved() {
		val saved = SessionViewState(proportionalSettings = ProportionalEditState(ProportionalFalloff.Root, 48f))

		val reopened = sessionViewStateOf(sessionStateJson(saved, defaultPose, model))

		assertEquals(saved, reopened)
	}

	@Test
	fun thePoseListsOnlyDeviationsAndLoadsClamped() {
		val tree = sessionStateJson(SessionViewState(), mapOf(angleX to 12.5f, angleY to 5f), model)
		assertEquals(mapOf(angleX to 12.5f), savedPoseOf(tree), "a parameter at its default is not listed")

		val live = initialLiveParams(model, mapOf(angleX to 99f, ParameterId("ParamGone") to 1f))
		assertEquals(mapOf(angleX to 30f, angleY to 5f), live.values, "a saved value is clamped to its range, and an unknown id never matches")
		assertEquals(defaultPose, initialLiveParams(model).values, "a plain open is every default")
	}

	/** Every member the wrong shape: nothing throws, and the state is the defaults (UMA §7.1). */
	@Test
	fun membersOfTheWrongShapeAreSkipped() {
		val junk =
			buildJsonObject {
				put(
					"pose",
					buildJsonArray {
						add(JsonPrimitive("ParamAngleX"))
						add(
							buildJsonObject {
								put("parameter", 7)
								put("value", "far")
							},
						)
					},
				)
				put("selection", "everything")
				put("parameterSelection", buildJsonObject { put("ids", 3) })
				put("mode", "sculpt")
				put("selectMode", 2)
				put("cursor2d", buildJsonArray { add(JsonPrimitive(1)) })
				put("uvCursor", "center")
				put("pivot", "origin")
				put(
					"proportional",
					buildJsonObject {
						put("enabled", "yes")
						put("falloff", "gaussian")
						put("radius", -4)
					},
				)
				put(
					"grid",
					buildJsonObject {
						put("scale", 0)
						put("subdivisions", 0)
					},
				)
			}

		assertEquals(SessionViewState(proportionalSettings = DEFAULT_PROPORTIONAL_EDIT_STATE), sessionViewStateOf(junk))
		assertEquals(emptyMap(), savedPoseOf(junk))
	}
}