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
 * A generic centered-label space body, used for spaces not yet built out (and as the viewport's
 * unavailable state). Deliberately tiny so any space can fall back to it during the staged build.
 *
 * 未実装の空間や、ビューポート不可状態に使う中央ラベルのプレースホルダ。
 *
 * @param String label The text to show centered.
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
