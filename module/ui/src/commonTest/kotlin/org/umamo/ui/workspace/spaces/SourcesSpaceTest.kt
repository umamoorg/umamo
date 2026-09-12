package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.model.percentOf
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.umamoDarkColors
import org.umamo.ui.theme.umamoLightColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit-tests the Sources space's pure pieces: the row visual (which glyph and traffic-light tint a
 * status reads as, and which word rides the tooltip) and the relink list's grouping by file.  Neither
 * needs a composition.
 */
class SourcesSpaceTest {
	private val icons = LocalUmamoIcons
	private val colors = umamoDarkColors
	private val artA = ArtSourceId("art-0")

	private fun node(kind: SourcesNodeKind, status: SourcesStatus): SourcesNode = SourcesNode("row", "Row", SourcesDetail.None, kind, status, emptyList())

	private fun layer(key: String, name: String): ArtSourceLayer = ArtSourceLayer(key, name, "", 0, 0, 4, 4, true)

	/** A file's presence swaps the glyph and tints only when the file is missing. */
	@Test
	fun fileRowsReadPresenceThroughTheGlyph() {
		val source = SourcesNodeKind.Source(artA)
		val missing = sourcesRowVisual(node(source, SourcesStatus.Missing), icons, colors)
		assertSame(icons.missingFile, missing.icon)
		assertEquals(colors.signalBad, missing.tint)
		assertSame(Res.string.sources_status_missing, missing.statusLabel)
		val present = sourcesRowVisual(node(source, SourcesStatus.Present), icons, colors)
		assertSame(icons.sources, present.icon)
		assertEquals(colors.text, present.tint)
		assertSame(Res.string.sources_status_present, present.statusLabel)
		val unknown = sourcesRowVisual(node(source, SourcesStatus.Unknown), icons, colors)
		assertSame(icons.sources, unknown.icon)
		assertEquals(colors.text, unknown.tint, "an unprobed file is not a missing one")
		assertSame(Res.string.sources_status_unknown, unknown.statusLabel)
	}

	/** Layer rows are the traffic light: green bound, amber bound by name, red unbound. */
	@Test
	fun layerRowsAreTheTrafficLight() {
		val layer = SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:1", true))
		val bound = sourcesRowVisual(node(layer, SourcesStatus.Bound), icons, colors)
		assertSame(icons.linked, bound.icon)
		assertEquals(colors.signalGood, bound.tint)
		assertSame(Res.string.sources_status_bound, bound.statusLabel)
		val byName = sourcesRowVisual(node(layer, SourcesStatus.BoundByName), icons, colors)
		assertSame(icons.linked, byName.icon)
		assertEquals(colors.signalCaution, byName.tint)
		assertSame(Res.string.sources_status_bound_unstable, byName.statusLabel)
		val unbound = sourcesRowVisual(node(layer, SourcesStatus.Unbound), icons, colors)
		assertSame(icons.unlinked, unbound.icon)
		assertEquals(colors.signalBad, unbound.tint)
		assertSame(Res.string.sources_status_unbound, unbound.statusLabel)
		val review = sourcesRowVisual(node(layer, SourcesStatus.NeedsReview), icons, colors)
		assertSame(icons.unlinked, review.icon)
		assertEquals(colors.signalCaution, review.tint, "a binding the file lost is caution, not broken: the tile keeps its art")
		assertSame(Res.string.sources_status_needs_review, review.statusLabel)
	}

	/** A tile on no page reads caution; a placed tile and a drawable carry no status at all. */
	@Test
	fun tilesAndDrawablesCarryAStatusOnlyWhenUnplaced() {
		val tile = SourcesNodeKind.Tile(AtlasTileId("t1"))
		val unplaced = sourcesRowVisual(node(tile, SourcesStatus.Unplaced), icons, colors)
		assertSame(icons.spaceTexture, unplaced.icon)
		assertEquals(colors.signalCaution, unplaced.tint)
		assertSame(Res.string.sources_status_unplaced, unplaced.statusLabel)
		val placed = sourcesRowVisual(node(tile, SourcesStatus.None), icons, colors)
		assertEquals(colors.text, placed.tint)
		assertNull(placed.statusLabel, "a placed tile shows no tooltip")
		val drawable = sourcesRowVisual(node(SourcesNodeKind.Drawable(DrawableId("a")), SourcesStatus.None), icons, colors)
		assertSame(icons.mesh, drawable.icon)
		assertNull(drawable.statusLabel)
		val group = sourcesRowVisual(node(SourcesNodeKind.UnboundGroup, SourcesStatus.None), icons, colors)
		assertSame(icons.unlinked, group.icon)
		assertEquals(colors.signalBad, group.tint, "the heading carries the group's status")
	}

	/** The signals are learned colors: identical in both schemes, and none of them is a keyframe color. */
	@Test
	fun signalColorsAreSharedAcrossSchemesAndDistinctFromKeyframes() {
		assertEquals(umamoDarkColors.signalGood, umamoLightColors.signalGood)
		assertEquals(umamoDarkColors.signalCaution, umamoLightColors.signalCaution)
		assertEquals(umamoDarkColors.signalBad, umamoLightColors.signalBad)
		val signals = setOf(colors.signalGood, colors.signalCaution, colors.signalBad)
		assertEquals(3, signals.size, "three distinct signals")
		val keyframes = setOf(colors.keyedOnKey, colors.keyedBetween, colors.keyedModified)
		assertTrue(signals.none { signal -> signal in keyframes }, "a signal never doubles as a keyframe state")
	}

	/** The relink list groups by file; the query keeps a layer by name or a whole file by its name. */
	@Test
	fun relinkGroupsByFileAndFiltersByLayerOrFileName() {
		val sources =
			listOf(
				ArtSource(artA, "Erica Tamamo.psd", null, "psd", listOf(layer("lyid:1", "Hair front"), layer("lyid:2", "Eye L"), layer("lyid:3", "Hair back"))),
				ArtSource(ArtSourceId("art-1"), "Extra parts.psd", null, "psd", listOf(layer("lyid:7", "Background"))),
				ArtSource(ArtSourceId("art-2"), "Empty.psd", null, "psd", emptyList()),
			)
		val all = relinkGroups(sources, "  ")
		assertEquals(listOf("Erica Tamamo.psd", "Extra parts.psd"), all.map { group -> group.source.name }, "a blank query lists every file that has layers")
		assertEquals(listOf("Hair front", "Eye L", "Hair back"), all[0].layers.map { layer -> layer.name })
		val byLayer = relinkGroups(sources, "hair")
		assertEquals(listOf("Erica Tamamo.psd"), byLayer.map { group -> group.source.name }, "a file with no matching layer is dropped")
		assertEquals(listOf("Hair front", "Hair back"), byLayer[0].layers.map { layer -> layer.name }, "only the matching layers survive, in order")
		val byFile = relinkGroups(sources, "EXTRA")
		assertEquals(listOf("Extra parts.psd"), byFile.map { group -> group.source.name })
		assertEquals(listOf("Background"), byFile[0].layers.map { layer -> layer.name }, "a file-name match keeps every layer of that file")
		assertTrue(relinkGroups(sources, "nothing here").isEmpty())
	}

	/** A row the file lost is kept for review, never offered as a relink target. */
	@Test
	fun lostRowsAreNeverRelinkTargets() {
		val sources = listOf(ArtSource(artA, "a.psd", null, "psd", listOf(layer("lyid:1", "Hair"), layer("lyid:2", "Old hair").copy(present = false))))
		assertEquals(listOf("Hair"), relinkGroups(sources, "").single().layers.map { layer -> layer.name })
		assertTrue(relinkGroups(sources, "old").isEmpty())
	}

	/** The chip's confidence is a whole percentage, rounded, never past the ends. */
	@Test
	fun suggestionScoresReadAsWholePercentages() {
		assertEquals(92, percentOf(0.924f))
		assertEquals(93, percentOf(0.925f))
		assertEquals(100, percentOf(1.2f))
		assertEquals(0, percentOf(-0.1f))
	}
}