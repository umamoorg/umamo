package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.MeshSelectMode
import org.umamo.render.DecodedImage
import org.umamo.render.ViewportCamera
import org.umamo.render.eval.paintOrder
import org.umamo.render.eval.renderOrder
import org.umamo.render.pick.PickCandidate
import org.umamo.render.pick.pickAllDrawables
import org.umamo.render.pick.pickDrawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.elementsInBox

/**
 * The static rest-pose front rank per drawable: its index in the back-to-front paint order the renderer
 * resolves, over the STATIC draw orders.  The UV editor is a rest-space authoring view, so island stacking
 * deliberately ignores any pose-blended draw-order channel.
 *
 * @param PuppetModel model The model whose drawables to rank.
 * @return Map<DrawableId, Float> The front rank per drawable (higher = more front).
 */
internal fun restFrontRank(model: PuppetModel): Map<DrawableId, Float> {
	val drawOrderById = model.drawables.associate { drawable -> drawable.id to drawable.drawOrder }
	val paintedBackToFront =
		if (model.renderRoot.children.isEmpty()) {
			paintOrder(model.drawables.map { drawable -> drawable.id }, drawOrderById)
		} else {
			renderOrder(model.renderRoot, drawOrderById)
		}

	return paintedBackToFront.withIndex().associate { (paintIndex, drawableId) -> drawableId to paintIndex.toFloat() }
}

/**
 * An alpha sampler over the shown surface - the atlas page or the source layer's artwork - for the
 * shared pick functions: (id, u, v) -> texel alpha 0..1.  v = 0 is the image's TOP row, matching the
 * decoded layout, so the sample needs no flip; a null image (the untextured 1x1 fallback) samples 1f
 * everywhere, so untextured islands stay clickable.
 *
 * @param DecodedImage? page The shown surface's decoded pixels, or null for the untextured fallback.
 * @return Function The sampler the pick functions consume.
 */
internal fun pageAlphaSampler(page: DecodedImage?): (DrawableId, Float, Float) -> Float {
	if (page == null) {
		return { _, _, _ -> 1f }
	}

	return { _, u, v ->
		val texelX = (u * page.width).toInt().coerceIn(0, page.width - 1)
		val texelY = (v * page.height).toInt().coerceIn(0, page.height - 1)
		// DecodedImage rgba is row-major, top row first, four bytes per texel; alpha is the fourth.
		(page.rgba[(texelY * page.width + texelX) * 4 + 3].toInt() and 0xFF) / 255f
	}
}

/**
 * The alpha threshold of the geometry tier.  The shared pickers reject a hit whose sampled alpha is
 * strictly below the threshold, and a sampler never returns less than 0, so a zero threshold admits
 * every texel and the pick becomes a pure mesh-containment test.
 */
private const val GEOMETRY_TIER_ALPHA_THRESHOLD = 0f

/**
 * The UV editor's island picker: the shared point pick (pickDrawable / pickAllDrawables) bound to
 * the shown surface's display-space islands, in two tiers.  The opaque tier samples alpha in the shown
 * surface's own frame, so overlapping islands resolve by the art actually visible at the click; when no
 * island is opaque there, the geometry tier takes any island whose mesh contains the point, so a click on
 * an island's transparent interior still selects it.  In the UV editor the mesh IS the island, which is
 * why this fallback is here and not in the 2D viewport's raster pick.
 *
 * @property Map<DrawableId, FloatArray> displayPositionsById Interleaved display-space (x, y) vertices per island.
 * @property Map<DrawableId, IntArray> indicesById Triangle index triples per island.
 * @property Map<DrawableId, FloatArray> meshUvsById The shown surface's uvs per island (the alpha-sample space).
 * @property Map<DrawableId, Float> frontRankById The static rest-pose front rank (higher = more front).
 * @property Function sampleAlpha (id, u, v) -> texel alpha over the shown surface.
 * @property Function atlasSizeOf (id) -> the shown image's (width, height), or null for the untextured fallback
 *   (full centrality instead of ray-marching a fake 1x1 page).
 */
internal class UvIslandPickController(
	val displayPositionsById: Map<DrawableId, FloatArray>,
	val indicesById: Map<DrawableId, IntArray>,
	val meshUvsById: Map<DrawableId, FloatArray>,
	val frontRankById: Map<DrawableId, Float>,
	val sampleAlpha: (DrawableId, Float, Float) -> Float,
	val atlasSizeOf: (DrawableId) -> Pair<Int, Int>?,
) {
	/**
	 * The front-most island whose mesh contains the point and whose texel on the shown surface there is
	 * opaque; failing that, the front-most island whose mesh contains the point at all; null only when
	 * the point lies outside every island.
	 *
	 * @param Float displayX The point X in display (texel) space.
	 * @param Float displayY The point Y in display (texel) space.
	 * @return DrawableId? The front-most hit of the first tier that has one, or null.
	 */
	fun topmostAt(displayX: Float, displayY: Float): DrawableId? =
		pickDrawable(displayX, displayY, displayPositionsById, indicesById, meshUvsById, frontRankById, sampleAlpha)
			?: pickDrawable(displayX, displayY, displayPositionsById, indicesById, meshUvsById, frontRankById, sampleAlpha, alphaThreshold = GEOMETRY_TIER_ALPHA_THRESHOLD)

	/**
	 * The islands under the point, front-to-back, each with its centrality - the overlap popup's
	 * candidate list, tiered like [topmostAt].  Geometry-tier candidates carry one flat centrality (a
	 * ray-march from a transparent hit measures nothing), so the popup's default row is the island a plain
	 * click there picks.
	 *
	 * @param Float displayX The point X in display (texel) space.
	 * @param Float displayY The point Y in display (texel) space.
	 * @return List<PickCandidate> The candidates, front-to-back.
	 */
	fun stackAt(displayX: Float, displayY: Float): List<PickCandidate> =
		pickAllDrawables(displayX, displayY, displayPositionsById, indicesById, meshUvsById, frontRankById, atlasSizeOf, sampleAlpha)
			.ifEmpty {
				pickAllDrawables(
					displayX,
					displayY,
					displayPositionsById,
					indicesById,
					meshUvsById,
					frontRankById,
					atlasSizeOf = { null },
					sampleAlpha = sampleAlpha,
					alphaThreshold = GEOMETRY_TIER_ALPHA_THRESHOLD,
				)
			}
}

/**
 * Builds the island picker for the shown surface from what the UV space already resolves: the shown
 * islands' display-space gizmo geometry, their uvs in that surface's own frame, the model's rest
 * front rank, and the surface's decoded pixels.
 *
 * @param List<GizmoMeshGeometry> geometries The islands' display-space gizmo geometry.
 * @param Map<DrawableId, Float> frontRank The model's rest-pose front rank (restFrontRank).
 * @param Map<DrawableId, FloatArray> uvsById Each island's uvs in the shown surface's frame (the alpha-sample space).
 * @param DecodedImage? image The shown surface's decoded pixels, or null for the untextured fallback.
 * @return UvIslandPickController The picker.
 */
internal fun uvIslandPick(
	geometries: List<GizmoMeshGeometry>,
	frontRank: Map<DrawableId, Float>,
	uvsById: Map<DrawableId, FloatArray>,
	image: DecodedImage?,
): UvIslandPickController =
	UvIslandPickController(
		displayPositionsById = geometries.mapNotNull { geometry -> geometry.drawableId?.let { drawableId -> drawableId to geometry.positions } }.toMap(),
		indicesById = geometries.mapNotNull { geometry -> geometry.drawableId?.let { drawableId -> drawableId to geometry.topology.faceCorners } }.toMap(),
		meshUvsById = uvsById,
		frontRankById = frontRank,
		sampleAlpha = pageAlphaSampler(image),
		atlasSizeOf = { image?.let { decodedImage -> decodedImage.width to decodedImage.height } },
	)

/**
 * The islands a finished box drag encloses: an island is enclosed when ANY of its UV vertices falls
 * inside the box (Blender's UV island box feel - a small box over an island's edge still takes it),
 * tested through the shared vertex box rule.  The result preserves the geometries' list order, so
 * the caller's active-target choice (the last enclosed island) is deterministic.
 *
 * @param List<GizmoMeshGeometry> geometries The islands' display-space gizmo geometry.
 * @param Offset cornerA One box corner in area-local pixels.
 * @param Offset cornerB The opposite box corner in area-local pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return List<DrawableId> The enclosed islands, in geometries order.
 */
internal fun uvIslandsInBox(
	geometries: List<GizmoMeshGeometry>,
	cornerA: Offset,
	cornerB: Offset,
	camera: ViewportCamera,
	size: IntSize,
): List<DrawableId> =
	geometries.mapNotNull { geometry ->
		geometry.drawableId.takeIf { elementsInBox(MeshSelectMode.Vertex, geometry, cornerA, cornerB, camera, size).isNotEmpty() }
	}