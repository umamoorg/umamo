package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.Cmo3TargetVersion
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CImageCanvas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.interop.cmo3.Cmo3TextureFrames
import org.umamo.interop.runtimeTargetOfCmo3Target
import org.umamo.runtime.keyform.withChannelsCompacted
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.canvasCenterWorldOriginX
import org.umamo.runtime.model.canvasCenterWorldOriginZ
import org.umamo.runtime.model.deriveRenderRoot

/**
 * Maps a parsed CMO3 model graph (`:format`) into the concrete [PuppetModel] (`:runtime`).
 *
 * This is the boundary that keeps CMO3's serialization quirks out of the deformation core.  The
 * raw graph is loosely typed (object fields are `Any?` holding already-resolved instances) and its
 * structure is encoded as GUID strings (`targetDeformerGuid`, `parentGuid`, `clipGuidList`), not
 * object links.  So mapping is two passes: (1) index every source by its GUID uuid → runtime id;
 * (2) build the runtime entities, resolving each cross-reference through those indices.
 *
 * This object is only the orchestrator.  The first pass is [Cmo3ImportContext]; the second sits in
 * per-concern producers beside it - [importParameters], [importParameterLinks], [importParameterTree],
 * [importParts], [importDeformers], [importDrawables], [importGlues], and [importRootChildren] - each
 * reading that one immutable context.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Model object graph</a>
 */
object Cmo3Import {
	/**
	 * Builds a [PuppetModel] from a CMO3 `CModelSource` root (e.g. from `Cmo3.read(file).root`).
	 *
	 * @param CModelSource modelSource The parsed model root.
	 * @param Boolean compactChannels Whether to run the post-import channel compaction (on by default).
	 *   Off imports the fanned-out tracks verbatim, which is the reference an equivalence test diffs
	 *   against and the switch to flip when bisecting a suspected compaction fault.
	 * @return PuppetModel The concrete runtime puppet.
	 */
	fun fromModelSource(modelSource: CModelSource, compactChannels: Boolean = true): PuppetModel =
		importModelSource(modelSource, compactChannels).puppet

	/**
	 * Builds a [PuppetModel] from a CMO3 `CModelSource` root together with the atlas ingest the model was
	 * read from, for a caller that also needs the ingest's per-tile pixel resources (the document loader's
	 * byte supplier).  One walk of the layered-art web serves both, so the model's tile ids and the
	 * supplier's keys cannot come from two runs that disagree.
	 *
	 * @param CModelSource modelSource The parsed model root.
	 * @param Boolean compactChannels Whether to run the post-import channel compaction; see [fromModelSource].
	 * @return Cmo3ImportedDocument The puppet plus the atlas ingest it carries.
	 */
	fun importModelSource(modelSource: CModelSource, compactChannels: Boolean = true): Cmo3ImportedDocument {
		// Pass 1 - GUID uuid → runtime id, per category: the context's indexes.
		val context = Cmo3ImportContext(modelSource)

		// Pass 2 - build runtime entities, resolving references through the indexes above.
		val parameters = importParameters(context)
		val parameterLinks = importParameterLinks(context)
		val parameterTree = importParameterTree(context)
		val parts = importParts(context)
		val deformers = importDeformers(context)

		// The layered-art web, read once as model state: the atlas pages, the tiles, and which tile each
		// drawable samples.  Metadata only - no pixel is decoded here.
		val atlasIngest = cmo3AtlasIngest(modelSource)
		// Which image each drawable is shown from and which frame its stored coordinates are in.
		val textureFrames = Cmo3TextureFrames(modelSource)
		val drawables = importDrawables(context, parameters, atlasIngest, textureFrames)
		val glues = importGlues(context)
		val rootChildren = importRootChildren(context, parts, drawables)

		// CMO3: CModelSource.canvas -> CImageCanvas fields pixelWidth / pixelHeight - the document canvas
		// size.  A drawable's canvas mesh (DrawableMesh.positions) lives in this canvas px space (Y down, the
		// canvas rect spanning [0, width] x [0, height]; art may overhang it); the deform eval NEGATES Y into
		// world space.
		// A model without a canvas keeps the (0, 0) defaults.
		val canvas = modelSource.canvas as? CImageCanvas
		val canvasWidth = (canvas?.pixelWidth ?: 0).toFloat()
		val canvasHeight = (canvas?.pixelHeight ?: 0).toFloat()

		// The world origin is the CANVAS CENTER - the same origin a MOC3 export computes for CanvasInfo
		// (MOC3 §5.3 originX/originY = canvasWidth/2, canvasHeight/2 across the whole corpus).  It is NOT
		// CModelSource.modelInfo's CModelInfo.originInPixels: that CPoint sits at its (0, 0) default in
		// every corpus model, so reading it collapses both axis lines onto the canvas corner.  The center
		// is canvas-space, so its y negates into world space like every vertex (a positive canvas y stored
		// raw would float a full canvas above the art - the "X axis above the head" bug).  The viewport
		// axes cross here and snap.cursorToWorldOrigin lands here.  canvasCenterWorldOriginX / Z are the one
		// definition of that center, shared with the CMO3 export's check for an origin it cannot store.

		// Draw order is a pure derivation of the org tree + the parts' draw-order group flags / values, so
		// the renderer's render-order tree is computed, never a parallel source of truth that can drift.
		val model =
			PuppetModel(
				parameters = parameters,
				parts = parts,
				deformers = deformers,
				drawables = drawables,
				rootChildren = rootChildren,
				rootPartId = importRootPartId(context),
				glues = glues,
				parameterLinks = parameterLinks,
				parameterTree = parameterTree,
				canvasWidth = canvasWidth,
				canvasHeight = canvasHeight,
				worldOriginX = canvasCenterWorldOriginX(canvasWidth),
				worldOriginZ = canvasCenterWorldOriginZ(canvasHeight),
				// CMO3: CModelSource field targetVersionNo - the authored SDK target.  The SDK(N/A)/Latest
				// sentinel, unknown values, and an absent field all map to NoTarget, so nothing is
				// restricted.
				runtimeTarget = runtimeTargetOfCmo3Target(Cmo3TargetVersion.fromVersionNo(modelSource.targetVersionNo as? Int)),
				// CMO3: CTextureManager field isTextureInputModelImageMode - true displays the combined
				// layer images, false the packed atlas.  The texture manager is mandatory even when empty
				// (docs/format/CMO3.md §3), so an absent one falls back to the atlas default.
				rendersFromSourceLayers = (modelSource.textureManager as? CTextureManager)?.isTextureInputModelImageMode ?: false,
				atlas = atlasIngest.atlas,
				sources = atlasIngest.sources,
			)
		val withRenderRoot = model.copy(renderRoot = model.deriveRenderRoot())
		val puppet = if (compactChannels) withRenderRoot.withChannelsCompacted() else withRenderRoot

		return Cmo3ImportedDocument(puppet, atlasIngest)
	}
}

/**
 * A CMO3 import's two products: the runtime puppet and the atlas ingest it was built from.
 *
 * @property PuppetModel     puppet      The concrete runtime puppet.
 * @property Cmo3AtlasIngest atlasIngest The layered-art web the puppet's atlas came from, carrying the
 *                                       per-tile pixel resources the model deliberately does not hold.
 */
class Cmo3ImportedDocument(
	val puppet: PuppetModel,
	val atlasIngest: Cmo3AtlasIngest,
)