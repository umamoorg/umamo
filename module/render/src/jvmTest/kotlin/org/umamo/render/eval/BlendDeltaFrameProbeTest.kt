package org.umamo.render.eval

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.model.MeshForm
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Determines what the baked MOC3 blend-shape deltas are relative to, by joining Model A's ingested
 * mesh bindings against Model A's stored delta rows and testing two hypotheses per non-neutral key:
 *
 *   H_SETUP: stored delta = authoredForm - restMesh  (the ingested MeshForm.positionDeltas verbatim)
 *   H_GRID:  stored delta = authoredForm - gridFormInterpolatedAtDefaultPose  (the same multilinear
 *            interpolation the runtime performs, sampled with every parameter at its default)
 *
 * The hypotheses coincide on drawables whose default-pose grid form equals the rest mesh; only
 * records where they differ discriminate. Units: MOC3 deltas are model space; the origin cancels
 * in a delta, so the conversion is x/ppu with Y components sign-flipped.
 *
 * MEASURED VERDICT (Model A, 16 discriminating records): H_SETUP is refuted outright (errors 20-50x
 * larger than H_GRID). H_GRID holds to first order, but a residual remains on warp-parented
 * meshes whose parent warp carries a morph on the same driving parameter - antisymmetric in the
 * key (e.g. ArtMesh80 comp 128: implied reference deviates +-147 px at keys -+10 while the
 * authored forms there are equal), consistent with the baker pre-compensating the mesh delta
 * through the morphed parent warp. The assert below is therefore DIRECTIONAL (grid beats setup
 * on every discriminating record); the posed oracle quantifies the coupling end to end.
 *
 * Skips gracefully when the Model A corpus pair is absent.
 */
class BlendDeltaFrameProbeTest {
	private val modelACmo3: File? =
		System.getProperty("cmo3.probe")?.split(',')?.map(::File)
			?.firstOrNull { it.isFile && it.name.startsWith("modelA") }
	private val modelAMoc3: File? =
		System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }
			?.walkTopDown()?.firstOrNull { it.isFile && it.name == "modelA.moc3" }

	@Test
	fun storedMeshDeltasIdentifyTheirReferenceFrame() {
		val cmo3File = modelACmo3
		val moc3File = modelAMoc3
		if (cmo3File == null || moc3File == null) {
			println("Model A corpus pair not present; skipping delta-frame probe")
			return
		}
		val root = Cmo3.read(cmo3File).root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)
		val model = MocCodec.read(moc3File.readBytes())
		val document = Moc3.decode(model)
		val pixelsPerUnit = model.canvasInfo?.pixelsPerUnit ?: error("no canvas info")
		val positionValues = model.sections.floatArray(Section.KEYFORM_POSITION_VALUES)
		val meshPositionIndex = model.sections.intArray(Section.KEYFORM_POSITION_INDEX)
		val defaultByParameter = puppet.parameters.associate { it.id to it.default }

		var discriminating = 0
		var setupWins = 0
		var gridWins = 0
		var coinciding = 0
		var worstGridError = 0f

		for (record in document.blendShapes) {
			if (record.target != BlendShapeTarget.ART_MESH) {
				continue
			}
			val meshId = document.artMeshes[record.targetIndex].id
			val parameterId = document.parameters[record.parameterIndex].id
			val drawable = puppet.drawables.firstOrNull { it.id.raw == meshId } ?: continue
			val binding = drawable.blendShapes.firstOrNull { it.parameterId.raw == parameterId } ?: continue
			val base = drawable.mesh?.positions ?: continue

			// The grid form at the DEFAULT pose, multilinearly interpolated (null when ungridded).
			val gridAtDefault =
				drawable.keyforms?.let { grid ->
					gridCorners(grid) { queried -> defaultByParameter[queried] ?: 0f }
						?.let { corners -> blendLocalFromCorners(grid, base, corners) }
				}

			for (keyIndex in binding.keys.indices) {
				if (keyIndex == binding.neutralIndex) {
					continue
				}
				val authoredDeltas = (binding.forms[keyIndex] as? MeshForm)?.positionDeltas ?: continue
				val stored = meshPositionIndex[record.recordBase + keyIndex]

				var setupError = 0f
				var gridError = 0f
				var hypothesisGap = 0f
				for (componentIndex in authoredDeltas.indices) {
					// Canvas -> model: divide by ppu; Y components (odd indices) flip sign.
					val sign = if (componentIndex % 2 == 1) -1f else 1f
					val setupDelta = sign * authoredDeltas[componentIndex] / pixelsPerUnit
					val gridReference =
						gridAtDefault?.let { it[componentIndex] - base[componentIndex] } ?: 0f
					val gridDelta = sign * (authoredDeltas[componentIndex] - gridReference) / pixelsPerUnit
					val storedValue = positionValues[stored + componentIndex]
					setupError = maxOf(setupError, abs(storedValue - setupDelta))
					gridError = maxOf(gridError, abs(storedValue - gridDelta))
					hypothesisGap = maxOf(hypothesisGap, abs(setupDelta - gridDelta))
				}

				val tolerance = 2e-3f
				if (hypothesisGap <= tolerance) {
					coinciding++
				} else {
					discriminating++
					worstGridError = maxOf(worstGridError, gridError)
					if (setupError < gridError) {
						setupWins++
					}
					if (gridError < setupError) {
						gridWins++
					}
					println(
						"[probe] $meshId/$parameterId key=${binding.keys[keyIndex]} " +
							"setupErr=$setupError gridErr=$gridError gap=$hypothesisGap",
					)
					// Diagnostic: the reference frame the FILE implies (authored - stored, canvas
					// units) vs the grid-at-default hypothesis, at the worst-residual component.
					var worstComponent = 0
					var worstResidual = 0f
					for (componentIndex in authoredDeltas.indices) {
						val sign = if (componentIndex % 2 == 1) -1f else 1f
						val impliedReference =
							authoredDeltas[componentIndex] - sign * positionValues[stored + componentIndex] * pixelsPerUnit
						val gridReference = gridAtDefault?.let { it[componentIndex] - base[componentIndex] } ?: 0f
						val residual = abs(impliedReference - gridReference)
						if (residual > worstResidual) {
							worstResidual = residual
							worstComponent = componentIndex
						}
					}
					val sign = if (worstComponent % 2 == 1) -1f else 1f
					val impliedReference =
						authoredDeltas[worstComponent] - sign * positionValues[stored + worstComponent] * pixelsPerUnit
					val gridReference = gridAtDefault?.let { it[worstComponent] - base[worstComponent] } ?: 0f
					println(
						"[probe]   worst comp=$worstComponent (${if (worstComponent % 2 == 0) "x" else "y"}) " +
							"authoredDelta=${authoredDeltas[worstComponent]} impliedRef=$impliedReference " +
							"gridRef=$gridReference residualPx=$worstResidual",
					)
				}
			}
		}
		println(
			"[probe] E5: discriminating=$discriminating (setup=$setupWins grid=$gridWins) " +
				"coinciding=$coinciding worstGridErr=$worstGridError",
		)
		assertTrue(discriminating + coinciding > 0, "Model A should yield joinable mesh records")
		if (discriminating > 0) {
			assertTrue(
				gridWins == discriminating,
				"grid-at-default should beat the setup frame on every discriminating record " +
					"(setup=$setupWins grid=$gridWins)",
			)
		}
	}
}
