package org.umamo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.help.ProjectInfo
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.Button
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.app_icon
import org.umamo.ui.resources.app_name
import org.umamo.ui.resources.quick_setup_artist_credit
import org.umamo.ui.resources.quick_setup_continue
import org.umamo.ui.resources.quick_setup_title
import org.umamo.ui.resources.splash_banner
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/** The banner's width-to-height ratio. */
private const val SPLASH_BANNER_ASPECT = 2f

/** The card's widest extent; the banner art is authored at three times this width (see [SplashBanner]). */
private val QUICK_SETUP_MAX_WIDTH = 560.dp

/**
 * The Quick Setup modal a first run opens with (and Help > Quick Setup... reopens): a banner over the few
 * choices worth making before anything else, in the preferences window's own rows.  Like that window, every
 * change writes through to settings at once, so the app re-localizes and re-themes live behind the scrim and
 * every way out only closes it.  A first run opens with the language already matched from the operating
 * system (see seedFirstRunSettings).
 *
 * @param Function onDismiss Closes the modal.
 */
@Composable
fun QuickSetupDialog(onDismiss: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	Box(
		// indication = null: the kit convention for a full-window scrim (see SettingsWindow).
		modifier =
			Modifier
				.fillMaxSize()
				.background(colors.overlayScrim)
				.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			// 90% of the window, but never wider than the cap.  The order matters: fillMaxWidth pins an exact width
			// that a later widthIn cannot shrink, so wrapContentWidth frees it first and the cap then applies to
			// what is left.  The card swallows clicks (enabled = false) so a press inside it is not read as a scrim
			// dismissal; the freed space beside a capped card has no handler, so a press there reaches the scrim.
			modifier =
				Modifier
					.fillMaxWidth(0.9f)
					.wrapContentWidth()
					.widthIn(max = QUICK_SETUP_MAX_WIDTH)
					.clickable(enabled = false, onClick = {}),
			color = colors.panelBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			// Scrolls only when the window is shorter than the card, so Continue stays reachable on a small screen.
			Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
				SplashBanner()
				Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
				Column(
					modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp),
					verticalArrangement = Arrangement.spacedBy(SETTING_ROW_SPACING),
				) {
					Text(text = stringResource(Res.string.quick_setup_title), style = typography.labelMedium, color = colors.textMuted)
					LanguageSettingRow()
					KeymapPresetSettingRow(quick = true)
					ThemeSettingRow()
					UpdateCheckSettingRow()
				}
				Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
					// Importing the settings of a previous version belongs at the start of this row, where Blender
					// puts its "Load 3.3 Settings" button.  It has nothing to do while every build reads and writes
					// the one unversioned <config>/umamo/settings.json (see desktopAppStorage), so a new build already
					// starts on the old file; it becomes meaningful once versions keep their settings side by side.
					Spacer(modifier = Modifier.weight(1f))
					Button(label = stringResource(Res.string.quick_setup_continue), onClick = onDismiss, primary = true)
				}
			}
		}
	}
}

/**
 * The splash's banner that shows above the quick setup options.
 *
 * The art is drawable/splash_banner.png:
 *     1:1 at 560x280 pixels
 *     2:1 at 1120x560 pixels.
 */
@Composable
private fun SplashBanner() {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val badgeShape = RoundedCornerShape(4.dp)
	Box(modifier = Modifier.fillMaxWidth().aspectRatio(SPLASH_BANNER_ASPECT).background(colors.headerBackground)) {
		Image(
			bitmap = imageResource(Res.drawable.splash_banner),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			filterQuality = FilterQuality.Medium,
			modifier = Modifier.matchParentSize(),
		)
		Row(
			modifier =
				Modifier
					.align(Alignment.BottomStart)
					.padding(12.dp)
					.background(colors.viewportBadgeBackground, badgeShape)
					.padding(horizontal = 8.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Image(painter = painterResource(Res.drawable.app_icon), contentDescription = null, modifier = Modifier.size(36.dp))
			Spacer(modifier = Modifier.width(8.dp))
			Text(text = stringResource(Res.string.app_name), style = typography.titleLarge, color = colors.viewportBadgeText)
		}
		Text(
			text = ProjectInfo.VERSION,
			style = typography.bodySmall,
			color = colors.viewportBadgeText,
			modifier =
				Modifier
					.align(Alignment.TopEnd)
					.padding(10.dp)
					.background(colors.viewportBadgeBackground, badgeShape)
					.padding(horizontal = 6.dp, vertical = 2.dp),
		)
		Text(
			text = stringResource(Res.string.quick_setup_artist_credit),
			style = typography.bodySmall,
			color = colors.viewportBadgeText,
			modifier =
				Modifier
					.align(Alignment.BottomEnd)
					.padding(10.dp)
					.background(colors.viewportBadgeBackground, badgeShape)
					.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}