package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.umamo.edit.EditorMode
import org.umamo.ui.properties.MutedNote
import org.umamo.ui.properties.PropertyContext
import org.umamo.ui.properties.TransformSection
import org.umamo.ui.resources.*

/**
 * The Item tab's Transform panel.  In Object mode it draws the Properties panel's own Transform rows for the item
 * shown, the active item or the one last made active (see [SidebarContext.shownTarget]), so the two panels show
 * and edit the same values through the same writes; with no item to show it says so, and an item with no
 * transform rows says that instead.  In Edit mode it shows the selected vertices instead (see [EditMedianPanel]).
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun ItemTransformPanel(context: SidebarContext) {
	if (context.mode == EditorMode.Edit) {
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