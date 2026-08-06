package org.umamo.interop.moc3

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocParameter
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform
import org.umamo.interop.moc3.import.Moc3Import
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-built-document tests for the two identity fallbacks [Moc3Import] applies when a MOC3 does not
 * carry what the corpus always carries: a blank or duplicated id (MOC3 §5.4) and an absent
 * deformer→part column (s15).
 *
 * The corpus cannot reach either path - every sample has unique non-empty ids and an s15 that places
 * something - so these are synthetic documents rather than corpus gates.  The inputs are exactly the
 * shapes a stripped, third-party, or synthesized MOC3 produces, which is the case `MocDecoder` reads
 * the block's common head defensively for.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.4</a>
 */
class Moc3ImportIdentityTest {
	/**
	 * Builds a one-keyform warp deformer (a 2x2 lattice), static on binding 0.
	 *
	 * @param String id                  The s11 id, possibly blank.
	 * @param Int    parentPartIndex     The s15 part index, -1 at the root.
	 * @return WarpDeformer The deformer.
	 */
	private fun staticWarp(id: String, parentPartIndex: Int): WarpDeformer =
		WarpDeformer(
			id = id,
			keyformBindingIndex = 0,
			isVisible = true,
			isEnabled = true,
			parentPartIndex = parentPartIndex,
			parentDeformerIndex = -1,
			rows = 1,
			columns = 1,
			mode = 0,
			keyforms = listOf(WarpKeyform(FloatArray(8), opacity = 1f, multiplyColor = null, screenColor = null)),
		)

	/**
	 * Builds a one-keyform, one-triangle art mesh, static on binding 0.
	 *
	 * @param String id                  The s33 id.
	 * @param Int    parentPartIndex     The s39 part index, -1 at the root.
	 * @param Int    parentDeformerIndex The s40 deformer index, -1 when parented to the part.
	 * @return ArtMesh The drawable.
	 */
	private fun staticMesh(id: String, parentPartIndex: Int, parentDeformerIndex: Int): ArtMesh =
		ArtMesh(
			id = id,
			textureIndex = 0,
			constantFlags = 0,
			parentPartIndex = parentPartIndex,
			parentDeformerIndex = parentDeformerIndex,
			vertexUvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
			triangleIndices = shortArrayOf(0, 1, 2),
			maskDrawableIndices = IntArray(0),
			keyformBindingIndex = 0,
			keyforms =
				listOf(
					ArtMeshKeyform(
						vertexPositions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
						opacity = 1f,
						drawOrder = 500f,
						multiplyColor = null,
						screenColor = null,
					),
				),
		)

	/**
	 * Assembles a document around the given deformers and meshes.  Binding 0 is the zero-axis static
	 * binding every corpus file reserves, which is what makes every object here static.
	 *
	 * @param List deformers  The deformer list, in file order.
	 * @param List artMeshes  The drawable list, in file order.
	 * @param List parts      The part list, in file order.
	 * @param List parameters The parameter list, in file order.
	 * @return MocDocument The assembled document.
	 */
	private fun documentOf(
		deformers: List<Deformer>,
		artMeshes: List<ArtMesh>,
		parts: List<Part> = listOf(part("PartHead"), part("PartBody")),
		parameters: List<MocParameter> = emptyList(),
	): MocDocument =
		MocDocument(
			version = MocVersion.V50,
			canvas = CanvasInfo(pixelsPerUnit = 1f, originX = 0f, originY = 0f, width = 100f, height = 100f),
			parameters = parameters,
			keyformBindings = mapOf(0 to KeyformBinding(index = 0, axes = emptyList())),
			parts = parts,
			deformers = deformers,
			artMeshes = artMeshes,
			glues = emptyList(),
			renderOrderGroups = emptyList(),
		)

	/**
	 * Builds a static root part.
	 *
	 * @param String id The part id.
	 * @return Part The part.
	 */
	private fun part(id: String): Part =
		Part(id = id, parentPartIndex = -1, keyformBindingIndex = 0, drawOrderKeyforms = floatArrayOf(0f))

	/**
	 * Builds a plain parameter axis.
	 *
	 * @param String id           The parameter id.
	 * @param Float  defaultValue Its default, which is also what the keyform grid samples at rest.
	 * @return MocParameter The parameter.
	 */
	private fun parameter(
		id: String,
		defaultValue: Float,
	): MocParameter =
		MocParameter(
			id = id,
			minimumValue = -30f,
			maximumValue = 30f,
			defaultValue = defaultValue,
			type = ParameterType.NORMAL,
		)

	/**
	 * A blank id, a duplicate id, and a file id that collides with what the synthesizer would have
	 * produced must all still yield distinct runtime ids, with the file's own ids kept verbatim.
	 *
	 * The collision is the interesting slot: deformer 0 is blank, so it synthesizes from its index and
	 * would take "Deformer0" - which deformer 3 already carries in the file.  The synthesizer has to
	 * dodge it rather than duplicate it, because the org tree and every cross-reference key off the id.
	 */
	@Test
	fun blankDuplicateAndCollidingDeformerIdsResolveDistinctly() {
		val document =
			documentOf(
				deformers =
					listOf(
						staticWarp(id = "", parentPartIndex = 0),
						staticWarp(id = "A", parentPartIndex = 0),
						staticWarp(id = "A", parentPartIndex = 0),
						staticWarp(id = "Deformer0", parentPartIndex = 0),
					),
				artMeshes = emptyList(),
			)

		val ids = Moc3Import.fromMocDocument(document, displayInfo = null).deformers.map { it.id.raw }

		assertEquals(4, ids.toSet().size, "every deformer id is distinct: $ids")
		// The first claimant of a file id keeps it; the later duplicate is the one that gets renamed.
		assertEquals("A", ids[1], "first claimant keeps its file id")
		assertTrue(ids[2] != "A", "the duplicate slot does not reuse the id")
		// A real file id always outranks a synthesized one, even when the synthesizer wanted it first.
		assertEquals("Deformer0", ids[3], "a file id is kept verbatim")
		assertTrue(ids[0] != "Deformer0", "the synthesized id dodges the file id it would have collided with")
	}

	/**
	 * The same rule holds for the other three id spaces, which a moc leaves just as unconstrained.
	 *
	 * Reachable through Umamo's own export, not only through a hand-built file: an id too wide for the
	 * 64-byte record is written shortened, so two names differing past the 63rd byte can arrive here as
	 * one.  Merging them would put both meshes' masks, part membership, and keyforms on one drawable and
	 * leave the other with none - a silent identity merge on a round trip, which is why every id space
	 * de-duplicates rather than only the deformers'.
	 */
	@Test
	fun duplicateParameterPartAndDrawableIdsResolveDistinctly() {
		val document =
			documentOf(
				deformers = emptyList(),
				artMeshes =
					listOf(
						staticMesh("Mesh", parentPartIndex = 0, parentDeformerIndex = -1),
						staticMesh("Mesh", parentPartIndex = 1, parentDeformerIndex = -1),
					),
				parts = listOf(part("Part"), part("Part")),
				parameters = listOf(parameter("ParamAngleX", defaultValue = 0f), parameter("ParamAngleX", defaultValue = 30f)),
			)

		val puppet = Moc3Import.fromMocDocument(document, displayInfo = null)

		val parameterIds = puppet.parameters.map { it.id.raw }
		assertEquals(2, parameterIds.toSet().size, "every parameter id is distinct: $parameterIds")
		assertEquals("ParamAngleX", parameterIds[0], "the first claimant keeps its file id")
		// Both defaults survive: an id-keyed lookup over the raw file ids would give the first parameter
		// the second's default, which is what a blend record on it would then be resolved against.
		assertEquals(listOf(0f, 30f), puppet.parameters.map { it.default }, "each parameter keeps its own default")
		val drawableIds = puppet.drawables.map { it.id.raw }
		assertEquals(2, drawableIds.toSet().size, "every drawable id is distinct: $drawableIds")
		assertTrue("Mesh" in drawableIds, "the first claimant keeps its file id")
		val partIds = puppet.parts.map { it.id.raw }
		assertEquals(2, partIds.toSet().size, "every part id is distinct: $partIds")
		assertEquals("Part", partIds[0], "the first claimant keeps its file id")
		// Each part keeps its OWN drawable rather than both landing in one: the merge this guards against
		// is invisible in an id list, but it shows up here as an empty part.
		assertTrue(
			puppet.parts.all { part -> part.children.size == 1 },
			"each part keeps its own drawable: ${puppet.parts.map { part -> part.children.size }}",
		)
	}

	/**
	 * With s15 absent (every deformer at -1) the org tree would be flat, so membership is inferred from
	 * the drawables each deformer deforms.
	 */
	@Test
	fun deformerPartsAreInferredWhenSection15PlacesNothing() {
		val document =
			documentOf(
				deformers = listOf(staticWarp("Warp0", parentPartIndex = -1), staticWarp("Warp1", parentPartIndex = -1)),
				artMeshes =
					listOf(
						staticMesh("Mesh0", parentPartIndex = 0, parentDeformerIndex = 0),
						staticMesh("Mesh1", parentPartIndex = 1, parentDeformerIndex = 1),
					),
			)

		val deformers = Moc3Import.fromMocDocument(document, displayInfo = null).deformers.associateBy { it.id.raw }

		assertEquals("PartHead", deformers.getValue("Warp0").partId?.raw, "inferred from its own drawable")
		assertEquals("PartBody", deformers.getValue("Warp1").partId?.raw, "inferred from its own drawable")
	}

	/**
	 * s15 is authoritative: when it places even one deformer, nothing is inferred - a deformer the file
	 * really does put at the root stays there rather than being pulled into a drawable's part.
	 */
	@Test
	fun section15WinsWheneverItPlacesAnything() {
		val document =
			documentOf(
				// Warp1 is deliberately at the root while deforming a drawable that lives in PartBody -
				// exactly the disagreement inference would "fix" if it were allowed to run.
				deformers = listOf(staticWarp("Warp0", parentPartIndex = 0), staticWarp("Warp1", parentPartIndex = -1)),
				artMeshes =
					listOf(
						staticMesh("Mesh0", parentPartIndex = 0, parentDeformerIndex = 0),
						staticMesh("Mesh1", parentPartIndex = 1, parentDeformerIndex = 1),
					),
			)

		val deformers = Moc3Import.fromMocDocument(document, displayInfo = null).deformers.associateBy { it.id.raw }

		assertEquals("PartHead", deformers.getValue("Warp0").partId?.raw, "s15 placement is kept")
		assertNull(deformers.getValue("Warp1").partId, "a root deformer stays at the root")
	}
}
