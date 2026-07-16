package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.ImageBitmap
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.render.pick.drawableCentroids
import org.umamo.render.pick.pickAllDrawables
import org.umamo.render.pick.pickDrawable
import org.umamo.render.pick.screenToWorldX
import org.umamo.render.pick.screenToWorldY
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasKeyByDrawable
import org.umamo.runtime.model.partNameByDrawable
import org.umamo.runtime.model.pickableIndicesByDrawable
import org.umamo.runtime.model.pickableUvsByDrawable
import org.umamo.ui.model.DrawableThumbnailProvider
import org.umamo.ui.model.DrawableThumbnailer

/**
 * CPU-side hit-testing and art previews for the viewport, over the current deformed pose. Runs entirely on
 * the UI thread and never touches GL: it reuses the renderer's pure-CPU pick geometry (a snapshot the render
 * thread produces) and its resolved draw order, and the retained CPU atlas pixels for the alpha gate. The
 * facade resolves each area's camera and size and forwards them here, so this class is area-agnostic.
 *
 * The model-derived lookup maps (pick indices / UVs, atlas keys, part labels) and the thumbnail provider are
 * rebuilt by [updateModel] so session-created drawables (a duplicate's fresh ".001" id) and visibility edits
 * stay pickable, sampleable, and labeled - maps frozen at construction would leave a duplicate unpickable.
 *
 * ビューポートの CPU ヒットテストとアートプレビュー。UI スレッドのみ、GL 不使用。
 *
 * @property PuppetRenderer renderer The renderer, for its pure-CPU pickGeometry() and drawnOrder().
 * @property PuppetTextures textures The atlas pages, for the alpha gate and page sizes.
 */
internal class ViewportPicker(
	private val renderer: PuppetRenderer,
	private val textures: PuppetTextures,
	model: PuppetModel,
) {
	// Per-drawable triangle indices for hit-testing, restricted to the drawables actually shown (the
	// Parts-panel eyeball cascade) and to those carrying a triangle mesh. Picking iterates the pose's
	// deformed positions and looks indices up here, so unshown / mesh-less drawables are never hit.
	private var pickableIndices: Map<DrawableId, IntArray> = model.pickableIndicesByDrawable()

	// Per-drawable UVs (full-atlas [0,1]) for the same pickable set, so picking can interpolate the hit
	// point's UV and sample the atlas alpha (rejecting clicks on a mesh's transparent triangle overhang).
	private var pickableUvs: Map<DrawableId, FloatArray> = model.pickableUvsByDrawable()

	// Drawable id -> owning part name, for the overlap-picker row labels.
	private var partNameByDrawableId: Map<DrawableId, String> = model.partNameByDrawable()

	// Drawable id -> atlas lookup key: the source-format id the atlas map is keyed by, resolved through
	// textureSourceId so a duplicate samples its SOURCE's texels for the pick alpha gate.
	private var atlasKeyByDrawableId: Map<DrawableId, String> = model.atlasKeyByDrawable()

	// Art-mesh previews for the overlap picker (the same provider the Outliner hover uses). Pure CPU over
	// the immutable atlas bytes, so it is built once and shared - a single cache across both consumers.
	private val thumbnailer = DrawableThumbnailer(model, textures)

	/**
	 * Rebuilds the model-derived lookup maps and the thumbnail provider after a committed edit or undo, so
	 * session-created drawables and visibility edits stay pickable, sampleable, and labeled.
	 *
	 * @param PuppetModel model The current model.
	 */
	fun updateModel(model: PuppetModel) {
		pickableIndices = model.pickableIndicesByDrawable()
		pickableUvs = model.pickableUvsByDrawable()
		partNameByDrawableId = model.partNameByDrawable()
		atlasKeyByDrawableId = model.atlasKeyByDrawable()
		thumbnailer.updateModel(model)
	}

	/**
	 * Hit-tests a viewport pixel against the current deformed pose and returns the FRONT-MOST opaque drawable
	 * under it, or null when the click misses or no pose is ready yet. Restricted to shown, meshed drawables;
	 * front/back uses the renderer's resolved draw order, and the transparent triangle overhang is rejected
	 * by the atlas-alpha gate.
	 *
	 * @param ViewportCamera camera The area's camera (screen-to-world inverse).
	 * @param Int width The area width in pixels.
	 * @param Int height The area height in pixels.
	 * @param Float cursorXpx Cursor X in pixels (top-left origin).
	 * @param Float cursorYpx Cursor Y in pixels (top-left origin).
	 * @return DrawableId The hit drawable, or null on a miss.
	 */
	fun pickAt(camera: ViewportCamera, width: Int, height: Int, cursorXpx: Float, cursorYpx: Float): DrawableId? {
		if (width <= 0 || height <= 0) {
			return null
		}
		val geometry = renderer.pickGeometry() ?: return null
		// Screen (y-down) to world (y-up), the inverse of the camera's worldToNdc - see ScreenSpacePick.
		val worldX = screenToWorldX(cursorXpx, camera, width)
		val worldY = screenToWorldY(cursorYpx, camera, height)
		return pickDrawable(
			worldX,
			worldY,
			geometry.worldPositions,
			pickableIndices,
			pickableUvs,
			frontRankMap(),
			::sampleTexelAlpha,
		)
	}

	/**
	 * All opaque drawables under a viewport pixel, FRONT-TO-BACK with each one's centrality - for the
	 * Alt-click overlap-picker popup. Same screen-to-world, hit, and alpha gate as [pickAt].
	 *
	 * @param ViewportCamera camera The area's camera.
	 * @param Int width The area width in pixels.
	 * @param Int height The area height in pixels.
	 * @param Float cursorXpx Cursor X in pixels (top-left origin).
	 * @param Float cursorYpx Cursor Y in pixels (top-left origin).
	 * @return List The opaque candidates, front-to-back.
	 */
	fun pickAllAt(
		camera: ViewportCamera,
		width: Int,
		height: Int,
		cursorXpx: Float,
		cursorYpx: Float,
	): List<PickCandidate> {
		if (width <= 0 || height <= 0) {
			return emptyList()
		}
		val geometry = renderer.pickGeometry() ?: return emptyList()
		val worldX = screenToWorldX(cursorXpx, camera, width)
		val worldY = screenToWorldY(cursorYpx, camera, height)
		return pickAllDrawables(
			worldX,
			worldY,
			geometry.worldPositions,
			pickableIndices,
			pickableUvs,
			frontRankMap(),
			::atlasSizeOf,
			::sampleTexelAlpha,
		)
	}

	/**
	 * Every visible drawable's world-space centroid at the current pose, for Object-mode region select. Reads
	 * the same posed pick geometry as [pickAt] and reduces each drawable to its centroid; empty before the
	 * first frame's geometry lands. Area-independent - the caller projects with its camera.
	 *
	 * @return Map The per-drawable [x, y] world centroid.
	 */
	fun drawableWorldCentroids(): Map<DrawableId, FloatArray> {
		val geometry = renderer.pickGeometry() ?: return emptyMap()
		return drawableCentroids(geometry.worldPositions)
	}

	/**
	 * A small preview of a drawable's art for the overlap-picker popup, delegated to the shared thumbnailer
	 * (the same provider the Outliner hover uses).  Returns null for an untextured or mesh-less drawable.
	 *
	 * @param DrawableId id The drawable to preview.
	 * @return ImageBitmap The cropped preview, or null when untextured or mesh-less.
	 */
	fun thumbnailFor(id: DrawableId): ImageBitmap? = thumbnailer.thumbnailFor(id)

	/** The shared art-mesh thumbnail provider, exposed so the host can wire it to the Outliner hover preview. */
	fun thumbnails(): DrawableThumbnailProvider = thumbnailer

	/**
	 * The name of the part a drawable belongs to, for the overlap-picker row labels, or null when the
	 * drawable sits at the root with no owning part.
	 *
	 * @param DrawableId id The drawable to look up.
	 * @return String The owning part's name, or null.
	 */
	fun partNameFor(id: DrawableId): String? = partNameByDrawableId[id]

	/** The renderer's resolved back-to-front draw list as a front-rank map (higher index = more front). */
	private fun frontRankMap(): Map<DrawableId, Float> =
		renderer.drawnOrder().withIndex().associate { (index, id) -> id to index.toFloat() }

	/** The drawable's atlas page size (width, height) in texels, or null when it is untextured. */
	private fun atlasSizeOf(id: DrawableId): Pair<Int, Int>? =
		textures.atlasIndexByDrawableId[atlasKeyByDrawableId[id] ?: id.raw]?.let { atlasIndex ->
			textures.atlases.getOrNull(atlasIndex)?.let { image -> image.width to image.height }
		}

	/**
	 * The atlas-texel alpha (0..1) for a drawable at a full-atlas (u, v), or 1f when the drawable is
	 * untextured (a flat draw color, treated as fully opaque). Reads the retained CPU atlas pixels; the
	 * alpha byte is the coverage whether or not the atlas is premultiplied, so no un-premultiply is needed.
	 *
	 * @param DrawableId id The drawable.
	 * @param Float u The full-atlas U.
	 * @param Float v The full-atlas V.
	 * @return Float The texel alpha, 0..1.
	 */
	private fun sampleTexelAlpha(id: DrawableId, u: Float, v: Float): Float {
		val atlasIndex = textures.atlasIndexByDrawableId[atlasKeyByDrawableId[id] ?: id.raw] ?: return 1f
		val image = textures.atlases.getOrNull(atlasIndex) ?: return 1f
		val px = (u * image.width).toInt().coerceIn(0, image.width - 1)
		val py = (v * image.height).toInt().coerceIn(0, image.height - 1)
		val alpha = image.rgba[(py * image.width + px) * 4 + 3].toInt() and 0xFF
		return alpha / 255f
	}
}
