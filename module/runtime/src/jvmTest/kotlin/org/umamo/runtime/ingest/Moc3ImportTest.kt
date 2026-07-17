package org.umamo.runtime.ingest

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import org.umamo.runtime.model.partByDrawable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads a real `.moc3` corpus sample (plus its sibling cdi3.json when present), maps it to a
 * `PuppetModel`, and checks the structural tree - the MOC3 counterpart of [Cmo3ImportTest].
 *
 * Self-contained; skips when the sample is absent. Pass `-Dmoc3.sample=/path/to/Model.moc3` (the
 * build supplies the local corpus Erica by default). The assertions verify referential integrity
 * (every index cross-reference resolves to a real entity) plus Erica-pinned golden counts.
 */
class Moc3ImportTest {
	private val sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun mapsCorpusModelToStructuralTree() {
		val file = sample
		if (file == null) {
			println("moc3.sample not present; skipping import test")
			return
		}
		val mocDocument = Moc3.decode(file.readBytes())
		val displayInfo = siblingDisplayInfo(file)
		val puppet = Moc3Import.fromMocDocument(mocDocument, displayInfo)

		val warpCount = puppet.deformers.count { it is Deformer.Warp }
		val rotationCount = puppet.deformers.count { it is Deformer.Rotation }
		println(
			"[Umamo][moc3import] parameters=${puppet.parameters.size} parts=${puppet.parts.size} " +
				"deformers=${puppet.deformers.size} (warp=$warpCount rotation=$rotationCount) " +
				"drawables=${puppet.drawables.size} glues=${puppet.glues.size}",
		)
		val totalVertices = puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
		val totalPolygons = puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
		println("[Umamo][moc3import] vertices=$totalVertices polygons=$totalPolygons")
		val artInterpolations = puppet.drawables.sumOf { it.keyforms?.cells?.size ?: 0 }
		val deformerInterpolations =
			puppet.deformers.sumOf { deformer ->
				when (deformer) {
					is Deformer.Warp -> deformer.keyforms?.cells?.size ?: 0
					is Deformer.Rotation -> deformer.keyforms?.cells?.size ?: 0
				}
			}
		println("[Umamo][moc3import] artInterpolations=$artInterpolations deformerInterpolations=$deformerInterpolations")
		println("[Umamo][moc3import] parameterTree top=${puppet.parameterTree.size} links=${puppet.parameterLinks.size}")
		println(
			"[Umamo][moc3import] canvas=${puppet.canvasWidth}x${puppet.canvasHeight} " +
				"origin=(${puppet.worldOriginX}, ${puppet.worldOriginY})",
		)

		// Golden gate: for the EricaTamamo corpus, pin the imported structure. Drawable-derived counts
		// intentionally differ from the CMO3 sample's (177 drawables / 31470 vertices / 337 art
		// interpolations): the bake drops the three sketch ("Guide Image") drawables.
		if (file.name.startsWith("EricaTamamo")) {
			assertEquals(116, puppet.parameters.size, "parameters")
			assertEquals(42, puppet.parts.size, "parts")
			assertEquals(350, warpCount, "warp deformers")
			assertEquals(6, rotationCount, "rotation deformers")
			assertEquals(174, puppet.drawables.size, "art meshes")
			assertEquals(4, puppet.glues.size, "glues")
			assertEquals(30638, totalVertices, "vertices")
			assertEquals(49510, totalPolygons, "polygons")
			assertEquals(334, artInterpolations, "art mesh interpolations")
			assertEquals(1297, deformerInterpolations, "deformer interpolations")
			assertEquals(71, puppet.parameterTree.size, "parameter tree top-level nodes")
			assertEquals(4500f, puppet.canvasWidth, "canvas width")
			assertEquals(6500f, puppet.canvasHeight, "canvas height")
			assertEquals(2250f, puppet.worldOriginX, "world origin x")
			assertEquals(-3250f, puppet.worldOriginY, "world origin y")
			assertEquals(
				listOf(
					ParameterLink(ParameterId("ParamAngleX"), ParameterId("ParamAngleY")),
					ParameterLink(ParameterId("ParamEyeBallX"), ParameterId("ParamEyeBallY")),
				),
				puppet.parameterLinks,
				"parameter links",
			)
			// cdi3 display names resolved for parameters and parts (spot checks; names are user data).
			assertTrue(
				puppet.parameters.any { it.id.raw != it.name },
				"expected cdi3 display names to differ from raw parameter ids somewhere",
			)
		}

		val partIds = puppet.parts.map { it.id }.toSet()
		val deformerIds = puppet.deformers.map { it.id }.toSet()
		val drawableIds = puppet.drawables.map { it.id }.toSet()

		assertTrue(puppet.parameters.isNotEmpty(), "expected parameters")
		assertTrue(puppet.drawables.isNotEmpty(), "expected drawables")

		// Referential integrity: every org-tree child resolves, every drawable is placed in the tree,
		// and every cross-reference (deformer parents, masks, glue meshes) resolves to a real entity.
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
		}
		for (glue in puppet.glues) {
			assertTrue(glue.meshA in drawableIds, "glue -> unknown mesh A ${glue.meshA.raw}")
			assertTrue(glue.meshB in drawableIds, "glue -> unknown mesh B ${glue.meshB.raw}")
		}

		// The imported render tree's leaves are exactly the drawables (the baked draw-order tree places
		// every mesh once).
		val renderLeaves = ArrayList<RenderNode>()

		fun collectLeaves(node: RenderNode) {
			when (node) {
				is RenderDrawable -> renderLeaves.add(node)
				is RenderGroup -> node.children.forEach(::collectLeaves)
			}
		}
		collectLeaves(puppet.renderRoot)
		assertEquals(puppet.drawables.size, renderLeaves.size, "render tree leaf count")
		for (leaf in renderLeaves) {
			assertTrue((leaf as RenderDrawable).id in drawableIds, "render leaf -> unknown drawable")
		}

		// The panel tree covers every parameter exactly once when cdi3 was present.
		if (displayInfo != null) {
			fun walkLeaves(nodes: List<ParameterNode>): List<ParameterId> =
				nodes.flatMap { node ->
					when (node) {
						is ParameterNode.Param -> listOf(node.id)
						is ParameterNode.Group -> walkLeaves(node.children)
					}
				}

			val parameterTreeLeaves = walkLeaves(puppet.parameterTree)
			assertEquals(puppet.parameters.size, parameterTreeLeaves.size, "parameter tree leaves")
			assertEquals(
				puppet.parameters.map { it.id }.toSet(),
				parameterTreeLeaves.toSet(),
				"every panel leaf is a real parameter",
			)
		}
	}

	/**
	 * Reads the sample's sibling `<basename>.cdi3.json`, or null when absent - the same basename
	 * discovery the document loader performs.
	 *
	 * @param File mocFile The `.moc3` sample.
	 * @return Cdi3Json? The parsed display info, or null.
	 */
	private fun siblingDisplayInfo(mocFile: File): Cdi3Json? {
		// Case-insensitive strip, matching the document loader's basename rule.
		val basename = if (mocFile.name.endsWith(".moc3", ignoreCase = true)) mocFile.name.dropLast(".moc3".length) else mocFile.name
		val cdi3File = mocFile.parentFile.resolve("$basename.cdi3.json")
		if (!cdi3File.isFile) {
			return null
		}
		return Moc3.readCdi3(cdi3File.readText())
	}
}
