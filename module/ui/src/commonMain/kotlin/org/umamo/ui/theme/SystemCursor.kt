package org.umamo.ui.theme

import androidx.compose.ui.input.pointer.PointerIcon

/*
 * The seam that turns the editor's own cursor art into an OS pointer.  Two mechanisms exist for showing a
 * cursor, and this is the second of them:
 *
 *   - Inside the viewport, a modal gesture HIDES the OS pointer ([hiddenPointerIcon]) and the overlay
 *     DRAWS the cursor itself with drawCursor, so it composites with the gizmo art.
 *   - Everywhere else - panel chrome, splitters, number fields - there is no overlay to draw into, so the
 *     art is rasterized once into a platform cursor and handed to pointerHoverIcon.
 *
 * Both paths therefore render the SAME UmamoCursor definitions from Cursor.kt; a control never falls back
 * to a stock OS arrow, which is what kept the designed set from applying outside the viewport before.
 */

/**
 * The OS pointer for one of the editor's own [UmamoCursor] definitions, rasterized from its vector art.
 * Desktop bakes a platform cursor (results cached per cursor, so a hover does not re-rasterize); touch
 * platforms have no hover pointer at all and return the default.
 *
 * Pass a cursor from [LocalUmamoCursors] - e.g. `LocalUmamoCursors.ewScroll` for a horizontal scrub or
 * drag.  The cursor's own [CursorHotspot] is honored, so the caller never offsets for the tip.
 *
 * @param UmamoCursor cursor The cursor definition to rasterize.
 * @return PointerIcon The platform pointer showing that art.
 */
expect fun umamoPointerIcon(cursor: UmamoCursor): PointerIcon

/**
 * A fully transparent pointer icon, used to hide the OS cursor during a modal transform so the overlay's
 * own drawn cursor reads cleanly without the system pointer doubled on top.  Desktop returns a transparent
 * custom cursor; touch platforms have no cursor to hide and return the default.
 *
 * @return PointerIcon A transparent (invisible) cursor, or the default where none can be built.
 */
expect fun hiddenPointerIcon(): PointerIcon
