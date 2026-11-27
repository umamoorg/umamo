package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A centered-label space body for a space with nothing of its own to show: Tool Details, which is not
 * built yet, the UV editor with no render service to draw with, and Properties when its search matches
 * no row.  Deliberately tiny, so any space can fall back to it.
 *
 * @param String label The text to show centered; empty for a bare panel.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun PlaceholderSpace(label: String, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier.fillMaxSize().padding(8.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.titleSmall,
			textAlign = TextAlign.Center,
		)
	}
}