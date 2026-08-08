package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Sections
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An id too wide for a moc's id record is shortened to fit, and the shortening never mints a duplicate.
 *
 * MOC3 §5.4 makes every id a fixed 64-byte record while CMO3 places no width limit on the same string,
 * so an over-wide id is a rig a rigger can really author - 22 CJK characters already exceed the record.
 * Truncating it is the only way to write it, and truncation is what creates the collision this guards:
 * two names differing only past the 63rd byte truncate to one.  A MOC3 addresses nothing by id, so the
 * file takes the duplicate silently and the loss surfaces on the way back, where the import merges the
 * two objects into one.
 *
 * Synthetic rather than corpus-backed on purpose: the corpus is what the official editor wrote, and its
 * own ids all fit.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.4</a>
 */
class Moc3ExportIdFitTest {
	/** A name whose UTF-8 form runs well past the record, so every mesh below has to be shortened. */
	private val overlongStem = "あ".repeat(40)

	/**
	 * Builds a one-triangle drawable with no keyforms, parented to [partId].
	 *
	 * @param String id     The drawable's model id.
	 * @param PartId partId Its owning part.
	 * @return Drawable The drawable.
	 */
	private fun drawable(
		id: String,
		partId: PartId,
	): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			// Bound to a page, so the only notice a rig here can raise is about its ids.
			texturePage = 0,
			mesh =
				DrawableMesh(
					positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					indices = intArrayOf(0, 1, 2),
				),
			geometryGrid = null,
		)

	/**
	 * A rig whose drawables carry [ids], all under one part.
	 *
	 * @param List<String> ids The drawable ids, in model order.
	 * @return PuppetModel The rig.
	 */
	private fun puppetWithDrawableIds(ids: List<String>): PuppetModel {
		val partId = PartId("Part1")
		val drawables = ids.map { id -> drawable(id, partId) }
		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(id = partId, name = "Part1", children = drawables.map { OrgChild.Drawable(it.id) })),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = listOf(OrgChild.Part(partId)),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 100f,
		)
	}

	/**
	 * Two ids that differ only past the record width must not be written as one name.
	 *
	 * The second is also checked for still FITTING: disambiguating by appending a suffix to an id
	 * already trimmed to the limit would push it back over, and the writer's own precondition would
	 * then reject the file the notice said it had written.
	 */
	@Test
	fun idsTruncatingOntoEachOtherAreDisambiguated() {
		val lowered =
			Moc3Export.toMocDocument(
				puppetWithDrawableIds(listOf(overlongStem + "左", overlongStem + "右")),
				MocVersion.V50,
			)

		val writtenIds = lowered.document.artMeshes.map { artMesh -> artMesh.id }
		assertEquals(2, writtenIds.toSet().size, "the two drawables were written under one id: $writtenIds")
		for (writtenId in writtenIds) {
			assertTrue(
				writtenId.encodeToByteArray().size < Sections.ID_STRIDE,
				"\"$writtenId\" does not fit a ${Sections.ID_STRIDE}-byte id record",
			)
		}
		// Each shortening is reported against the id the RIG carries, so the rigger can find the object
		// the notice is about; the written name is on the reason.
		val subjects =
			lowered.report.notices
				.filterIsInstance<ExportNotice.UnsupportedChange>()
				.filter { notice -> notice.category == ExportEntityCategory.Drawable }
				.map { notice -> notice.subject }
		assertEquals(listOf(overlongStem + "左", overlongStem + "右"), subjects, "both shortenings were reported")
	}

	/**
	 * A shortened id never takes a name another object holds outright.
	 *
	 * The short drawable here is exactly what the long one truncates to, and it is written LAST - so
	 * resolving collisions in lowering order, as they came, would hand its own id away to the long one
	 * and rename the object that never needed shortening at all.
	 */
	@Test
	fun aShortenedIdNeverTakesAnIdSomethingElseHoldsOutright() {
		val overlong = "A".repeat(Sections.ID_STRIDE + 20)
		val truncated = "A".repeat(Sections.ID_STRIDE - 1)
		val lowered = Moc3Export.toMocDocument(puppetWithDrawableIds(listOf(overlong, truncated)), MocVersion.V50)

		val writtenIds = lowered.document.artMeshes.map { artMesh -> artMesh.id }
		assertEquals(truncated, writtenIds[1], "the id that fits was written verbatim")
		assertTrue(writtenIds[0] != truncated, "the shortened id took a name that was already spoken for")
	}

	/**
	 * The cdi3 beside the MOC3 names the objects by the ids the MOC got, not the ones the model carries.
	 *
	 * The two files are joined on that string by every runtime and by Umamo's own import: a cdi3 built
	 * from the model's ids would name an object the MOC3 does not contain under that name, so the display
	 * name - the one thing the MOC3 cannot carry, which is the entire reason the cdi3 is written - is lost
	 * on exactly the objects whose ids had to be shortened.
	 */
	@Test
	fun theCdi3NamesTheIdsTheMocWasWrittenWith() {
		val bundle =
			Moc3Sidecars.bundle(
				puppetWithDrawableIds(listOf(overlongStem + "左", overlongStem + "右")),
				basename = "rig",
				version = MocVersion.V50,
				pages = emptyList(),
			)

		val mocFile = bundle.files.first { file -> file.name == bundle.mocFileName }
		val displayInfoFile = bundle.files.first { file -> file.name == "rig.cdi3.json" }
		val writtenIds = Moc3.read(mocFile.bytes).artMeshes.map { artMesh -> artMesh.id }
		val displayInfo = Moc3.readCdi3(displayInfoFile.bytes.decodeToString())

		assertEquals(
			writtenIds,
			displayInfo.drawables.orEmpty().map { drawable -> drawable.id },
			"the cdi3 names drawables the moc does not contain",
		)
		// And the pairing survives a round trip: each mesh gets its own name back rather than falling
		// back to the shortened id.
		val reimported = Moc3Import.fromMocDocument(Moc3.read(mocFile.bytes), displayInfo)
		assertEquals(
			setOf(overlongStem + "左", overlongStem + "右"),
			reimported.drawables.mapTo(HashSet()) { drawable -> drawable.name },
			"a shortened drawable lost its display name",
		)
	}

	/**
	 * The cdi3 names only the objects the moc contains - never one the export dropped.
	 *
	 * The mesh-less drawable here is dropped, and its id is exactly what the kept drawable's over-long id
	 * shortens to.  A dropped object claims no id, so the shortening lands on that very string: naming
	 * the dropped one anyway would put TWO entries under a single id in the cdi3, and the import's join
	 * on that string would pick between them arbitrarily - handing a mesh a deleted object's name.
	 */
	@Test
	fun theCdi3OmitsObjectsTheExportDropped() {
		val overlong = "A".repeat(Sections.ID_STRIDE + 20)
		val truncated = "A".repeat(Sections.ID_STRIDE - 1)
		val partId = PartId("Part1")
		val kept = drawable(overlong, partId)
		// Mesh-less, so the eligibility pass drops it before the plan is built.
		val dropped = drawable(truncated, partId).copy(mesh = null)
		val sketchPartId = PartId("Sketch")
		val puppet =
			PuppetModel(
				parameters = emptyList(),
				parts =
					listOf(
						Part(id = partId, name = "Part1", children = listOf(OrgChild.Drawable(kept.id), OrgChild.Drawable(dropped.id))),
						Part(id = sketchPartId, name = "Sketch", children = emptyList(), isSketch = true),
					),
				deformers = emptyList(),
				drawables = listOf(kept, dropped),
				rootChildren = listOf(OrgChild.Part(partId), OrgChild.Part(sketchPartId)),
				rootPartId = null,
				canvasWidth = 100f,
				canvasHeight = 100f,
			)

		val bundle = Moc3Sidecars.bundle(puppet, basename = "rig", version = MocVersion.V50, pages = emptyList())
		val displayInfo = Moc3.readCdi3(bundle.files.first { file -> file.name == "rig.cdi3.json" }.bytes.decodeToString())

		assertEquals(
			listOf(truncated),
			displayInfo.drawables.orEmpty().map { drawable -> drawable.id },
			"the cdi3 names a drawable the moc does not contain",
		)
		assertEquals(overlong, displayInfo.drawables.orEmpty().single().name, "the entry is the kept mesh's, not the dropped one's")
		assertEquals(
			listOf("Part1"),
			displayInfo.parts.map { part -> part.id },
			"the cdi3 names a sketch part the bake leaves out",
		)
	}

	/** An id that fits is written verbatim and raises nothing. */
	@Test
	fun idsThatFitAreWrittenVerbatimAndReportNothing() {
		val lowered = Moc3Export.toMocDocument(puppetWithDrawableIds(listOf("ArtMesh1", "ArtMesh2")), MocVersion.V50)

		assertEquals(listOf("ArtMesh1", "ArtMesh2"), lowered.document.artMeshes.map { artMesh -> artMesh.id })
		assertEquals(emptyList(), lowered.report.notices, "a rig within the record width has nothing to report")
	}
}