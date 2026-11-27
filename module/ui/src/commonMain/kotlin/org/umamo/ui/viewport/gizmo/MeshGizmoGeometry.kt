package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.transform.MeshTransforms
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.distanceToSegment
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.drawableId

/*
 * The gizmo kit both work surfaces share, the 2D viewport and the UV editor: screen-space hit tests and
 * box / circle queries (ViewportGeometryAdapters.kt and this file), the Edit overlay's derive for the
 * renderer (EditMeshOverlayProducer.kt; the renderer draws every wireframe), the draw helpers every
 * overlay renders alike (GizmoChrome.kt), selection input
 * (GizmoSelectionInput.kt, MarqueeSelectController.kt, ObjectPickController.kt), and the modal transform
 * (TransformGestureFrame.kt, ModalTransformController.kt, ModalGestureState.kt, CursorWrap.kt).
 * Nothing here knows which surface it serves: a surface hands its geometry in as
 * GizmoMeshGeometry, and code that needs a surface's own types belongs in viewport2d or uv.
 */

/**
 * One mesh's geometry as the gizmo machinery sees it: positions plus topology, agnostic of WHERE the
 * positions came from.  The Edit overlay feeds deformer-projected world shapes; a UV editor feeds raw
 * texture coordinates mapped into its own space.  The shared element queries below and the UV scene's
 * overlays take this record, so a new editor space reuses them by constructing one - never by
 * re-implementing the hit / box / circle rules.
 *
 * @property ElementOwner owner What the elements belong to (the selection key): a drawable's mesh, or a warp's
 *   lattice.
 * @property ElementTopology topology The vertices, edges, and faces.
 * @property FloatArray positions The interleaved (x, y) vertex positions in the hosting space's
 *   world units (what the area camera projects).
 * @property BackFacingElements? backFacing The elements that face away from the viewer, which a click passes over
 *   for any other within reach (and a hidden back for good), or null when none do or the surface does not prefer the
 *   front.
 */
internal class GizmoMeshGeometry(
	val owner: ElementOwner,
	val topology: ElementTopology,
	val positions: FloatArray,
	val backFacing: BackFacingElements? = null,
) {
	/** The unique edges, in the topology's order. */
	val edges: List<MeshElement.Edge> get() = topology.edges

	/** The drawable whose mesh this is, or null for a lattice; a UV editor's geometry is always a drawable's. */
	val drawableId: DrawableId? get() = owner.drawableId
}

/**
 * The elements of a mesh that face away from the viewer, each indexed as its [GizmoMeshGeometry.topology] orders them.
 *
 * @property BooleanArray vertices One per vertex.
 * @property BooleanArray edges One per edge, in the topology's edge order.
 * @property BooleanArray faces One per face.
 * @property Boolean hidden Whether the area draws them at no opacity: then nothing picks them, even with no other
 *   element within reach, and a box or a brush leaves them out, since what does not show cannot be aimed at.
 */
internal class BackFacingElements(
	val vertices: BooleanArray,
	val edges: BooleanArray,
	val faces: BooleanArray,
	val hidden: Boolean = false,
) {
	/**
	 * The flags of one element domain.
	 *
	 * @param MeshSelectMode selectMode The domain.
	 * @return BooleanArray Its flags, by ordinal.
	 */
	fun of(selectMode: MeshSelectMode): BooleanArray =
		when (selectMode) {
			MeshSelectMode.Vertex -> vertices
			MeshSelectMode.Edge -> edges
			MeshSelectMode.Face -> faces
		}
}

/**
 * The elements of [selectMode]'s domain a box or a brush leaves out: the back-facing ones where the area hides them,
 * else none.
 *
 * @param MeshSelectMode selectMode The domain.
 * @return BooleanArray? The left-out elements by ordinal, or null for none.
 */
private fun GizmoMeshGeometry.hiddenIn(selectMode: MeshSelectMode): BooleanArray? = backFacing?.takeIf { back -> back.hidden }?.of(selectMode)

/**
 * The active element's own covered median: the median of the vertices the active element covers, in the
 * geometry's space - what the Active Element pivot anchors on and the "to Active" snaps aim at.
 *
 * @param MeshSelection selection The mesh selection.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' gizmo geometry.
 * @return Pair<Float, Float>? The median, or null when nothing is active, the active mesh is not shown,
 *   or its element covers no vertex.
 */
internal fun activeElementMedian(selection: MeshSelection, geometries: List<GizmoMeshGeometry>): Pair<Float, Float>? {
	val active = selection.activeElement ?: return null
	val activeGeometry = geometries.firstOrNull { geometry -> geometry.owner == active.owner } ?: return null
	val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeGeometry.topology)

	return if (activeCovered.isEmpty()) {
		null
	} else {
		MeshTransforms.medianPivot(activeGeometry.positions, activeCovered)
	}
}

/**
 * The nearest element of the given domain under the pointer across every mesh, or null.  Each mesh's
 * own nearest candidate is compared across meshes by its domain's screen-space metric, so overlapping
 * meshes resolve to the visually closest element.  An element that faces away loses to any element within reach that
 * does not, in its own mesh and across meshes, so a click over a folded lattice takes its front; one the area hides
 * is never hit.
 *
 * @param MeshSelectMode selectMode The element domain to pick in.
 * @param List<GizmoMeshGeometry> geometries The meshes' gizmo geometry.
 * @param Offset pointer The cursor in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return ActiveMeshElement? The hit element with its mesh, or null.
 */
internal fun hitTestMeshes(
	selectMode: MeshSelectMode,
	geometries: List<GizmoMeshGeometry>,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
): ActiveMeshElement? {
	var best: ElementHit? = null
	var bestOwner: ElementOwner? = null

	for (geometry in geometries) {
		val backFacing = geometry.backFacing
		// The front first; an element that faces away only when nothing else of the mesh is within reach, and never
		// where the area hides it.
		val hit =
			elementHitIn(selectMode, geometry, pointer, camera, size, backFacing?.of(selectMode), back = false)
				?: backFacing?.takeUnless { back -> back.hidden }?.let { elementHitIn(selectMode, geometry, pointer, camera, size, null, back = true) }
				?: continue
		val current = best

		if (current == null || (current.back && !hit.back) || (current.back == hit.back && hit.metric < current.metric)) {
			best = hit
			bestOwner = geometry.owner
		}
	}

	val owner = bestOwner ?: return null

	return best?.let { hit -> ActiveMeshElement(owner, hit.element) }
}

/**
 * One mesh's nearest element under the pointer, its domain's screen-space metric, and whether it faces away.
 *
 * @property MeshElement element The element.
 * @property Float metric Its distance from the pointer by its domain's metric, in screen pixels.
 * @property Boolean back Whether it faces away.
 */
private class ElementHit(val element: MeshElement, val metric: Float, val back: Boolean)

/**
 * The nearest element of [selectMode]'s domain in one mesh under the pointer, leaving out [excluded].
 *
 * @param MeshSelectMode selectMode The element domain to pick in.
 * @param GizmoMeshGeometry geometry The mesh.
 * @param Offset pointer The cursor in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @param BooleanArray? excluded The domain's elements that cannot be hit, or null for none.
 * @param Boolean back Whether a hit here counts as facing away.
 * @return ElementHit? The hit, or null.
 */
private fun elementHitIn(
	selectMode: MeshSelectMode,
	geometry: GizmoMeshGeometry,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
	excluded: BooleanArray?,
	back: Boolean,
): ElementHit? =
	when (selectMode) {
		MeshSelectMode.Vertex -> {
			hitTestVertex(geometry.positions, pointer, camera, size, excluded)?.let { vertexIndex ->
				val screen = worldToScreen(geometry.positions[vertexIndex * 2], geometry.positions[vertexIndex * 2 + 1], camera, size)
				ElementHit(MeshElement.Vertex(vertexIndex), (screen - pointer).getDistance(), back)
			}
		}

		MeshSelectMode.Edge -> {
			hitTestEdge(geometry.positions, geometry.edges, pointer, camera, size, excluded)?.let { edge ->
				val start = worldToScreen(geometry.positions[edge.endpointLow * 2], geometry.positions[edge.endpointLow * 2 + 1], camera, size)
				val end = worldToScreen(geometry.positions[edge.endpointHigh * 2], geometry.positions[edge.endpointHigh * 2 + 1], camera, size)
				ElementHit(edge, distanceToSegment(pointer.x, pointer.y, start.x, start.y, end.x, end.y), back)
			}
		}

		MeshSelectMode.Face -> {
			hitTestFace(geometry.positions, geometry.topology, pointer, camera, size, excluded)?.let { face ->
				ElementHit(face, (faceCentroidOnScreen(geometry, face.index, camera, size) - pointer).getDistance(), back)
			}
		}
	}

/**
 * One face's centroid on screen: its world center ([ElementTopology.faceCenterInto]) projected - the projection is
 * affine, so this is the mean of its corners' projections.
 *
 * @param GizmoMeshGeometry geometry The mesh's gizmo geometry.
 * @param Int faceIndex The face.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Offset The centroid in screen pixels.
 */
private fun faceCentroidOnScreen(geometry: GizmoMeshGeometry, faceIndex: Int, camera: ViewportCamera, size: IntSize): Offset {
	val center = FloatArray(2)
	geometry.topology.faceCenterInto(geometry.positions, faceIndex, center, 0)

	return worldToScreen(center[0], center[1], camera, size)
}

/**
 * The elements of one mesh enclosed by a rubber-band box, in the given domain (vertex inside, edge
 * both-endpoints, face centroid - the shared Blender rules), less the back-facing ones the area hides.
 *
 * @param MeshSelectMode selectMode The element domain to enclose.
 * @param GizmoMeshGeometry geometry The mesh's gizmo geometry.
 * @param Offset cornerA One box corner in screen pixels.
 * @param Offset cornerB The opposite box corner.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<MeshElement> The enclosed elements.
 */
internal fun elementsInBox(
	selectMode: MeshSelectMode,
	geometry: GizmoMeshGeometry,
	cornerA: Offset,
	cornerB: Offset,
	camera: ViewportCamera,
	size: IntSize,
): Set<MeshElement> =
	when (selectMode) {
		MeshSelectMode.Vertex -> {
			verticesInBox(geometry.positions, cornerA, cornerB, camera, size, geometry.hiddenIn(selectMode))
				.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
				.toSet<MeshElement>()
		}

		MeshSelectMode.Edge -> {
			edgesInBox(geometry.positions, geometry.edges, cornerA, cornerB, camera, size, geometry.hiddenIn(selectMode))
		}

		MeshSelectMode.Face -> {
			facesInBox(geometry.positions, geometry.topology, cornerA, cornerB, camera, size, geometry.hiddenIn(selectMode))
		}
	}

/**
 * Computes the Circle-select result for one brush stamp: enclose the elements of [working]'s select mode
 * within [radiusPx] of [center] (screen space) on EVERY mesh, less the back-facing ones the area hides, and either
 * add them (a paint stroke) or remove them (an erase stroke).
 *
 * @param MeshSelection working The stroke's accumulating selection (seeded from the committed selection).
 * @param Boolean erasing True to remove the enclosed elements, false to add them.
 * @param Offset center The brush center in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param List<GizmoMeshGeometry> geometries The meshes' gizmo geometry.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return MeshSelection The updated working selection.
 */
internal fun circleSelection(
	working: MeshSelection,
	erasing: Boolean,
	center: Offset,
	radiusPx: Float,
	geometries: List<GizmoMeshGeometry>,
	camera: ViewportCamera,
	size: IntSize,
): MeshSelection {
	val insideByOwner =
		geometries.associate { geometry ->
			val hidden = geometry.hiddenIn(working.selectMode)
			val inside: Set<MeshElement> =
				when (working.selectMode) {
					MeshSelectMode.Vertex -> {
						verticesInCircle(geometry.positions, center, radiusPx, camera, size, hidden)
							.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
							.toSet<MeshElement>()
					}

					MeshSelectMode.Edge -> {
						edgesInCircle(geometry.positions, geometry.edges, center, radiusPx, camera, size, hidden)
					}

					MeshSelectMode.Face -> {
						facesInCircle(geometry.positions, geometry.topology, center, radiusPx, camera, size, hidden)
					}
				}
			geometry.owner to inside
		}

	return if (erasing) {
		MeshSelectionOps.remove(working, insideByOwner)
	} else {
		MeshSelectionOps.box(working, insideByOwner, additive = true)
	}
}

/**
 * The per-domain highlight state the wireframe draw renders: the stored selection projected onto
 * vertices, edges, and faces.  Only the current select mode's domain comes straight from the selection;
 * the others are derived with Blender's display rules (an edge from both endpoints, a face from all of
 * its own vertices or edges, a face's edges flushing down in face mode).
 *
 * @property Set<Int> selectedVertexIndices Vertices drawn selected (vertex mode only).
 * @property Set<MeshElement.Edge> selectedEdges Edges drawn selected (stored or derived).
 * @property Set<Int> selectedFaceIndices Face ordinals drawn filled (stored or derived).
 * @property Int? activeVertexIndex The active vertex (vertex mode), or null.
 * @property MeshElement.Edge? activeEdge The active edge (edge mode), or null.
 * @property Int? activeFaceIndex The active face's ordinal (face mode), or null.
 */
internal class MeshHighlightSets(
	val selectedVertexIndices: Set<Int>,
	val selectedEdges: Set<MeshElement.Edge>,
	val selectedFaceIndices: Set<Int>,
	val activeVertexIndex: Int?,
	val activeEdge: MeshElement.Edge?,
	val activeFaceIndex: Int?,
)

/**
 * Projects the stored selection into per-domain highlight sets for the wireframe draw, by the display
 * rules [MeshHighlightSets] describes.
 *
 * @param Set<MeshElement> elements One mesh's stored elements (the current select mode's domain).
 * @param MeshElement? active The active element when it lives on this mesh, or null.
 * @param MeshSelectMode selectMode The current select mode.
 * @param ElementTopology topology The mesh's topology.
 * @return MeshHighlightSets The projected highlight state for this mesh.
 */
internal fun buildHighlightSets(
	elements: Set<MeshElement>,
	active: MeshElement?,
	selectMode: MeshSelectMode,
	topology: ElementTopology,
): MeshHighlightSets =
	when (selectMode) {
		MeshSelectMode.Vertex -> {
			val vertexIndices = elements.filterIsInstance<MeshElement.Vertex>().map { it.index }.toSet()
			MeshHighlightSets(
				selectedVertexIndices = vertexIndices,
				selectedEdges = MeshTopology.edgesWithBothEndpointsSelected(topology, vertexIndices),
				selectedFaceIndices =
					MeshTopology.facesWithAllVerticesSelected(topology, vertexIndices)
						.map { face -> face.index }
						.toSet(),
				activeVertexIndex = (active as? MeshElement.Vertex)?.index,
				activeEdge = null,
				activeFaceIndex = null,
			)
		}

		MeshSelectMode.Edge -> {
			val selectedEdges = elements.filterIsInstance<MeshElement.Edge>().toSet()
			MeshHighlightSets(
				selectedVertexIndices = emptySet(),
				selectedEdges = selectedEdges,
				selectedFaceIndices =
					MeshTopology.facesWithAllEdgesSelected(topology, selectedEdges)
						.map { face -> face.index }
						.toSet(),
				activeVertexIndex = null,
				activeEdge = active as? MeshElement.Edge,
				activeFaceIndex = null,
			)
		}

		MeshSelectMode.Face -> {
			val faceIndices = elements.filterIsInstance<MeshElement.Face>().map { face -> face.index }.toSet()
			MeshHighlightSets(
				selectedVertexIndices = emptySet(),
				selectedEdges = faceIndices.flatMap { faceIndex -> topology.edgesOfFace(faceIndex) }.toSet(),
				selectedFaceIndices = faceIndices,
				activeVertexIndex = null,
				activeEdge = null,
				activeFaceIndex = (active as? MeshElement.Face)?.index,
			)
		}
	}