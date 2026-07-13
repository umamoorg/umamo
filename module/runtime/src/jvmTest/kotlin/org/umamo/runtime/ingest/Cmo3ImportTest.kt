package org.umamo.runtime.ingest

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.partByDrawable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads a real `.cmo3` corpus sample, maps it to a `PuppetModel`, and checks the structural tree.
 *
 * Self-contained (no editor jar); skips when the sample is absent. Pass
 * `-Dcmo3.sample=/path/to/Model.cmo3`. The printed summary matches the official editor's Parts/Parameters
 * panels; the assertions verify referential integrity (every GUID cross-reference resolves to a real entity).
 */
class Cmo3ImportTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun mapsCorpusModelToStructuralTree() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping import test")
			return
		}
		val root = Cmo3.read(file).root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)

		val warpCount = puppet.deformers.count { it is Deformer.Warp }
		val rotationCount = puppet.deformers.count { it is Deformer.Rotation }
		println(
			"[Umamo][import] parameters=${puppet.parameters.size} parts=${puppet.parts.size} " +
				"deformers=${puppet.deformers.size} (warp=$warpCount rotation=$rotationCount) " +
				"drawables=${puppet.drawables.size} rootPart=${puppet.rootPartId?.raw}",
		)
		println("[Umamo][import] first params: " + puppet.parameters.take(10).joinToString { it.id.raw })

		val totalVertices = puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
		val totalPolygons = puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
		println("[Umamo][import] vertices=$totalVertices polygons=$totalPolygons")

		val artInterpolations = puppet.drawables.sumOf { it.keyforms?.cells?.size ?: 0 }
		val deformerInterpolations =
			puppet.deformers.sumOf { deformer ->
				when (deformer) {
					is Deformer.Warp -> deformer.keyforms?.cells?.size ?: 0
					is Deformer.Rotation -> deformer.keyforms?.cells?.size ?: 0
				}
			}
		println(
			"[Umamo][import] artInterpolations=$artInterpolations " +
				"deformerInterpolations=$deformerInterpolations",
		)

		// Golden gate: for the EricaTamamo corpus, assert the exact figures from
		// test/corpus/EricaTamamo.stats. Other samples get only the sample-agnostic integrity checks
		// below. (Parameter count is observed - the stats file does not list it.)
		if (file.name.startsWith("EricaTamamo")) {
			assertEquals(116, puppet.parameters.size, "parameters")
			// CMO3 combined-parameter pairs: head Angle X/Y and Eyeball X/Y are the model's only links.
			assertEquals(
				listOf(
					ParameterLink(ParameterId("ParamAngleX"), ParameterId("ParamAngleY")),
					ParameterLink(ParameterId("ParamEyeBallX"), ParameterId("ParamEyeBallY")),
				),
				puppet.parameterLinks,
				"parameter links",
			)

			// CMO3 parameter-panel folders: the rootParameterGroup has 71 panel-order children - 67 loose
			// parameters interleaved with 4 subgroups (two share the display name "Eye", so identity is by id).
			fun walkGroups(nodes: List<ParameterNode>): List<ParameterNode.Group> =
				nodes.filterIsInstance<ParameterNode.Group>().flatMap { group -> listOf(group) + walkGroups(group.children) }

			fun walkLeaves(nodes: List<ParameterNode>): List<ParameterId> =
				nodes.flatMap { node ->
					when (node) {
						is ParameterNode.Param -> listOf(node.id)
						is ParameterNode.Group -> walkLeaves(node.children)
					}
				}

			assertEquals(71, puppet.parameterTree.size, "parameter tree top-level nodes")
			val parameterGroups = walkGroups(puppet.parameterTree)
			assertEquals(
				listOf("ParamGroup", "ParamGroup2", "ParamGroup3", "ParamGroup4"),
				parameterGroups.map { it.id.raw },
				"parameter group ids",
			)
			assertEquals(
				listOf("Eye", "Eye", "Folder 1", "Tail Rotation"),
				parameterGroups.map { it.name },
				"parameter group names",
			)
			assertEquals(
				listOf(3, 3, 40, 3),
				parameterGroups.map { it.children.size },
				"parameter group child counts",
			)
			val parameterTreeLeaves = walkLeaves(puppet.parameterTree)
			assertEquals(116, parameterTreeLeaves.size, "parameter tree leaves")
			assertEquals(
				puppet.parameters.map { it.id }.toSet(),
				parameterTreeLeaves.toSet(),
				"every panel leaf is a real parameter",
			)
			assertEquals(42, puppet.parts.size, "parts")
			assertEquals(350, warpCount, "warp deformers")
			assertEquals(6, rotationCount, "rotation deformers")
			assertEquals(177, puppet.drawables.size, "art meshes")
			assertEquals(31470, totalVertices, "vertices")
			assertEquals(50933, totalPolygons, "polygons")
			assertEquals(337, artInterpolations, "art mesh interpolations")
			assertEquals(1297, deformerInterpolations, "deformer interpolations")
		}

		val partIds = puppet.parts.map { it.id }.toSet()
		val deformerIds = puppet.deformers.map { it.id }.toSet()
		val drawableIds = puppet.drawables.map { it.id }.toSet()

		assertTrue(puppet.parameters.isNotEmpty(), "expected parameters")
		assertTrue(puppet.drawables.isNotEmpty(), "expected drawables")

		// Referential integrity: every org-tree child resolves to a real entity, and every drawable is
		// placed somewhere in the tree (membership lives only in the tree now - the import's safety net
		// guarantees no mesh is orphaned).
		fun checkOrgChildren(children: List<OrgChild>, owner: String) {
			for (child in children) {
				when (child) {
					is OrgChild.Part -> assertTrue(child.id in partIds, "$owner -> unknown sub-part ${child.id.raw}")
					is OrgChild.Drawable -> assertTrue(child.id in drawableIds, "$owner -> unknown mesh ${child.id.raw}")
				}
			}
		}
		checkOrgChildren(puppet.rootChildren, "root")
		for (part in puppet.parts) {
			checkOrgChildren(part.children, "part ${part.id.raw}")
		}
		val placedDrawables = puppet.partByDrawable().keys
		for (drawable in puppet.drawables) {
			assertTrue(drawable.id in placedDrawables, "drawable ${drawable.id.raw} is missing from the org tree")
			drawable.parentDeformerId?.let {
				assertTrue(it in deformerIds, "drawable ${drawable.id.raw} -> unknown deformer ${it.raw}")
			}
			for (mask in drawable.maskedBy) {
				assertTrue(mask in drawableIds, "drawable ${drawable.id.raw} -> unknown mask ${mask.raw}")
			}
		}
		for (deformer in puppet.deformers) {
			deformer.parent?.let {
				assertTrue(it in deformerIds, "deformer ${deformer.id.raw} -> unknown parent ${it.raw}")
			}
			deformer.partId?.let {
				assertTrue(it in partIds, "deformer ${deformer.id.raw} -> unknown part ${it.raw}")
			}
		}
	}

	/**
	 * Corpus-free check that drawable and deformer display names come from CMO3 localName, falling back to
	 * the id when localName is absent - the same rule the outliner relies on to label its rows readably.
	 */
	@Test
	fun capturesLocalNameWithIdFallbackForDrawablesAndDeformers() {
		fun makeId(value: String) = Id("").apply { idstr = value }

		val namedMesh =
			CArtMeshSource().apply {
				id = makeId("ArtMesh1")
				localName = "Front Hair"
			}
		val unnamedMesh = CArtMeshSource().apply { id = makeId("ArtMesh9") } // localName null -> id
		val namedWarp =
			CWarpDeformerSource().apply {
				id = makeId("Warp1")
				localName = "Warp Deformer of Bag front12"
			}
		val unnamedWarp = CWarpDeformerSource().apply { id = makeId("Warp9") } // localName null -> id

		val model =
			CModelSource().apply {
				drawableSourceSet = CDrawableSourceSet().apply { _sources = arrayListOf(namedMesh, unnamedMesh) }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = arrayListOf(namedWarp, unnamedWarp) }
			}

		val puppet = Cmo3Import.fromModelSource(model)

		assertEquals("Front Hair", puppet.drawables.first { it.id.raw == "ArtMesh1" }.name, "named drawable")
		assertEquals("ArtMesh9", puppet.drawables.first { it.id.raw == "ArtMesh9" }.name, "unnamed drawable falls back to id")
		assertEquals("Warp Deformer of Bag front12", puppet.deformers.first { it.id.raw == "Warp1" }.name, "named deformer")
		assertEquals("Warp9", puppet.deformers.first { it.id.raw == "Warp9" }.name, "unnamed deformer falls back to id")
	}

	/**
	 * Corpus-free check that the CMO3 editor lock maps inverted onto the runtime selectable flag: a locked
	 * source ingests as isSelectable = false and an unlocked one as isSelectable = true, for drawables and
	 * both deformer kinds alike (Cubism lock = not selectable).
	 */
	@Test
	fun mapsCmo3LockInvertedOntoSelectable() {
		fun makeId(value: String) = Id("").apply { idstr = value }

		val lockedMesh =
			CArtMeshSource().apply {
				id = makeId("ArtMesh1")
				isLocked = true
			}
		val unlockedMesh = CArtMeshSource().apply { id = makeId("ArtMesh2") }
		val lockedWarp =
			CWarpDeformerSource().apply {
				id = makeId("Warp1")
				isLocked = true
			}
		val lockedRotation =
			CRotationDeformerSource().apply {
				id = makeId("Rotation1")
				isLocked = true
			}
		val unlockedWarp = CWarpDeformerSource().apply { id = makeId("Warp2") }

		val model =
			CModelSource().apply {
				drawableSourceSet = CDrawableSourceSet().apply { _sources = arrayListOf(lockedMesh, unlockedMesh) }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = arrayListOf(lockedWarp, lockedRotation, unlockedWarp) }
			}

		val puppet = Cmo3Import.fromModelSource(model)

		assertFalse(puppet.drawables.first { it.id.raw == "ArtMesh1" }.isSelectable, "locked drawable ingests unselectable")
		assertTrue(puppet.drawables.first { it.id.raw == "ArtMesh2" }.isSelectable, "unlocked drawable ingests selectable")
		assertFalse(puppet.deformers.first { it.id.raw == "Warp1" }.isSelectable, "locked warp ingests unselectable")
		assertFalse(puppet.deformers.first { it.id.raw == "Rotation1" }.isSelectable, "locked rotation ingests unselectable")
		assertTrue(puppet.deformers.first { it.id.raw == "Warp2" }.isSelectable, "unlocked warp ingests selectable")
	}
}
