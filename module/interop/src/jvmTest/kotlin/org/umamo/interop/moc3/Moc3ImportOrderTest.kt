package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the orderings the MOC3 import derives from its own internal sequence.
 *
 * Panel order - the parts panel's top-to-bottom stacking - is not stored in a `.moc3` at all.  The import
 * RECONSTRUCTS it by walking the render tree and reversing the leaf sequence, and then two separate
 * results read that reconstruction: the flat drawable list and every org-tree child list.  So the import
 * has a real ordering contract, render tree BEFORE panel indices BEFORE the org tree, and breaking it
 * throws nothing: the panel indices simply come back empty and every drawable ranks `Int.MAX_VALUE`,
 * which yields a different, entirely plausible order.
 *
 * Nothing else notices.  `Moc3ExportRoundTripTest` compares by id precisely so that index churn cannot
 * red it; `Moc3ImportTest` asserts each drawable is placed in the org tree exactly once, not where.  This
 * is the gate that would fail if a decomposition ran those three passes in the wrong order.
 *
 * Skips gracefully without samples, so it covers nothing on CI - the corpus is gitignored.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class Moc3ImportOrderTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir
			?.walkTopDown()
			// work/ holds our own bake outputs - same models, no new coverage.
			?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
			?.sortedBy { it.name }
			?.toList()
			.orEmpty()

	@Test
	fun theFlatDrawableListIsTheRenderTreesLeafOrder() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping import order gate")
			return
		}
		val failures = ArrayList<String>()
		var covered = 0
		var reordered = 0
		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))
			// A document with no render-order groups reconstructs no panel order at all - the import falls
			// back to deriving a tree from the org tree, which is a different contract than this one.
			if (source.renderOrderGroups.isEmpty()) {
				continue
			}
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			covered++

			val flatOrder = puppet.drawables.map { drawable -> drawable.id }
			val leafOrder = leavesOf(puppet.renderRoot)
			if (flatOrder != leafOrder) {
				failures.add(
					"${file.name}: the flat drawable list is not the render tree's leaf order " +
						"(${firstDivergence(flatOrder, leafOrder)})",
				)
			}

			// THE discriminating count.  When the render tree happens to place drawables in file order the
			// invariant above also holds for an import that reconstructed nothing at all, because an empty
			// panel-index map leaves the stable sort on file order.  Only a model whose leaf order differs
			// from its file order can tell the two apart.
			val fileOrder = source.artMeshes.map { artMesh -> DrawableId(artMesh.id) }
			if (leafOrder != fileOrder) {
				reordered++
			}
		}
		assertTrue(covered > 0, "no corpus model carries render-order groups")
		assertTrue(
			reordered > 0,
			"every model in this run renders in file order - a reconstruction that produced nothing would pass too",
		)
		println("[import-order] $covered models with render groups, $reordered where leaf order differs from file order")
		assertEquals(emptyList(), failures.take(25), "the flat drawable order diverged from the render tree")
	}

	@Test
	fun orgTreeChildrenFollowPanelOrder() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping org-tree order gate")
			return
		}
		val failures = ArrayList<String>()
		var covered = 0
		var multiChildContainers = 0
		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))
			if (source.renderOrderGroups.isEmpty()) {
				continue
			}
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			covered++

			// The org tree sorts front-to-back and the flat list back-to-front, so one is the other
			// reversed - both being views of the same reconstructed panel index.
			val panelRank =
				puppet.drawables
					.asReversed()
					.withIndex()
					.associate { (rank, drawable) -> drawable.id to rank }
			val partRanks = HashMap<PartId, Int>()

			for ((containerName, children) in containersOf(puppet)) {
				if (children.size > 1) {
					multiChildContainers++
				}
				var previousRank = Int.MIN_VALUE
				for (child in children) {
					val rank =
						when (child) {
							is OrgChild.Drawable -> panelRank[child.id] ?: Int.MAX_VALUE
							is OrgChild.Part -> partRankOf(puppet, child.id, panelRank, partRanks, HashSet())
						}
					if (rank < previousRank) {
						failures.add(
							"${file.name}: $containerName lists $child at panel rank $rank after $previousRank - " +
								"its children are not in panel order",
						)
						break
					}
					previousRank = rank
				}
			}
		}
		assertTrue(covered > 0, "no corpus model carries render-order groups")
		// A tree of single-child containers is sorted no matter what the sort did.
		assertTrue(multiChildContainers > 0, "no container in this run had two children to order")
		println("[import-order] $covered models, $multiChildContainers containers with more than one child")
		assertEquals(emptyList(), failures.take(25), "org-tree children diverged from panel order")
	}

	/**
	 * Every ordered child list in [puppet]'s org tree: the root's, then one per part.
	 *
	 * @param PuppetModel puppet The imported rig.
	 * @return List<Pair<String, List<OrgChild>>> Each container's label and its children.
	 */
	private fun containersOf(puppet: PuppetModel): List<Pair<String, List<OrgChild>>> =
		listOf("root" to puppet.rootChildren) + puppet.parts.map { part -> "part ${part.id.raw}" to part.children }

	/**
	 * A part's panel rank: the minimum over every drawable in its subtree.
	 *
	 * Memoized into [partRanks], and guarded by [visiting] so a malformed parent cycle - which the import
	 * normalizes but this walk would otherwise follow - terminates.
	 *
	 * @param PuppetModel puppet    The imported rig.
	 * @param PartId      partId    The part to rank.
	 * @param Map         panelRank Each drawable's panel rank.
	 * @param MutableMap  partRanks The memo table.
	 * @param MutableSet  visiting  The parts already on this walk's stack.
	 * @return Int The rank, or Int.MAX_VALUE when the subtree holds no drawable.
	 */
	private fun partRankOf(
		puppet: PuppetModel,
		partId: PartId,
		panelRank: Map<DrawableId, Int>,
		partRanks: MutableMap<PartId, Int>,
		visiting: MutableSet<PartId>,
	): Int {
		partRanks[partId]?.let { cached ->
			return cached
		}
		if (!visiting.add(partId)) {
			return Int.MAX_VALUE
		}
		var minimum = Int.MAX_VALUE
		for (child in puppet.partById[partId]?.children.orEmpty()) {
			val rank =
				when (child) {
					is OrgChild.Drawable -> panelRank[child.id] ?: Int.MAX_VALUE
					is OrgChild.Part -> partRankOf(puppet, child.id, panelRank, partRanks, visiting)
				}
			if (rank < minimum) {
				minimum = rank
			}
		}
		visiting.remove(partId)
		partRanks[partId] = minimum
		return minimum
	}

	/**
	 * The render tree's drawable leaves, depth-first - the back-to-front draw sequence.
	 *
	 * @param RenderNode node The subtree root.
	 * @return List<DrawableId> The leaves, in order.
	 */
	private fun leavesOf(node: RenderNode): List<DrawableId> {
		val leaves = ArrayList<DrawableId>()

		fun walk(current: RenderNode) {
			when (current) {
				is RenderDrawable -> leaves.add(current.id)
				is RenderGroup -> current.children.forEach(::walk)
			}
		}
		walk(node)
		return leaves
	}

	/**
	 * Where two id sequences first differ, for a failure message that names the position rather than
	 * dumping two lists of several hundred ids.
	 *
	 * @param List<DrawableId> actual   The sequence under test.
	 * @param List<DrawableId> expected The sequence it should equal.
	 * @return String A one-line description of the first divergence.
	 */
	private fun firstDivergence(
		actual: List<DrawableId>,
		expected: List<DrawableId>,
	): String {
		if (actual.size != expected.size) {
			return "${actual.size} drawables vs ${expected.size} leaves"
		}
		val position = actual.indices.firstOrNull { index -> actual[index] != expected[index] } ?: return "same order"
		return "at $position: ${actual[position].raw} vs ${expected[position].raw}"
	}
}