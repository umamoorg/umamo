package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.edit
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.Cmo3ExportReport
import org.umamo.interop.DrawableField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportNotice
import org.umamo.interop.GlueDiff
import org.umamo.interop.ParameterField
import org.umamo.interop.ParameterGroupField
import org.umamo.interop.PuppetDiff
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

/**
 * Lowers an edited [PuppetModel] back onto the retained CMO3 graph it was imported from - the
 * export half of the CMO3 interop boundary (Cmo3Import is the import half).
 *
 * EN: The design is a state-based reconcile, not a Change-log replay: [apply] re-imports the graph
 *     to get a baseline, diffs the edited model against it, and only diff entries touch the graph.
 *     An unedited model therefore leaves the graph untouched and a subsequent Cmo3.write byte-
 *     identical; a re-export after an export reconciles against the just-updated graph and is a
 *     no-op.  Structure runs first (created entities get identity shells, deleted ones leave their
 *     source sets), then every surviving/created entity's fields flow through the one field-level
 *     lowering path.  Edits the lowering cannot (yet or ever) express in CMO3 are returned as
 *     notices, never silently dropped.
 *
 *     The graph is mutated IN PLACE ([Cmo3Model] holds identity-keyed reconcile metadata a deep
 *     copy cannot carry).  The full diff is computed before any mutation, so a failed export
 *     leaves either the untouched or the fully-reconciled graph - and either way the next apply
 *     self-heals from model state.
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

	/**
	 * Reconciles [edited] onto [target]'s graph in place and returns the advisory report.
	 *
	 * A future synthesized-baseline path (MOC3-origin -> CMO3, see TODO.md) constructs a fresh
	 * target graph and reuses this same reconcile - an empty baseline lowers everything as created.
	 *
	 * @param PuppetModel edited The session's current model (EditorSession.model.value - NOT the
	 *                           document's original import, which edits never update).
	 * @param Cmo3Model   target The retained CMO3 model whose graph receives the edits.
	 * @return Cmo3ExportReport The notices for everything not (yet) lowered.
	 */
	fun apply(edited: PuppetModel, target: Cmo3Model): Cmo3ExportReport {
		val modelSource = target.root as? CModelSource ?: error("CMO3 model root is not a CModelSource")
		val baseline = Cmo3Import.fromModelSource(modelSource)
		val diff = diffPuppetModels(baseline, edited)
		if (diff.isEmpty) {
			return Cmo3ExportReport(emptyList())
		}
		val notices = ArrayList<ExportNotice>()
		val editor = target.edit()

		// Phase 1 - set membership: identity shells for creations, source removal for deletions.
		val structural = Cmo3StructureLowering(modelSource, Cmo3GraphIndex(modelSource), editor, edited, notices)
		val editedParameterById = edited.parameters.associateBy(Parameter::id)
		val editedDrawableById = edited.drawables.associateBy(Drawable::id)
		val synthesizedParameters = HashSet<Any>()
		val synthesizedGroups = HashSet<Any>()
		val synthesizedDrawables = HashSet<Any>()
		for (entityDiff in diff.parameters) {
			when (entityDiff) {
				is EntityDiff.Created ->
					editedParameterById[entityDiff.id]?.let { parameter ->
						if (structural.synthesizeParameter(parameter)) {
							synthesizedParameters.add(entityDiff.id)
						}
					}
				is EntityDiff.Deleted -> structural.deleteParameter(entityDiff.id)
				is EntityDiff.Changed -> Unit
			}
		}
		for (entityDiff in diff.parameterGroups) {
			when (entityDiff) {
				is EntityDiff.Created ->
					if (structural.synthesizeParameterGroup(entityDiff.id)) {
						synthesizedGroups.add(entityDiff.id)
					}
				is EntityDiff.Deleted -> structural.deleteParameterGroup(entityDiff.id)
				is EntityDiff.Changed -> Unit
			}
		}
		for (entityDiff in diff.drawables) {
			when (entityDiff) {
				is EntityDiff.Created ->
					editedDrawableById[entityDiff.id]?.let { drawable ->
						if (structural.synthesizeDrawable(drawable)) {
							synthesizedDrawables.add(entityDiff.id)
						}
					}
				is EntityDiff.Deleted -> structural.deleteDrawable(entityDiff.id)
				is EntityDiff.Changed -> Unit
			}
		}
		for (entityDiff in diff.parts) {
			if (entityDiff is EntityDiff.Deleted) {
				structural.deletePart(entityDiff.id.raw)
			}
		}
		for (entityDiff in diff.deformers) {
			if (entityDiff is EntityDiff.Deleted) {
				structural.deleteDeformer(entityDiff.id.raw)
			}
		}
		for (glueDiff in diff.glues) {
			if (glueDiff is GlueDiff.Deleted) {
				structural.deleteGlue(glueDiff.meshA, glueDiff.meshB, glueDiff.ordinal)
			}
		}

		// Deletions are done; synthesized creations become Changed-with-every-field so their shells
		// are populated by the ordinary field lowering (one tested path, no synthesis copy of it).
		val upgradedDiff =
			PuppetDiff(
				parameters =
					diff.parameters.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedParameters ->
								EntityDiff.Changed(entityDiff.id, setOf(ParameterField.NAME, ParameterField.RANGE))
							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				parameterGroups =
					diff.parameterGroups.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedGroups ->
								EntityDiff.Changed(
									entityDiff.id,
									setOf(ParameterGroupField.NAME, ParameterGroupField.INITIALLY_OPEN, ParameterGroupField.CHILDREN),
								)
							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				parts = diff.parts.filterNot { it is EntityDiff.Deleted },
				deformers = diff.deformers.filterNot { it is EntityDiff.Deleted },
				drawables =
					diff.drawables.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedDrawables ->
								EntityDiff.Changed(entityDiff.id, ALL_DRAWABLE_FIELDS)
							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				glues = diff.glues.filterNot { it is GlueDiff.Deleted },
				document = diff.document,
			)

		// Phase 2 - field lowering over a REBUILT index (it must see the shells and forget the
		// removed sources).  Parameters and groups first (tree rebuilds reference their sources),
		// then deformer moves (they mutate _childGuids), then parts (whose CHILDREN rebuild anchors
		// on the moved lists), then drawables, glues, and the document fields.
		val lowering =
			Cmo3PropertyLowering(
				target = target,
				index = Cmo3GraphIndex(modelSource),
				editor = editor,
				baseline = baseline,
				edited = edited,
				notices = notices,
			)
		lowering.lowerParameters(upgradedDiff.parameters)
		lowering.lowerParameterGroups(upgradedDiff.parameterGroups)
		lowering.lowerDeformers(upgradedDiff.deformers)
		lowering.lowerParts(upgradedDiff.parts)
		lowering.lowerDrawables(upgradedDiff.drawables)
		lowering.flushWeldNotice()
		lowering.lowerGlues(upgradedDiff.glues)
		lowering.lowerDocument(upgradedDiff.document)

		if (structural.deletedAnything) {
			editor.pruneUnreachableShared()
		}
		return Cmo3ExportReport(notices)
	}
}
