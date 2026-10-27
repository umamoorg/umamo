package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.umamo.ui.properties.PropertyContext
import org.umamo.ui.properties.TransformSection
import org.umamo.ui.resources.*

/**
 * The Item tab's Transform panel: the Properties panel's own Transform rows for the item shown, the active
 * item or the one last made active (see [SidebarContext.shownTarget]), so the two panels show and edit the
 * same values through the same writes.  With no item to show it says so; an item with no transform rows says
 * that instead.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun ItemTransformPanel(context: SidebarContext) {
	val shown = context.shownTarget

	if (shown == null) {
		PanelHint(Res.string.sidebar_item_nothing_selected)

		return
	}

	val propertyContext = PropertyContext(context.puppet, context.selection, shown, context.session)
	val rows = TransformSection.rows(propertyContext)

	if (rows.isEmpty()) {
		PanelHint(Res.string.properties_transform_unavailable)

		return
	}

	for ((rowIndex, row) in rows.withIndex()) {
		key(rowIndex) {
			row.content(propertyContext)
		}
	}
}