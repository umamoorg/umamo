package org.umamo.ui.graphics

import androidx.compose.ui.graphics.Color
import org.umamo.runtime.model.ColorRgb

/**
 * Converts the model's [ColorRgb] (three 0..1 float channels, no alpha) to a Compose [Color], forced
 * opaque - the model-to-UI half of the bridge the UI edits composite tint colors through.
 *
 * @return Color The opaque Compose color with the same red / green / blue.
 */
fun ColorRgb.toComposeColor(): Color = Color(red = red, green = green, blue = blue, alpha = 1f)

/**
 * Converts a Compose [Color] back to the model's [ColorRgb], dropping the alpha channel ([ColorRgb] has
 * none - the source formats' alpha is always opaque).  The UI-to-model half of the composite color bridge.
 *
 * @return ColorRgb The red / green / blue as 0..1 floats.
 */
fun Color.toColorRgb(): ColorRgb = ColorRgb(red = red, green = green, blue = blue)