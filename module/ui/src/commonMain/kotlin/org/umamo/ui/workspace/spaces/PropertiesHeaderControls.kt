package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.TextField
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.properties.PROPERTIES_VIEW_STATE_KEY
import org.umamo.ui.properties.PropertiesViewState
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.workspace.AreaScope

/**
 * The Properties panel's area-header controls (mounted via SpaceDescriptor.headerContent): the property
 * search field, right-aligned in the header's flexible middle.  Reads and writes the area's shared
 * [PropertiesViewState] so the body filters live and auto-switches to a tab with matches; renders nothing
 * without an open document, matching the other header controls.
 *
 * プロパティパネルのエリアヘッダ内容。右寄せの検索フィールド。エリア共有の PropertiesViewState を読み書きする。
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 */
@Composable
internal fun RowScope.PropertiesHeaderControls(scope: AreaScope) {
	if (LocalPuppet.current == null) {
		return
	}
	val viewState = scope.spaceState(PROPERTIES_VIEW_STATE_KEY) { PropertiesViewState() }
	// A leading weight spacer right-aligns the search field within the header slot.
	Spacer(modifier = Modifier.weight(1f))
	// Fixed width rather than weight: the field must leave room for the space-picker chip in a narrow area.
	Box(modifier = Modifier.width(160.dp)) {
		TextField(
			value = viewState.query,
			onValueChange = { newQuery -> viewState.query = newQuery },
			modifier = Modifier.fillMaxWidth(),
			placeholder = stringResource(Res.string.search_hint),
		)
		// A clear (X) affordance at the field's trailing edge, shown only when there is text to clear.
		if (viewState.query.isNotEmpty()) {
			IconButton(
				icon = LocalUmamoIcons.close,
				onClick = { viewState.query = "" },
				contentDescription = stringResource(Res.string.search_clear),
				modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
				size = DpSize(18.dp, 18.dp),
				glyphSize = 20.dp,
			)
		}
	}
	Spacer(modifier = Modifier.weight(1f))
}
