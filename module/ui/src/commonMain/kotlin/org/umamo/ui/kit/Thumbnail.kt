package org.umamo.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import kotlin.math.ceil

/** The default square edge of a thumbnail slot (the overlap picker's compact preview). */
val DefaultThumbnailSize = 44.dp

/** The cell edge of the transparency checker drawn behind a thumbnail. */
private val ThumbnailCheckerCell = 6.dp

/**
 * A square art-mesh preview slot, shared by the overlap picker and the Outliner hover preview. When
 * [thumbnail] is present it is drawn over a small transparency checker (the themed viewport checker colors)
 * so the layer's silhouette reads against the surface; when it is null the slot is an empty box of the same
 * size, keeping label-only rows aligned with thumbnailed ones.
 *
 * アートメッシュのサムネイル枠。重なり選択ポップアップとアウトライナーのホバープレビューで共用。
 *
 * @param ImageBitmap? thumbnail The layer-art preview, or null for an untextured drawable.
 * @param Dp           size      The square edge of the slot.
 */
@Composable
fun ThumbnailSlot(thumbnail: ImageBitmap?, size: Dp = DefaultThumbnailSize) {
	val slotModifier = Modifier.size(size)
	if (thumbnail == null) {
		Box(modifier = slotModifier)
		return
	}
	val colors = LocalUmamoColors.current
	val checkerLight = colors.transparencyCheckerLight
	val checkerDark = colors.transparencyCheckerDark
	Box(
		modifier =
			slotModifier
				.clip(LocalUmamoShapes.current.small)
				.drawBehind {
					val cell = ThumbnailCheckerCell.toPx()
					val columns = ceil(this.size.width / cell).toInt()
					val rows = ceil(this.size.height / cell).toInt()
					var rowIndex = 0
					while (rowIndex < rows) {
						var columnIndex = 0
						while (columnIndex < columns) {
							val isDarkCell = (rowIndex + columnIndex) % 2 == 1
							drawRect(
								color = if (isDarkCell) checkerDark else checkerLight,
								topLeft = Offset(columnIndex * cell, rowIndex * cell),
								size = Size(cell, cell),
							)
							columnIndex += 1
						}
						rowIndex += 1
					}
				},
	) {
		Image(
			bitmap = thumbnail,
			contentDescription = null,
			contentScale = ContentScale.Fit,
			modifier = Modifier.fillMaxSize(),
		)
	}
}
