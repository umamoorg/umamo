package org.umamo.interop.moc3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.interop.cmo3.import.Cmo3Import
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * A part with no drawable under it has no panel index, so the import places it by the part table, which
 * lists a parent's sub-parts in their panel order.  Two gates hold that: sibling parts follow the part
 * table, and on the corpus twins (the same model as `.cmo3` and as the editor's bake) they follow the
 * editor's own parts tree.
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
			skipTest("moc3.samples not present; skipping import order gate")
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
			skipTest("moc3.samples not present; skipping org-tree order gate")
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

					// A part with no drawable under it has no panel rank; the part table places it, and
					// orgTreeSiblingPartsFollowThePartTable checks that.
					if (rank == Int.MAX_VALUE) {
						continue
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

	@Test
	fun orgTreeSiblingPartsFollowThePartTable() {
		val files = samples()

		if (files.isEmpty()) {
			skipTest("moc3.samples not present; skipping sibling part order gate")
		}

		val failures = ArrayList<String>()
		var covered = 0
		var anchoredEmptyParts = 0

		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))

			if (source.renderOrderGroups.isEmpty()) {
				continue
			}

			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			covered++
			// The import keeps its part records in file order, so a record's position is its part-table index.
			val fileIndexByPart = puppet.parts.withIndex().associate { (fileIndex, part) -> part.id to fileIndex }
			val panelRank =
				puppet.drawables
					.asReversed()
					.withIndex()
					.associate { (rank, drawable) -> drawable.id to rank }
			val partRanks = HashMap<PartId, Int>()

			for ((containerName, children) in containersOf(puppet)) {
				val siblingParts = children.filterIsInstance<OrgChild.Part>()
				val fileIndices = siblingParts.map { child -> fileIndexByPart.getValue(child.id) }

				if (fileIndices != fileIndices.sorted()) {
					failures.add(
						"${file.name}: $containerName lists its sub-parts as ${siblingParts.map { child -> child.id.raw }}, " +
							"not in part-table order",
					)
				}

				// THE discriminating count: an empty part with a ranked sibling part after it in the part table
				// is exactly what a plain sort by panel rank would move to the end.  Counted in table order, not
				// in the import's, which is the order under test.
				val ranks =
					siblingParts
						.sortedBy { child -> fileIndexByPart.getValue(child.id) }
						.map { child -> partRankOf(puppet, child.id, panelRank, partRanks, HashSet()) }
				anchoredEmptyParts +=
					ranks.indices.count { position ->
						ranks[position] == Int.MAX_VALUE && ranks.drop(position + 1).any { rank -> rank != Int.MAX_VALUE }
					}
			}
		}

		assertTrue(covered > 0, "no corpus model carries render-order groups")
		assertTrue(
			anchoredEmptyParts > 0,
			"no corpus model has an empty part ahead of a sibling part with drawables - a sort that moved empty parts last would pass too",
		)
		println("[import-order] $covered models, $anchoredEmptyParts empty parts ahead of a ranked sibling part")
		assertEquals(emptyList(), failures.take(25), "org-tree sub-parts diverged from part-table order")
	}

	@Test
	fun orgTreeSiblingPartsMatchTheEditorsTree() {
		val twins = twins()

		if (twins.isEmpty()) {
			skipTest("no cmo3/moc3 twins in the corpus; skipping editor part order gate")
		}

		val failures = ArrayList<String>()
		val differentRevisions = ArrayList<String>()
		var compared = 0
		var anchoredEmptyParts = 0
		var emptyParts = 0
		var emptyPartsWithEditorNeighbors = 0

		for ((name, cmo3File, moc3File) in twins) {
			val mocDocument = Moc3.decode(MocCodec.read(moc3File.readBytes()))
			val ours = Moc3Import.fromMocDocument(mocDocument, displayInfo = null)
			val editors = Cmo3Import.fromModelSource(assertIs<CModelSource>(Cmo3.read(cmo3File).root))
			// The same revision test Cmo3ToMoc3CrossFormatTest applies: a moc baked from another edit of the
			// project disagrees about far more than order, so it is no oracle for this.
			val editorMeshIds = editors.drawables.map { drawable -> drawable.id.raw }.toSet()
			val missingMeshes = mocDocument.artMeshes.count { artMesh -> artMesh.id !in editorMeshIds }

			if (missingMeshes > mocDocument.artMeshes.size / 10) {
				differentRevisions.add("$name ($missingMeshes/${mocDocument.artMeshes.size} art meshes absent)")

				continue
			}

			compared++
			val editorChildren = containersOf(editors).toMap()

			for ((containerName, children) in containersOf(ours)) {
				// The bake drops some objects (a sketch part), so each side is compared over what both hold.
				val editorSequence = editorChildren[containerName] ?: continue
				val shared = children.toSet().intersect(editorSequence.toSet())
				val ourParts = children.filter { child -> child is OrgChild.Part && child in shared }
				val editorParts = editorSequence.filter { child -> child is OrgChild.Part && child in shared }

				if (ourParts != editorParts) {
					val ourIds = ourParts.map { child -> (child as OrgChild.Part).id.raw }
					val editorIds = editorParts.map { child -> (child as OrgChild.Part).id.raw }
					failures.add("$name: $containerName orders its sub-parts $ourIds, the editor $editorIds")
				}

				// THE discriminating count, as in orgTreeSiblingPartsFollowThePartTable: an empty part the
				// editor lists ahead of a sibling part with drawables.
				val editorPartIsEmpty = editorParts.map { child -> !hasDrawableUnder(ours, (child as OrgChild.Part).id) }
				anchoredEmptyParts +=
					editorPartIsEmpty.indices.count { position ->
						editorPartIsEmpty[position] && editorPartIsEmpty.drop(position + 1).any { isEmpty -> !isEmpty }
					}

				// Where an empty part falls among its drawable siblings is not in the moc, so this is reported
				// rather than asserted.
				val ourShared = children.filter { child -> child in shared }
				val editorShared = editorSequence.filter { child -> child in shared }

				for ((position, child) in ourShared.withIndex()) {
					if (child !is OrgChild.Part || hasDrawableUnder(ours, child.id)) {
						continue
					}

					emptyParts++
					val editorPosition = editorShared.indexOf(child)

					if (
						ourShared.getOrNull(position - 1) == editorShared.getOrNull(editorPosition - 1) &&
						ourShared.getOrNull(position + 1) == editorShared.getOrNull(editorPosition + 1)
					) {
						emptyPartsWithEditorNeighbors++
					}
				}
			}
		}

		assertTrue(compared > 0, "no twin was the same revision")
		assertTrue(
			anchoredEmptyParts > 0,
			"no twin has an empty part ahead of a sibling part with drawables - a sort that moved empty parts last would pass too",
		)
		println(
			"[import-order] $compared twins, $emptyPartsWithEditorNeighbors/$emptyParts empty parts between the editor's neighbors" +
				(if (differentRevisions.isEmpty()) "" else "; skipped as different revisions: $differentRevisions"),
		)
		assertEquals(emptyList(), failures.take(25), "org-tree sub-parts diverged from the editor's parts tree")
	}

	/**
	 * The corpus twins, paired by base name: the same model saved as `.cmo3` and baked to `.moc3` by the
	 * official editor.
	 *
	 * @return List Each (base name, cmo3, moc3) triple the corpus can pair.
	 */
	private fun twins(): List<Triple<String, File, File>> {
		val cmo3Files =
			System.getProperty("cmo3.probe")
				?.split(',')
				?.map { path -> File(path.trim()) }
				?.filter { file -> file.isFile }
				?.associateBy { file -> file.nameWithoutExtension }
				.orEmpty()
		val moc3Files = samples().associateBy { file -> file.nameWithoutExtension }

		return cmo3Files.keys
			.intersect(moc3Files.keys)
			.sorted()
			.map { name -> Triple(name, cmo3Files.getValue(name), moc3Files.getValue(name)) }
	}

	/**
	 * Tells whether any drawable sits anywhere under a part.
	 *
	 * @param PuppetModel puppet The rig.
	 * @param PartId      partId The part.
	 * @param MutableSet  seen   The parts already walked, so a malformed cycle terminates.
	 * @return Boolean True when the part's subtree holds a drawable.
	 */
	private fun hasDrawableUnder(
		puppet: PuppetModel,
		partId: PartId,
		seen: MutableSet<PartId> = HashSet(),
	): Boolean {
		if (!seen.add(partId)) {
			return false
		}

		return puppet.partById[partId]?.children.orEmpty().any { child ->
			when (child) {
				is OrgChild.Drawable -> true
				is OrgChild.Part -> hasDrawableUnder(puppet, child.id, seen)
			}
		}
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