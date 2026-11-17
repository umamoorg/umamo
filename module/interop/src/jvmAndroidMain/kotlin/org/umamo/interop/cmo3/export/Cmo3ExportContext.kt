package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3TextureFrames
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * What one CMO3 export reconciles, the same from its first pass to its last: the two models, the
 * graph the edited one is lowered onto, and the caller's inputs.
 *
 * What changes while the export runs travels beside the context instead.  The graph index is rebuilt
 * after every pass that adds or removes sources, so each pass is handed its own, and the notice sink
 * fills as the passes go.
 *
 * @property Cmo3Model         target        The model whose graph and archive receive the edits.
 * @property CModelSource      modelSource   The graph's root.
 * @property Cmo3GraphEditor   editor        The slot bookkeeping a write to a read object goes through.
 * @property PuppetModel       baseline      The graph as imported before any pass wrote to it.
 * @property PuppetModel       edited        The session's model, the state the graph is reconciled to.
 * @property Cmo3TextureFrames textureFrames The document's texture frames, taken from the graph before
 *                                           the export changed it.
 * @property Function          tileRasters   The document's own pixels for a tile, or null when it holds
 *                                           none.
 * @property Long              nowMillis     The timestamp a layer minted into the graph records.
 */
internal class Cmo3ExportContext(
	val target: Cmo3Model,
	val modelSource: CModelSource,
	val editor: Cmo3GraphEditor,
	val baseline: PuppetModel,
	val edited: PuppetModel,
	val textureFrames: Cmo3TextureFrames,
	val tileRasters: (AtlasTileId) -> RasterImage?,
	val nowMillis: Long,
) {
	/** The edited model's parameters by id. */
	val editedParameterById: Map<ParameterId, Parameter> by lazy { edited.parameters.associateBy(Parameter::id) }

	/** The edited model's parts by id. */
	val editedPartById: Map<PartId, Part> by lazy { edited.parts.associateBy(Part::id) }

	/** The edited model's deformers by id. */
	val editedDeformerById: Map<DeformerId, Deformer> by lazy { edited.deformers.associateBy(Deformer::id) }

	/** The edited model's drawables by id. */
	val editedDrawableById: Map<DrawableId, Drawable> by lazy { edited.drawables.associateBy(Drawable::id) }

	/** The baseline's parts by id. */
	val baselinePartById: Map<PartId, Part> by lazy { baseline.parts.associateBy(Part::id) }

	/** The baseline's deformers by id. */
	val baselineDeformerById: Map<DeformerId, Deformer> by lazy { baseline.deformers.associateBy(Deformer::id) }

	/** The baseline's drawables by id. */
	val baselineDrawableById: Map<DrawableId, Drawable> by lazy { baseline.drawables.associateBy(Drawable::id) }
}