package org.umamo.ui.help

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.storage.UmamoLog
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.CloseButton
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.about_license
import org.umamo.ui.resources.about_title
import org.umamo.ui.resources.about_version
import org.umamo.ui.resources.app_name
import org.umamo.ui.resources.dialog_close
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The Help → About dialog: application name, version, license line, and the project URLs, in the
 * same scrim-plus-card modal family as the settings window (opened by the help.about command; the
 * shell owns the visible state and routes Escape, this composable only renders).
 *
 * バージョン情報ダイアログ。名称・バージョン・ライセンス・URL を設定ウィンドウと同系のモーダルで表示する。
 *
 * @param Function onDismiss Closes the dialog (Escape is routed here by the shell; also the scrim / close button).
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	Box(
		modifier =
			Modifier
				.fillMaxSize()
				.background(colors.overlayScrim)
				.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			modifier = Modifier.width(420.dp).clickable(enabled = false, onClick = {}),
			color = colors.panelBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxWidth()) {
				Row(
					modifier = Modifier.fillMaxWidth().background(colors.headerBackground).padding(horizontal = 16.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(text = stringResource(Res.string.about_title), style = typography.titleSmall, modifier = Modifier.weight(1f))
					CloseButton(onClick = onDismiss, contentDescription = stringResource(Res.string.dialog_close))
				}
				Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
				Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(text = stringResource(Res.string.app_name), style = typography.titleSmall)
					Text(text = stringResource(Res.string.about_version, ProjectInfo.VERSION), style = typography.bodyMedium)
					Text(text = stringResource(Res.string.about_license), style = typography.bodySmall, color = colors.textMuted)
					LinkLine(ProjectInfo.WEB_SITE_URL)
					LinkLine(ProjectInfo.SOURCE_CODE_URL)
				}
			}
		}
	}
}

/**
 * One clickable URL line: shown accent-colored and opened through the platform UriHandler on click,
 * failing quietly (a log line, never a crash) when the platform refuses - the same policy as the
 * Help menu's link items.
 *
 * @param String url The URL shown and opened.
 */
@Composable
private fun LinkLine(url: String) {
	val uriHandler = LocalUriHandler.current
	Text(
		text = url,
		style = LocalUmamoTypography.current.bodySmall,
		color = LocalUmamoColors.current.accent,
		modifier =
			Modifier.clickable {
				runCatching { uriHandler.openUri(url) }.onFailure { failure -> UmamoLog.error("could not open $url", failure) }
			},
	)
}
