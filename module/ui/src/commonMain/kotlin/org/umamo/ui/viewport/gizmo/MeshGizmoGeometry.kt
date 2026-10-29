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

/*
 * The gizmo kit both work surfaces share, the 2D viewport and the UV editor: screen-space hit tests and
 * box / circle queries (ViewportGeometryAdapters.kt and this file), the Edit overlay's derive for the
 * renderer (EditMeshOverlayProducer.kt; the renderer draws every wireframe), the draw helpers every
 * overlay renders alike (GizmoChrome.kt), selection input
 * (GizmoSelectionInput.kt, MarqueeSelectController.kt, ObjectPickController.kt), and the modal transform
 * (TransformOperators.kt, ModalTransformController.kt, ModalGestureState.kt, CursorWrap.kt,
 * ViewportCursor.kt).  Nothing here knows which surface it serves: a surface hands its geometry in as
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
 */
internal class GizmoMeshGeometry(
	val owner: ElementOwner,
	val topology: ElementTopology,
	val positions: FloatArray,
) {
	/** The unique edges, in the topology's order. */
	val edges: List<MeshElement.Edge> get() = topology.edges

	/** The drawable whose mesh this is, or null for a lattice; a UV editor's geometry is always a drawable's. */
	val drawableId: DrawableId? get() = (owner as? ElementOwner.Drawable)?.id
}

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
 * own hit test already picks its nearest candidate; this compares the survivors across meshes by the
 * same screen-space metric each domain uses (vertex distance, point-to-segment distance, face
 * centroid distance - the mean of the face's corners), so overlapping meshes resolve to the visually
 * closest element.
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
	var best: ActiveMeshElement? = null
	var bestMetric = Float.MAX_VALUE

	for (geometry in geometries) {
		when (selectMode) {
			MeshSelectMode.Vertex -> {
				val vertexIndex = hitTestVertex(geometry.positions, pointer, camera, size) ?: continue
				val screen = worldToScreen(geometry.positions[vertexIndex * 2], geometry.positions[vertexIndex * 2 + 1], camera, size)
				val metric = (screen - pointer).getDistance()

				if (metric < bestMetric) {
					bestMetric = metric
					best = ActiveMeshElement(geometry.owner, MeshElement.Vertex(vertexIndex))
				}
			}

			MeshSelectMode.Edge -> {
				val edge = hitTestEdge(geometry.positions, geometry.edges, pointer, camera, size) ?: continue
				val start = worldToScreen(geometry.positions[edge.endpointLow * 2], geometry.positions[edge.endpointLow * 2 + 1], camera, size)
				val end = worldToScreen(geometry.positions[edge.endpointHigh * 2], geometry.positions[edge.endpointHigh * 2 + 1], camera, size)
				val metric = distanceToSegment(pointer.x, pointer.y, start.x, start.y, end.x, end.y)

				if (metric < bestMetric) {
					bestMetric = metric
					best = ActiveMeshElement(geometry.owner, edge)
				}
			}

			MeshSelectMode.Face -> {
				val face = hitTestFace(geometry.positions, geometry.topology, pointer, camera, size) ?: continue
				val metric = (faceCentroidOnScreen(geometry, face.index, camera, size) - pointer).getDistance()

				if (metric < bestMetric) {
					bestMetric = metric
					best = ActiveMeshElement(geometry.owner, face)
				}
			}
		}
	}

	return best
}

/**
 * One face's centroid on screen: the mean of its corners' projections.
 *
 * @param GizmoMeshGeometry geometry The mesh's gizmo geometry.
 * @param Int faceIndex The face.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Offset The centroid in screen pixels.
 */
private fun faceCentroidOnScreen(geometry: GizmoMeshGeometry, faceIndex: Int, camera: ViewportCamera, size: IntSize): Offset {
	var sumX = 0f
	var sumY = 0f
	val topology = geometry.topology

	for (cornerIndex in 0 until topology.cornersPerFace) {
		val corner = topology.faceCorners[faceIndex * topology.cornersPerFace + cornerIndex]
		val screen = worldToScreen(geometry.positions[corner * 2], geometry.positions[corner * 2 + 1], camera, size)
		sumX += screen.x
		sumY += screen.y
	}

	return Offset(sumX / topology.cornersPerFace, sumY / topology.cornersPerFace)
}

/**
 * The elements of one mesh enclosed by a rubber-band box, in the given domain (vertex inside, edge
 * both-endpoints, face centroid - the shared Blender rules).
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
			verticesInBox(geometry.positions, cornerA, cornerB, camera, size)
				.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
				.toSet<MeshElement>()
		}

		MeshSelectMode.Edge -> {
			edgesInBox(geometry.positions, geometry.edges, cornerA, cornerB, camera, size)
		}

		MeshSelectMode.Face -> {
			facesInBox(geometry.positions, geometry.topology, cornerA, cornerB, camera, size)
		}
	}

/**
 * Computes the Circle-select result for one brush stamp: enclose the elements of [working]'s select mode
 * within [radiusPx] of [center] (screen space) on EVERY mesh, and either add them (a paint stroke) or
 * remove them (an erase stroke).
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
			val inside: Set<MeshElement> =
				when (working.selectMode) {
					MeshSelectMode.Vertex -> {
						verticesInCircle(geometry.positions, center, radiusPx, camera, size)
							.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
							.toSet<MeshElement>()
					}

					MeshSelectMode.Edge -> {
						edgesInCircle(geometry.positions, geometry.edges, center, radiusPx, camera, size)
					}

					MeshSelectMode.Face -> {
						facesInCircle(geometry.positions, geometry.topology, center, radiusPx, camera, size)
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
 * vertices, edges, and faces. Only the current select mode's domain comes straight from the selection;
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
 * Projects the stored selection into per-domain highlight sets for the wireframe draw, applying
 * Blender's derive-up rules (vertex mode lights edges with both endpoints selected and faces with all
 * vertices selected; edge mode lights faces with all their own edges selected) and flush-down display
 * (face mode lights the selected faces' own edges).
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