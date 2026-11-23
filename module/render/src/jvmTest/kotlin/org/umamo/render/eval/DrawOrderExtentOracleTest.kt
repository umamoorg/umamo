package org.umamo.render.eval

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.model.RenderOrderChild
import org.umamo.testkit.requireSample
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential gate on the render-order group draw-order extent (MOC3.md §5.6, sections 84/85).
 *
 * The corpus cannot reach this by itself.  In every sample, each group's extreme draw orders are
 * already held by some child's first keyform, so a fold over first keyforms and a fold over every
 * keyform write the same bytes, and the byte-exact lowering gate passes either way.  This gate makes
 * the difference observable: per sample it re-keys one part child and one drawable child so that
 * only the child's last keyform leaves the range its group spans, writes the document, and poses
 * the runtime on that keyform.
 *
 * It asserts the two things the runtime gets wrong when the stored extent does not cover the posed
 * draw order: no two drawables share a render order, and the re-keyed child sorts past all of its
 * siblings.  A file the runtime cannot update at all fails as a dump error.
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + `moc3.samples`.
 */
class DrawOrderExtentOracleTest {
	/**
	 * One re-keyed child: the edited document, the pose that reaches the edit, and the drawables whose
	 * relative order the edit decides.
	 */
	private class ExtentCase(
		val label: String,
		val document: MocDocument,
		val pose: Map<String, Float>,
		val movedDrawables: Set<String>,
		val siblingDrawables: Set<String>,
		val sortsAbove: Boolean,
	)

	/** The ids of every drawable in render-order group [groupIndex]'s subtree. */
	private fun drawablesUnder(document: MocDocument, groupIndex: Int): List<String> =
		document.renderOrderGroups[groupIndex].children.flatMap { child -> drawablesOf(document, child) }

	/** The ids of the drawables [child] stands for: itself, or its sub-group's whole subtree. */
	private fun drawablesOf(document: MocDocument, child: RenderOrderChild): List<String> =
		if (child.kind == 0) {
			listOf(document.artMeshes[child.index].id)
		} else {
			drawablesUnder(document, child.groupIndex)
		}

	/** The draw order of [child] at each keyform of its grid. */
	private fun drawOrdersOf(document: MocDocument, child: RenderOrderChild): List<Float> =
		if (child.kind == 0) {
			document.artMeshes[child.index].keyforms.map { keyform -> keyform.drawOrder }
		} else {
			document.parts[child.index].drawOrderKeyforms.toList()
		}

	/** [document] with [child]'s last keyform moved to [drawOrder]. */
	private fun withLastKeyformAt(document: MocDocument, child: RenderOrderChild, drawOrder: Float): MocDocument {
		val parts =
			if (child.kind == 0) {
				document.parts
			} else {
				document.parts.mapIndexed { partIndex, part ->
					if (partIndex == child.index) {
						part.copy(drawOrderKeyforms = part.drawOrderKeyforms.copyOf().also { orders -> orders[orders.lastIndex] = drawOrder })
					} else {
						part
					}
				}
			}
		val artMeshes =
			if (child.kind != 0) {
				document.artMeshes
			} else {
				document.artMeshes.mapIndexed { meshIndex, mesh ->
					if (meshIndex == child.index) {
						mesh.copy(
							keyforms =
								mesh.keyforms.mapIndexed { keyformIndex, keyform ->
									if (keyformIndex == mesh.keyforms.lastIndex) keyform.copy(drawOrder = drawOrder) else keyform
								},
						)
					} else {
						mesh
					}
				}
			}

		return MocDocument(
			version = document.version,
			canvas = document.canvas,
			parameters = document.parameters,
			keyformBindings = document.bindings.associateBy { binding -> binding.index },
			parts = parts,
			deformers = document.deformers,
			artMeshes = artMeshes,
			glues = document.glues,
			renderOrderGroups = document.renderOrderGroups,
			blendShapes = document.blendShapes,
			offscreens = document.offscreens,
			keyPositionsHasParameterUnion = document.keyPositionsHasParameterUnion,
		)
	}

	/**
	 * The first child of [kind] in [document] that can be re-keyed out of its group's range, or null.
	 *
	 * A usable child is keyed on axes that do not repeat (exactly max wraps to min on a repeating one,
	 * so the pose would miss the last keyform), has siblings to sort against, and sits in a group
	 * with room left on the 0-1000 draw-order scale.
	 */
	private fun caseFor(document: MocDocument, kind: Int): ExtentCase? {
		for (group in document.renderOrderGroups) {
			val groupOrders = group.children.flatMap { child -> drawOrdersOf(document, child) }

			for (child in group.children) {
				if (child.kind != kind) {
					continue
				}

				val orders = drawOrdersOf(document, child)
				val bindingIndex =
					if (kind == 0) document.artMeshes[child.index].keyformBindingIndex else document.parts[child.index].keyformBindingIndex
				val binding = document.keyformBinding(bindingIndex) ?: continue

				if (orders.size < 2 || binding.gridSize != orders.size) {
					continue
				}

				if (binding.axes.any { axis -> document.parameters[axis.parameterIndex].repeats }) {
					continue
				}

				val moved = drawablesOf(document, child).toSet()
				val siblings = group.children.filter { sibling -> sibling !== child }.flatMap { sibling -> drawablesOf(document, sibling) }.toSet()

				if (moved.isEmpty() || siblings.isEmpty()) {
					continue
				}

				val sortsAbove = groupOrders.max() + DRAW_ORDER_STEP <= HIGHEST_DRAW_ORDER
				val drawOrder = if (sortsAbove) groupOrders.max() + DRAW_ORDER_STEP else groupOrders.min() - DRAW_ORDER_STEP

				if (drawOrder < 0f) {
					continue
				}

				val name = if (kind == 0) document.artMeshes[child.index].id else document.parts[child.index].id

				return ExtentCase(
					label = "${if (kind == 0) "drawable" else "part"} $name keyed to $drawOrder",
					document = withLastKeyformAt(document, child, drawOrder),
					pose = binding.axes.associate { axis -> document.parameters[axis.parameterIndex].id to axis.keyPositions.last() },
					movedDrawables = moved,
					siblingDrawables = siblings,
					sortsAbove = sortsAbove,
				)
			}
		}

		return null
	}

	@Test
	fun aChildKeyedPastItsGroupsFirstKeyformsStillSortsInTheRuntime() {
		val dumpModel = requireSample("relive.dumpModel")
		val runtimeLib = requireSample("relive.coreLib")
		val samples =
			requireSample("moc3.samples")
				.walkTopDown()
				.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
				.sortedBy { it.name }
				.toList()

		if (samples.isEmpty()) {
			skipTest("no moc3 samples under -Dmoc3.samples")
		}

		val outputDir = File("build/draw-order-extent").apply { mkdirs() }
		val failures = ArrayList<String>()
		var posedParts = 0
		var posedDrawables = 0

		for (mocFile in samples) {
			val source = runCatching { Moc3.read(mocFile.readBytes()) }.getOrNull() ?: continue

			for (kind in listOf(1, 0)) {
				val case = caseFor(source, kind) ?: continue
				val where = "${mocFile.name}: ${case.label} at ${case.pose}"
				val written = File(outputDir, "${mocFile.nameWithoutExtension}-kind$kind.moc3").apply { writeBytes(Moc3.write(case.document)) }
				val dump = runCatching { runOracleDump(dumpModel, runtimeLib, written, case.pose) }
				written.delete()

				if (kind == 0) {
					posedDrawables++
				} else {
					posedParts++
				}

				val entries = dump.getOrNull()?.entries

				if (entries == null) {
					// Only the first line: the rest of the message is the dump's own output.
					failures.add("$where: ${dump.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()}")

					continue
				}

				val shared =
					entries.entries
						.groupBy({ entry -> entry.value.renderOrder }, { entry -> entry.key })
						.filterValues { drawableIds -> drawableIds.size > 1 }

				if (shared.isNotEmpty()) {
					failures.add("$where: drawables share a render order, e.g. ${shared.entries.take(3)}")
				}

				// A drawable the runtime did not evaluate at this pose carries no sort key to compare.
				val movedOrders = case.movedDrawables.mapNotNull { id -> entries[id]?.takeUnless(::oracleNeverEvaluated)?.renderOrder }
				val siblingOrders = case.siblingDrawables.mapNotNull { id -> entries[id]?.takeUnless(::oracleNeverEvaluated)?.renderOrder }

				if (movedOrders.isEmpty() || siblingOrders.isEmpty()) {
					continue
				}

				val sortsPastSiblings =
					if (case.sortsAbove) movedOrders.min() > siblingOrders.max() else movedOrders.max() < siblingOrders.min()

				if (!sortsPastSiblings) {
					failures.add(
						"$where: the child does not sort ${if (case.sortsAbove) "above" else "below"} its siblings " +
							"(its render orders ${movedOrders.min()}..${movedOrders.max()}, theirs ${siblingOrders.min()}..${siblingOrders.max()})",
					)
				}
			}
		}

		println("[oracle] draw-order extent: $posedParts part cases, $posedDrawables drawable cases, ${failures.size} failures")
		assertTrue(posedParts > 0, "no corpus model had a keyed part to re-key")
		assertTrue(posedDrawables > 0, "no corpus model had a keyed drawable to re-key")
		assertTrue(failures.isEmpty(), "the runtime mis-sorts a child keyed past its group's first keyforms:\n" + failures.joinToString("\n"))
	}

	private companion object {
		/** How far past its group's range the re-keyed keyform lands. */
		const val DRAW_ORDER_STEP: Float = 10f

		/** The top of the draw-order scale. */
		const val HIGHEST_DRAW_ORDER: Float = 1000f
	}
}