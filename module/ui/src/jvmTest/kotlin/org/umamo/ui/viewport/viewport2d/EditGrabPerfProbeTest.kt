package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelection
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.withMeshPositions
import org.umamo.edit.withMeshUvs
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.ContentBounds
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasKeyByDrawable
import org.umamo.runtime.model.drawableNameByDrawable
import org.umamo.runtime.model.partNameByDrawable
import org.umamo.runtime.model.pickableIndicesByDrawable
import org.umamo.runtime.model.pickableUvsByDrawable
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.rgbaToImageBitmap
import org.umamo.ui.model.thumbnails.DrawableThumbnailer
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.viewport.UvSceneContent
import org.umamo.ui.viewport.gizmo.EditMeshOverlayProducer
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.applyOperator
import org.umamo.ui.viewport.gizmo.gestureParameters
import org.umamo.ui.viewport.uv.UvEditOverlayProducer
import org.umamo.ui.viewport.uv.UvShownScene
import org.umamo.ui.workspace.spaces.uv.UvGizmoGeometryCache
import org.umamo.ui.workspace.spaces.uv.shownSurfaceDrawables
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
 * Grab does - the per-commit geometry capture, the mesh overlay's derive (cold on Edit entry, warm when
 * a commit moved positions only, and with one mesh's selection changed), the latch, the per-pointer-event
 * drive and its halves, the per-push picker rebuild, and the frame image conversion - plus the
 * Object-mode latch and drive over the same rig.  The overlay's draw is the renderer's, measured by the
 * render-side probe.  Pins nothing - see
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
	 * The UV Edit wireframe's derive over every meshed drawable shown on one 4096-texel surface, the most a
	 * UV area can show (modelF's own pages split it): the area's geometry cache and the overlay producer,
	 * each cold, warm, with one mesh moved (a one-mesh UV Grab's drive), and with every mesh moved (a
	 * whole-selection UV Grab's drive).  The moved rows alternate between two models, so every round
	 * re-derives what moved.
	 */
	@Test
	fun probeUvSceneDerive() {
		val mocFile = sample
		if (mocFile == null) {
			println("moc3.perfSample not present; skipping perf probe")
			return
		}
		val model = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.read(mocFile.readBytes()), null))
		val session = EditorSession(model)
		session.selectAllObjects()
		session.setMode(EditorMode.Edit)
		if (session.mode.value != EditorMode.Edit) {
			report("could not enter Edit mode; skipping the UV rows")
			return
		}
		session.selectAllMeshElements()
		val selection = session.meshSelection.value
		val shown = shownSurfaceDrawables(model, EditorMode.Edit, selection) { true }
		val side = 4096
		report("UV surface: ${side}x$side, shown meshes=${shown.size}, vertices=${shown.sumOf { drawable -> drawable.mesh!!.uvs.size / 2 }}")
		val firstId = shown.first().id
		val movedOne = model.withMeshUvs(firstId, shiftedUvs(shown.first().mesh!!.uvs))
		val movedAll = shown.fold(model) { moving, drawable -> moving.withMeshUvs(drawable.id, shiftedUvs(drawable.mesh!!.uvs)) }

		/**
		 * The area's geometry through [cache] over [source].
		 *
		 * @param UvGizmoGeometryCache cache The cache.
		 * @param PuppetModel source The model.
		 * @return List<GizmoMeshGeometry> The geometry.
		 */
		fun geometriesOf(cache: UvGizmoGeometryCache, source: PuppetModel): List<GizmoMeshGeometry> {
			val drawables = shownSurfaceDrawables(source, EditorMode.Edit, selection) { true }
			return cache.geometries(drawables, cache.surfaceUvs(drawables, source, null), side, side)
		}

		timed("U1 UV geometry cache, cold (every mesh's edges and display positions) [on a surface switch]", 3) { geometriesOf(UvGizmoGeometryCache(), model) }
		val cache = UvGizmoGeometryCache()
		val geometries = geometriesOf(cache, model)
		val warm = timed("U1w UV geometry cache, warm (nothing moved) [per UV area recomposition]") { geometriesOf(cache, model) }
		report("U1w kept every geometry: ${warm.indices.all { geometryIndex -> warm[geometryIndex] === geometries[geometryIndex] }}")
		timed("U1o UV geometry cache, one mesh moved [per drive, one-mesh UV Grab]") { round -> geometriesOf(cache, if (round % 2 == 0) movedOne else model) }
		timed("U1a UV geometry cache, every mesh moved [per drive, whole-selection UV Grab]") { round -> geometriesOf(cache, if (round % 2 == 0) movedAll else model) }
		val oneGeometries = geometriesOf(UvGizmoGeometryCache(), movedOne)
		val allGeometries = geometriesOf(UvGizmoGeometryCache(), movedAll)

		val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)
		val content = UvSceneContent.AtlasPage(0)
		timed("U2 UV overlay derive, cold (every mesh's edges and flags) [on Edit entry]", 3) {
			UvEditOverlayProducer().produce(EditorMode.Edit, selection, UvShownScene(content, null, model, geometries), sizes)
		}
		val producer = UvEditOverlayProducer()
		val first = producer.produce(EditorMode.Edit, selection, UvShownScene(content, null, model, geometries), sizes)
		val again = timed("U2w UV overlay derive, warm (same geometry) [per positions-only commit]") { producer.produce(EditorMode.Edit, selection, UvShownScene(content, null, model, geometries), sizes) }
		report("U2w handed back the same instance: ${again === first}")
		timed("U2o UV overlay derive, one mesh moved [per drive, one-mesh UV Grab]") { round ->
			producer.produce(EditorMode.Edit, selection, UvShownScene(content, null, model, if (round % 2 == 0) oneGeometries else geometries), sizes)
		}
		timed("U2a UV overlay derive, every mesh moved [per drive, whole-selection UV Grab]") { round ->
			producer.produce(EditorMode.Edit, selection, UvShownScene(content, null, model, if (round % 2 == 0) allGeometries else geometries), sizes)
		}
	}

	/**
	 * Texture coordinates moved a quarter texel of a 4096 surface right and up, in a new array.
	 *
	 * @param FloatArray uvs The coordinates.
	 * @return FloatArray The moved copy.
	 */
	private fun shiftedUvs(uvs: FloatArray): FloatArray = FloatArray(uvs.size) { componentIndex -> uvs[componentIndex] + if (componentIndex % 2 == 0) 1f / 16384 else -1f / 16384 }

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
		probeOverlayDerive(model, meshSelection)

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
		probeFrameImage()
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
	 * The mesh overlay's derive over the whole selection: a fresh producer (every mesh's edges and flags,
	 * as on Edit entry), the same inputs again (a commit that moved positions only, which must hand back
	 * the same instance), and one mesh's selection changing back and forth (a click or a brush stamp).
	 *
	 * @param PuppetModel model The committed model.
	 * @param MeshSelection meshSelection The whole-selection mesh selection.
	 */
	private fun probeOverlayDerive(model: PuppetModel, meshSelection: MeshSelection) {
		val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)
		timed("A2 mesh overlay derive, cold (every mesh's edges and flags) [on Edit entry]", 3) {
			EditMeshOverlayProducer().produce(EditorMode.Edit, meshSelection, model, sizes)
		}
		val producer = EditMeshOverlayProducer()
		val first = producer.produce(EditorMode.Edit, meshSelection, model, sizes)
		val warm = timed("A2w mesh overlay derive, warm (nothing it shows changed) [per positions-only commit]") { producer.produce(EditorMode.Edit, meshSelection, model, sizes) }
		report("A2w handed back the same instance: ${warm === first}")
		val firstId = meshSelection.drawableIds.first()
		val trimmed = meshSelection.copy(elementsByDrawable = meshSelection.elementsByDrawable + (firstId to meshSelection.elementsOf(firstId).drop(1).toSet()))
		timed("A2s mesh overlay derive, one mesh's selection changed [per click or brush stamp]") { round ->
			producer.produce(EditorMode.Edit, if (round % 2 == 0) trimmed else meshSelection, model, sizes)
		}
	}

	/** The frame image conversion every rendered frame pays on the render thread. */
	private fun probeFrameImage() {
		val rgba = ByteArray(areaWidth * areaHeight * 4)
		timed("F1 rgbaToImageBitmap ${areaWidth}x$areaHeight [per rendered frame, render thread]") {
			rgbaToImageBitmap(rgba, areaWidth, areaHeight, RgbaAlphaType.Opaque)
		}
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