package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3TargetVersionNo
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

	/**
	 * The conversion outcome: the fresh model, the reconcile's advisory report, and the puppet
	 * actually encoded - the input after the atlas un-dedup prepass, whose uvs differ from the
	 * caller's wherever a baked twin was routed to its own synthesized slot.  Round-trip checks
	 * must compare a re-import against THIS puppet, not the caller's.
	 */
	public class Result(
		public val model: Cmo3Model,
		public val report: ExportReport,
		public val puppet: PuppetModel,
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
		// Baked twins (one atlas slot, several canvas placements) are unrepresentable in the
		// model-image web; the prepass copies each additional placement's patch onto a synthesized
		// page and remaps those drawables' uvs there.  Everything below runs on ITS outputs.
		val undedup = Cmo3AtlasUndedup.undeduplicate(puppet, pages, pageIndexByDrawableId)
		val effectivePuppet = undedup.puppet
		val effectivePages = undedup.pages
		val effectivePageIndexByDrawableId = undedup.pageIndexByDrawableId
		val skeleton =
			Cmo3SkeletonBuilder.buildBlank(
				modelName = modelName,
				canvasWidth = effectivePuppet.canvasWidth.roundToInt(),
				canvasHeight = effectivePuppet.canvasHeight.roundToInt(),
				targetVersionNo = effectivePuppet.runtimeTarget.cmo3TargetVersionNo(),
			)
		// Each page's drawable regions feed the per-drawable patch webs (crop + placement fit).
		// The puppet's mesh.positions MUST be canvas-frame here: the app's MOC3 document loader
		// normalizes parent-local rest meshes through :render's restMeshesToCanvasSpace before any
		// export, and callers converting a raw Moc3Import puppet must do the same (the official
		// source-level positions and the whole placement web are canvas geometry).
		val regionsByPage = List(effectivePages.size) { ArrayList<Cmo3ImageChainBuilder.DrawableRegion>() }
		for (drawable in effectivePuppet.drawables) {
			val pageIndex = effectivePageIndexByDrawableId[drawable.id.raw] ?: continue
			val mesh = drawable.mesh ?: continue
			regionsByPage.getOrNull(pageIndex)?.add(
				Cmo3ImageChainBuilder.DrawableRegion(drawable.id.raw, mesh.uvs, mesh.positions, mesh.indices),
			)
		}
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				effectivePages.map { page -> Cmo3ImageChainBuilder.AtlasPage(page.pngBytes, page.width, page.height) },
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
		for (drawable in effectivePuppet.drawables) {
			val pageIndex = effectivePageIndexByDrawableId[drawable.id.raw] ?: continue
			val binding =
				chain.bindingByDrawableId[drawable.id.raw] ?: chain.pageFallbackBindings.getOrNull(pageIndex)
			binding?.let { resolved -> bindings[drawable.id.raw] = resolved }
		}
		val report = Cmo3Export.apply(effectivePuppet, model, bindings)
		// Every fresh-graph export is by definition source-art-less - the stand-in document above is
		// sliced out of the atlas - so the notice leads the report rather than hiding behind the
		// per-entity findings.  It is the one finding that explains why the file will not render in
		// the official editor, which no amount of per-drawable detail would tell the user.
		return Result(
			model,
			report.copy(notices = listOf(ExportNotice.MissingSourceArt(effectivePages.size)) + report.notices),
			effectivePuppet,
		)
	}
}