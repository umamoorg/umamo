package org.umamo.ui.help

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.button.CloseButton
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.credits_title
import org.umamo.ui.resources.dialog_close
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The Help → Credits dialog: the bundled CREDITS.md (the single consolidated home of credits and
 * third-party license attributions) rendered as a scrollable sheet, in the same scrim-plus-card
 * modal family as the settings window.  The file is read from the build-time-bundled compose
 * resource, so editing the repo-root CREDITS.md and rebuilding is the whole update path.
 *
 * The rendering is deliberately line-based, not a Markdown engine: headings get title styles, the
 * fenced license block gets monospace, everything else is body text - sufficient for this one
 * document and nothing more to maintain.
 *
 * クレジットダイアログ。ビルド時に同梱した CREDITS.md をスクロール表示する（Markdown エンジンは使わず、
 * 行単位の簡易整形のみ）。
 *
 * @param Function onDismiss Closes the dialog (Escape is routed here by the shell; also the scrim / close button).
 */
@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	// Null until the resource read lands (one frame, tiny file); rendered as an empty sheet meanwhile.
	val creditsText by produceState<String?>(initialValue = null) {
		value =
			runCatching { Res.readBytes("files/CREDITS.md").decodeToString() }
				.getOrElse { failure -> "CREDITS.md could not be loaded: ${failure.message}" }
	}
	Box(
		modifier =
			Modifier
				.fillMaxSize()
				.background(colors.overlayScrim)
				.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			modifier =
				Modifier
					.fillMaxWidth(0.82f)
					.fillMaxHeight(0.82f)
					.widthIn(max = 760.dp)
					.heightIn(max = 560.dp)
					.clickable(enabled = false, onClick = {}),
			color = colors.panelBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				Row(
					modifier = Modifier.fillMaxWidth().background(colors.headerBackground).padding(horizontal = 16.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(text = stringResource(Res.string.credits_title), style = typography.titleSmall, modifier = Modifier.weight(1f))
					CloseButton(onClick = onDismiss, contentDescription = stringResource(Res.string.dialog_close))
				}
				Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
				Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(20.dp)) {
					val scrollState = rememberScrollState()
					Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
						CreditsBody(creditsText.orEmpty())
					}
					VerticalScrollbarOverlay(scrollState)
				}
			}
		}
	}
}

/**
 * Renders the credits text line by line: "# " and "## " headings get title/label styles (with
 * breathing room above subsections), ``` fences toggle a monospace style for the embedded license
 * block (the fence lines themselves are dropped), blank lines become spacers, and everything else is
 * small body text.
 *
 * @param String text The full CREDITS.md contents.
 */
@Composable
private fun CreditsBody(text: String) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val monospace = typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
	var insideFence = false
	for (line in text.lines()) {
		when {
			line.trimStart().startsWith("```") -> {
				insideFence = !insideFence
			}

			insideFence -> {
				Text(text = line, style = monospace, color = colors.textMuted)
			}

			line.startsWith("## ") -> {
				Spacer(modifier = Modifier.height(12.dp))
				Text(text = line.removePrefix("## "), style = typography.labelMedium)
				Spacer(modifier = Modifier.height(4.dp))
			}

			line.startsWith("# ") -> {
				Text(text = line.removePrefix("# "), style = typography.titleSmall)
				Spacer(modifier = Modifier.height(4.dp))
			}

			line.isBlank() -> {
				Spacer(modifier = Modifier.height(6.dp))
			}

			else -> {
				Text(text = line, style = typography.bodySmall)
			}
		}
	}
}
