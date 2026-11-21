package org.umamo.ui.kit.field

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalThemeMode
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.UmamoTheme
import java.io.File
import kotlin.test.Test

/** THROWAWAY probe for the repeat accent's screenshot pass.  Never committed. */
class RepeatAccentProbeTest {
	private val outDir = File("/tmp/claude-1000/-home-alexia-Repositories-umamo/c627d229-5aa2-438e-b1ca-937345db2e7f/scratchpad/repeat-accent")

	private val marks = listOf(SliderKeyMark(0f, SliderKeyShape.Circle), SliderKeyMark(0.25f, SliderKeyShape.Circle), SliderKeyMark(1f, SliderKeyShape.Circle))

	@Test
	fun renderSheets() {
		outDir.mkdirs()
		val dark = listOf("accent (today)" to null, "#FCDD83 brand yellow" to Color(0xFFFCDD83))
		val light =
			listOf(
				"accent (today)" to null,
				"#FCDD83 brand yellow" to Color(0xFFFCDD83),
				"#C8992B" to Color(0xFFC8992B),
				"#B8860B" to Color(0xFFB8860B),
				"#A07A10" to Color(0xFFA07A10),
				"#8C6A0C" to Color(0xFF8C6A0C),
			)
		render("dark", dark)
		render("light", light)
	}

	private fun render(mode: String, candidates: List<Pair<String, Color?>>) {
		val scale = 2f
		val widthDp = 800
		val heightDp = 40 + candidates.size * 150
		val scene =
			ImageComposeScene(width = (widthDp * scale).toInt(), height = (heightDp * scale).toInt(), density = Density(scale)) {
				CompositionLocalProvider(LocalThemeMode provides mode) {
					UmamoTheme {
						Sheet(candidates)
					}
				}
			}
		val image = scene.render()
		val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
		File(outDir, "repeat-accent-$mode.png").writeBytes(bytes)
		scene.close()
	}

	@Composable
	private fun Sheet(candidates: List<Pair<String, Color?>>) {
		val colors = LocalUmamoColors.current
		Column(
			modifier = Modifier.background(colors.panelBackground).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			for ((label, candidate) in candidates) {
				Row(
					modifier = Modifier.background(colors.headerBackground).padding(8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(16.dp),
				) {
					Text(text = label, color = colors.text, modifier = Modifier.width(140.dp))
					Slider(value = 0.6f, onValueChange = {}, keyMarks = marks, accentColor = candidate ?: Color.Unspecified, modifier = Modifier.width(200.dp))
					Dial(value = 0.6f, onValueChange = {}, keyMarks = marks, accentColor = candidate ?: Color.Unspecified, modifier = Modifier.size(120.dp))
					Pad2D(
						xValue = 0.3f,
						yValue = 0.6f,
						onChange = { _, _ -> },
						xKeyMarks = marks,
						yKeyMarks = marks,
						accentColor = candidate ?: Color.Unspecified,
						modifier = Modifier.width(150.dp),
					)
					Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Swatch(candidate ?: colors.accent, "this")
						Swatch(Color(0xFFD1B727), "on-key")
						Swatch(Color(0xFFD9A62B), "caution")
					}
				}
			}
		}
	}

	@Composable
	private fun Swatch(color: Color, label: String) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			Box(modifier = Modifier.size(16.dp).background(color))
			Text(text = label, color = LocalUmamoColors.current.textMuted)
		}
	}
}