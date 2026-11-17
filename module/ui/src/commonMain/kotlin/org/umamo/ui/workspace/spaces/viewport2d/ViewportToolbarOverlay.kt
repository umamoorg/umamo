package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandIconButton
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.ObserveForCommands
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes

/** The square size of one toolbar button (generous for pen and touch). */
private val TOOLBAR_BUTTON_SIZE = 28.dp

/** The glyph size inside a toolbar button. */
private val TOOLBAR_GLYPH_SIZE = 18.dp

/**
 * The viewport's floating left tool toolbar (Blender's toolbar, reduced to a strip): the viewport tool family as
 * the registry lists it, the select tools then the modal transforms.  Each button is its command's - the glyph, the
 * label, lit while the tool is armed or in flight - so the buttons, shortcuts, pies, and palette all share one
 * behavior.
 *
 * A tool that does not apply is NOT drawn: the strip holds the tools of this mode, so a tool of another mode is
 * absent rather than grayed, and nobody meets a button that would refuse.  The palette hides an inapplicable
 * command the same way.  With no open document the whole strip is drawn grayed, so the chrome reads the same before
 * the first file opens.
 *
 * The caller gates the strip's visibility and mounts it in a [org.umamo.ui.kit.PointerShield], keeping
 * this composable a pure strip.
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun ViewportToolbarOverlay(modifier: Modifier = Modifier) {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	// Each change recomposes the strip, whose buttons then ask their commands which tool is armed or in flight.
	ObserveForCommands(
		session?.activeSelectTool?.collectAsState()?.value,
		session?.activeMeshOperator?.collectAsState()?.value,
		session?.activeObjectOperator?.collectAsState()?.value,
		session?.activeBrush?.collectAsState()?.value,
		// The mode and the element selection too: a button's enabled state is read when it is composed, and the
		// Deform brush's command applies only in Edit mode.  Without these the strip would keep the state its
		// buttons had when it was composed.
		session?.mode?.collectAsState()?.value,
		session?.meshSelection?.collectAsState()?.value,
	)
	Column(
		modifier =
			modifier
				.background(colors.panelBackground, shapes.small)
				.border(width = 1.dp, color = colors.panelBorder, shape = shapes.small)
				.padding(3.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		for (tool in commands.inGroup(CommandFamily.ViewportTool)) {
			// With a document open, a tool that does not apply is not drawn at all; with none, every tool is
			// drawn so the strip reads the same before the first file opens.
			if (session != null && !tool.availability.isAvailable()) {
				continue
			}

			CommandIconButton(
				command = tool,
				appearance = IconButtonAppearance.Filled(shapes.small),
				size = DpSize(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE),
				glyphSize = TOOLBAR_GLYPH_SIZE,
			)
		}
	}
}