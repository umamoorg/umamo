package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.SCROLLBAR_THICKNESS

/*
 * The measurements every Sources row shares.  The row body lays its slots out by these and the space
 * stripes its list by the row height, so each number has one home.
 */

/** Row height, shared by every row; the zebra fill behind the list stripes by it. */
internal val SOURCES_ROW_HEIGHT = 22.dp

/** Per-depth indentation, matching the outliner's. */
internal val SOURCES_INDENT_PER_DEPTH = 12.dp

/** Fixed width of the disclosure-chevron slot. */
internal val SOURCES_CHEVRON_WIDTH = 14.dp

/** Fixed width of the status-glyph slot. */
internal val SOURCES_ICON_WIDTH = 16.dp

/** The size the status glyph draws at inside its slot. */
internal val SOURCES_GLYPH_SIZE = 14.dp

/**
 * How far the row's fill, border, and drop ring sit inside its bounds, so neighboring highlighted rows
 * read as separate bands.  The row's hit area stays the full row, and its content padding gives the inset
 * back so nothing inside moves.
 */
internal val SOURCES_ROW_BAND_INSET = 1.dp

/** The padding before the chevron of a row at depth 0. */
internal val SOURCES_ROW_PADDING_START = 4.dp

/**
 * The padding after a row's last slot, which is its chip when it has one: the scrollbar's width and a
 * little more, so the bar that overlays the list's right edge while it scrolls covers no chip.
 */
internal val SOURCES_ROW_PADDING_END = SCROLLBAR_THICKNESS + 2.dp

/** The gap between the status glyph and the label. */
internal val SOURCES_ICON_LABEL_GAP = 4.dp

/** The gap between the label and the secondary text after it. */
internal val SOURCES_LABEL_DETAIL_GAP = 8.dp

/** The gap between a row's text and its chip. */
internal val SOURCES_CHIP_GAP = 6.dp

/** The relink menu's width, which its search box fixes so the rows stay put as the search narrows them. */
internal val SOURCES_RELINK_MENU_WIDTH = 320.dp