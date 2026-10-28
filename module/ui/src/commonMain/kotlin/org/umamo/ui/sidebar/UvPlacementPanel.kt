package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.SelectionTarget
import org.umamo.edit.atlas.setAtlasPlacements
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.ui.kit.StackPosition
import org.umamo.ui.kit.field.AspectLockFieldPair
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.properties.MutedNote
import org.umamo.ui.properties.TexelPositionFields
import org.umamo.ui.properties.UNBOUNDED_RANGE
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.uv.LocalUvSidebarSurface
import org.umamo.ui.viewport.uv.TexelAxis

/**
 * The UV editor's Item tab in Object mode: where the shown drawable's art sits on its page, the tile placement the
 * UV editor's Object-mode Grab, Rotate, and Scale move.  Position is where the art's origin lands, in page pixels
 * from the top-left with y down; Angle turns the art about that origin; Scale sizes it along its own axes, a
 * negative scale mirroring it, and its lock keeps the two in proportion.  Each typed value is one undo step filed as the matching move, and the pages
 * recompose from it.  While the area's own placement drag runs, the numbers follow it.
 *
 * The fields are dimmed wherever the UV editor's own placement drag would refuse: a pinned tile, a document whose
 * texture coordinates address the source layers rather than the pages, a view that is not a page with source art
 * to recompose it from, a tile on another page than the one shown, or while a UV transform runs.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun UvPlacementPanel(context: SidebarContext) {
	val session = context.session ?: return
	val surface = LocalUvSidebarSurface.current ?: return
	val shown = context.shownTarget

	if (shown == null) {
		MutedNote(Res.string.sidebar_item_nothing_selected)

		return
	}

	val puppet = context.puppet
	val drawable = (shown as? SelectionTarget.Drawable)?.let { target -> puppet.drawables.firstOrNull { candidate -> candidate.id == target.id } }
	val tile = drawable?.atlasTileId?.let { tileId -> puppet.atlas.tileById[tileId] }

	if (drawable == null || tile == null) {
		MutedNote(Res.string.properties_transform_unavailable)

		return
	}

	val committed = tile.placement

	if (committed == null) {
		MutedNote(Res.string.sidebar_uv_unplaced)

		return
	}

	val uvOperator by session.activeUvOperator.collectAsState()
	// The area's own placement drag shows where it would put the tile; any other time, the committed placement.
	val placement = surface.placementScene.drag?.result?.placementByTile?.get(tile.id) ?: committed
	val editable =
		!tile.pinned &&
			puppet.atlas.storedUvsAddressPages &&
			surface.placementSurface != null &&
			surface.geometries.any { geometry -> geometry.drawableId == drawable.id } &&
			uvOperator == null

	/**
	 * Lands [updated] as the tile's placement, one undo step under [kind]'s name.  Nothing lands when it is the
	 * placement the tile already has.
	 *
	 * @param AtlasPlacement updated The new placement.
	 * @param MeshOperatorKind kind The move it files as.
	 */
	fun commit(updated: AtlasPlacement, kind: MeshOperatorKind) {
		if (!editable || updated == committed) {
			return
		}

		session.setAtlasPlacements(mapOf(tile.id to updated), kind)
	}

	TexelPositionFields(
		texelX = placement.positionX,
		texelY = placement.positionY,
		xDescription = Res.string.sidebar_placement_x_description,
		yDescription = Res.string.sidebar_placement_y_description,
		enabled = editable,
		onCommit = { axis, value ->
			val updated =
				when (axis) {
					TexelAxis.X -> committed.copy(positionX = value)
					TexelAxis.Y -> committed.copy(positionY = value)
				}
			commit(updated, MeshOperatorKind.Grab)
		},
	)
	// A zero scale would collapse the art to a line no mapping can invert, so it is refused; a negative one
	// mirrors the art and is kept.
	AspectLockFieldPair(
		firstLabel = stringResource(Res.string.placement_options_scale_x),
		firstDescription = stringResource(Res.string.sidebar_placement_scale_x_description),
		firstValue = placement.scaleX,
		secondLabel = stringResource(Res.string.placement_options_scale_y),
		secondDescription = stringResource(Res.string.sidebar_placement_scale_y_description),
		secondValue = placement.scaleY,
		range = UNBOUNDED_RANGE,
		decimals = 2,
		enabled = editable,
		onCommit = { scaleX, scaleY ->
			if (scaleX != 0f && scaleY != 0f) {
				commit(committed.copy(scaleX = scaleX, scaleY = scaleY), MeshOperatorKind.Scale)
			}
		},
	)
	FieldStack(
		listOf(
			{ position ->
				PlacementNumberRow(
					label = Res.string.placement_options_angle,
					description = Res.string.sidebar_placement_angle_description,
					value = placement.rotationDegrees,
					decimals = 1,
					unitSuffix = stringResource(Res.string.unit_degrees),
					enabled = editable,
					position = position,
				) { angle -> commit(committed.copy(rotationDegrees = angle), MeshOperatorKind.Rotate) }
			},
		),
	)
}

/**
 * One labelled placement number: a field that shows a scrub's draft and commits once, on release.
 *
 * @param StringResource label The row's label.
 * @param StringResource description What the number measures, for its tooltip.
 * @param Float value The number shown.
 * @param Int decimals The decimals shown.
 * @param String? unitSuffix The unit after the number, or null with none.
 * @param Boolean enabled Whether the field accepts input.
 * @param StackPosition position The field's place in its stack.
 * @param Function onCommit Called with the typed, stepped, or released value.
 */
@Composable
private fun PlacementNumberRow(
	label: StringResource,
	description: StringResource,
	value: Float,
	decimals: Int,
	unitSuffix: String? = null,
	enabled: Boolean,
	position: StackPosition,
	onCommit: (Float) -> Unit,
) {
	PropertyFieldRow(stringResource(label), description = stringResource(description)) {
		NumberField(
			value = value,
			onValueChange = onCommit,
			modifier = Modifier.fillMaxWidth(),
			range = UNBOUNDED_RANGE,
			decimals = decimals,
			unitSuffix = unitSuffix,
			enabled = enabled,
			stackPosition = position,
		)
	}
}