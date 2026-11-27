package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.umamo.edit.EditorMode
import org.umamo.edit.editsElements
import org.umamo.ui.properties.MutedNote
import org.umamo.ui.properties.PropertyContext
import org.umamo.ui.properties.TransformSection
import org.umamo.ui.resources.*

/**
 * The Item tab's Transform panel: in Object mode the Properties panel's own Transform rows for the shown item
 * ([SidebarContext.shownTarget]), so both panels edit the same values through the same writes; in Edit and Pose
 * mode the selected vertices or lattice points ([EditMedianPanel]).
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun ItemTransformPanel(context: SidebarContext) {
	if (context.mode.editsElements) {
		EditMedianPanel(context)

		return
	}

	val shown = context.shownTarget

	if (shown == null) {
		MutedNote(Res.string.sidebar_item_nothing_selected)

		return
	}

	val propertyContext = PropertyContext(context.puppet, context.selection, shown, context.session)
	val rows = TransformSection.rows(propertyContext)

	if (rows.isEmpty()) {
		MutedNote(Res.string.properties_transform_unavailable)

		return
	}

	for ((rowIndex, row) in rows.withIndex()) {
		key(rowIndex) {
			row.content(propertyContext)
		}
	}
}

/**
 * The UV editor's Item tab Transform panel: in Edit mode the selected texture coordinates (see [UvMedianPanel]),
 * in Object mode the shown drawable's placement on its page (see [UvPlacementPanel]).
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun UvItemTransformPanel(context: SidebarContext) {
	if (context.mode == EditorMode.Edit) {
		UvMedianPanel(context)

		return
	}

	UvPlacementPanel(context)
}