package org.umamo.render.puppet

import org.umamo.render.FrameOverlays
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.WarpLatticeLayout

/**
 * Which color a warp's lattice draws in, and so its shape ([drawsOutline]): the idle color of a lattice shown only
 * because its area shows every deformer, the selected color, or the active color.  The layer draws the roles in this
 * order, so a selected lattice lands over an idle one and the active lattice over both.
 */
enum class LatticeRole {
	Idle,
	Selected,
	Active,
	;

	/**
	 * Whether a lattice in this role draws slim - its outer edges and its four corner dots alone - rather than its
	 * whole grid: an idle lattice does, so an area showing every deformer stays readable, and a selected or active one
	 * shows its grid.  The one rule the lattice layer's draws and the picks over them read ([LatticePoints.shapeFor]).
	 */
	val drawsOutline: Boolean get() = this == Idle
}

/**
 * The warps whose lattices the 2D viewport draws over the art, each in its role, and the sizes it draws them
 * at.  The value names the warps and nothing more: the renderer reads each one's control points from the pose
 * it is drawing, so a lattice follows its art through every pose without a new value, and the clicks that read
 * the same pose land on what is drawn.
 *
 * An [LatticeRole.Idle] lattice draws only in a frame whose [org.umamo.render.FrameOverlays.allLattices] is set;
 * a selected or active one draws in every frame that draws the mesh overlay.
 *
 * @property Map<DeformerId, LatticeRole> roles The warps to draw, each in its role.  A deformer that is not a
 *   warp, or that the pose hides, is left out.
 * @property MeshOverlaySizes sizes The control-point dot radius and the grid line width, in display pixels.
 * @property Set<DeformerId> pendingIds The warps holding a Pose-mode lattice not yet keyed, drawn in the palette's
 *   pending color whatever their role.
 */
class LatticeOverlay(
	val roles: Map<DeformerId, LatticeRole>,
	val sizes: MeshOverlaySizes,
	val pendingIds: Set<DeformerId> = emptySet(),
)

/**
 * One warp's lattice as the current pose draws it: its layout and its control points in world space, x right
 * and z up, the space the 2D viewport projects and picks in.  Immutable; a pose that leaves a lattice where it
 * was keeps the same instance, so whatever compares it by identity (the GPU upload) does nothing.
 *
 * @property WarpLatticeLayout layout The lattice's points, grid lines, and cells.
 * @property FloatArray world The control points, interleaved x, z, in the layout's point order.
 */
class LatticePoints(
	val layout: WarpLatticeLayout,
	val world: FloatArray,
) {
	/** The whole grid as drawn: every control point dotted, every grid line. */
	val full: LatticeShape by lazy(LazyThreadSafetyMode.PUBLICATION) { LatticeShape(world, layout.edgeEndpoints, layout.pointCount) }

	/**
	 * The slim outline as drawn: the border points gathered out of [world] in the layout's outline order
	 * ([WarpLatticeLayout.outlinePointIndices]), the four corners first and dotted alone, and the border ring's edges.
	 */
	val outline: LatticeShape by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val indices = layout.outlinePointIndices
		val positions = FloatArray(indices.size * 2)

		for (slot in indices.indices) {
			positions[slot * 2] = world[indices[slot] * 2]
			positions[slot * 2 + 1] = world[indices[slot] * 2 + 1]
		}

		LatticeShape(positions, layout.outlineEdgeEndpoints, OUTLINE_DOT_COUNT)
	}

	/**
	 * What this lattice draws in [role], and so what picks it: the [outline] for a role that draws slim
	 * ([LatticeRole.drawsOutline]), else the [full] grid.  Each shape is built once per instance, so an unmoved
	 * lattice hands back the same arrays and the upload that compares them by identity does nothing.
	 *
	 * @param LatticeRole role The lattice's role.
	 * @return LatticeShape The drawn shape.
	 */
	fun shapeFor(role: LatticeRole): LatticeShape = if (role.drawsOutline) outline else full
}

/**
 * What of one lattice is drawn, and so what a click on it can land on: the points, the edges between them, and how
 * many of the points, from the first, are dotted.  The undotted rest are only the edges' anchors.
 *
 * @property FloatArray positions The points in world space, interleaved x, z.
 * @property IntArray edgeEndpoints The edges as index pairs into [positions].
 * @property Int dotCount How many points, from the first, draw a dot and pick as a point.
 */
class LatticeShape(
	val positions: FloatArray,
	val edgeEndpoints: IntArray,
	val dotCount: Int,
)

/** The dots a slim outline draws: its four corners, which the layout lists first. */
private const val OUTLINE_DOT_COUNT = 4

/** The triangle list every lattice carries in the layer: none, since a lattice never fills and never draws a diagonal. */
private val NO_TRIANGLES = IntArray(0)

/**
 * Every warp's lattice the pose built, in the model's deformer order, from the pose's baked worlds: each warp's
 * world control points ([WarpWorld.worldControlPoints]).  A warp the pose hides is absent, as it is from the bake.
 *
 * Arrays carry over from [previous] wherever they still hold: a lattice whose points did not move keeps its
 * instance, and one whose size did not change keeps its layout, so the GPU upload and buffers that compare them by
 * identity are left alone.
 *
 * @param List<Deformer> deformers The model's deformers, in model order.
 * @param Map<DeformerId, DeformerWorld> worlds The pose's baked deformer worlds.
 * @param Map<DeformerId, LatticePoints> previous The lattices the previous pose produced, or empty.
 * @return Map<DeformerId, LatticePoints> The lattices, in model order.
 */
internal fun latticePointsOf(
	deformers: List<Deformer>,
	worlds: Map<DeformerId, DeformerWorld>,
	previous: Map<DeformerId, LatticePoints>,
): Map<DeformerId, LatticePoints> {
	val lattices = LinkedHashMap<DeformerId, LatticePoints>()

	for (deformer in deformers) {
		if (deformer !is Deformer.Warp) {
			continue
		}

		val warpWorld = worlds[deformer.id] as? WarpWorld ?: continue
		val earlier = previous[deformer.id]
		val layout =
			earlier?.layout?.takeIf { held -> held.rows == warpWorld.rows && held.columns == warpWorld.cols }
				?: WarpLatticeLayout(warpWorld.rows, warpWorld.cols)

		if (warpWorld.cp.size != layout.pointCount * 2) {
			continue
		}

		val world = warpWorld.worldControlPoints()

		val unmoved = earlier != null && earlier.layout === layout && earlier.world.contentEquals(world)
		lattices[deformer.id] = if (unmoved) earlier else LatticePoints(layout, world)
	}

	return lattices
}

/**
 * The lattice layer the renderer draws: a direct overlay of kind [MeshOverlayKind.Lattice] holding each warp
 * [overlay] lists that [lattices] holds, in model order, each styled with its role and pending mark
 * ([MeshStyle.Lattice]) and drawn in its role's shape ([LatticePoints.shapeFor]).  Null when none of them is drawn.
 *
 * [previous] is handed back whole when nothing in it changed, and each mesh value in it is reused while its style
 * and shape hold, so a frame over an unchanged pose and selection uploads nothing.
 *
 * @param LatticeOverlay overlay The warps to draw and their roles.
 * @param Map<DeformerId, LatticePoints> lattices The current pose's lattices ([latticePointsOf]), in model order.
 * @param DirectMeshOverlay? previous The layer the previous call produced, or null.
 * @return DirectMeshOverlay? The layer, or null when no listed warp is drawn.
 */
internal fun latticeLayerOf(
	overlay: LatticeOverlay,
	lattices: Map<DeformerId, LatticePoints>,
	previous: DirectMeshOverlay?,
): DirectMeshOverlay? {
	val previousByOwner = previous?.overlay?.meshes?.associateBy { mesh -> mesh.owner } ?: emptyMap()
	val meshes = ArrayList<MeshOverlayMesh>()
	val positionsByOwner = HashMap<ElementOwner, FloatArray>()
	val trianglesByOwner = HashMap<ElementOwner, IntArray>()

	for ((deformerId, lattice) in lattices) {
		val role = overlay.roles[deformerId] ?: continue
		val owner = ElementOwner.Deformer(deformerId)
		val held = previousByOwner[owner]
		val style = MeshStyle.Lattice(role, deformerId in overlay.pendingIds)
		val shape = lattice.shapeFor(role)
		val mesh =
			if (held != null && held.style == style && held.edgeEndpoints === shape.edgeEndpoints) {
				held
			} else {
				MeshOverlayMesh(
					owner = owner,
					vertexCount = shape.positions.size / 2,
					edgeEndpoints = shape.edgeEndpoints,
					vertexFlags = ByteArray(0),
					edgeFlags = ByteArray(0),
					faceFlags = ByteArray(0),
					activeVertex = null,
					activeEdge = null,
					activeFace = null,
					style = style,
					vertexDotCount = shape.dotCount,
				)
			}
		meshes.add(mesh)
		positionsByOwner[owner] = shape.positions
		trianglesByOwner[owner] = NO_TRIANGLES
	}

	if (meshes.isEmpty()) {
		return null
	}

	if (previous != null && previous.overlay.sizes == overlay.sizes && sameLayer(previous, meshes, positionsByOwner)) {
		return previous
	}

	return DirectMeshOverlay(
		MeshOverlay(MeshOverlayKind.Lattice, MeshOverlaySelectMode.Vertex, meshes, overlay.sizes),
		positionsByOwner,
		trianglesByOwner,
	)
}

/**
 * Whether [previous] already holds exactly these meshes, in this order, over these position arrays.
 *
 * @param DirectMeshOverlay previous The earlier layer.
 * @param List<MeshOverlayMesh> meshes The meshes the layer would hold now.
 * @param Map<ElementOwner, FloatArray> positionsByOwner Their positions.
 * @return Boolean True when every mesh and array is the same instance.
 */
private fun sameLayer(previous: DirectMeshOverlay, meshes: List<MeshOverlayMesh>, positionsByOwner: Map<ElementOwner, FloatArray>): Boolean {
	val previousMeshes = previous.overlay.meshes

	if (previousMeshes.size != meshes.size) {
		return false
	}

	for (meshIndex in meshes.indices) {
		val mesh = meshes[meshIndex]

		if (previousMeshes[meshIndex] !== mesh || previous.positionsByOwner[mesh.owner] !== positionsByOwner[mesh.owner]) {
			return false
		}
	}

	return true
}

/** Every lattice role, what a frame showing every deformer draws. */
internal val EVERY_LATTICE_ROLE: Set<LatticeRole> = LatticeRole.entries.toSet()

/** The roles a frame that shows only the picked deformers draws. */
private val PICKED_LATTICE_ROLES: Set<LatticeRole> = setOf(LatticeRole.Selected, LatticeRole.Active)

/**
 * The lattice roles a frame drawing with [overlays] draws: none when the frame hides the mesh overlay or draws the
 * lattices at no opacity, every role when it shows every deformer, and only the selected and active ones otherwise.
 * The one rule both the renderer's lattice draws and the picks over them ([latticesDrawnIn]) read, so a pick - and
 * so a hover - never lands on a lattice the area does not draw.
 *
 * @param FrameOverlays overlays The frame's overlay options.
 * @return Set<LatticeRole> The drawn roles.
 */
fun latticeRolesDrawnIn(overlays: FrameOverlays): Set<LatticeRole> =
	when {
		!overlays.meshOverlay || overlays.latticeOpacity <= 0f -> emptySet()
		overlays.allLattices -> EVERY_LATTICE_ROLE
		else -> PICKED_LATTICE_ROLES
	}

/**
 * The lattices a frame drawing with [overlays] shows of [lattices]: each listed warp whose role the frame draws
 * ([latticeRolesDrawnIn]), whole.  What box and circle select test by center; a click reads the drawn shapes
 * ([latticeShapesDrawnIn]).
 *
 * @param LatticeOverlay? overlay The lattices the renderer holds, or null.
 * @param Map<DeformerId, LatticePoints> lattices The current pose's lattices, in model order.
 * @param FrameOverlays overlays The frame's overlay options.
 * @return Map<DeformerId, LatticePoints> The drawn lattices, in model order.
 */
fun latticesDrawnIn(
	overlay: LatticeOverlay?,
	lattices: Map<DeformerId, LatticePoints>,
	overlays: FrameOverlays,
): Map<DeformerId, LatticePoints> {
	val roles = latticeRolesDrawnIn(overlays)

	if (overlay == null || roles.isEmpty()) {
		return emptyMap()
	}

	return lattices.filter { (deformerId, _) -> overlay.roles[deformerId] in roles }
}

/**
 * The shapes a frame drawing with [overlays] draws of [lattices]: each lattice [latticesDrawnIn] keeps, in its
 * role's shape ([LatticePoints.shapeFor]).  What a click or a hover in that frame can land on, so a lattice picks
 * exactly where its dots and lines are drawn.
 *
 * @param LatticeOverlay? overlay The lattices the renderer holds, or null.
 * @param Map<DeformerId, LatticePoints> lattices The current pose's lattices, in model order.
 * @param FrameOverlays overlays The frame's overlay options.
 * @return Map<DeformerId, LatticeShape> The drawn shapes, in model order.
 */
fun latticeShapesDrawnIn(
	overlay: LatticeOverlay?,
	lattices: Map<DeformerId, LatticePoints>,
	overlays: FrameOverlays,
): Map<DeformerId, LatticeShape> {
	val drawn = latticesDrawnIn(overlay, lattices, overlays)

	if (overlay == null || drawn.isEmpty()) {
		return emptyMap()
	}

	val shapes = LinkedHashMap<DeformerId, LatticeShape>()

	for ((deformerId, lattice) in drawn) {
		val role = overlay.roles[deformerId] ?: continue
		shapes[deformerId] = lattice.shapeFor(role)
	}

	return shapes
}