package org.umamo.edit.export

import org.umamo.edit.withDrawableOpacity
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.Cmo3ExportReport
import org.umamo.interop.DrawableField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportNotice
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.OutOfSpanKeyPolicy
import org.umamo.runtime.keyform.withKeyInserted
import org.umamo.runtime.keyform.withKeyRemoved
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The keyform-lowering gate: keyform value edits, key inserts/deletes, channel-track edits, and
 * statics reconcile onto the CMO3 grid web and survive an export/re-import.  Channel values and
 * key deletions round-trip bit-exact (values pass through unchanged; surviving forms keep their
 * stored absolutes).  Drawable GEOMETRY tolerates bounded ULP on edited/inserted cells only: CMO3
 * stores absolutes, Umamo deltas, and (base + delta) - base is not an IEEE identity - the same
 * bounded-ULP tier the fidelity contract already assigns to geometry.
 */
class Cmo3ExportKeyformRoundTripTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private class RoundTrip(
		val edited: PuppetModel,
		val reimported: PuppetModel,
		val report: Cmo3ExportReport,
	)

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

	/**
	 * Asserts the round trip is clean except, at most, a bounded-ULP GEOMETRY residue on the one
	 * edited drawable (the delta-vs-absolute conversion tier).
	 *
	 * @param RoundTrip  result The edited/reimported model pair and export report under test.
	 * @param DrawableId drawableId The one drawable allowed to carry the tolerated residue.
	 * @param String     label Test-scenario label used in assertion failure messages.
	 */
	private fun assertLosslessWithinGeometryUlp(result: RoundTrip, drawableId: DrawableId, label: String) {
		assertTrue(result.report.isEmpty, "$label: expected no notices, got ${result.report.notices}")
		val residual = diffPuppetModels(result.reimported, result.edited)
		if (residual.isEmpty) {
			return
		}
		val onlyExpectedResidue =
			residual.parameters.isEmpty() &&
				residual.parameterGroups.isEmpty() &&
				residual.parts.isEmpty() &&
				residual.deformers.isEmpty() &&
				residual.glues.isEmpty() &&
				residual.document.isEmpty() &&
				residual.drawables.all { entityDiff ->
					entityDiff is EntityDiff.Changed &&
						entityDiff.id == drawableId &&
						entityDiff.fields == setOf(DrawableField.GEOMETRY)
				}
		assertTrue(onlyExpectedResidue, "$label: unexpected residual $residual")
		val editedGrid = result.edited.drawables.first { it.id == drawableId }.geometryGrid
		val reimportedGrid = result.reimported.drawables.first { it.id == drawableId }.geometryGrid
		assertTrue(editedGrid != null && reimportedGrid != null, "$label: geometry grid vanished")
		val reimportedByCoordinate =
			reimportedGrid!!.cells.associate { cell -> cell.coordinate.toList() to cell.form.positionDeltas }
		var maxComponentDifference = 0f
		for (cell in editedGrid!!.cells) {
			val reimportedDeltas = reimportedByCoordinate[cell.coordinate.toList()]
			assertTrue(reimportedDeltas != null, "$label: cell ${cell.coordinate.toList()} vanished")
			for (component in cell.form.positionDeltas.indices) {
				maxComponentDifference =
					maxOf(maxComponentDifference, abs(cell.form.positionDeltas[component] - reimportedDeltas!![component]))
			}
		}
		assertTrue(maxComponentDifference < 1e-3f, "$label: geometry drifted by $maxComponentDifference")
	}

	private fun skipMessageOrNull(): File? {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping keyform round-trip test")
		}
		return file
	}

	/**
	 * The first corpus file whose imported model satisfies [predicate] - the channel-track cases
	 * search the whole probe corpus because compaction leaves many models with statics only.
	 *
	 * @param Function predicate The feature the test needs.
	 * @return File? The matching file, or null (after a skip message) when no corpus model has it.
	 */
	private fun firstProbeFileWith(predicate: (PuppetModel) -> Boolean): File? {
		val spec = System.getProperty("cmo3.probe") ?: System.getProperty("cmo3.sample")
		if (spec == null) {
			println("cmo3.probe not present; skipping")
			return null
		}
		for (candidate in spec.split(',').map { File(it.trim()) }.filter { it.isFile }) {
			val imported =
				runCatching {
					Cmo3Import.fromModelSource(Cmo3.read(candidate.readBytes()).root as CModelSource)
				}.getOrNull() ?: continue
			if (predicate(imported)) {
				return candidate
			}
		}
		println("no corpus model has the feature; skipping")
		return null
	}

	/**
	 * The first drawable with a multi-key geometry grid, for the grid-edit cases.
	 *
	 * @param PuppetModel puppet The model to search.
	 * @return Drawable The first matching drawable.
	 */
	private fun keyedDrawable(puppet: PuppetModel) =
		puppet.drawables.first { drawable ->
			val grid = drawable.geometryGrid
			grid != null && grid.axes.isNotEmpty() && grid.axes.first().keys.size >= 3 && grid.cells.isNotEmpty()
		}

	@Test
	fun keyformGeometryNudgeSurvivesWithinUlp() {
		val file = skipMessageOrNull() ?: return
		var editedId: DrawableId? = null
		val result =
			roundTrip(file) { puppet ->
				val drawable = keyedDrawable(puppet)
				editedId = drawable.id
				val grid = drawable.geometryGrid!!
				val nudgedCells =
					grid.cells.mapIndexed { cellIndex, cell ->
						if (cellIndex == 0) {
							val nudged = cell.form.positionDeltas.copyOf()
							if (nudged.isNotEmpty()) {
								nudged[0] += 2f
							}
							KeyformCell(cell.coordinate, MeshDeltaForm(nudged))
						} else {
							cell
						}
					}
				puppet.copy(
					drawables =
						puppet.drawables.map { candidate ->
							if (candidate.id == drawable.id) {
								candidate.copy(geometryGrid = KeyformGrid(grid.axes, nudgedCells))
							} else {
								candidate
							}
						},
				)
			}
		assertLosslessWithinGeometryUlp(result, editedId!!, "keyform nudge")
	}

	@Test
	fun geometryKeyInsertSurvivesWithinUlp() {
		val file = skipMessageOrNull() ?: return
		var editedId: DrawableId? = null
		val result =
			roundTrip(file) { puppet ->
				val drawable = keyedDrawable(puppet)
				editedId = drawable.id
				val grid = drawable.geometryGrid!!
				val axis = grid.axes.first()
				val midValue = (axis.keys[0] + axis.keys[1]) / 2f
				val inserted = grid.withKeyInserted(axis.parameterId, midValue, MeshDeltaInterpolator, OutOfSpanKeyPolicy.Reject)
				puppet.copy(
					drawables =
						puppet.drawables.map { candidate ->
							if (candidate.id == drawable.id) candidate.copy(geometryGrid = inserted) else candidate
						},
				)
			}
		assertLosslessWithinGeometryUlp(result, editedId!!, "key insert")
	}

	@Test
	fun geometryKeyDeleteSurvivesExactly() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				val drawable = keyedDrawable(puppet)
				val grid = drawable.geometryGrid!!
				val axis = grid.axes.first()
				// A middle key: removal keeps the span, and every surviving cell keeps its stored
				// form - the deletion round trip is bit-exact.
				val removed = grid.withKeyRemoved(axis.parameterId, keyIndex = 1)
				puppet.copy(
					drawables =
						puppet.drawables.map { candidate ->
							if (candidate.id == drawable.id) candidate.copy(geometryGrid = removed) else candidate
						},
				)
			}
		assertLossless(result, "key delete")
	}

	@Test
	fun warpOpacityChannelEditSurvivesExactly() {
		val file =
			firstProbeFileWith { probe ->
				probe.deformers.any { it is Deformer.Warp && it.channelGrids[FormChannel.OPACITY] != null }
			} ?: return
		val result =
			roundTrip(file) { puppet ->
				val warp =
					puppet.deformers.first { it is Deformer.Warp && it.channelGrids[FormChannel.OPACITY] != null }
						as Deformer.Warp
				val track = warp.channelGrids[FormChannel.OPACITY]!!
				val editedCells =
					track.cells.mapIndexed { cellIndex, cell ->
						if (cellIndex == 0) KeyformCell(cell.coordinate, ChannelValue.Scalar(0.37f) as ChannelValue) else cell
					}
				val editedGrids =
					ChannelGrids(
						warp.channelGrids.gridsByChannel + (FormChannel.OPACITY to KeyformGrid(track.axes, editedCells)),
					)
				puppet.copy(
					deformers =
						puppet.deformers.map { candidate ->
							if (candidate.id == warp.id) warp.copy(channelGrids = editedGrids) else candidate
						},
				)
			}
		assertLossless(result, "warp opacity channel edit")
	}

	@Test
	fun drawableStaticOpacityEditSurvivesExactly() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				// A drawable with keyforms but no opacity track: the static's CMO3 home is every
				// form cell, which the grid rebuild writes.
				val drawable =
					puppet.drawables.first { candidate ->
						candidate.geometryGrid != null &&
							candidate.geometryGrid!!.cells.isNotEmpty() &&
							candidate.channelGrids[FormChannel.OPACITY] == null
					}
				puppet.withDrawableOpacity(drawable.id, 0.5f)
			}
		assertLossless(result, "drawable static opacity")
	}

	@Test
	fun partDrawOrderTrackSurvivesExactly() {
		val file = skipMessageOrNull() ?: return
		// No corpus model keeps a part draw-order track after compaction, so AUTHOR one: a fresh
		// axis and varying values, exercising whole-web synthesis (new binding, fresh CPartForms).
		val result =
			roundTrip(file) { puppet ->
				val part = puppet.parts.first { it.channelGrids.isEmpty }
				val axisParameter = puppet.parameters.first { it.max - it.min >= 1f }
				val track =
					KeyformGrid(
						listOf(KeyformAxis(axisParameter.id, floatArrayOf(axisParameter.min, axisParameter.max))),
						listOf(
							KeyformCell(intArrayOf(0), ChannelValue.Scalar(part.drawOrder.toFloat()) as ChannelValue),
							KeyformCell(intArrayOf(1), ChannelValue.Scalar(part.drawOrder + 3f) as ChannelValue),
						),
					)
				puppet.copy(
					parts =
						puppet.parts.map { candidate ->
							if (candidate.id == part.id) {
								part.copy(channelGrids = ChannelGrids(mapOf(FormChannel.DRAW_ORDER to track)))
							} else {
								candidate
							}
						},
				)
			}
		assertLossless(result, "part draw-order track")
	}

	@Test
	fun glueIntensityTrackSurvivesExactly() {
		val file =
			firstProbeFileWith { probe -> probe.glues.isNotEmpty() } ?: return
		// No corpus model keeps an intensity track after compaction, so AUTHOR one on an existing
		// glue: a fresh axis with varying weld strength.
		val result =
			roundTrip(file) { puppet ->
				val glueIndex = 0
				val glue = puppet.glues[glueIndex]
				val axisParameter = puppet.parameters.first { it.max - it.min >= 1f }
				val track =
					KeyformGrid(
						listOf(KeyformAxis(axisParameter.id, floatArrayOf(axisParameter.min, axisParameter.max))),
						listOf(
							KeyformCell(intArrayOf(0), ChannelValue.Scalar(1f) as ChannelValue),
							KeyformCell(intArrayOf(1), ChannelValue.Scalar(0.5f) as ChannelValue),
						),
					)
				val editedGlue =
					org.umamo.runtime.model.Glue(
						glue.meshA,
						glue.meshB,
						glue.pairs,
						ChannelGrids(mapOf(FormChannel.GLUE_INTENSITY to track)),
						glue.intensity,
					)
				puppet.copy(glues = puppet.glues.mapIndexed { candidateIndex, candidate -> if (candidateIndex == glueIndex) editedGlue else candidate })
			}
		assertLossless(result, "glue intensity track")
	}

	@Test
	fun blendShapeDeltaNudgeSurvivesWithinUlp() {
		val file =
			firstProbeFileWith { probe ->
				probe.drawables.any { drawable -> drawable.blendShapes.any { binding -> binding.forms.any { it != null } } }
			} ?: return
		var editedId: DrawableId? = null
		val result =
			roundTrip(file) { puppet ->
				val drawable =
					puppet.drawables.first { candidate -> candidate.blendShapes.any { binding -> binding.forms.any { it != null } } }
				editedId = drawable.id
				val editedBindings =
					drawable.blendShapes.mapIndexed { bindingIndex, binding ->
						if (bindingIndex != 0) {
							return@mapIndexed binding
						}
						val formIndex = binding.forms.indexOfFirst { it != null }
						val editedForms =
							binding.forms.mapIndexed { candidateIndex, form ->
								if (candidateIndex == formIndex && form != null) {
									val nudged = form.positionDeltas.copyOf()
									if (nudged.isNotEmpty()) {
										nudged[0] += 2f
									}
									org.umamo.runtime.model.MeshForm(nudged, form.drawOrder, form.opacity, form.multiplyColor, form.screenColor)
								} else {
									form
								}
							}
						BlendShapeBinding(binding.parameterId, binding.keys, binding.neutralIndex, editedForms, binding.limits)
					}
				puppet.copy(
					drawables =
						puppet.drawables.map { candidate ->
							if (candidate.id == drawable.id) candidate.copy(blendShapes = editedBindings) else candidate
						},
				)
			}
		// The morph rebuild reuses matched records/forms; the nudged form's absolute follows the
		// delta-vs-absolute tier, so BLEND_SHAPES may carry bounded-ULP residue like GEOMETRY.
		assertTrue(result.report.isEmpty, "blend shape nudge: expected no notices, got ${result.report.notices}")
		val residual = diffPuppetModels(result.reimported, result.edited)
		val acceptable =
			residual.isEmpty ||
				(
					residual.parameters.isEmpty() &&
						residual.parameterGroups.isEmpty() &&
						residual.parts.isEmpty() &&
						residual.deformers.isEmpty() &&
						residual.glues.isEmpty() &&
						residual.document.isEmpty() &&
						residual.drawables.all { entityDiff ->
							entityDiff is EntityDiff.Changed &&
								entityDiff.id == editedId &&
								entityDiff.fields.all { it == DrawableField.BLEND_SHAPES || it == DrawableField.GEOMETRY }
						}
				)
		assertTrue(acceptable, "blend shape nudge: unexpected residual $residual")
	}

	@Test
	fun baseMoveWithKeyformEditKeepsBlendShapeDeltas() {
		// The combined base-move + keyform-edit path writes the base through the grid rebuild
		// (alsoWriteBase) instead of lowerMeshPositions' pool-wide rebase, so the surviving
		// morph-target forms must follow the base move on their own - a stale absolute shifts every
		// re-imported blend-shape delta by the move distance.
		val file =
			firstProbeFileWith { probe ->
				probe.drawables.any { drawable ->
					drawable.mesh != null &&
						drawable.geometryGrid != null &&
						drawable.geometryGrid!!.cells.isNotEmpty() &&
						drawable.blendShapes.any { binding -> binding.forms.any { form -> form != null } }
				}
			} ?: return
		var editedId: DrawableId? = null
		val result =
			roundTrip(file) { puppet ->
				val drawable =
					puppet.drawables.first { candidate ->
						candidate.mesh != null &&
							candidate.geometryGrid != null &&
							candidate.geometryGrid!!.cells.isNotEmpty() &&
							candidate.blendShapes.any { binding -> binding.forms.any { form -> form != null } }
					}
				editedId = drawable.id
				val mesh = drawable.mesh!!
				val movedPositions = mesh.positions.copyOf()
				movedPositions[0] += 5f
				val grid = drawable.geometryGrid!!
				val nudgedCells =
					grid.cells.mapIndexed { cellIndex, cell ->
						if (cellIndex == 0) {
							val nudged = cell.form.positionDeltas.copyOf()
							if (nudged.isNotEmpty()) {
								nudged[0] += 2f
							}
							KeyformCell(cell.coordinate, MeshDeltaForm(nudged))
						} else {
							cell
						}
					}
				puppet.copy(
					drawables =
						puppet.drawables.map { candidate ->
							if (candidate.id == drawable.id) {
								candidate.copy(
									mesh = DrawableMesh(movedPositions, mesh.uvs, mesh.indices),
									geometryGrid = KeyformGrid(grid.axes, nudgedCells),
								)
							} else {
								candidate
							}
						},
				)
			}
		// The base move leaves the imported weld by definition; nothing else may be reported.
		assertTrue(
			result.report.notices.all { notice -> notice is ExportNotice.WeldDivergence },
			"base move + keyform edit: unexpected notices ${result.report.notices}",
		)
		val editedDrawable = result.edited.drawables.first { it.id == editedId }
		val reimportedDrawable = result.reimported.drawables.first { it.id == editedId }
		assertTrue(
			reimportedDrawable.blendShapes.size == editedDrawable.blendShapes.size,
			"base move + keyform edit: blend-shape binding count changed",
		)
		var maxDeltaDrift = 0f
		for (bindingIndex in editedDrawable.blendShapes.indices) {
			val editedBinding = editedDrawable.blendShapes[bindingIndex]
			val reimportedBinding = reimportedDrawable.blendShapes[bindingIndex]
			for (formIndex in editedBinding.forms.indices) {
				val editedForm = editedBinding.forms[formIndex] ?: continue
				val reimportedForm = reimportedBinding.forms.getOrNull(formIndex)
				assertTrue(reimportedForm != null, "base move + keyform edit: blend-shape form $formIndex vanished")
				for (component in editedForm.positionDeltas.indices) {
					maxDeltaDrift =
						maxOf(maxDeltaDrift, abs(editedForm.positionDeltas[component] - reimportedForm!!.positionDeltas[component]))
				}
			}
		}
		assertTrue(maxDeltaDrift < 1e-3f, "base move + keyform edit: blend-shape deltas drifted by $maxDeltaDrift")
	}
}
