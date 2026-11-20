package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.edit
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.raster.RasterImage
import org.umamo.interop.DeformerField
import org.umamo.interop.DrawableField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportFormat
import org.umamo.interop.ExportReport
import org.umamo.interop.GlueDiff
import org.umamo.interop.GlueField
import org.umamo.interop.ParameterField
import org.umamo.interop.ParameterGroupField
import org.umamo.interop.PartField
import org.umamo.interop.PuppetDiff
import org.umamo.interop.cmo3.Cmo3TextureFrames
import org.umamo.interop.cmo3.import.Cmo3Import
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * Lowers an edited [PuppetModel] back onto a fresh read of the retained CMO3 graph it was imported
 * from - the export half of the CMO3 interop boundary (Cmo3Import is the import half).
 *
 * The design is a state-based reconcile, not a Change-log replay: [apply] re-imports the graph
 * to get a baseline, diffs the edited model against it, and only diff entries touch the graph.
 * An unedited model therefore leaves the graph untouched, and a subsequent Cmo3.write re-emits its
 * main.xml byte for byte.  Structure runs first (created entities get identity shells, deleted
 * ones leave their source sets), then every surviving/created entity's fields flow through the
 * one field-level lowering path.  Edits the lowering cannot (yet or ever) express in CMO3 are
 * returned as notices, never silently dropped.
 *
 * The target is mutated IN PLACE, pass by pass - the atlas web and the archive entries behind it,
 * the model icons, the structural creates and deletes, the field lowering, and the prune - with no
 * rollback, so a failure part way through leaves it half-reconciled.  The target must therefore be
 * a model nothing else reads: a fresh Cmo3.read of a retained archive, which rebuilds the
 * identity-keyed serializer metadata a deep copy of a graph cannot carry, or a graph synthesized
 * for the export (Cmo3Conversion.freshCmo3).
 */
object Cmo3Export {
	/** Every field of a synthesized drawable, so its shell is populated through the Changed path. */
	private val ALL_DRAWABLE_FIELDS =
		setOf(
			DrawableField.NAME,
			DrawableField.PARENT_DEFORMER,
			DrawableField.BLEND_MODE,
			DrawableField.ALPHA_BLEND_MODE,
			DrawableField.MASKED_BY,
			DrawableField.INVERT_MASK,
			DrawableField.CULLING,
			DrawableField.VISIBLE,
			DrawableField.SELECTABLE,
			DrawableField.MESH_TOPOLOGY,
			DrawableField.GEOMETRY,
			DrawableField.CHANNELS,
			DrawableField.STATICS,
			DrawableField.BLEND_SHAPES,
		)

	/** Every field of a synthesized part. */
	private val ALL_PART_FIELDS = PartField.entries.toSet()

	/** Every warp field except KIND (fixed at synthesis) and the rotation-only BASE_ANGLE. */
	private val ALL_WARP_FIELDS =
		buildSet {
			addAll(DeformerField.entries.toSet())
			remove(DeformerField.KIND)
			remove(DeformerField.BASE_ANGLE)
		}

	/** Every rotation field except KIND (fixed at synthesis) and the warp-only lattice pair. */
	private val ALL_ROTATION_FIELDS =
		buildSet {
			addAll(DeformerField.entries.toSet())
			remove(DeformerField.KIND)
			remove(DeformerField.LATTICE)
			remove(DeformerField.QUAD_TRANSFORM)
		}

	/** Every field of a synthesized glue. */
	private val ALL_GLUE_FIELDS = GlueField.entries.toSet()

	/**
	 * Reconciles [edited] onto [target]'s graph in place and returns the advisory report.
	 *
	 * The fresh-graph synthesis path reuses this same reconcile over a blank target graph, whose
	 * empty baseline lowers everything as created.
	 *
	 * @param PuppetModel edited The session's current model (EditorSession.model.value - NOT the
	 *                           document's original import, which edits never update).
	 * @param Cmo3Model   target The model whose graph and archive receive the edits, mutated in place
	 *                           and left half-reconciled when a pass fails - a fresh read of a
	 *                           retained archive or a synthesized graph, never a model something
	 *                           else reads.
	 * @param Map         drawableTextureBindings Per-drawable-id texture webs for created drawables
	 *                           without a texture source; empty for CMO3-origin exports.
	 * @param List        recomposedPages The pages a repack composed for the edited model's packing,
	 *                           in the edited model's page order; empty when the session did not
	 *                           repack, which leaves the graph and archive untouched.
	 * @param Function    tileRasters The document's own pixels for a tile, or null when it holds none;
	 *                           a reloaded or added tile with a raster writes its real layer into the
	 *                           retained graph, and one without declines the atlas-web reconcile.
	 * @param Long        nowMillis The timestamp a layer minted into the retained graph records.
	 * @param RasterImage modelThumbnail The model's rest-pose thumbnail the three model icons take on
	 *                           an edited export, or null to leave the icons the graph has.
	 * @return ExportReport The notices for everything not (yet) lowered.
	 */
	fun apply(
		edited: PuppetModel,
		target: Cmo3Model,
		drawableTextureBindings: Map<String, Cmo3DrawableTextureBinding> = emptyMap(),
		recomposedPages: List<Cmo3PageImage> = emptyList(),
		tileRasters: (AtlasTileId) -> RasterImage? = { null },
		nowMillis: Long = 0L,
		modelThumbnail: RasterImage? = null,
	): ExportReport {
		val modelSource = target.root as? CModelSource ?: error("CMO3 model root is not a CModelSource")
		val baseline = Cmo3Import.fromModelSource(modelSource)
		val diff = diffPuppetModels(baseline, edited)

		if (diff.isEmpty) {
			return ExportReport(ExportFormat.Cmo3, emptyList())
		}

		// Taken before any pass writes: a reload rewrite rebuilds a model image's cache, and every later
		// pass must still know which textures were the editor's reduced copies.
		val textureFrames = Cmo3TextureFrames(modelSource)
		val notices = Cmo3ExportNotices()
		val context = Cmo3ExportContext(target, modelSource, target.edit(), baseline, edited, textureFrames, tileRasters, nowMillis)
		// The atlas-web reconcile runs before the graph lowering so the stale-page notices know
		// whether the stored pages already show the new packing.  Strictly diff-gated by the CALLER:
		// an unedited document passes no pages, and the graph and archive are then never touched.
		val webResult = Cmo3AtlasWebLowering(context).reconcile(recomposedPages)

		// The model icons follow an edited export (the diff is non-empty here): the editor regenerates
		// them on its own saves, and a thumbnail of the rest pose is what it shows for the file.
		if (modelThumbnail != null) {
			(modelSource.textureManager as? CTextureManager)?.let { textureManager ->
				Cmo3RetainedIcons(context, Cmo3RetainedArchive(target, textureManager)).replaceModelIcons(modelSource, modelThumbnail)
			}
		}

		// A created drawable over art the reconcile minted binds through the binding it handed back;
		// a caller's own binding for the same drawable (the fresh-graph path) outranks it.
		val bindings =
			if (webResult.mintedBindings.isEmpty()) {
				drawableTextureBindings
			} else {
				webResult.mintedBindings + drawableTextureBindings
			}
		val structure = lowerStructure(context, diff, notices, bindings)
		lowerFields(context, promotedDiff(context, diff, structure), notices, webResult)

		if (webResult.pruneNeeded || structure.deletedAnything) {
			context.editor.pruneUnreachableShared()
		}

		return notices.report()
	}

	/**
	 * What the structural pass did.
	 *
	 * @property Set     parameters      The parameters whose shells were synthesized.
	 * @property Set     parameterGroups The parameter groups whose shells were synthesized.
	 * @property Set     parts           The parts whose shells were synthesized.
	 * @property Set     deformers       The deformers whose shells were synthesized.
	 * @property Set     drawables       The drawables whose shells were synthesized.
	 * @property Set     glues           The glues whose shells were synthesized.
	 * @property Boolean deletedAnything True when a source was removed, so the shared pool needs its prune.
	 */
	private class StructuralOutcome(
		val parameters: Set<ParameterId>,
		val parameterGroups: Set<ParameterGroupId>,
		val parts: Set<PartId>,
		val deformers: Set<DeformerId>,
		val drawables: Set<DrawableId>,
		val glues: Set<GlueKey>,
		val deletedAnything: Boolean,
	)

	/**
	 * What names one glue in a diff: its two meshes, and its place among the glues between them.
	 *
	 * @property DrawableId meshA   The glue's first mesh.
	 * @property DrawableId meshB   The glue's second mesh.
	 * @property Int        ordinal The glue's index among the glues of that pair.
	 */
	private data class GlueKey(val meshA: DrawableId, val meshB: DrawableId, val ordinal: Int)

	/**
	 * The structural pass - set membership: an identity shell for every creation, source removal for
	 * every deletion.
	 *
	 * Category order follows the reference web (parameters and groups first, then parts and deformers,
	 * then the drawables that bind to them, then the glues that bind drawables), and the structural
	 * index is REBUILT before the drawables and again before the glues so same-export creations resolve.
	 *
	 * @param Cmo3ExportContext context  The export's context.
	 * @param PuppetDiff        diff     The edited model's diff against the baseline.
	 * @param Cmo3ExportNotices notices  The notice sink.
	 * @param Map               bindings Per-drawable-id texture webs for created drawables.
	 * @return StructuralOutcome What was synthesized, and whether anything was deleted.
	 */
	private fun lowerStructure(
		context: Cmo3ExportContext,
		diff: PuppetDiff,
		notices: Cmo3ExportNotices,
		bindings: Map<String, Cmo3DrawableTextureBinding>,
	): StructuralOutcome {
		var deletedAnything = false
		var structural = Cmo3StructureLowering(context, Cmo3GraphIndex(context.modelSource), notices, bindings)

		/**
		 * Rolls the structural lowering onto a fresh index, carrying the deletion flag forward.
		 *
		 * @return Cmo3StructureLowering The replacement instance.
		 */
		fun refreshedStructural(): Cmo3StructureLowering {
			deletedAnything = deletedAnything || structural.deletedAnything

			return Cmo3StructureLowering(context, Cmo3GraphIndex(context.modelSource), notices, bindings)
		}

		val parameters =
			runStructuralPass(
				diff.parameters,
				synthesize = { id -> context.editedParameterById[id]?.let { parameter -> structural.synthesizeParameter(parameter) } ?: false },
				delete = { id -> structural.deleteParameter(id) },
			)
		val parameterGroups =
			runStructuralPass(
				diff.parameterGroups,
				synthesize = { id -> structural.synthesizeParameterGroup(id) },
				delete = { id -> structural.deleteParameterGroup(id) },
			)
		val parts =
			runStructuralPass(
				diff.parts,
				synthesize = { id -> context.editedPartById[id]?.let { part -> structural.synthesizePart(part) } ?: false },
				delete = { id -> structural.deletePart(id.raw) },
			)
		val deformers =
			runStructuralPass(
				diff.deformers,
				synthesize = { id -> context.editedDeformerById[id]?.let { deformer -> structural.synthesizeDeformer(deformer) } ?: false },
				delete = { id -> structural.deleteDeformer(id.raw) },
			)
		structural = refreshedStructural()
		val drawables =
			runStructuralPass(
				diff.drawables,
				synthesize = { id -> context.editedDrawableById[id]?.let { drawable -> structural.synthesizeDrawable(drawable) } ?: false },
				delete = { id -> structural.deleteDrawable(id) },
			)
		structural = refreshedStructural()
		val editedGluesByPair = context.edited.glues.groupBy { glue -> glue.meshA to glue.meshB }
		val glues =
			runGlueStructuralPass(
				diff.glues,
				synthesize = { created ->
					editedGluesByPair[created.meshA to created.meshB]?.getOrNull(created.ordinal)?.let { glue -> structural.synthesizeGlue(glue, created.ordinal) } ?: false
				},
				delete = { deleted -> structural.deleteGlue(deleted.meshA, deleted.meshB, deleted.ordinal) },
			)
		deletedAnything = deletedAnything || structural.deletedAnything

		return StructuralOutcome(parameters, parameterGroups, parts, deformers, drawables, glues, deletedAnything)
	}

	/**
	 * Runs one category's structural pass: synthesizes a shell for each creation, removes the source of
	 * each deletion, and leaves a change to the field pass.
	 *
	 * @param List     diffs      The category's diffs.
	 * @param Function synthesize Synthesizes the shell of a created entity, true when it now exists.
	 * @param Function delete     Removes a deleted entity's source.
	 * @return Set The ids whose shells were synthesized.
	 */
	private fun <TId, TField> runStructuralPass(
		diffs: List<EntityDiff<TId, TField>>,
		synthesize: (TId) -> Boolean,
		delete: (TId) -> Unit,
	): Set<TId> {
		val synthesized = HashSet<TId>()

		for (entityDiff in diffs) {
			when (entityDiff) {
				is EntityDiff.Created -> {
					if (synthesize(entityDiff.id)) {
						synthesized.add(entityDiff.id)
					}
				}

				is EntityDiff.Deleted -> {
					delete(entityDiff.id)
				}

				is EntityDiff.Changed -> {}
			}
		}

		return synthesized
	}

	/**
	 * The glues' structural pass, [runStructuralPass] over the glue diff's own shape.
	 *
	 * @param List     diffs      The glue diffs.
	 * @param Function synthesize Synthesizes the shell of a created glue, true when it now exists.
	 * @param Function delete     Removes a deleted glue's source.
	 * @return Set The glues whose shells were synthesized.
	 */
	private fun runGlueStructuralPass(
		diffs: List<GlueDiff>,
		synthesize: (GlueDiff.Created) -> Boolean,
		delete: (GlueDiff.Deleted) -> Unit,
	): Set<GlueKey> {
		val synthesized = HashSet<GlueKey>()

		for (glueDiff in diffs) {
			when (glueDiff) {
				is GlueDiff.Created -> {
					if (synthesize(glueDiff)) {
						synthesized.add(GlueKey(glueDiff.meshA, glueDiff.meshB, glueDiff.ordinal))
					}
				}

				is GlueDiff.Deleted -> {
					delete(glueDiff)
				}

				is GlueDiff.Changed -> {}
			}
		}

		return synthesized
	}

	/**
	 * The diff the field pass runs on.  Deletions are done and leave it; a synthesized creation becomes
	 * Changed with every field, so its shell is populated by the ordinary field lowering (one tested
	 * path, no synthesis copy of it).
	 *
	 * @param Cmo3ExportContext context   The export's context.
	 * @param PuppetDiff        diff      The edited model's diff against the baseline.
	 * @param StructuralOutcome structure What the structural pass synthesized.
	 * @return PuppetDiff The diff for the field pass.
	 */
	private fun promotedDiff(context: Cmo3ExportContext, diff: PuppetDiff, structure: StructuralOutcome): PuppetDiff =
		PuppetDiff(
			parameters = promoteSynthesized(diff.parameters, structure.parameters) { setOf(ParameterField.NAME, ParameterField.RANGE, ParameterField.REPEAT) },
			parameterGroups =
				promoteSynthesized(diff.parameterGroups, structure.parameterGroups) {
					setOf(ParameterGroupField.NAME, ParameterGroupField.INITIALLY_OPEN, ParameterGroupField.CHILDREN)
				},
			parts = promoteSynthesized(diff.parts, structure.parts) { ALL_PART_FIELDS },
			deformers =
				promoteSynthesized(diff.deformers, structure.deformers) { id ->
					if (context.editedDeformerById[id] is Deformer.Warp) ALL_WARP_FIELDS else ALL_ROTATION_FIELDS
				},
			drawables = promoteSynthesized(diff.drawables, structure.drawables) { ALL_DRAWABLE_FIELDS },
			glues = promoteSynthesizedGlues(diff.glues, structure.glues),
			// Tiles have no structural pass: art arrives and leaves with an import, never with a pack,
			// so a created or deleted tile forwards as-is and takes its notice in the lowering.
			atlasTiles = diff.atlasTiles,
			document = diff.document,
		)

	/**
	 * One category's diffs for the field pass: a synthesized creation as a change of [fieldsOf] its id,
	 * no deletion, and everything else as it is.  A creation whose synthesis failed stays Created, which
	 * the field pass skips.
	 *
	 * @param List     diffs       The category's diffs.
	 * @param Set      synthesized The ids whose shells were synthesized.
	 * @param Function fieldsOf    The fields a synthesized entity's shell is populated through.
	 * @return List The diffs for the field pass.
	 */
	private fun <TId, TField> promoteSynthesized(
		diffs: List<EntityDiff<TId, TField>>,
		synthesized: Set<TId>,
		fieldsOf: (TId) -> Set<TField>,
	): List<EntityDiff<TId, TField>> =
		diffs.mapNotNull { entityDiff ->
			when {
				entityDiff is EntityDiff.Created && entityDiff.id in synthesized -> {
					EntityDiff.Changed(entityDiff.id, fieldsOf(entityDiff.id))
				}

				entityDiff is EntityDiff.Deleted -> {
					null
				}

				else -> {
					entityDiff
				}
			}
		}

	/**
	 * The glue diffs for the field pass, [promoteSynthesized] over the glue diff's own shape.
	 *
	 * @param List diffs       The glue diffs.
	 * @param Set  synthesized The glues whose shells were synthesized.
	 * @return List The diffs for the field pass.
	 */
	private fun promoteSynthesizedGlues(diffs: List<GlueDiff>, synthesized: Set<GlueKey>): List<GlueDiff> =
		diffs.mapNotNull { glueDiff ->
			when {
				glueDiff is GlueDiff.Created && GlueKey(glueDiff.meshA, glueDiff.meshB, glueDiff.ordinal) in synthesized -> {
					GlueDiff.Changed(glueDiff.meshA, glueDiff.meshB, glueDiff.ordinal, ALL_GLUE_FIELDS)
				}

				glueDiff is GlueDiff.Deleted -> {
					null
				}

				else -> {
					glueDiff
				}
			}
		}

	/**
	 * The field pass: every diffed field with a direct CMO3 field to write, over an index REBUILT after
	 * the structural pass (it must see the shells and forget the removed sources).  Parameters and
	 * groups first (tree rebuilds reference their sources), then deformer moves (they mutate
	 * _childGuids), then parts (whose CHILDREN rebuild anchors on the moved lists), then drawables,
	 * glues, the atlas tiles, and the document fields.  Every entity's channel-backed values (statics,
	 * keyforms, geometry) go through the one keyform lowering, and mesh topology and glue re-binding
	 * through the one topology lowering.
	 *
	 * A Created diff that still reaches this pass is one the structural pass tried to synthesize and
	 * failed, and it has already reported why, so each lowering skips it silently.  Deleted diffs never
	 * arrive: the structural pass consumes every deletion and [promotedDiff] strips them.
	 *
	 * Every assignment that can target an element or attribute the source document omitted pairs with
	 * the graph editor's ensure call - the writer replays recorded slots, so a bare assignment on a
	 * read object would otherwise be dropped silently.  Anchors follow the generated model's declared
	 * field order (the editor's document order), so an ensured element lands where the editor writes it.
	 *
	 * @param Cmo3ExportContext           context   The export's context.
	 * @param PuppetDiff                  diff      The promoted diff.
	 * @param Cmo3ExportNotices           notices   The notice sink.
	 * @param Cmo3AtlasWebLowering.Result webResult What the atlas-web reconcile did.
	 */
	private fun lowerFields(context: Cmo3ExportContext, diff: PuppetDiff, notices: Cmo3ExportNotices, webResult: Cmo3AtlasWebLowering.Result) {
		val index = Cmo3GraphIndex(context.modelSource)
		val keyforms = Cmo3KeyformLowering(context, index, notices)
		val topology = Cmo3MeshTopologyLowering(context, index, notices)
		val panelTree = Cmo3PanelTreeLowering(context, index, notices)
		val parameters = Cmo3ParameterLowering(context, index, notices, panelTree)
		parameters.lowerParameters(diff.parameters)
		parameters.lowerParameterGroups(diff.parameterGroups)
		Cmo3DeformerLowering(context, index, notices, keyforms).lowerDeformers(diff.deformers)
		Cmo3PartLowering(context, index, notices, keyforms, panelTree).lowerParts(diff.parts)
		val drawables = Cmo3DrawableLowering(context, index, notices, keyforms, topology)
		drawables.lowerDrawables(diff.drawables)
		drawables.flushWeldNotice()
		Cmo3GlueLowering(context, index, notices, keyforms, topology).lowerGlues(diff.glues)
		Cmo3AtlasTileLowering(context, index, notices, webResult.pagesRecomposed, webResult.reconciledTileIds).lowerAtlasTiles(diff.atlasTiles)
		Cmo3DocumentLowering(context, index, notices, panelTree, webResult.pagesRecomposed).lowerDocument(diff.document)
	}
}