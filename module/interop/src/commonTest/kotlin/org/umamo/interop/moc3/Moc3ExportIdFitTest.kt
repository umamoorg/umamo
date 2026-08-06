package org.umamo.interop.moc3

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Sections
import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.export.Moc3Export
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
 * two names differing only past the 63rd byte truncate to one.  A moc addresses nothing by id, so the
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
		// the notice is about; the written name is in the detail.
		val subjects =
			lowered.report.notices
				.filterIsInstance<ExportNotice.UnsupportedChange>()
				.filter { notice -> notice.category == "drawable" }
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

	/** An id that fits is written verbatim and raises nothing. */
	@Test
	fun idsThatFitAreWrittenVerbatimAndReportNothing() {
		val lowered = Moc3Export.toMocDocument(puppetWithDrawableIds(listOf("ArtMesh1", "ArtMesh2")), MocVersion.V50)

		assertEquals(listOf("ArtMesh1", "ArtMesh2"), lowered.document.artMeshes.map { artMesh -> artMesh.id })
		assertEquals(emptyList(), lowered.report.notices, "a rig within the record width has nothing to report")
	}
}
