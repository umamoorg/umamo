package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.PuppetModel
import kotlin.math.roundToInt

/**
 * Converts a puppet with NO retained CMO3 graph (a MOC3-origin document) into a fresh Cmo3Model:
 * the blank skeleton plus the per-page image chain are synthesized, serialized, and read back
 * through the normal codec, then the ordinary reconcile lowers the whole puppet onto the empty
 * baseline as created entities.  Stateless - each call builds a new graph; the caller writes the
 * result with Cmo3.write.
 *
 * KNOWN GAP (2026-08-03): the file this produces LOADS cleanly in the official Cubism Editor -
 * correct part/deformer hierarchy, parameters, and texture atlas - but the puppet does NOT render
 * there.  It is a documented functionality gap rather than an open defect, because a .moc3 does
 * not carry the SOURCE ART a CMO3 is built around: [Cmo3ImageChainBuilder] can only fabricate a
 * source document by slicing the packed atlas back apart, and that reconstruction is what the
 * art-sourcing pipeline replaces (docs/plan/art-sourcing-pipeline.md Phase H - an imported MOC3
 * will reconcile its original PSD/CLIP/KRA before it can emit a valid CMO3).  CMO3.md section
 * Fresh-Graph Synthesis records what differential testing against Cubism's own golden file and a
 * third-party converter already RULED OUT (geometry, coordinate-frame sign, element shape, null
 * coverage, and the source-art web itself), so start there rather than re-deriving it.
 *
 * The CMO3-origin path ([Cmo3Export.apply] onto a retained graph) is unaffected - it keeps the
 * document's real layered art, and its byte-identity gates hold.
 */
public object Cmo3Conversion {
	/** One atlas page: the original PNG bytes (model3 texture order) plus its pixel dimensions. */
	public class AtlasPage(
		public val pngBytes: ByteArray,
		public val width: Int,
		public val height: Int,
	)

	/** The conversion outcome: the fresh model plus the reconcile's advisory report. */
	public class Result(
		public val model: Cmo3Model,
		public val report: ExportReport,
	)

	/**
	 * Builds a fresh CMO3 for [puppet].
	 *
	 * @param PuppetModel puppet    The model to convert (the session's current state).
	 * @param List        pages     The atlas pages, in model3 texture order.
	 * @param Map         pageIndexByDrawableId Each drawable id's atlas page index; a drawable
	 *                              missing here cannot bind a texture and surfaces as a notice.
	 * @param String      modelName The document display name the skeleton records.
	 * @param Long        nowMillis The wall-clock import timestamp the image chain records.
	 * @param Int         obfuscateKey The container XOR key; the editor mints one per save.
	 * @return Result The fresh model plus the reconcile report.
	 */
	public fun freshCmo3(
		puppet: PuppetModel,
		pages: List<AtlasPage>,
		pageIndexByDrawableId: Map<String, Int>,
		modelName: String,
		nowMillis: Long,
		obfuscateKey: Int,
	): Result {
		val rebased = rebaseToArtPlacement(puppet, pages, pageIndexByDrawableId)
		val skeleton =
			Cmo3SkeletonBuilder.buildBlank(
				modelName = modelName,
				canvasWidth = puppet.canvasWidth.roundToInt(),
				canvasHeight = puppet.canvasHeight.roundToInt(),
				targetVersionNo = puppet.runtimeTarget.cmo3TargetVersionNo(),
			)
		// Each page's drawable regions feed the per-drawable patch webs (crop + placement fit), over
		// the ART-ALIGNED base [rebaseToArtPlacement] just produced.
		val regionsByPage = List(pages.size) { ArrayList<Cmo3ImageChainBuilder.DrawableRegion>() }
		for (drawable in rebased.drawables) {
			val pageIndex = pageIndexByDrawableId[drawable.id.raw] ?: continue
			val mesh = drawable.mesh ?: continue
			regionsByPage.getOrNull(pageIndex)?.add(
				Cmo3ImageChainBuilder.DrawableRegion(drawable.id.raw, mesh.uvs, mesh.positions, mesh.indices),
			)
		}
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				pages.map { page -> Cmo3ImageChainBuilder.AtlasPage(page.pngBytes, page.width, page.height) },
				regionsByPage,
				nowMillis,
			)
		val model =
			Cmo3.read(
				Cmo3FreshFile.assemble(
					skeleton.root,
					skeleton.iconEntries.map { icon ->
						Cmo3FreshFile.PngEntry(
							icon.path,
							icon.pngBytes,
						)
					} + chain.pngEntries,
					obfuscateKey,
				),
			)
		val bindings = HashMap<String, Cmo3DrawableTextureBinding>()
		for (drawable in rebased.drawables) {
			val pageIndex = pageIndexByDrawableId[drawable.id.raw] ?: continue
			val binding =
				chain.bindingByDrawableId[drawable.id.raw] ?: chain.pageFallbackBindings.getOrNull(pageIndex)
			binding?.let { resolved -> bindings[drawable.id.raw] = resolved }
		}
		val report = Cmo3Export.apply(rebased, model, bindings)
		// Every fresh-graph export is by definition source-art-less - the stand-in document above is
		// sliced out of the atlas - so the notice leads the report rather than hiding behind the
		// per-entity findings.  It is the one finding that explains why the file will not render in
		// the official editor, which no amount of per-drawable detail would tell the user.
		return Result(model, report.copy(notices = listOf(ExportNotice.MissingSourceArt(pages.size)) + report.notices))
	}

	/**
	 * Re-bases every keyed drawable's mesh onto its ART placement: the atlas patch's own pixel grid,
	 * translated to sit where the drawable already rests.
	 *
	 * A base mesh is a FREE CHOICE in this export.  Geometry only ever reaches a renderer as
	 * `base + delta` and a keyform grid's weights sum to one, so shifting the base by some per-vertex
	 * D and every stored delta by -D leaves every rendered pose bit-identical.  What the choice DOES
	 * change is the texture web, which is derived from the base directly and so cancels nothing.
	 *
	 * The incoming base is the rest POSE - the app's MOC3 loader evaluates the deformer chain at
	 * default parameters through :render's restMeshesToCanvasSpace - which is where the drawable
	 * comes to rest, not where its art sits.  Fitting a texture placement against that measures
	 * packing composed with rest deformation and blames the packer for all of it: on EricaTamamo,
	 * whose atlas the modeller packed entirely upright, the fit reports 48 rotations up to a full 180
	 * degrees and 239 px of residual.  Against the CMO3's own untouched base the same fit is exact -
	 * zero rotation, unit scale, 0.00 px over all 177 drawables - because a Cubism base mesh IS the
	 * art, related to the atlas by pure translation.
	 *
	 * So rather than trying to recover that placement by undoing the deformers, this DEFINES it:
	 * `position = uv x pageSize + t`, with t carrying the patch's centroid onto the drawable's
	 * current one so the art lands where the puppet already is.  Every downstream transform then
	 * falls out exactly - the placement fit returns a pure translation, the packing is position-only,
	 * and the crop resamples through an identity linear part, so it stays a lossless copy.
	 *
	 * UNKEYED drawables are left alone.  With no grid there is no delta to absorb the shift, so their
	 * base IS their geometry and re-basing would move them on screen; they keep the fitted placement.
	 *
	 * @param PuppetModel puppet The model to re-base.
	 * @param List        pages  The atlas pages, in model3 texture order.
	 * @param Map         pageIndexByDrawableId Each drawable id's atlas page index.
	 * @return PuppetModel The model with art-aligned drawable bases and compensated deltas.
	 */
	private fun rebaseToArtPlacement(
		puppet: PuppetModel,
		pages: List<AtlasPage>,
		pageIndexByDrawableId: Map<String, Int>,
	): PuppetModel {
		val drawables =
			puppet.drawables.map { drawable ->
				val mesh = drawable.mesh ?: return@map drawable
				val grid = drawable.geometryGrid ?: return@map drawable
				val pageIndex = pageIndexByDrawableId[drawable.id.raw] ?: return@map drawable
				val page = pages.getOrNull(pageIndex) ?: return@map drawable
				val vertexCount = minOf(mesh.uvs.size, mesh.positions.size) / 2
				if (vertexCount == 0) {
					return@map drawable
				}
				// t: the translation carrying the patch's centroid onto the drawable's current one.
				var sumOffsetX = 0.0
				var sumOffsetY = 0.0
				for (vertexIndex in 0 until vertexCount) {
					sumOffsetX += mesh.positions[2 * vertexIndex] - mesh.uvs[2 * vertexIndex].toDouble() * page.width
					sumOffsetY += mesh.positions[2 * vertexIndex + 1] - mesh.uvs[2 * vertexIndex + 1].toDouble() * page.height
				}
				val offsetX = sumOffsetX / vertexCount
				val offsetY = sumOffsetY / vertexCount
				val artPositions = mesh.positions.copyOf()
				val shift = FloatArray(mesh.positions.size)
				for (vertexIndex in 0 until vertexCount) {
					val artX = (mesh.uvs[2 * vertexIndex].toDouble() * page.width + offsetX).toFloat()
					val artY = (mesh.uvs[2 * vertexIndex + 1].toDouble() * page.height + offsetY).toFloat()
					shift[2 * vertexIndex] = artX - mesh.positions[2 * vertexIndex]
					shift[2 * vertexIndex + 1] = artY - mesh.positions[2 * vertexIndex + 1]
					artPositions[2 * vertexIndex] = artX
					artPositions[2 * vertexIndex + 1] = artY
				}
				drawable.copy(
					mesh = DrawableMesh(positions = artPositions, uvs = mesh.uvs, indices = mesh.indices),
					geometryGrid =
						KeyformGrid(
							grid.axes,
							grid.cells.map { cell ->
								KeyformCell(
									cell.coordinate,
									MeshDeltaForm(
										FloatArray(cell.form.positionDeltas.size) { component ->
											cell.form.positionDeltas[component] - shift.getOrElse(component) { 0f }
										},
									),
								)
							},
						),
				)
			}
		return puppet.copy(drawables = drawables)
	}
}
