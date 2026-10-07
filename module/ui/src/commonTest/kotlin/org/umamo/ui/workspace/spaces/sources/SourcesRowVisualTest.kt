package org.umamo.ui.workspace.spaces.sources

import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.SourceLayerRef
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
 * Pins the row visual: which glyph and traffic-light tint a status reads as, and which word rides the
 * glyph's tooltip.  None needs a composition.
 */
class SourcesRowVisualTest {
	private val icons = LocalUmamoIcons
	private val colors = umamoDarkColors
	private val artA = ArtSourceId("art-0")

	private fun node(kind: SourcesNodeKind, status: SourcesStatus): SourcesNode = SourcesNode("row", "Row", SourcesDetail.None, kind, status, emptyList())

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
		val emptied = sourcesRowVisual(node(layer, SourcesStatus.Emptied), icons, colors)
		assertSame(icons.unlinked, emptied.icon)
		assertEquals(colors.signalCaution, emptied.tint)
		assertSame(Res.string.sources_status_emptied, emptied.statusLabel, "the same wait, its own reason")
		val replaced = sourcesRowVisual(node(layer, SourcesStatus.SourceReplaced), icons, colors)
		assertSame(icons.unlinked, replaced.icon)
		assertEquals(colors.signalCaution, replaced.tint)
		assertSame(Res.string.sources_status_replaced, replaced.statusLabel, "lost to a replacement: the same wait again, its own reason")
		val ignored = sourcesRowVisual(node(layer, SourcesStatus.Ignored), icons, colors)
		assertSame(icons.unlinked, ignored.icon)
		assertEquals(colors.textMuted, ignored.tint, "settled by the rigger, so no signal color")
		assertSame(Res.string.sources_status_ignored, ignored.statusLabel)
	}

	/** A tile bound to a file the document does not list reads caution, with its own reason on the tooltip. */
	@Test
	fun aTileBoundToAnUnlistedFileReadsCaution() {
		val visual = sourcesRowVisual(node(SourcesNodeKind.Tile(AtlasTileId("tile")), SourcesStatus.SourceNotListed), icons, colors)
		assertSame(icons.spaceTexture, visual.icon)
		assertEquals(colors.signalCaution, visual.tint)
		assertSame(Res.string.sources_status_source_not_listed, visual.statusLabel)
		assertTrue(SourcesStatus.SourceNotListed.isReview, "it joins the review filter")
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
}