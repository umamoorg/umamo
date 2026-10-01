package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.resources.*
import org.umamo.ui.theme.UmamoColors
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.UmamoIcons

/**
 * How a row's leading icon reads: the glyph, its tint, and the status word the glyph's tooltip carries.
 *
 * @property UmamoIcon       icon        The glyph.
 * @property Color           tint        The glyph's color.
 * @property StringResource? statusLabel The row's status as a tooltip, or null for a row with none.
 */
internal class SourcesRowVisual(
	val icon: UmamoIcon,
	val tint: Color,
	val statusLabel: StringResource?,
)

/**
 * The icon a row draws with, carrying the row's status the way a traffic light does: green for a
 * layer bound by a stable key, amber for one bound by name (a binding that holds only while the
 * layer keeps its name and place), a tile on no page or bound to a file the document does not list, or a
 * binding whose layer the file lost, red for
 * an unbound layer, a missing file, or the unbound-art group, and the muted text color for a layer the
 * rigger ignored.  The glyph itself already says what the
 * row is - a file, a link, a tile, a mesh - and a missing file swaps to the missing-file glyph, so the
 * status word is a tooltip, never row text.  Pure, so the mapping is testable without a composition.
 *
 * @param SourcesNode node   The row.
 * @param UmamoIcons  icons  The icon set.
 * @param UmamoColors colors The palette.
 * @return SourcesRowVisual The glyph, tint, and tooltip.
 */
internal fun sourcesRowVisual(node: SourcesNode, icons: UmamoIcons, colors: UmamoColors): SourcesRowVisual =
	when (node.kind) {
		is SourcesNodeKind.Source ->
			when (node.status) {
				SourcesStatus.Missing -> SourcesRowVisual(icons.missingFile, colors.signalBad, Res.string.sources_status_missing)
				SourcesStatus.Unknown -> SourcesRowVisual(icons.sources, colors.text, Res.string.sources_status_unknown)
				else -> SourcesRowVisual(icons.sources, colors.text, Res.string.sources_status_present)
			}
		is SourcesNodeKind.Layer ->
			when (node.status) {
				SourcesStatus.Unbound -> SourcesRowVisual(icons.unlinked, colors.signalBad, Res.string.sources_status_unbound)
				SourcesStatus.BoundByName -> SourcesRowVisual(icons.linked, colors.signalCaution, Res.string.sources_status_bound_unstable)
				// The tile is bound, but to a layer its file no longer lists: linked to nothing, waiting on a decision.
				SourcesStatus.NeedsReview -> SourcesRowVisual(icons.unlinked, colors.signalCaution, Res.string.sources_status_needs_review)
				// Bound to a layer the file still has but erased: the same wait, with a different reason on the tooltip.
				SourcesStatus.Emptied -> SourcesRowVisual(icons.unlinked, colors.signalCaution, Res.string.sources_status_emptied)
				// Bound to a key the replacement file does not mint: the same wait again, and the tooltip says so.
				SourcesStatus.SourceReplaced -> SourcesRowVisual(icons.unlinked, colors.signalCaution, Res.string.sources_status_replaced)
				// Kept out of the rig on purpose: no signal color, since the row is settled rather than waiting.
				SourcesStatus.Ignored -> SourcesRowVisual(icons.unlinked, colors.textMuted, Res.string.sources_status_ignored)
				else -> SourcesRowVisual(icons.linked, colors.signalGood, Res.string.sources_status_bound)
			}
		is SourcesNodeKind.Tile ->
			when (node.status) {
				SourcesStatus.Unplaced -> SourcesRowVisual(icons.spaceTexture, colors.signalCaution, Res.string.sources_status_unplaced)
				// Bound to a file the document does not list: the binding waits on a decision, like a lost layer's.
				SourcesStatus.SourceNotListed -> SourcesRowVisual(icons.spaceTexture, colors.signalCaution, Res.string.sources_status_source_not_listed)
				else -> SourcesRowVisual(icons.spaceTexture, colors.text, null)
			}
		is SourcesNodeKind.Drawable -> SourcesRowVisual(icons.mesh, colors.outlinerObjectTint, null)
		// Every tile under the group is unbound; the one red marker at the heading is the group's status.
		SourcesNodeKind.UnboundGroup -> SourcesRowVisual(icons.unlinked, colors.signalBad, Res.string.sources_status_unbound)
	}