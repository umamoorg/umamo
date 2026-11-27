package org.umamo.ui.viewport.gizmo

/*
 * The two area ids the gizmo tests run their overlays and transforms in, shared by the Compose fixtures
 * (jvmTest) and the transform and request tests that run without Compose (commonTest), so a case can check
 * that a gesture or a request belongs to the area it started in.
 */

/** The left area's id. */
internal const val LEFT_AREA = "left"

/** The right area's id. */
internal const val RIGHT_AREA = "right"