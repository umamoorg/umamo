package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/** Edge length of a bound row's type glyph, matching [RelationField]'s. */
private val RELATION_ROW_GLYPH_SIZE = 14.dp

/** The bound-list height a relation list opens at. */
private val RELATION_LIST_DEFAULT_HEIGHT = 76.dp

/** The shortest the bound list may be dragged (about two rows). */
private val RELATION_LIST_MIN_HEIGHT = 44.dp

/** The tallest the bound list may be dragged. */
private val RELATION_LIST_MAX_HEIGHT = 320.dp

/** The grab handle's strip height at the bottom of the bound list. */
private val RELATION_GRIP_HEIGHT = 10.dp

/**
 * The multi-value sibling of [RelationField]: a scrollable list of bound entities, each row carrying its
 * type glyph, its name, and a remove button, over an add field that searches the remaining candidates and
 * offers the same eyedropper.  A grab handle under the list drags its height, so a long list can be given
 * room without the whole panel growing.
 *
 * The bound list is a set, not a sequence - for clip masks the order does not affect coverage - so rows are
 * not reorderable.
 *
 * [RelationField] の複数値版。束縛済みの一覧（種別アイコン・名前・削除）と、追加用の検索欄からなる。
 *
 * @param T The bound entity type.
 * @param List entries The currently bound entities, in display order.
 * @param List candidates The entities the add field offers (the caller excludes those already bound).
 * @param Function label The display name of an entity (also what the add field's search matches).
 * @param Function icon The type glyph of an entity.
 * @param Function onAdd Binds an entity.
 * @param Function onRemove Unbinds an entity.
 * @param Modifier modifier The layout modifier.
 * @param Function? onPick Arms an eyedropper pick, or null to omit the eyedropper.
 * @param Boolean picking Whether a pick armed by THIS field is in flight.
 * @param String addPlaceholder The muted text shown in the empty add field.
 * @param String emptyLabel The muted text shown when nothing is bound.
 * @param String removeDescription The accessible label of a row's remove button.
 */
@Composable
fun <T> RelationListField(
	entries: List<T>,
	candidates: List<T>,
	label: (T) -> String,
	icon: (T) -> UmamoIcon,
	onAdd: (T) -> Unit,
	onRemove: (T) -> Unit,
	modifier: Modifier = Modifier,
	onPick: (() -> Unit)? = null,
	picking: Boolean = false,
	addPlaceholder: String = "",
	emptyLabel: String = "",
	removeDescription: String = "",
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val density = LocalDensity.current
	val scrollState = rememberScrollState()
	var listHeight by remember { mutableStateOf(RELATION_LIST_DEFAULT_HEIGHT) }
	// The grip drags the list's own height; the panel around it is untouched (this is not an area splitter).
	val dragState =
		remember {
			DraggableState { deltaPx ->
				val delta = with(density) { deltaPx.toDp() }
				listHeight = (listHeight + delta).coerceIn(RELATION_LIST_MIN_HEIGHT, RELATION_LIST_MAX_HEIGHT)
			}
		}

	Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		RelationField(
			selected = null,
			candidates = candidates,
			label = label,
			icon = icon,
			onSelect = { candidate -> candidate?.let(onAdd) },
			modifier = Modifier.fillMaxWidth(),
			onPick = onPick,
			picking = picking,
			placeholder = addPlaceholder,
		)
		Box(
			modifier =
				Modifier
					.fillMaxWidth()
					.height(listHeight)
					.clip(shapes.small)
					.background(colors.tabBackground)
					.border(1.dp, colors.controlBorder, shapes.small),
		) {
			Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(2.dp)) {
				if (entries.isEmpty()) {
					Text(
						text = emptyLabel,
						style = typography.bodySmall,
						color = colors.textDisabled,
						maxLines = 1,
						modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
					)
				} else {
					for (entry in entries) {
						key(label(entry)) {
							RelationBoundRow(
								label = label(entry),
								icon = icon(entry),
								removeDescription = removeDescription,
								onRemove = { onRemove(entry) },
							)
						}
					}
				}
			}
			VerticalScrollbarOverlay(scrollState)
		}
		// The resize grab handle: Blender puts it under the list, centred.
		Box(
			modifier =
				Modifier
					.fillMaxWidth()
					.height(RELATION_GRIP_HEIGHT)
					.pointerHoverIcon(PointerIcon.Hand)
					.draggable(state = dragState, orientation = Orientation.Vertical),
			contentAlignment = Alignment.Center,
		) {
			Canvas(modifier = Modifier.size(RELATION_ROW_GLYPH_SIZE)) {
				drawIcon(LocalUmamoIcons.gripHorizontal, colors.textMuted)
			}
		}
	}
}

/**
 * One bound row inside a [RelationListField]: the entity's type glyph and name, with a trailing remove.
 *
 * @param String label The entity's display name.
 * @param UmamoIcon icon The entity's type glyph.
 * @param String removeDescription The accessible label of the remove button.
 * @param Function onRemove Unbinds this entity.
 */
@Composable
private fun RelationBoundRow(label: String, icon: UmamoIcon, removeDescription: String, onRemove: () -> Unit) {
	val colors = LocalUmamoColors.current
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Canvas(modifier = Modifier.size(RELATION_ROW_GLYPH_SIZE)) {
			drawIcon(icon, colors.textMuted)
		}
		Spacer(modifier = Modifier.width(6.dp))
		Text(
			text = label,
			style = LocalUmamoTypography.current.bodySmall,
			color = colors.text,
			maxLines = 1,
			modifier = Modifier.weight(1f),
		)
		IconButton(
			icon = LocalUmamoIcons.close,
			onClick = onRemove,
			contentDescription = removeDescription,
			size = DpSize(16.dp, 16.dp),
			glyphSize = 12.dp,
		)
	}
}

/** The height a relation list opens at, exposed so a caller can reason about its default footprint. */
val relationListDefaultHeight: Dp get() = RELATION_LIST_DEFAULT_HEIGHT
