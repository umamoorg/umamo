package org.umamo.ui.model.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.choiceValue
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.edit.mesh.commitGeneratedMeshes
import org.umamo.edit.mesh.commitUnchangedGeneration
import org.umamo.interop.art.mesh.ArtMeshNotice
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.MeshFit
import org.umamo.interop.art.mesh.isMeshingGuardFailure
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.SessionAtlasPages
import kotlin.math.round

/*
 * Generate Mesh from Art as an operation: the run on the UI thread around the headless pipeline
 * (GenerateMeshPipeline.kt), committed as ONE undo step and registered on the operation settings strip,
 * whose adjustments re-mesh from the same starting model.  The Add Artwork flow's shape: the model is
 * read at the start, the work runs on the default dispatcher, and the result lands only if the model is
 * still the one it was built from.
 */

/**
 * What a Generate Mesh from Art run needs from the shell.
 *
 * @property EditorSession      session            The document's session.
 * @property SourceArtRasters   artRasters         The document's raster store.
 * @property SessionAtlasPages? sessionAtlasPages  The page resolver to pre-warm after a pack, or null.
 * @property Boolean            premultipliedAlpha The document's texture-convention flag.
 * @property CoroutineScope     scope              Where the strip's reruns run.
 * @property Function           rememberOptions    Remembers the options a run or an adjustment landed with.
 */
internal class GenerateMeshHost(
	val session: EditorSession,
	val artRasters: SourceArtRasters,
	val sessionAtlasPages: SessionAtlasPages?,
	val premultipliedAlpha: Boolean,
	val scope: CoroutineScope,
	val rememberOptions: (MeshGenerationOptions) -> Unit,
)

/**
 * The status notice one outcome reports.
 *
 * @property String       messageKey The notice key.
 * @property List<String> arguments  Its counts.
 */
internal data class GenerateMeshNotice(val messageKey: String, val arguments: List<String> = emptyList())

/**
 * The skips a row of the operation strip can undo: the Alpha Threshold for art too faint, Minimum
 * Outline Points or the threshold for art that fits no budget, the Outer Margin for pinned art with no
 * room, the spacings for a mesh the mesher withheld.  A run that generates nothing but one of these still
 * opens the strip.
 */
private val ROW_ADJUSTABLE_SKIPS: Set<GenerateMeshSkipReason> =
	setOf(GenerateMeshSkipReason.NothingOpaque, GenerateMeshSkipReason.OverBudget, GenerateMeshSkipReason.PinnedWithoutRoom, GenerateMeshSkipReason.Withheld)

/**
 * The notice an outcome reports: a refused pack first, then the skips a rigger can do something about
 * (pinned art with no room, a mesh over every budget), then drawables glue holds throughout, then any
 * skip, then meshes that needed a raised budget or their holes filled, then meshes built coarser than
 * the rows asked for, then the plain count.
 *
 * @param GenerateMeshOutcome outcome The outcome.
 * @return GenerateMeshNotice The notice.
 */
internal fun generateMeshNoticeOf(outcome: GenerateMeshOutcome): GenerateMeshNotice {
	if (outcome is GenerateMeshOutcome.PackRefused) {
		return GenerateMeshNotice("notice.mesh.packRefused")
	}

	val generated = outcome as? GenerateMeshOutcome.Generated
	val generatedCount = generated?.generatedIds?.size ?: 0
	val coarsenedCount = generated?.coarsenedIds?.size ?: 0
	val refitCount = generated?.refitIds?.size ?: 0
	val pinnedCount = outcome.skips.values.count { reason -> reason == GenerateMeshSkipReason.PinnedWithoutRoom }
	val overBudgetCount = outcome.skips.values.count { reason -> reason == GenerateMeshSkipReason.OverBudget }
	val gluedCount = outcome.skips.values.count { reason -> reason == GenerateMeshSkipReason.GluedThroughout }

	return when {
		pinnedCount > 0 -> GenerateMeshNotice("notice.mesh.pinnedWithoutRoom", listOf(generatedCount.toString(), pinnedCount.toString()))
		overBudgetCount > 0 -> GenerateMeshNotice("notice.mesh.overBudget", listOf(generatedCount.toString(), overBudgetCount.toString()))
		gluedCount > 0 -> GenerateMeshNotice("notice.mesh.gluedThroughout", listOf(generatedCount.toString(), gluedCount.toString()))
		generatedCount == 0 -> GenerateMeshNotice("notice.mesh.noneGenerated")
		outcome.skips.isNotEmpty() -> GenerateMeshNotice("notice.mesh.generatedWithSkips", listOf(generatedCount.toString(), outcome.skips.size.toString()))
		refitCount > 0 -> GenerateMeshNotice("notice.mesh.generatedRefit", listOf(generatedCount.toString(), refitCount.toString()))
		coarsenedCount > 0 -> GenerateMeshNotice("notice.mesh.generatedCoarser", listOf(generatedCount.toString(), coarsenedCount.toString()))
		else -> GenerateMeshNotice("notice.mesh.generated", listOf(generatedCount.toString()))
	}
}

/**
 * Generates meshes from art for [targets] (and every drawable sharing their art) as ONE undo step and
 * registers the step on the operation settings strip.
 *
 * Runs on the UI thread; the planning, the meshing, and any pack run on the default dispatcher.  The
 * meshes are built against the model current at the start, so any edit landing meanwhile supersedes
 * them.  A guard tripping anywhere in the pipeline is a refusal with a notice, never a crash.
 *
 * @param GenerateMeshHost      host    The session, art, resolver, scope, and options memory.
 * @param List<DrawableId>      targets The drawables asked for.
 * @param MeshGenerationOptions options The options to run with.
 * @param String?               areaId  The area the command was dispatched over, or null - where the strip shows.
 * @return Boolean Whether any mesh landed.
 */
internal suspend fun runGenerateMesh(host: GenerateMeshHost, targets: List<DrawableId>, options: MeshGenerationOptions, areaId: String?): Boolean {
	val session = host.session
	val modelAtStart = session.model.value
	val minimumAlphaThreshold = modelAtStart.atlas.composition.alphaThreshold
	val landedOptions = MeshGenerationOptions(options.preset, clampedMeshSettings(options.settings, minimumAlphaThreshold))
	val planned =
		try {
			withContext(Dispatchers.Default) {
				// The run's own decoder: every tile once, and nothing of it outlives the run.
				val decode = RunTileDecoder { tileId -> host.artRasters.decodeRaster(tileId) }
				val plan = planMeshGeneration(modelAtStart, targets, decode)
				plan to generateMeshOutcome(modelAtStart, plan, landedOptions.settings, decode, host.premultipliedAlpha)
			}
		} catch (failure: RuntimeException) {
			if (!failure.isMeshingGuardFailure()) {
				throw failure
			}

			UmamoLog.error("generate mesh: the run failed; nothing was applied", failure)
			session.emitNotice("notice.mesh.noneGenerated", NoticePlacement.StatusBar)

			return false
		}
	val plan = planned.first
	val outcome = planned.second
	logSharedArt(modelAtStart, targets, plan.drawableIds)
	logOutcome(modelAtStart, outcome)

	if (outcome !is GenerateMeshOutcome.Generated) {
		emitNotice(session, generateMeshNoticeOf(outcome))

		// When a row could change the outcome, an unchanged step opens the strip on it, so the rigger is
		// never left with no way to reach the rows; an adjustment that generates lands over the step.
		if (outcome is GenerateMeshOutcome.NothingGenerated && outcome.skips.values.any { reason -> reason in ROW_ADJUSTABLE_SKIPS } && session.commitUnchangedGeneration(modelAtStart, plan.drawableIds)) {
			host.rememberOptions(landedOptions)
			registerGenerateMesh(host, modelAtStart, areaId, plan, landedOptions, minimumAlphaThreshold)
		}

		return false
	}

	if (!session.commitGeneratedMeshes(modelAtStart, outcome.model, plan.drawableIds)) {
		UmamoLog.warn("generate mesh: the document changed while the meshes were generated; nothing was applied")
		session.emitNotice("notice.mesh.superseded", NoticePlacement.StatusBar)

		return false
	}

	outcome.textures?.let { textures -> host.sessionAtlasPages?.prewarm(outcome.model.atlas, textures) }
	emitNotice(session, generateMeshNoticeOf(outcome))
	host.rememberOptions(landedOptions)
	registerGenerateMesh(host, outcome.model, areaId, plan, landedOptions, minimumAlphaThreshold)

	return true
}

/**
 * Registers a landed run on the operation settings strip.  Choosing a preset - another one, or the one
 * already shown, to undo edits to its rows - re-seeds the density rows before the rerun reads them; a
 * newer adjustment cancels the rerun still in flight.
 *
 * @param GenerateMeshHost      host                  The host.
 * @param PuppetModel           committed             The model the run committed.
 * @param String?               areaId                Where the strip shows.
 * @param GenerateMeshPlan      plan                  The run's plan, kept for the reruns.
 * @param MeshGenerationOptions options               The options the run landed with.
 * @param Int                   minimumAlphaThreshold The atlas's alpha threshold.
 */
private fun registerGenerateMesh(
	host: GenerateMeshHost,
	committed: PuppetModel,
	areaId: String?,
	plan: GenerateMeshPlan,
	options: MeshGenerationOptions,
	minimumAlphaThreshold: Int,
) {
	val session = host.session
	var seededPreset = options.preset
	var adjustmentJob: Job? = null
	session.registerAdjustableOperation(committed, areaId, meshGenerationParameters(options, minimumAlphaThreshold)) { record ->
		var current = record
		val rowPreset = ArtMeshPreset.fromKey(record.parameters.choiceValue(MeshGenerationParameterKeys.PRESET, seededPreset.key))

		if (rowPreset != seededPreset || record.adjustedKey == MeshGenerationParameterKeys.PRESET) {
			seededPreset = rowPreset
			current = session.reviseAdjustableParameters(record, reseededMeshGenerationParameters(record.parameters, rowPreset, minimumAlphaThreshold)) ?: return@registerAdjustableOperation
		}

		adjustmentJob?.cancel()
		val adjusting = current
		adjustmentJob = host.scope.launch { adjustGenerateMesh(host, adjusting, plan, options, minimumAlphaThreshold) }
	}
}

/**
 * Re-meshes for an adjustment of the strip: the record's rows become the options, the SAME plan meshes
 * again over the record's base model, and the result lands over the operation's own step.  An outcome
 * that generates nothing leaves the previous result standing; a newer adjustment, or a record cleared
 * while this one ran, drops it.
 *
 * @param GenerateMeshHost      host                  The host.
 * @param AdjustableOperation   record                The record with the adjusted rows.
 * @param GenerateMeshPlan      plan                  The first run's plan.
 * @param MeshGenerationOptions fallback              The options the first run used.
 * @param Int                   minimumAlphaThreshold The atlas's alpha threshold.
 */
internal suspend fun adjustGenerateMesh(
	host: GenerateMeshHost,
	record: AdjustableOperation,
	plan: GenerateMeshPlan,
	fallback: MeshGenerationOptions,
	minimumAlphaThreshold: Int,
) {
	val session = host.session
	val base = record.baseSnapshot.model
	val options = meshGenerationOptionsOf(record.parameters, fallback, minimumAlphaThreshold)
	val outcome =
		try {
			withContext(Dispatchers.Default) {
				generateMeshOutcome(base, plan, options.settings, RunTileDecoder { tileId -> host.artRasters.decodeRaster(tileId) }, host.premultipliedAlpha)
			}
		} catch (failure: RuntimeException) {
			if (!failure.isMeshingGuardFailure()) {
				throw failure
			}

			UmamoLog.error("generate mesh: the adjustment failed; the previous result stands", failure)

			return
		}
	logOutcome(base, outcome)

	if (outcome !is GenerateMeshOutcome.Generated) {
		UmamoLog.warn("generate mesh: the adjusted settings generate nothing; the previous result stands")
		emitNotice(session, generateMeshNoticeOf(outcome))

		return
	}

	// Two quick edits of a row can finish out of order; only the result for the rows on show lands.
	if (session.adjustableOperation.value?.parameters != record.parameters) {
		UmamoLog.info("generate mesh: a newer adjustment superseded this one; nothing was applied")

		return
	}

	outcome.textures?.let { textures -> host.sessionAtlasPages?.prewarm(outcome.model.atlas, textures) }

	if (!session.amendLastCommit(record, outcome.model)) {
		UmamoLog.info("generate mesh: the adjustment was superseded before it landed; nothing was applied")

		return
	}

	emitNotice(session, generateMeshNoticeOf(outcome))
	host.rememberOptions(options)
}

/**
 * Logs the drawables a run covers without being asked for, because they share art with one that was:
 * a twin outside the selection changes too, and the log is where the rigger finds out which.
 *
 * @param PuppetModel      base        The model the run started on, for the names.
 * @param List<DrawableId> targets     The drawables asked for.
 * @param List<DrawableId> drawableIds Every drawable the run covers.
 */
private fun logSharedArt(base: PuppetModel, targets: List<DrawableId>, drawableIds: List<DrawableId>) {
	val asked = targets.toSet()
	val sharing = drawableIds.filter { drawableId -> drawableId !in asked }

	if (sharing.isEmpty()) {
		return
	}

	val nameById = base.drawables.associate { drawable -> drawable.id to drawable.name }
	UmamoLog.info("generate mesh: also regenerating ${sharing.joinToString { drawableId -> "'${nameById[drawableId] ?: drawableId.raw}'" }}, which share art with the selection")
}

/**
 * Logs one outcome: every skipped drawable with its reason, what the mesher adjusted for every generated
 * drawable (the rows on the strip still show what was asked), every pack refusal, and the counts.
 *
 * @param PuppetModel         base    The model the run started on, for the names.
 * @param GenerateMeshOutcome outcome The outcome.
 */
private fun logOutcome(base: PuppetModel, outcome: GenerateMeshOutcome) {
	val nameById = base.drawables.associate { drawable -> drawable.id to drawable.name }

	for ((drawableId, reason) in outcome.skips) {
		UmamoLog.info("generate mesh: '${nameById[drawableId] ?: drawableId.raw}' skipped ($reason)")
	}

	when (outcome) {
		is GenerateMeshOutcome.PackRefused -> {
			for (refusal in outcome.refusals) {
				UmamoLog.warn("generate mesh: the atlas has no room for '${refusal.tileName}' (${refusal.reason}); nothing was applied")
			}
		}

		is GenerateMeshOutcome.Generated -> {
			for ((drawableId, notices) in outcome.noticesByDrawable) {
				for (notice in notices) {
					UmamoLog.info("generate mesh: '${nameById[drawableId] ?: drawableId.raw}': ${wordingOf(notice)}")
				}
			}

			for ((drawableId, fit) in outcome.fitsByDrawable) {
				UmamoLog.info("generate mesh: '${nameById[drawableId] ?: drawableId.raw}': ${wordingOf(fit)}, as the rows could not fit it")
			}

			UmamoLog.info(
				"generate mesh: ${outcome.generatedIds.size} mesh(es) generated, ${outcome.skips.size} skipped, ${outcome.packedTileIds.size} tile(s) packed to a new spot",
			)
		}

		is GenerateMeshOutcome.NothingGenerated -> {
			UmamoLog.warn("generate mesh: nothing generated; ${outcome.skips.size} skipped")
		}
	}
}

/**
 * The log's words for how a mesh the rows could not fit was made to fit.
 *
 * @param MeshFit fit The fit.
 * @return String The words.
 */
private fun wordingOf(fit: MeshFit): String {
	val budget = fit.budget?.let { raised -> "meshed under a vertex budget of $raised" }
	val holes = if (fit.holesFilled) "with its holes filled" else null

	return listOfNotNull(budget, holes).joinToString(" and ").ifEmpty { "meshed as the rows asked" }
}

/**
 * The log's words for one adjustment the mesher made.
 *
 * @param ArtMeshNotice notice The notice.
 * @return String The words.
 */
private fun wordingOf(notice: ArtMeshNotice): String =
	when (notice) {
		is ArtMeshNotice.SpacingCoarsened -> coarseningWording(notice)
		is ArtMeshNotice.WorkingGridCoarsened -> "the layer is too large for a ${notice.requestedCellSize} px working cell, so a ${notice.cellSize} px cell was used and the outline sits a little further out"
		is ArtMeshNotice.OutlineDensified -> "${notice.chordCount} outline chord(s) crossed another ring after simplification and were kept at their traced density"
		is ArtMeshNotice.InnerRingEdgesDropped -> "${notice.edgeCount} inner-ring edge(s) crossed the outline and were left out"
		is ArtMeshNotice.PinsShareVertex -> "${notice.pinCount} glued vertex pin(s) sit at the same position and share one vertex"
		is ArtMeshNotice.DeformationToleranceUnmet -> "the vertex budget ran out with the new mesh still up to ${tenths(notice.largestError)} px from the rig's old shape"
		is ArtMeshNotice.RefinementBudgetReached -> "refinement reached the vertex budget with ${notice.underTargetTriangleCount} triangle(s) still below the minimum angle"
		is ArtMeshNotice.NothingOpaque -> "nothing in the art reaches the alpha threshold"
		is ArtMeshNotice.OverBudget -> "the mesh needs ${notice.vertexCount} vertices, past the budget of ${notice.budget}"
		is ArtMeshNotice.StructureCheckFailed -> "the mesh was withheld: ${notice.reason}"
	}

/**
 * The log's words for a mesh coarsened to fit its vertex budget.
 *
 * @param ArtMeshNotice.SpacingCoarsened notice The notice.
 * @return String The words.
 */
private fun coarseningWording(notice: ArtMeshNotice.SpacingCoarsened): String {
	val interior = notice.interiorSpacing?.let { spacing -> "interior spacing ${tenths(spacing)} px" } ?: "no interior points (refinement fills it)"

	return "coarsened to stay within the vertex budget: outline spacing ${tenths(notice.outlineSpacing)} px, $interior"
}

/**
 * A length rounded to a tenth of a pixel, for the log.
 *
 * @param Double value The length, pixels.
 * @return Double The rounded length.
 */
private fun tenths(value: Double): Double = round(value * 10.0) / 10.0

/**
 * Emits one outcome's notice on the status bar.
 *
 * @param EditorSession      session The session.
 * @param GenerateMeshNotice notice  The notice.
 */
private fun emitNotice(session: EditorSession, notice: GenerateMeshNotice) {
	session.emitNotice(notice.messageKey, NoticePlacement.StatusBarAndNearCursor, notice.arguments)
}

/**
 * The drawables Generate Mesh from Art is asked for: the Edit-mode session's meshes, whole, or the
 * Object-mode selection's drawables that have a mesh.  The run adds every drawable sharing their art.
 *
 * @param EditorSession session The document's session.
 * @return List<DrawableId> The drawables, empty when nothing is selected to mesh.
 */
internal fun generateMeshTargets(session: EditorSession): List<DrawableId> =
	if (session.mode.value == EditorMode.Edit) {
		session.meshSelection.value.drawableIds
	} else {
		eligibleTransformDrawables(session.selection.value, session.model.value).orEmpty()
	}

/**
 * Whether a Generate Mesh from Art run is in flight.  Held for as long as the window, beside the
 * options memory, because the command table that holds the launcher is rebuilt whenever its keys
 * change (a selection change mid-run does) and a flag inside the launcher would start over with it.
 */
internal class MeshGenerationRunState {
	/** True from a run's launch until it has landed or given up. */
	var running: Boolean = false
}

/**
 * The Generate Mesh from Art command's action for one document: each call meshes the drawables
 * [generateMeshTargets] names, starting from the options the window remembers (or the settings' preset
 * before any run), and shows its strip over the area it is given.  A call while a run is still going is
 * ignored - the rigger sees the first one land.
 *
 * @param EditorSession                session            The document's session.
 * @param SourceArtRasters             artRasters         The document's raster store.
 * @param SessionAtlasPages?           sessionAtlasPages  The page resolver to pre-warm after a pack, or null.
 * @param Boolean                      premultipliedAlpha The document's texture-convention flag.
 * @param CoroutineScope               scope              Where the run and its adjustments launch.
 * @param MeshGenerationSessionOptions sessionOptions     The window's memory of the last options.
 * @param MeshGenerationRunState       runState           The window's record of a run in flight.
 * @param Function                     seedPresetKey      Reads the settings' preset key, for a first run.
 * @return Function The action, handed the area id the command was dispatched over, or null.
 */
internal fun generateMeshLauncher(
	session: EditorSession,
	artRasters: SourceArtRasters,
	sessionAtlasPages: SessionAtlasPages?,
	premultipliedAlpha: Boolean,
	scope: CoroutineScope,
	sessionOptions: MeshGenerationSessionOptions,
	runState: MeshGenerationRunState,
	seedPresetKey: () -> String?,
): (areaId: String?) -> Unit {
	val host = GenerateMeshHost(session, artRasters, sessionAtlasPages, premultipliedAlpha, scope, sessionOptions::record)

	return { areaId ->
		val targets = generateMeshTargets(session)

		if (targets.isEmpty()) {
			session.emitNotice("notice.mesh.noTargets", NoticePlacement.StatusBar)
		} else if (runState.running) {
			UmamoLog.info("generate mesh: a run is already going; this one was ignored")
		} else {
			runState.running = true
			val options = sessionOptions.optionsFor(seedPresetKey())
			scope.launch {
				try {
					runGenerateMesh(host, targets, options, areaId)
				} finally {
					runState.running = false
				}
			}
		}
	}
}