package org.umamo.format.cmo3

import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CEditableMeshExtension
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the corpus encodings of GEditableMesh2's untyped fields, which the CMO3 export's geometry
 * sync and the topology rebuild both depend on: point is a float-array mirroring the art mesh's
 * positions (a SEPARATE array object, never a shared ref to CArtMeshSource.positions), pointUid is
 * an int-array of one uid per vertex, and edge is a SHORT-array of vertex-index pairs into the
 * point array (vertex ordinals, not uids).  Printed distributions feed docs/format/CMO3.md; the
 * assertions are the invariants the export writes against.
 */
class GEditableMeshProbeTest {
	@Test
	fun editableMeshEncodingsHoldAcrossTheCorpus() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping editable-mesh probe")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		val failures = ArrayList<String>()
		var meshCount = 0
		var sharedPointArrayCount = 0
		var edgeBearingCount = 0
		var delaunayCount = 0
		for (file in files) {
			val root = Cmo3.read(file.readBytes()).root as? CModelSource ?: continue
			val drawables =
				(((root.drawableSourceSet as? CDrawableSourceSet)?._sources as? Iterable<*>) ?: emptyList<Any?>())
					.filterIsInstance<CArtMeshSource>()
			for (drawable in drawables) {
				val extensions = (drawable._extensions as? Iterable<*>) ?: emptyList<Any?>()
				val editableMesh =
					extensions.filterIsInstance<CEditableMeshExtension>().firstOrNull()?.editableMesh as? GEditableMesh2
						?: continue
				meshCount++
				val positions = drawable.positions as? FloatArray
				val point = editableMesh.point
				val pointUid = editableMesh.pointUid
				val edge = editableMesh.edge
				if (point != null && point !is FloatArray) {
					failures.add("${file.name}: point is ${point::class.simpleName}")
					continue
				}
				if (pointUid != null && pointUid !is IntArray) {
					failures.add("${file.name}: pointUid is ${pointUid::class.simpleName}")
					continue
				}
				if (edge != null && edge !is ShortArray) {
					failures.add("${file.name}: edge is ${edge::class.simpleName}")
					continue
				}
				val pointArray = point as? FloatArray
				val uidArray = pointUid as? IntArray
				val edgeArray = edge as? ShortArray
				if (pointArray != null && positions != null) {
					if (pointArray === positions) {
						sharedPointArrayCount++
					}
					if (pointArray.size != positions.size) {
						failures.add("${file.name}: point size ${pointArray.size} != positions size ${positions.size}")
					}
					if (uidArray != null && uidArray.size * 2 != pointArray.size) {
						failures.add("${file.name}: pointUid count ${uidArray.size} != vertex count ${pointArray.size / 2}")
					}
				}
				if (edgeArray != null && edgeArray.isNotEmpty()) {
					edgeBearingCount++
					if (edgeArray.size % 2 != 0) {
						failures.add("${file.name}: edge array size ${edgeArray.size} is odd")
					}
					val vertexCount = (pointArray?.size ?: 0) / 2
					if (vertexCount > 0 && edgeArray.any { endpoint -> endpoint < 0 || endpoint >= vertexCount }) {
						failures.add("${file.name}: edge endpoint out of vertex range")
					}
				}
				if (editableMesh.useDelaunayTriangulation) {
					delaunayCount++
				}
			}
		}
		println(
			"editable meshes: $meshCount, point===positions shares: $sharedPointArrayCount, " +
				"edge-bearing: $edgeBearingCount, delaunay: $delaunayCount",
		)
		assertTrue(failures.isEmpty(), "editable-mesh probe failed:\n" + failures.joinToString("\n"))
	}
}