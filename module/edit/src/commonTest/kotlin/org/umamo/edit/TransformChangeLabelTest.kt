package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the history label every whole-gesture transform reports, across all three domains and every
 * [MeshOperatorKind].
 *
 * Two things are being protected.  First, the domain has to be readable at a glance in the history panel -
 * an object transform, an Edit-mode mesh transform, and a UV transform must never share a label, which is
 * why the keys sit in three separate namespaces rather than being distinguished only by the operator.
 * Second, each key must have a matching arm in the history panel's resource lookup: an unmapped key does
 * not fail anywhere, it silently renders as the generic "Edit" label, so the exhaustive table below is what
 * makes a forgotten string visible.
 */
class TransformChangeLabelTest {
	private val drawableIds = listOf(DrawableId("a"))
	private val vertexIndices = mapOf(DrawableId("a") to listOf(0, 1))

	/** Edit mode names all four of its operators, Vertex Slide included. */
	@Test
	fun meshTransformLabelsPerOperator() {
		assertEquals("change.mesh.move", MeshChange.TransformVertices(vertexIndices, MeshOperatorKind.Grab).labelKey)
		assertEquals("change.mesh.scale", MeshChange.TransformVertices(vertexIndices, MeshOperatorKind.Scale).labelKey)
		assertEquals("change.mesh.rotate", MeshChange.TransformVertices(vertexIndices, MeshOperatorKind.Rotate).labelKey)
		assertEquals("change.mesh.slide", MeshChange.TransformVertices(vertexIndices, MeshOperatorKind.VertexSlide).labelKey)
	}

	/** Object mode has no Vertex Slide of its own, so a slide reads as a plain move rather than an unmapped key. */
	@Test
	fun objectTransformLabelsPerOperator() {
		assertEquals("change.object.move", MeshChange.TransformDrawables(drawableIds, MeshOperatorKind.Grab).labelKey)
		assertEquals("change.object.scale", MeshChange.TransformDrawables(drawableIds, MeshOperatorKind.Scale).labelKey)
		assertEquals("change.object.rotate", MeshChange.TransformDrawables(drawableIds, MeshOperatorKind.Rotate).labelKey)
		assertEquals("change.object.move", MeshChange.TransformDrawables(drawableIds, MeshOperatorKind.VertexSlide).labelKey)
	}

	/** The UV editor's slide is unimplemented (see TODO § UV Editor); until it lands it degrades to the move label. */
	@Test
	fun uvTransformLabelsPerOperator() {
		assertEquals("change.uv.move", MeshChange.TransformUvs(vertexIndices, MeshOperatorKind.Grab).labelKey)
		assertEquals("change.uv.scale", MeshChange.TransformUvs(vertexIndices, MeshOperatorKind.Scale).labelKey)
		assertEquals("change.uv.rotate", MeshChange.TransformUvs(vertexIndices, MeshOperatorKind.Rotate).labelKey)
		assertEquals("change.uv.move", MeshChange.TransformUvs(vertexIndices, MeshOperatorKind.VertexSlide).labelKey)
	}

	/**
	 * No two domains share a key for the same operator.  This is the requirement the labels exist for: a
	 * glance at the history panel has to say whether a step was an object, mesh, or UV operation, and three
	 * domains reusing one key would read identically no matter how the strings were worded.
	 */
	@Test
	fun theThreeDomainsNeverShareALabelKey() {
		for (kind in MeshOperatorKind.entries) {
			val keys =
				setOf(
					MeshChange.TransformVertices(vertexIndices, kind).labelKey,
					MeshChange.TransformDrawables(drawableIds, kind).labelKey,
					MeshChange.TransformUvs(vertexIndices, kind).labelKey,
				)
			assertEquals(3, keys.size, "$kind produced a shared label key across domains: $keys")
		}
	}

	/** Mirror keeps its own key in the UV namespace - it is not a G / S / R gesture. */
	@Test
	fun mirrorKeepsItsOwnUvKey() {
		assertEquals("change.uv.mirror", MeshChange.MirrorUvs(drawableIds, mirrorU = true).labelKey)
		assertEquals("change.uv.mirror", MeshChange.MirrorUvs(drawableIds, mirrorU = false).labelKey)
	}

	/** Every transform label is undoable - a gesture the user cannot step back over would be a trap. */
	@Test
	fun everyTransformIsUndoable() {
		for (kind in MeshOperatorKind.entries) {
			assertTrue(MeshChange.TransformVertices(vertexIndices, kind).undoability == Undoability.Undoable, "$kind vertices")
			assertTrue(MeshChange.TransformDrawables(drawableIds, kind).undoability == Undoability.Undoable, "$kind drawables")
			assertTrue(MeshChange.TransformUvs(vertexIndices, kind).undoability == Undoability.Undoable, "$kind uvs")
		}
	}
}
