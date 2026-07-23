package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.setCanvasSize
import org.umamo.edit.setWorldOrigin
import org.umamo.ui.kit.FieldStack
import org.umamo.ui.kit.NumberField
import org.umamo.ui.resources.*

/*
 * The Document tab's sections: properties of the document itself rather than of any selected item, so they
 * are the one tab that shows with an empty selection.
 */

/** Document > Canvas: the document canvas size and world origin. */
internal val CanvasSection =
	PropertySection(
		id = "document.canvas",
		title = Res.string.properties_section_canvas,
		rows = { context ->
			val puppet = context.puppet
			val session = context.session
			listOf(
				// Width + height joined into one stacked group; the whole stack is one searchable row.
				PropertyRow(
					terms = listOf(Res.string.properties_field_canvas_width, Res.string.properties_field_canvas_height),
				) { _ ->
					val pixels = stringResource(Res.string.unit_pixels)
					FieldStack(
						listOf(
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_canvas_width)) {
									NumberField(
										value = puppet.canvasWidth,
										onValueChange = { newWidth -> session?.setCanvasSize(newWidth, puppet.canvasHeight) },
										modifier = Modifier.fillMaxWidth(),
										range = POSITIVE_RANGE,
										decimals = 0,
										unitSuffix = pixels,
										stackPosition = position,
									)
								}
							},
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_canvas_height)) {
									NumberField(
										value = puppet.canvasHeight,
										onValueChange = { newHeight -> session?.setCanvasSize(puppet.canvasWidth, newHeight) },
										modifier = Modifier.fillMaxWidth(),
										range = POSITIVE_RANGE,
										decimals = 0,
										unitSuffix = pixels,
										stackPosition = position,
									)
								}
							},
						),
					)
				},
				// The origin x / y into a second stacked group below it.
				PropertyRow(
					terms = listOf(Res.string.properties_field_origin_x, Res.string.properties_field_origin_z),
				) { _ ->
					FieldStack(
						listOf(
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_origin_x)) {
									NumberField(
										value = puppet.worldOriginX,
										onValueChange = { newX -> session?.setWorldOrigin(newX, puppet.worldOriginY) },
										modifier = Modifier.fillMaxWidth(),
										range = UNBOUNDED_RANGE,
										decimals = 1,
										stackPosition = position,
									)
								}
							},
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_origin_z)) {
									NumberField(
										value = puppet.worldOriginY,
										onValueChange = { newY -> session?.setWorldOrigin(puppet.worldOriginX, newY) },
										modifier = Modifier.fillMaxWidth(),
										range = UNBOUNDED_RANGE,
										decimals = 1,
										stackPosition = position,
									)
								}
							},
						),
					)
				},
			)
		},
	)

/**
 * Document > Runtime: runtime / export-target API compatibility options (Cubism, Ayagami, and others).
 * This is document-level configuration with no model backing yet, so the first cut renders a placeholder;
 * the runtime-compatibility target data model is a dedicated follow-up.
 */
internal val RuntimeSection =
	PropertySection(
		id = "document.runtime",
		title = Res.string.properties_section_runtime,
		rows = { _ ->
			listOf(
				PropertyRow(terms = listOf(Res.string.properties_runtime_placeholder)) { _ ->
					PropertyLine(stringResource(Res.string.properties_runtime_placeholder))
				},
			)
		},
	)
