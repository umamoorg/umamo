package org.umamo.render.puppet

import org.umamo.runtime.model.DrawableId

/** The flag of a primitive nobody selected: drawn in the domain's idle color. */
const val OVERLAY_FLAG_IDLE: Byte = 0

/** The flag of a selected primitive: drawn in the domain's selected color. */
const val OVERLAY_FLAG_SELECTED: Byte = 1

/** The flag of the one active primitive of a domain: drawn last, in the domain's active color. */
const val OVERLAY_FLAG_ACTIVE: Byte = 2

/**
 * What a mesh overlay is for, which decides what it draws: the Edit-mode wireframe with its per-element
 * flags, or the plain object wireframe of every listed mesh's edges in the idle color, with no fills and
 * no dots.
 */
enum class MeshOverlayKind {
	Edit,
	ObjectWireframe,
}

/**
 * The Edit-mode select mode the overlay mirrors, which decides the dot kind and the fill rule: vertex
 * dots in Vertex mode, face-centroid dots and every face filled in Face mode, neither in Edge mode.
 */
enum class MeshOverlaySelectMode {
	Vertex,
	Edge,
	Face,
}

/**
 * The overlay's sizes in DISPLAY pixels; the renderer multiplies by the render scale at draw time, the
 * way the grid line width already does, so a supersampled frame keeps the same on-screen size.
 *
 * @property Float vertexDotRadiusPx The vertex dot radius.
 * @property Float edgeWidthPx The edge line width.
 * @property Float faceDotRadiusPx The face-centroid dot radius.
 */
data class MeshOverlaySizes(
	val vertexDotRadiusPx: Float,
	val edgeWidthPx: Float,
	val faceDotRadiusPx: Float,
)

/**
 * One mesh's share of an overlay: its unique edges and the flags of its vertices, edges, and faces, all
 * in the mesh's OWN indices.  The renderer derives nothing from the selection; the producer ships these
 * as data, derived with the edit module's rules, so the two cannot drift.
 *
 * The value pairs with the model it was built against through [vertexCount] (and the face flag count,
 * when faces draw): a mesh whose resident disagrees is skipped for that frame and self-heals on the next
 * publish, the rule the Compose wireframe applied to ordinals a newer highlight set named.  A producer
 * that keeps a flag array's INSTANCE across publishes avoids a re-upload, since the renderer compares
 * the arrays by identity.
 *
 * A glue mesh draws at its pre-weld positions, the cage Edit mode edits and every pick and transform
 * reads (decision D15 in docs/plan/edit-mode-performance.md): the weld happens in the art's glue draw,
 * and the overlay reads the store its own capture wrote.  So a seam vertex's dot sits up to its weld
 * displacement from the art's seam, most of all on a vertex the weld pulls fully onto its partner.
 *
 * @property DrawableId drawableId The mesh's drawable.
 * @property Int vertexCount The vertex count the data was built against.
 * @property IntArray edgeEndpoints Two local vertex indices per unique edge, low index first, in the
 *   edit module's first-encounter order.
 * @property ByteArray vertexFlags One flag per vertex, or empty when nothing is flagged (the object
 *   wireframe).
 * @property ByteArray edgeFlags One flag per edge, parallel to [edgeEndpoints], or empty.
 * @property ByteArray faceFlags One flag per triangle of the mesh, in triangle order, or empty.
 * @property Int? activeVertex The active vertex, or null; also flagged active in [vertexFlags].
 * @property Int? activeEdge The active edge's ordinal in [edgeEndpoints], or null.
 * @property Int? activeFace The active triangle's ordinal, or null.
 */
class MeshOverlayMesh(
	val drawableId: DrawableId,
	val vertexCount: Int,
	val edgeEndpoints: IntArray,
	val vertexFlags: ByteArray,
	val edgeFlags: ByteArray,
	val faceFlags: ByteArray,
	val activeVertex: Int?,
	val activeEdge: Int?,
	val activeFace: Int?,
) {
	/** How many unique edges the mesh carries. */
	val edgeCount: Int get() = edgeEndpoints.size / 2

	init {
		require(edgeEndpoints.size % 2 == 0) { "edge endpoints come in pairs, got ${edgeEndpoints.size} for $drawableId" }
		for (endpoint in edgeEndpoints) {
			require(endpoint in 0 until vertexCount) { "edge endpoint $endpoint is outside the $vertexCount vertices of $drawableId" }
		}
		require(vertexFlags.isEmpty() || vertexFlags.size == vertexCount) { "vertex flags must be empty or one per vertex for $drawableId" }
		require(edgeFlags.isEmpty() || edgeFlags.size == edgeCount) { "edge flags must be empty or one per edge for $drawableId" }
		require(activeVertex == null || activeVertex in 0 until vertexCount) { "active vertex $activeVertex is outside $drawableId" }
		require(activeEdge == null || activeEdge in 0 until edgeCount) { "active edge $activeEdge is outside $drawableId" }
		require(activeFace == null || activeFace in faceFlags.indices) { "active face $activeFace is outside the flagged faces of $drawableId" }
	}
}

/**
 * The mesh overlay the renderer draws over the art: the Edit-mode wireframe, dots, and face fills, or
 * the object wireframe.  Immutable; the UI builds a new value when the selection, the mode, or the
 * session's meshes change, never when a preview push moves positions.
 *
 * The mesh ORDER defines the overlay's position-store layout, the way the glue layout's walk order does.
 *
 * @property MeshOverlayKind kind What the overlay draws.
 * @property MeshOverlaySelectMode selectMode The select mode mirrored (read for [MeshOverlayKind.Edit]).
 * @property List<MeshOverlayMesh> meshes The meshes shown, in layout order.
 * @property MeshOverlaySizes sizes The dot and line sizes in display pixels.
 */
class MeshOverlay(
	val kind: MeshOverlayKind,
	val selectMode: MeshOverlaySelectMode,
	val meshes: List<MeshOverlayMesh>,
	val sizes: MeshOverlaySizes,
)