package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [Moc3ExportOptions] includes and omits, and that the options-less default stays the carrying
 * behavior the lowering always had.
 *
 * Synthetic rigs on purpose: the corpus is what the official editor wrote, and its bakes already had
 * their hidden objects deleted or carried by the editor's own dialog - the option surface under test
 * here is Umamo's.
 */
class Moc3ExportOptionsTest {
	/**
	 * Builds a one-triangle drawable with no keyforms and no deformer parent.
	 *
	 * @param String  id        The drawable's model id.
	 * @param Boolean isVisible The drawable's own eyeball flag.
	 * @param List    maskedBy  The drawables whose alpha clips this one.
	 * @return Drawable The drawable.
	 */
	private fun drawable(
		id: String,
		isVisible: Boolean = true,
		maskedBy: List<DrawableId> = emptyList(),
	): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = maskedBy,
			texturePage = 0,
			isVisible = isVisible,
			mesh =
				DrawableMesh(
					positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f),
					uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					indices = intArrayOf(0, 1, 2),
				),
			geometryGrid = null,
		)

	/**
	 * A rig of the given parts, each owning the drawables its children name.
	 *
	 * @param List parts     The parts, in panel order; all become root children.
	 * @param List drawables Every drawable the parts reference.
	 * @return PuppetModel The rig.
	 */
	private fun puppetOf(
		parts: List<Part>,
		drawables: List<Drawable>,
	): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = parts,
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = parts.map { part -> OrgChild.Part(part.id) },
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 100f,
		)

	/**
	 * A rig with a hidden part whose subtree holds a visible child part, plus a visible part with
	 * one visible and one own-flag-hidden drawable.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun puppetWithHiddenObjects(): PuppetModel {
		val hiddenRootId = PartId("HiddenRoot")
		val visibleChildId = PartId("VisibleChild")
		val visiblePartId = PartId("VisiblePart")
		val underHiddenRoot = drawable("UnderHiddenRoot")
		val underVisibleChild = drawable("UnderVisibleChild")
		val shown = drawable("Shown")
		val ownFlagHidden = drawable("OwnFlagHidden", isVisible = false)
		return puppetOf(
			parts =
				listOf(
					Part(
						id = hiddenRootId,
						name = "HiddenRoot",
						children = listOf(OrgChild.Drawable(underHiddenRoot.id), OrgChild.Part(visibleChildId)),
						isVisible = false,
					),
					Part(id = visibleChildId, name = "VisibleChild", children = listOf(OrgChild.Drawable(underVisibleChild.id))),
					Part(
						id = visiblePartId,
						name = "VisiblePart",
						children = listOf(OrgChild.Drawable(shown.id), OrgChild.Drawable(ownFlagHidden.id)),
					),
				),
			drawables = listOf(underHiddenRoot, underVisibleChild, shown, ownFlagHidden),
		).copy(
			// The child part sits under the hidden root, not at the model root.
			rootChildren = listOf(OrgChild.Part(hiddenRootId), OrgChild.Part(visiblePartId)),
		)
	}

	/** The reasons of every drop notice in [report], keyed by the dropped drawable's id. */
	private fun dropReasonsBySubject(report: List<ExportNotice>): Map<String?, ExportNoticeReason> =
		report
			.filterIsInstance<ExportNotice.UnsupportedChange>()
			.associate { notice -> notice.subject to notice.reason }

	/** The options-less export carries every hidden object with its flag clear, exactly as before. */
	@Test
	fun defaultOptionsCarryHiddenObjectsWithTheFlagClear() {
		val lowered = Moc3Export.toMocDocument(puppetWithHiddenObjects(), MocVersion.V50)

		assertEquals(
			listOf("HiddenRoot", "VisibleChild", "VisiblePart"),
			lowered.document.parts.map { part -> part.id },
			"a hidden part was dropped without the option asking for it",
		)
		assertFalse(
			lowered.document.parts.first { part -> part.id == "HiddenRoot" }.isVisible,
			"the hidden part lost its cleared flag",
		)
		assertEquals(
			listOf("UnderHiddenRoot", "UnderVisibleChild", "Shown", "OwnFlagHidden"),
			lowered.document.artMeshes.map { artMesh -> artMesh.id },
		)
		assertFalse(
			lowered.document.artMeshes.first { artMesh -> artMesh.id == "OwnFlagHidden" }.isVisible,
			"the hidden art mesh lost its cleared flag",
		)
		assertEquals(emptyList(), lowered.report.notices, "carrying everything has nothing to report")
	}

	/** Omitting hidden parts drops the whole subtree - the visible child part included - with notices. */
	@Test
	fun omittingHiddenPartsDropsTheSubtree() {
		val options = Moc3ExportOptions(exportHiddenParts = false)
		val lowered = Moc3Export.toMocDocument(puppetWithHiddenObjects(), MocVersion.V50, options = options)

		assertEquals(listOf("VisiblePart"), lowered.document.parts.map { part -> part.id })
		assertEquals(
			listOf("Shown", "OwnFlagHidden"),
			lowered.document.artMeshes.map { artMesh -> artMesh.id },
			"a drawable under the hidden subtree survived",
		)
		val reasons = dropReasonsBySubject(lowered.report.notices)
		assertEquals(ExportNoticeReason.HiddenPartOmittedByExportOption, reasons["UnderHiddenRoot"])
		assertEquals(
			ExportNoticeReason.HiddenPartOmittedByExportOption,
			reasons["UnderVisibleChild"],
			"the hidden-part cascade missed the visible child part's drawable",
		)
	}

	/** Omitting hidden art meshes drops own-flag-hidden drawables and leaves their siblings intact. */
	@Test
	fun omittingHiddenArtMeshesDropsOwnFlagHiddenDrawables() {
		val options = Moc3ExportOptions(exportHiddenDrawables = false)
		val lowered = Moc3Export.toMocDocument(puppetWithHiddenObjects(), MocVersion.V50, options = options)

		assertEquals(
			listOf("UnderHiddenRoot", "UnderVisibleChild", "Shown"),
			lowered.document.artMeshes.map { artMesh -> artMesh.id },
			"only the own-flag-hidden drawable should drop; hidden PARTS were not omitted",
		)
		assertEquals(
			ExportNoticeReason.HiddenDrawableOmittedByExportOption,
			dropReasonsBySubject(lowered.report.notices)["OwnFlagHidden"],
		)
	}

	/** A hidden mask a surviving drawable clips by is kept, flag clear, rather than unclipping it. */
	@Test
	fun aHiddenMaskASurvivorReferencesIsKept() {
		val partId = PartId("Part1")
		val mask = drawable("Mask", isVisible = false)
		val clipped = drawable("Clipped", maskedBy = listOf(mask.id))
		val unreferencedHidden = drawable("UnreferencedHidden", isVisible = false)
		val puppet =
			puppetOf(
				parts =
					listOf(
						Part(
							id = partId,
							name = "Part1",
							children =
								listOf(
									OrgChild.Drawable(mask.id),
									OrgChild.Drawable(clipped.id),
									OrgChild.Drawable(unreferencedHidden.id),
								),
						),
					),
				drawables = listOf(mask, clipped, unreferencedHidden),
			)
		val options = Moc3ExportOptions(exportHiddenDrawables = false)
		val lowered = Moc3Export.toMocDocument(puppet, MocVersion.V50, options = options)

		assertEquals(
			listOf("Mask", "Clipped"),
			lowered.document.artMeshes.map { artMesh -> artMesh.id },
			"the referenced mask must survive; the unreferenced hidden drawable must not",
		)
		assertFalse(lowered.document.artMeshes.first { artMesh -> artMesh.id == "Mask" }.isVisible)
		assertEquals(
			1,
			lowered.document.artMeshes.first { artMesh -> artMesh.id == "Clipped" }.maskDrawableIndices.size,
			"the survivor's mask reference resolved to nothing",
		)
		val reasons = dropReasonsBySubject(lowered.report.notices)
		assertNull(reasons["Mask"], "the kept mask must not be reported as dropped")
		assertEquals(ExportNoticeReason.HiddenDrawableOmittedByExportOption, reasons["UnreferencedHidden"])
		assertTrue(
			lowered.report.notices.none { notice ->
				notice is ExportNotice.UnsupportedChange && notice.reason is ExportNoticeReason.ClippingMaskNotInExport
			},
			"keeping the mask should leave nothing for the mask filter to report",
		)
	}

	/** Keeping guide-image parts writes the sketch subtree the default drops. */
	@Test
	fun keepingGuideImagePartsWritesTheSketchSubtree() {
		val sketchPartId = PartId("Sketch")
		val guide = drawable("Guide")
		val puppet =
			puppetOf(
				parts =
					listOf(
						Part(id = sketchPartId, name = "Sketch", children = listOf(OrgChild.Drawable(guide.id)), isSketch = true),
					),
				drawables = listOf(guide),
			)

		val kept = Moc3Export.toMocDocument(puppet, MocVersion.V50, options = Moc3ExportOptions(exportGuideImageParts = true))
		assertEquals(listOf("Guide"), kept.document.artMeshes.map { artMesh -> artMesh.id })
		assertEquals(emptyList(), kept.report.notices, "a kept guide has nothing to report")

		val dropped = Moc3Export.toMocDocument(puppet, MocVersion.V50)
		assertEquals(emptyList(), dropped.document.artMeshes, "the options-less default must still drop the sketch subtree")
	}

	/** The scale override lands in the canvas record AND rescales the geometry with it. */
	@Test
	fun pixelsPerUnitOverrideScalesTheBake() {
		val partId = PartId("Part1")
		val mesh = drawable("ArtMesh1")
		val puppet =
			puppetOf(
				parts = listOf(Part(id = partId, name = "Part1", children = listOf(OrgChild.Drawable(mesh.id)))),
				drawables = listOf(mesh),
			)

		val defaulted = Moc3Export.toMocDocument(puppet, MocVersion.V50)
		// pixelsPerUnit is null on the rig, so the heuristic resolves to the canvas width.
		assertEquals(100f, defaulted.document.canvas?.pixelsPerUnit)

		val doubled =
			Moc3Export.toMocDocument(puppet, MocVersion.V50, options = Moc3ExportOptions(pixelsPerUnitOverride = 200f))
		assertEquals(200f, doubled.document.canvas?.pixelsPerUnit)

		// Twice the pixels per unit means every model-space position is half the size.  The record and
		// the geometry must come from the same number - a header-only override would fail this.
		val defaultedPositions = defaulted.document.artMeshes.single().keyforms.single().vertexPositions
		val doubledPositions = doubled.document.artMeshes.single().keyforms.single().vertexPositions
		val coordinateIndex = defaultedPositions.indices.first { index -> abs(defaultedPositions[index]) > 1e-6f }
		assertEquals(
			defaultedPositions[coordinateIndex] / 2f,
			doubledPositions[coordinateIndex],
			1e-6f,
			"the geometry did not follow the overridden scale",
		)
	}

	/** Opting the cdi3 out removes the file and the manifest's reference together. */
	@Test
	fun omittingTheDisplayInfoDropsTheFileAndTheManifestReference() {
		val partId = PartId("Part1")
		val mesh = drawable("ArtMesh1")
		val puppet =
			puppetOf(
				parts = listOf(Part(id = partId, name = "Part1", children = listOf(OrgChild.Drawable(mesh.id)))),
				drawables = listOf(mesh),
			)

		val bundle =
			Moc3Sidecars.bundle(
				puppet,
				basename = "rig",
				version = MocVersion.V50,
				pages = emptyList(),
				options = Moc3ExportOptions(includeDisplayInfo = false),
			)

		assertTrue(bundle.files.none { file -> file.name == "rig.cdi3.json" }, "the opted-out cdi3 was still written")
		val manifestFile = bundle.files.first { file -> file.name == "rig.model3.json" }
		val manifest = Moc3.readModel3(manifestFile.bytes.decodeToString())
		assertNull(manifest.fileReferences.displayInfo, "the manifest still references the opted-out cdi3")
	}
}