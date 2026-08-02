package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.interop.Cmo3ExportReport
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.runtime.model.PuppetModel
import kotlin.math.roundToInt

/**
 * Converts a puppet with NO retained CMO3 graph (a MOC3-origin document) into a fresh Cmo3Model:
 * the blank skeleton plus the per-page image chain are synthesized, serialized, and read back
 * through the normal codec, then the ordinary reconcile lowers the whole puppet onto the empty
 * baseline as created entities.  Stateless - each call builds a new graph; the caller writes the
 * result with Cmo3.write.
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
		public val report: Cmo3ExportReport,
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
		val skeleton =
			Cmo3SkeletonBuilder.buildBlank(
				modelName = modelName,
				canvasWidth = puppet.canvasWidth.roundToInt(),
				canvasHeight = puppet.canvasHeight.roundToInt(),
				targetVersionNo = puppet.runtimeTarget.cmo3TargetVersionNo(),
			)
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				pages.map { page -> Cmo3ImageChainBuilder.AtlasPage(page.pngBytes, page.width, page.height) },
				nowMillis,
			)
		val model =
			Cmo3.read(
				Cmo3FreshFile.assemble(
					skeleton.root,
					skeleton.iconEntries.map { icon -> Cmo3FreshFile.PngEntry(icon.path, icon.pngBytes) } + chain.pngEntries,
					obfuscateKey,
				),
			)
		val bindings = HashMap<String, Cmo3DrawableTextureBinding>()
		for (drawable in puppet.drawables) {
			val pageIndex = pageIndexByDrawableId[drawable.id.raw] ?: continue
			chain.pageBindings.getOrNull(pageIndex)?.let { binding -> bindings[drawable.id.raw] = binding }
		}
		val report = Cmo3Export.apply(puppet, model, bindings)
		return Result(model, report)
	}
}
