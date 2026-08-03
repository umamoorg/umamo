package org.umamo.edit.export

import org.umamo.edit.withCanvasSize
import org.umamo.edit.withDeformerBaseAngle
import org.umamo.edit.withDeformerName
import org.umamo.edit.withDeformerPart
import org.umamo.edit.withDeformerSelectable
import org.umamo.edit.withDrawableAlphaBlendMode
import org.umamo.edit.withDrawableBlendMode
import org.umamo.edit.withDrawableCulling
import org.umamo.edit.withDrawableInvertMask
import org.umamo.edit.withDrawableMaskedBy
import org.umamo.edit.withDrawableName
import org.umamo.edit.withDrawableParentDeformer
import org.umamo.edit.withDrawableSelectable
import org.umamo.edit.withDrawableVisibility
import org.umamo.edit.withMeshPositions
import org.umamo.edit.withMeshUvs
import org.umamo.edit.withOrgChildMoved
import org.umamo.edit.withParameterGroupRenamed
import org.umamo.edit.withParameterLink
import org.umamo.edit.withParameterRange
import org.umamo.edit.withParameterRenamed
import org.umamo.edit.withPartGroupMode
import org.umamo.edit.withPartName
import org.umamo.edit.withPartSketch
import org.umamo.edit.withPartVisibility
import org.umamo.edit.withRuntimeTarget
import org.umamo.edit.withWorldOrigin
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.interop.Cmo3ExportReport
import org.umamo.interop.DocumentField
import org.umamo.interop.ExportNotice
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The export-with-changes gate: apply real :edit ops to an imported corpus model, reconcile them
 * onto the retained graph, re-emit the file, re-import it, and assert the edits are not lost - the
 * re-imported model must diff empty against the edited one.  Also pins the never-silent contract:
 * an edit the lowering cannot express must surface as a notice AND remain visible in the residual
 * diff, not vanish.
 */
class Cmo3ExportRoundTripTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private class RoundTrip(
		val edited: PuppetModel,
		val reimported: PuppetModel,
		val report: Cmo3ExportReport,
	)

	/**
	 * Imports the sample, applies [edit], exports through the reconcile, and re-imports the
	 * re-emitted bytes.
	 *
	 * @param File     file The corpus sample.
	 * @param Function edit The model edit under test.
	 * @return RoundTrip The edited model, the re-imported model, and the export report.
	 */
	private fun roundTrip(file: File, edit: (PuppetModel) -> PuppetModel): RoundTrip {
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val edited = edit(Cmo3Import.fromModelSource(modelSource))
		val report = Cmo3Export.apply(edited, cmo3)
		val reimportedSource = Cmo3.read(Cmo3.write(cmo3)).root as? CModelSource ?: error("re-read root is not a CModelSource")
		return RoundTrip(edited, Cmo3Import.fromModelSource(reimportedSource), report)
	}

	private fun assertLossless(result: RoundTrip, label: String) {
		assertTrue(result.report.isEmpty, "$label: expected no notices, got ${result.report.notices}")
		val residual = diffPuppetModels(result.reimported, result.edited)
		assertTrue(residual.isEmpty, "$label: edits lost through export/import: $residual")
	}

	private fun skipMessageOrNull(): File? {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping export round-trip test")
		}
		return file
	}

	@Test
	fun renamesSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				puppet
					.withPartName(puppet.parts.first().id, "Renamed Part")
					.withDrawableName(puppet.drawables.first().id, "Renamed Drawable")
					.let { model ->
						val deformer = model.deformers.firstOrNull() ?: return@let model
						model.withDeformerName(deformer.id, "Renamed Deformer")
					}
					.let { model -> model.withParameterRenamed(model.parameters.first().id, "Renamed Parameter") }
			}
		assertLossless(result, "renames")
	}

	@Test
	fun visibilityAndSelectableFlagsSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				puppet
					.withPartVisibility(puppet.parts.first().id, visible = false)
					.withDrawableVisibility(puppet.drawables.first().id, visible = false)
					.withDrawableSelectable(puppet.drawables.first().id, selectable = false)
					.let { model ->
						val deformer = model.deformers.firstOrNull() ?: return@let model
						model.withDeformerSelectable(deformer.id, selectable = false)
					}
			}
		assertLossless(result, "visibility/selectable")
	}

	@Test
	fun blendCullingAndMaskEditsSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				val drawableId = puppet.drawables.first().id
				val maskTarget = puppet.drawables.last().id
				puppet
					.withDrawableBlendMode(drawableId, BlendMode.Multiply)
					.withDrawableAlphaBlendMode(drawableId, AlphaBlendMode.Atop)
					.withDrawableCulling(drawableId, culling = true)
					.withDrawableInvertMask(drawableId, invert = true)
					.withDrawableMaskedBy(drawableId, listOf(maskTarget))
			}
		assertLossless(result, "blend/culling/mask")
	}

	@Test
	fun reparentEditsSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				// Detach a deformed drawable, and move a deformer to the tree root - both reference
				// rewrites plus (for the deformer) the parts-panel _childGuids move.
				val deformedDrawable = puppet.drawables.firstOrNull { it.parentDeformerId != null }
				val partedDeformer = puppet.deformers.firstOrNull { it.partId != null }
				var model = puppet
				if (deformedDrawable != null) {
					model = model.withDrawableParentDeformer(deformedDrawable.id, null)
				}
				if (partedDeformer != null) {
					model = model.withDeformerPart(partedDeformer.id, null)
				}
				model
			}
		assertLossless(result, "reparent")
	}

	@Test
	fun partFieldEditsSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				val partId = puppet.parts.first().id
				puppet
					.withPartSketch(partId, sketch = true)
					.withPartGroupMode(partId, PartGroupMode.Grouped)
			}
		assertLossless(result, "part fields")
	}

	@Test
	fun deformerFieldEditsSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val rotation =
			run {
				val cmo3 = Cmo3.read(file.readBytes())
				Cmo3Import.fromModelSource(cmo3.root as CModelSource).deformers
					.firstOrNull { it is org.umamo.runtime.model.Deformer.Rotation }
			}
		if (rotation == null) {
			println("${file.name} has no rotation deformer; skipping base-angle round trip")
			return
		}
		val result = roundTrip(file) { puppet -> puppet.withDeformerBaseAngle(rotation.id, 42.5f) }
		assertLossless(result, "deformer fields")
	}

	@Test
	fun parameterRangeAndLinkEditsSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		// Range edits change how a re-import COMPACTS channel tracks (axisSpansRange keys off the
		// range), so a range edit on any axis-bearing parameter round-trips semantically equal but
		// representationally different.  Pick a parameter no axis references at all - collected from
		// an UNCOMPACTED import, since a compacted-away track still resurfaces on re-import when the
		// widened range stops its axis from spanning.
		val uncompacted =
			Cmo3Import.fromModelSource(Cmo3.read(file.readBytes()).root as CModelSource, compactChannels = false)
		val referencedParameterIds =
			buildSet {
				for (drawable in uncompacted.drawables) {
					drawable.geometryGrid?.axes?.forEach { axis -> add(axis.parameterId) }
					drawable.channelGrids.gridsByChannel.values.forEach { grid -> grid.axes.forEach { add(it.parameterId) } }
					drawable.blendShapes.forEach { binding -> add(binding.parameterId) }
				}
				for (deformer in uncompacted.deformers) {
					deformer.channelGrids.gridsByChannel.values.forEach { grid -> grid.axes.forEach { add(it.parameterId) } }
				}
				for (part in uncompacted.parts) {
					part.channelGrids.gridsByChannel.values.forEach { grid -> grid.axes.forEach { add(it.parameterId) } }
				}
				for (glue in uncompacted.glues) {
					glue.channelGrids.gridsByChannel.values.forEach { grid -> grid.axes.forEach { add(it.parameterId) } }
				}
			}
		val unreferenced = uncompacted.parameters.firstOrNull { it.id !in referencedParameterIds }
		val result =
			roundTrip(file) { puppet ->
				var model = puppet
				if (unreferenced != null) {
					model =
						model.withParameterRange(
							unreferenced.id,
							unreferenced.min - 5f,
							unreferenced.default,
							unreferenced.max + 5f,
						)
				}
				val existingLink = model.parameterLinks.firstOrNull()
				if (existingLink != null) {
					model = model.withParameterLink(existingLink.horizontal, existingLink.vertical, linked = false)
				}
				model
			}
		assertLossless(result, "parameter range/link")
	}

	@Test
	fun treeMovesSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				// Move the first part-owned drawable to the end of the root level - exercises both the
				// old part's _childGuids rebuild and the root part's.
				val ownedDrawable =
					puppet.parts.asSequence()
						.flatMap { part -> part.children.asSequence() }
						.filterIsInstance<OrgChild.Drawable>()
						.firstOrNull() ?: return@roundTrip puppet
				puppet.withOrgChildMoved(ownedDrawable, newParentId = null, before = null)
			}
		assertLossless(result, "tree moves")
	}

	@Test
	fun parameterGroupEditsSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val hasGroup =
			run {
				val cmo3 = Cmo3.read(file.readBytes())
				Cmo3Import.fromModelSource(cmo3.root as CModelSource).parameterTree.any { it is ParameterNode.Group }
			}
		if (!hasGroup) {
			println("${file.name} has no parameter groups; skipping group round trip")
			return
		}
		val result =
			roundTrip(file) { puppet ->
				val group = puppet.parameterTree.filterIsInstance<ParameterNode.Group>().first()
				puppet.withParameterGroupRenamed(group.id, "Renamed Group")
			}
		assertLossless(result, "parameter group")
	}

	@Test
	fun canvasAndRuntimeTargetSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				val newWidth = puppet.canvasWidth + 16f
				val newHeight = puppet.canvasHeight + 16f
				puppet
					.withCanvasSize(newWidth, newHeight)
					// CMO3 derives the origin as the canvas center, so a session exporting a resize
					// moves its origin to the new center - the state the exported file will reopen at.
					.withWorldOrigin(newWidth / 2f, -(newHeight / 2f))
					.withRuntimeTarget(RuntimeTarget.Cubism42)
			}
		assertLossless(result, "canvas/runtime target")
	}

	@Test
	fun vertexNudgeSurvivesRoundTripWithAWeldNotice() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				val drawable = puppet.drawables.first { it.mesh != null }
				val nudged = drawable.mesh!!.positions.copyOf()
				nudged[0] += 3f
				nudged[1] -= 2f
				puppet.withMeshPositions(drawable.id, nudged)
			}
		// Export as-authored + warn (the TODO step-8 decision): the geometry survives exactly, and
		// the weld-divergence notice names the edited mesh.
		val weld = result.report.notices.filterIsInstance<ExportNotice.WeldDivergence>().singleOrNull()
		assertTrue(weld != null && weld.drawableNames.isNotEmpty(), "weld notice fires for a base-geometry edit")
		assertTrue(
			result.report.notices.none { it is ExportNotice.UnsupportedChange },
			"a base nudge lowers fully: ${result.report.notices}",
		)
		val residual = diffPuppetModels(result.reimported, result.edited)
		assertTrue(residual.isEmpty, "vertex nudge lost through export/import: $residual")
	}

	@Test
	fun uvEditsSurviveRoundTripOnPackedAndUnpackedDrawables() {
		val file = skipMessageOrNull() ?: return
		// Exercise both UV frame conventions when the model has both: a packed (atlas-region)
		// drawable writes verbatim, an unpacked one goes through the forward model-image affine.
		val cmo3 = Cmo3.read(file.readBytes())
		val root = cmo3.root as CModelSource
		val packedIds =
			buildSet<DrawableId> {
				val sources =
					(((root.drawableSourceSet as? CDrawableSourceSet)?._sources as? Iterable<*>) ?: emptyList<Any?>())
						.filterIsInstance<CArtMeshSource>()
				for (source in sources) {
					val extensions = (source._extensions as? Iterable<*>) ?: emptyList<Any?>()
					val hasRegion =
						extensions.filterIsInstance<CTextureInputExtension>().any { extension ->
							((extension._textureInputs as? Iterable<*>) ?: emptyList<Any?>()).any { it is CTextureInput_TextureAtlasRegion }
						}
					if (hasRegion) {
						(source.id as? Id)?.idstr?.let { idStr -> add(DrawableId(idStr)) }
					}
				}
			}
		val puppetProbe = Cmo3Import.fromModelSource(root)
		val packed = puppetProbe.drawables.firstOrNull { it.mesh != null && it.id in packedIds }
		val unpacked = puppetProbe.drawables.firstOrNull { it.mesh != null && it.id !in packedIds }
		val result =
			roundTrip(file) { puppet ->
				var model = puppet
				for (pick in listOfNotNull(packed, unpacked)) {
					val mesh = model.drawables.first { it.id == pick.id }.mesh!!
					val movedUvs = mesh.uvs.copyOf()
					if (movedUvs.size >= 2) {
						movedUvs[0] += 0.01f
						movedUvs[1] += 0.01f
					}
					model = model.withMeshUvs(pick.id, movedUvs)
				}
				model
			}
		val weld = result.report.notices.filterIsInstance<ExportNotice.WeldDivergence>().singleOrNull()
		assertTrue(weld != null, "weld notice fires for a UV edit")
		assertTrue(
			result.report.notices.none { it is ExportNotice.UnsupportedChange },
			"a UV edit lowers fully: ${result.report.notices}",
		)
		val residual = diffPuppetModels(result.reimported, result.edited)
		assertTrue(residual.isEmpty, "UV edits lost through export/import: $residual")
	}

	@Test
	fun worldOriginEditReportsInsteadOfSilentlyDropping() {
		val file = skipMessageOrNull() ?: return
		val result = roundTrip(file) { puppet -> puppet.withWorldOrigin(puppet.worldOriginX + 10f, puppet.worldOriginY) }
		assertTrue(
			result.report.notices.any { notice -> notice is ExportNotice.UnsupportedChange && notice.subject == "world origin" },
			"world origin edit must surface as a notice",
		)
		val residual = diffPuppetModels(result.reimported, result.edited)
		assertTrue(DocumentField.WORLD_ORIGIN in residual.document, "the unlowered origin stays visible in the diff")
	}

	@Test
	fun renameSurvivesAcrossTheWholeCorpus() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping corpus rename smoke")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		val failures = ArrayList<String>()
		for (file in files) {
			val outcome =
				runCatching {
					val result =
						roundTrip(file) { puppet ->
							// Some synthetic corpus models (the offscreen probes) carry parts but no
							// drawables; rename whichever entity the model has.
							val drawable = puppet.drawables.firstOrNull()
							val part = puppet.parts.firstOrNull()
							when {
								drawable != null -> puppet.withDrawableName(drawable.id, "Corpus Renamed")
								part != null -> puppet.withPartName(part.id, "Corpus Renamed")
								else -> puppet
							}
						}
					if (!result.report.isEmpty) {
						failures.add("${file.name}: notices ${result.report.notices}")
					} else {
						val residual = diffPuppetModels(result.reimported, result.edited)
						if (!residual.isEmpty) {
							failures.add("${file.name}: residual diff $residual")
						}
					}
				}
			outcome.exceptionOrNull()?.let { failure -> failures.add("${file.name}: threw $failure") }
		}
		assertTrue(failures.isEmpty(), "corpus rename smoke failed:\n" + failures.joinToString("\n"))
	}
}
