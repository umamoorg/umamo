package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.withMeshPositions
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.ContentBounds
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasKeyByDrawable
import org.umamo.runtime.model.drawableNameByDrawable
import org.umamo.runtime.model.partNameByDrawable
import org.umamo.runtime.model.pickableIndicesByDrawable
import org.umamo.runtime.model.pickableUvsByDrawable
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.graphics.rgbaToImageBitmap
import org.umamo.ui.model.thumbnails.DrawableThumbnailer
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.viewport.ViewportColorSettings
import org.umamo.ui.viewport.ViewportOverlayColors
import org.umamo.ui.viewport.gizmo.MeshHighlightSets
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.applyOperator
import org.umamo.ui.viewport.gizmo.gestureParameters
import java.io.File
import kotlin.test.Test

/** The output prefix every row carries, so the rows grep out of a verbose test log. */
private const val PROBE_TAG = "[edit-grab-perf]"

/** The area the latches and the modal transforms are keyed on. */
private const val PROBE_AREA_ID = "edit-grab-perf"

/** The label column width, so the numbers line up across rows. */
private const val LABEL_WIDTH = 86

/**
 * Print-only Edit-mode and Object-mode Grab perf probe on the moc3.perfSample model (modelF by default:
 * 1330 drawables, 220k vertices): the wall time of each stage of the UI-thread work a whole-selection
 * Grab does - the per-commit geometry capture, the latch, the per-pointer-event drive and its halves,
 * the per-push picker rebuild, and the Edit overlay's per-frame re-pose, wireframe record, and wireframe
 * raster - plus the Object-mode latch and drive over the same rig.  The wireframe is recorded into a
 * Skia picture and then rasterized onto a CPU surface, so the record and the raster are separate rows;
 * the app rasterizes through GPU-backed Skia, which this cannot measure.  Pins nothing - see
 * docs/plan/edit-mode-performance.md for the numbers and what each phase is expected to move.  Skips
 * without the corpus.  Standard streams are off in the build, so the rows show with --info or in
 * build/test-results.
 */
class EditGrabPerfProbeTest {
	private val areaWidth = 1600
	private val areaHeight = 900
	private val rounds = 8
	private val sample: File? = System.getProperty("moc3.perfSample")?.let(::File)?.takeIf { it.isFile }

	/** Every stage of a whole-selection Grab over one session: Object mode first, then Edit mode. */
	@Test
	fun probeWholeSelectionGrabStages() {
		val mocFile = sample
		if (mocFile == null) {
			println("moc3.perfSample not present; skipping perf probe")
			return
		}
		val loadStart = System.nanoTime()
		val model = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.read(mocFile.readBytes()), null))
		report("load ${mocFile.name}: ${millisSince(loadStart)} ms")
		describeModel(model)
		val camera = fitCamera(model)
		val size = IntSize(areaWidth, areaHeight)
		report("camera: fit zoom=${camera.zoom} center=(${camera.centerX}, ${camera.centerY}) area=${areaWidth}x$areaHeight")

		val session = EditorSession(model)
		val pushed = ArrayList<PuppetModel>()
		probeObjectMode(session, camera, size, pushed)
		probeEditMode(session, model, camera, size, pushed)
	}

	/**
	 * The Object-mode rows: the whole-selection latch (one capture per eligible drawable) and the drive.
	 *
	 * @param EditorSession session The session, in Object mode at the neutral pose.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @param MutableList<PuppetModel> pushed Where the drives' preview models land.
	 */
	private fun probeObjectMode(session: EditorSession, camera: ViewportCamera, size: IntSize, pushed: MutableList<PuppetModel>) {
		session.selectAllObjects()
		session.beginObjectOperator(MeshOperatorKind.Grab, PROBE_AREA_ID)
		if (session.activeObjectOperator.value?.areaId != PROBE_AREA_ID) {
			report("Object-mode Grab did not latch; skipping the O rows")
			return
		}
		val objectTransform = ObjectModalTransform(PROBE_AREA_ID, session) { folded -> pushed.add(folded) }
		timed("O1 ObjectModalTransform.begin (captureDrawableWorld per eligible drawable) [once per gesture]", 1) {
			objectTransform.begin(MeshOperatorKind.Grab)
		}
		if (objectTransform.gesture.capture == null) {
			report("Object-mode capture is empty; skipping O2")
		} else {
			timed("O2 ObjectModalTransform.drivePreview [per pointer event, UI thread]") { round ->
				objectTransform.drivePreview(pointerFor(round), camera, size)
			}
		}
		session.clearObjectOperator()
		objectTransform.end()
	}

	/**
	 * The Edit-mode rows: the per-commit capture, the latch, the drive and its halves, the per-push
	 * rebuilds, and the overlay's per-frame work.
	 *
	 * @param EditorSession session The session, about to enter Edit mode.
	 * @param PuppetModel model The committed model.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @param MutableList<PuppetModel> pushed Where the drives' preview models land.
	 */
	private fun probeEditMode(session: EditorSession, model: PuppetModel, camera: ViewportCamera, size: IntSize, pushed: MutableList<PuppetModel>) {
		session.setMode(EditorMode.Edit)
		if (session.mode.value != EditorMode.Edit) {
			report("could not enter Edit mode; skipping the Edit rows")
			return
		}
		session.selectAllMeshElements()
		val meshSelection = session.meshSelection.value
		val drawableIds = meshSelection.drawableIds
		report("session: edit meshes=${drawableIds.size} selectedElements=${drawableIds.sumOf { drawableId -> meshSelection.elementsOf(drawableId).size }}")

		val liveGeometry = timed("A1 editMeshGeometries [once per commit; also the latch's input]", 3) { editMeshGeometries(model, drawableIds) }
		val highlights = timed("A2 editHighlights [once per selection change]", 3) { editHighlights(meshSelection, liveGeometry) }

		val modalTransform = EditModalTransform(PROBE_AREA_ID, session) { folded -> pushed.add(folded) }
		session.beginMeshOperator(MeshOperatorKind.Grab, PROBE_AREA_ID)
		timed("B1 EditModalTransform.begin [once per gesture]", 1) { modalTransform.begin(MeshOperatorKind.Grab, liveGeometry, meshSelection) }
		val capture = modalTransform.gesture.capture
		if (capture == null) {
			report("Edit-mode capture is empty; skipping the drive rows")
			session.clearMeshOperator()
			return
		}
		report("capture: entries=${capture.transform.entries.size} groups=${capture.transform.entries.sumOf { entry -> entry.groups.size }} pivotMode=${session.pivotMode.value}")

		val firstPreview = pushed.size
		timed("C1 drivePreview end to end [per pointer event, UI thread]") { round -> modalTransform.drivePreview(pointerFor(round), camera, size) }
		val previews = pushed.subList(firstPreview, pushed.size)
		if (previews.isEmpty()) {
			report("no preview was pushed; skipping the per-push and per-frame rows")
			session.clearMeshOperator()
			return
		}
		probeDriveHalves(capture.transform, capture.geometryById, model, camera, size)
		probePushRebuilds(model, previews)
		probeFrameRows(liveGeometry, highlights, meshSelection, previews, camera, size)
		session.clearMeshOperator()
		modalTransform.end()
	}

	/**
	 * The three halves of one drive, each timed on its own: the operator over the frozen world shapes,
	 * the inverse back onto the base meshes, and the fold of the new positions into a preview model.
	 *
	 * @param ModalTransformCapture transform The gesture's shared capture.
	 * @param Map<DrawableId, DrawableWorldGeometry> geometryById Each moving mesh's frozen geometry.
	 * @param PuppetModel model The committed model the fold starts from.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	private fun probeDriveHalves(
		transform: ModalTransformCapture,
		geometryById: Map<DrawableId, DrawableWorldGeometry>,
		model: PuppetModel,
		camera: ViewportCamera,
		size: IntSize,
	) {
		val frame = TransformGestureFrame(transform.anchor, Offset.Zero, Offset(40f, 20f), null, camera, size)
		val parameters = gestureParameters(MeshOperatorKind.Grab, frame, transform.rotationTracker)
		val transformedById = HashMap<DrawableId, FloatArray>()
		timed("C1a   applyOperator (translate + weighted pass) over every entry") {
			for (entry in transform.entries) {
				transformedById[entry.drawableId] = applyOperator(MeshOperatorKind.Grab, entry.positions, entry.groups, parameters, entry.influence)
			}
		}
		val newBaseById = HashMap<DrawableId, FloatArray>()
		timed("C1b   worldToBase (deformer inverse + movementToBase) over every entry") {
			for (entry in transform.entries) {
				val geometry = geometryById.getValue(entry.drawableId)
				newBaseById[entry.drawableId] = geometry.worldToBase(transformedById.getValue(entry.drawableId), entry.movedIndices)
			}
		}
		timed("C1c   withMeshPositions fold over every entry") {
			var working = model
			for (entry in transform.entries) {
				working = working.withMeshPositions(entry.drawableId, newBaseById.getValue(entry.drawableId))
			}
			working
		}
	}

	/**
	 * What the desktop service rebuilds on every preview push: the picker's lookups and the thumbnailer.
	 *
	 * @param PuppetModel model The committed model.
	 * @param List<PuppetModel> previews The drives' preview models, oldest first.
	 */
	private fun probePushRebuilds(model: PuppetModel, previews: List<PuppetModel>) {
		timed("C2 picker query rebuild (indices, uvs, names, atlas keys) [per push, UI thread]") { round ->
			val preview = previews[round % previews.size]
			preview.pickableIndicesByDrawable()
			preview.pickableUvsByDrawable()
			preview.partNameByDrawable()
			preview.drawableNameByDrawable()
			preview.atlasKeyByDrawable()
		}
		val thumbnailer = DrawableThumbnailer(model, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false))
		timed("C3 DrawableThumbnailer.updateModel [per push, UI thread]") { round -> thumbnailer.updateModel(previews[round % previews.size]) }
	}

	/**
	 * The Edit overlay's per-rendered-frame work: the re-pose of every moving mesh, then the wireframe
	 * record and raster in vertex and edge modes, then the frame image conversion.
	 *
	 * @param List<EditMeshGeometry> liveGeometry The session meshes' live geometry.
	 * @param Map highlights The vertex-mode highlight sets.
	 * @param MeshSelection meshSelection The whole-selection mesh selection.
	 * @param List<PuppetModel> previews The drives' preview models, oldest first.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	private fun probeFrameRows(
		liveGeometry: List<EditMeshGeometry>,
		highlights: Map<DrawableId, MeshHighlightSets>,
		meshSelection: MeshSelection,
		previews: List<PuppetModel>,
		camera: ViewportCamera,
		size: IntSize,
	) {
		val reuse = HashMap<DrawableId, Pair<Drawable, FrameMeshGeometry>>()
		timed("D1 frameMeshGeometries (re-pose every moving mesh) [per rendered frame, UI thread]") { round ->
			frameMeshGeometries(previews[round % previews.size], liveGeometry, reuse)
		}
		val frameGeometry = frameMeshGeometries(previews.last(), liveGeometry, HashMap())
		val colors = defaultOverlayColors()
		val surface = Surface.makeRasterN32Premul(areaWidth, areaHeight)
		for (selectMode in listOf(MeshSelectMode.Vertex, MeshSelectMode.Edge)) {
			val modeHighlights =
				if (selectMode == meshSelection.selectMode) {
					highlights
				} else {
					editHighlights(meshSelection.copy(selectMode = selectMode), liveGeometry)
				}
			probeWireframe(selectMode, liveGeometry, modeHighlights, frameGeometry, colors, camera, size, surface)
		}
		surface.close()
		val rgba = ByteArray(areaWidth * areaHeight * 4)
		timed("F1 rgbaToImageBitmap ${areaWidth}x$areaHeight [per rendered frame, render thread]") {
			rgbaToImageBitmap(rgba, areaWidth, areaHeight, RgbaAlphaType.Opaque)
		}
	}

	/**
	 * Records the wireframe pass into a Skia picture and rasterizes it onto [surface], [rounds] times,
	 * printing the record and the raster as separate rows.
	 *
	 * @param MeshSelectMode selectMode The select mode drawn.
	 * @param List<EditMeshGeometry> liveGeometry The session meshes' live geometry.
	 * @param Map highlights The highlight sets for [selectMode].
	 * @param Map frameGeometry The displayed frame's geometry per mesh.
	 * @param ViewportOverlayColors colors The mesh gizmo palette.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @param Surface surface The CPU raster surface the picture plays back onto.
	 */
	private fun probeWireframe(
		selectMode: MeshSelectMode,
		liveGeometry: List<EditMeshGeometry>,
		highlights: Map<DrawableId, MeshHighlightSets>,
		frameGeometry: Map<DrawableId, FrameMeshGeometry>,
		colors: ViewportOverlayColors,
		camera: ViewportCamera,
		size: IntSize,
		surface: Surface,
	) {
		val recordTimes = ArrayList<Long>(rounds)
		val rasterTimes = ArrayList<Long>(rounds)
		val areaSize = Size(areaWidth.toFloat(), areaHeight.toFloat())
		repeat(rounds) {
			val recorder = PictureRecorder()
			val recordCanvas = recorder.beginRecording(Rect.makeWH(areaSize.width, areaSize.height))
			val recordStart = System.nanoTime()
			CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, recordCanvas.asComposeCanvas(), areaSize) {
				drawEditWireframes(liveGeometry, highlights, frameGeometry, selectMode, colors, camera, size)
			}
			val picture = recorder.finishRecordingAsPicture()
			val recordEnd = System.nanoTime()
			surface.canvas.clear(0)
			surface.canvas.drawPicture(picture)
			surface.flushAndSubmit()
			val rasterEnd = System.nanoTime()
			recordTimes.add(recordEnd - recordStart)
			rasterTimes.add(rasterEnd - recordEnd)
			picture.close()
			recorder.close()
		}
		stats("E1 drawEditWireframes RECORD ($selectMode mode) [per rendered frame, UI thread]", recordTimes)
		stats("E2 drawEditWireframes RASTER on CPU Skia ($selectMode mode) [per rendered frame]", rasterTimes)
	}

	/**
	 * Prints the rig's size: what the rows below are over.
	 *
	 * @param PuppetModel model The loaded model.
	 */
	private fun describeModel(model: PuppetModel) {
		val meshed = model.drawables.filter { drawable -> drawable.mesh != null }
		val warpIds = model.deformers.filterIsInstance<Deformer.Warp>().map { deformer -> deformer.id }.toSet()
		val rotationIds = model.deformers.filterIsInstance<Deformer.Rotation>().map { deformer -> deformer.id }.toSet()
		report(
			"model: drawables=${model.drawables.size} meshed=${meshed.size} " +
				"vertices=${meshed.sumOf { drawable -> drawable.mesh!!.positions.size / 2 }} " +
				"triangles=${meshed.sumOf { drawable -> drawable.mesh!!.indices.size / 3 }} " +
				"deformers=${model.deformers.size} warpParented=${meshed.count { drawable -> drawable.parentDeformerId in warpIds }} " +
				"rotationParented=${meshed.count { drawable -> drawable.parentDeformerId in rotationIds }} " +
				"unparented=${meshed.count { drawable -> drawable.parentDeformerId == null }} " +
				"keyed=${meshed.count { drawable -> drawable.geometryGrid != null }}",
		)
	}

	/**
	 * A camera that fits every rest mesh into the area, in the renderer's world frame (model y negated),
	 * so the wireframe rows draw the whole rig on screen rather than clipping most of it.
	 *
	 * @param PuppetModel model The loaded model.
	 * @return ViewportCamera The fitted camera.
	 */
	private fun fitCamera(model: PuppetModel): ViewportCamera {
		var minX = Float.MAX_VALUE
		var minWorldY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxWorldY = -Float.MAX_VALUE
		for (drawable in model.drawables) {
			val positions = drawable.mesh?.positions ?: continue
			var slot = 0
			while (slot + 1 < positions.size) {
				val worldY = -positions[slot + 1]
				minX = minOf(minX, positions[slot])
				maxX = maxOf(maxX, positions[slot])
				minWorldY = minOf(minWorldY, worldY)
				maxWorldY = maxOf(maxWorldY, worldY)
				slot += 2
			}
		}
		return ViewportCamera.fit(ContentBounds(minX, minWorldY, maxX - minX, maxWorldY - minWorldY), areaWidth, areaHeight)
	}

	/**
	 * The mesh gizmo palette at its settings defaults.
	 *
	 * @return ViewportOverlayColors The palette.
	 */
	private fun defaultOverlayColors(): ViewportOverlayColors =
		ViewportOverlayColors(
			vertexIdle = defaultColor(ViewportColorSettings.VERTEX_IDLE_DEFAULT),
			vertexSelected = defaultColor(ViewportColorSettings.VERTEX_SELECTED_DEFAULT),
			vertexActive = defaultColor(ViewportColorSettings.VERTEX_ACTIVE_DEFAULT),
			vertexOffKey = defaultColor(ViewportColorSettings.VERTEX_OFFKEY_DEFAULT),
			edgeIdle = defaultColor(ViewportColorSettings.EDGE_IDLE_DEFAULT),
			edgeSelected = defaultColor(ViewportColorSettings.EDGE_SELECTED_DEFAULT),
			edgeActive = defaultColor(ViewportColorSettings.EDGE_ACTIVE_DEFAULT),
			edgeOffKey = defaultColor(ViewportColorSettings.EDGE_OFFKEY_DEFAULT),
			faceIdle = defaultColor(ViewportColorSettings.FACE_IDLE_DEFAULT),
			faceSelected = defaultColor(ViewportColorSettings.FACE_SELECTED_DEFAULT),
			faceActive = defaultColor(ViewportColorSettings.FACE_ACTIVE_DEFAULT),
			faceOffKey = defaultColor(ViewportColorSettings.FACE_OFFKEY_DEFAULT),
			warning = defaultColor(ViewportColorSettings.WARNING_COLOR_DEFAULT),
			pinnedPlacement = defaultColor(ViewportColorSettings.PINNED_PLACEMENT_COLOR_DEFAULT),
			selectionHighlight = defaultColor(ViewportColorSettings.SELECTION_HIGHLIGHT_DEFAULT),
			activeSelectionHighlight = defaultColor(ViewportColorSettings.ACTIVE_SELECTION_HIGHLIGHT_DEFAULT),
		)

	/**
	 * One settings default parsed to a color; the defaults are literals, so a parse failure is a bug.
	 *
	 * @param String hex The default's hex text.
	 * @return Color The color.
	 */
	private fun defaultColor(hex: String): Color = checkNotNull(parseHexColor(hex)) { "unparseable default color $hex" }

	/**
	 * The virtual pointer for one drive round: a different landing per round, so no drive is a no-op.
	 *
	 * @param Int round The round ordinal.
	 * @return Offset The pointer in area pixels.
	 */
	private fun pointerFor(round: Int): Offset = Offset(12f * (round + 1), 7f * (round + 1))

	/**
	 * Runs [block] [count] times, each timed, prints the row, and returns the last result.
	 *
	 * @param String label The row label.
	 * @param Int count How many timed runs; at least one.
	 * @param Function block The work, handed the round ordinal.
	 * @return TResult The last run's result.
	 */
	private fun <TResult> timed(label: String, count: Int = rounds, block: (Int) -> TResult): TResult {
		val times = ArrayList<Long>(count)
		var start = System.nanoTime()
		var last = block(0)
		times.add(System.nanoTime() - start)
		for (round in 1 until count) {
			start = System.nanoTime()
			last = block(round)
			times.add(System.nanoTime() - start)
		}
		stats(label, times)
		return last
	}

	/**
	 * Prints one row: the label with the min, median, and max over [nanos]; an empty list prints n=0.
	 *
	 * @param String label The row label.
	 * @param List<Long> nanos The timed runs in nanoseconds.
	 */
	private fun stats(label: String, nanos: List<Long>) {
		if (nanos.isEmpty()) {
			report("%-${LABEL_WIDTH}s (n=0)".format(label))
			return
		}
		val sorted = nanos.sorted()
		report(
			"%-${LABEL_WIDTH}s min %8.1f ms  median %8.1f ms  max %8.1f ms  (n=%d)".format(
				label,
				sorted.first() / 1e6,
				sorted[sorted.size / 2] / 1e6,
				sorted.last() / 1e6,
				sorted.size,
			),
		)
	}

	/**
	 * Milliseconds elapsed since a nanoTime stamp, formatted for a row.
	 *
	 * @param Long startNanos The stamp.
	 * @return String The elapsed milliseconds with one decimal.
	 */
	private fun millisSince(startNanos: Long): String = "%.1f".format((System.nanoTime() - startNanos) / 1e6)

	/**
	 * Prints one tagged line.
	 *
	 * @param String line The line.
	 */
	private fun report(line: String) {
		println("$PROBE_TAG $line")
	}
}