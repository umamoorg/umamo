package org.umamo.ui.model

import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterChoice
import org.umamo.edit.ParameterUnit
import org.umamo.edit.booleanValue
import org.umamo.edit.choiceValue
import org.umamo.edit.intValue
import org.umamo.format.atlas.AtlasPackOptions

/*
 * The repack's face on the operation settings strip: its pack options as the strip's typed rows, and
 * back, plus the session memory the next repack starts from.  This file is the repack's whole
 * contribution to the strip - a later operation's rows are a sibling of this file, not an edit to
 * the strip.
 */

/** The parameter keys the repack's rows carry (the strip maps their label keys to strings). */
internal object RepackParameterKeys {
	const val PAGE_SIZE = "repack.pageSize"
	const val GUTTER = "repack.gutter"
	const val EXTRUDE = "repack.extrude"
	const val ALLOW_ROTATION = "repack.allowRotation"
	const val KEEP_PINNED = "repack.keepPinned"
	const val POWER_OF_TWO = "repack.powerOfTwo"
	const val SQUARE_PAGES = "repack.squarePages"
	const val SHRINK_PAGES = "repack.shrinkPages"
	const val ALPHA_THRESHOLD = "repack.alphaThreshold"
}

/** The maximum page sizes the strip offers; the document's own size joins the list when it is none of these. */
internal val REPACK_PAGE_SIZE_CHOICES: List<Int> = listOf(1024, 2048, 4096, 8192, 16384)

/** The widest gutter the strip offers, in pixels. */
internal const val REPACK_MAX_GUTTER = 64

/**
 * The strip's rows for [options], in display order.
 *
 * The extrusion's range follows the gutter: the packer requires extrude in 0..gutter, so the row can
 * never offer a value the packer refuses.  The page size is a choice over the common sizes because a
 * free integer would invite sizes no GPU wants; the document's current size is offered even when it
 * is not one of them, so an imported document's own size is always one click away.  It is the
 * MAXIMUM page size, and the row says so: with Shrink Pages on (the default) the packer settles on
 * the smallest power-of-two side that needs no more pages than this one would, so a larger choice
 * changes nothing until the art needs the room or Shrink Pages is off.  Keep Pinned Tiles is the
 * one row that is not a packer option: it decides whether the pinned tiles are handed to the packer
 * fixed or free.
 *
 * @param AtlasPackOptions options    The options the rows show.
 * @param Boolean          keepPinned Whether the pinned tiles stay where they are.
 * @return List The rows.
 */
internal fun repackParameters(options: AtlasPackOptions, keepPinned: Boolean = true): List<OperatorParameter> {
	val pageSides = (REPACK_PAGE_SIZE_CHOICES + options.maxPageSize).distinct().sorted()
	return listOf(
		OperatorParameter.ChoiceParameter(
			RepackParameterKeys.PAGE_SIZE,
			RepackParameterKeys.PAGE_SIZE,
			options.maxPageSize.toString(),
			pageSides.map { side -> ParameterChoice(side.toString()) },
		),
		OperatorParameter.IntParameter(RepackParameterKeys.GUTTER, RepackParameterKeys.GUTTER, options.gutter, 0, REPACK_MAX_GUTTER, unit = ParameterUnit.Pixels),
		OperatorParameter.IntParameter(RepackParameterKeys.EXTRUDE, RepackParameterKeys.EXTRUDE, options.extrude, 0, options.gutter, unit = ParameterUnit.Pixels),
		OperatorParameter.BooleanParameter(RepackParameterKeys.ALLOW_ROTATION, RepackParameterKeys.ALLOW_ROTATION, options.allowRotation),
		OperatorParameter.BooleanParameter(RepackParameterKeys.KEEP_PINNED, RepackParameterKeys.KEEP_PINNED, keepPinned),
		OperatorParameter.BooleanParameter(RepackParameterKeys.POWER_OF_TWO, RepackParameterKeys.POWER_OF_TWO, options.powerOfTwoPages),
		OperatorParameter.BooleanParameter(RepackParameterKeys.SQUARE_PAGES, RepackParameterKeys.SQUARE_PAGES, options.squarePages),
		OperatorParameter.BooleanParameter(RepackParameterKeys.SHRINK_PAGES, RepackParameterKeys.SHRINK_PAGES, options.shrinkPages),
		OperatorParameter.IntParameter(RepackParameterKeys.ALPHA_THRESHOLD, RepackParameterKeys.ALPHA_THRESHOLD, options.alphaThreshold, 1, 255),
	)
}

/**
 * The options [parameters] describe, over [fallback] for anything the rows do not carry.
 *
 * The extrusion is clamped to the gutter on the way back so an edit that narrowed the gutter under
 * a wider extrusion still yields options the packer accepts; the strip re-renders from the options
 * the pack actually used, so the clamp shows.
 *
 * @param List             parameters The strip's rows.
 * @param AtlasPackOptions fallback   The options for rows that are absent.
 * @return AtlasPackOptions The options to pack with.
 */
internal fun repackOptionsOf(parameters: List<OperatorParameter>, fallback: AtlasPackOptions): AtlasPackOptions {
	val gutter = parameters.intValue(RepackParameterKeys.GUTTER, fallback.gutter).coerceIn(0, REPACK_MAX_GUTTER)
	return fallback.copy(
		maxPageSize = parameters.choiceValue(RepackParameterKeys.PAGE_SIZE, fallback.maxPageSize.toString()).toIntOrNull() ?: fallback.maxPageSize,
		gutter = gutter,
		extrude = parameters.intValue(RepackParameterKeys.EXTRUDE, fallback.extrude).coerceIn(0, gutter),
		allowRotation = parameters.booleanValue(RepackParameterKeys.ALLOW_ROTATION, fallback.allowRotation),
		powerOfTwoPages = parameters.booleanValue(RepackParameterKeys.POWER_OF_TWO, fallback.powerOfTwoPages),
		squarePages = parameters.booleanValue(RepackParameterKeys.SQUARE_PAGES, fallback.squarePages),
		shrinkPages = parameters.booleanValue(RepackParameterKeys.SHRINK_PAGES, fallback.shrinkPages),
		alphaThreshold = parameters.intValue(RepackParameterKeys.ALPHA_THRESHOLD, fallback.alphaThreshold).coerceIn(1, 255),
	)
}

/**
 * Whether [parameters] keep the pinned tiles where they are; true when the row is absent.
 *
 * @param List parameters The strip's rows.
 * @return Boolean The keep-pinned choice.
 */
internal fun repackKeepPinnedOf(parameters: List<OperatorParameter>): Boolean = parameters.booleanValue(RepackParameterKeys.KEEP_PINNED, true)

/**
 * The repack's session memory: the options the last pack ran with, held for the life of the window
 * and deliberately never persisted to settings - the pattern the MOC3 export options use.
 *
 * Two lifetimes in one object.  The spacing, rotation, keep-pinned, page-shape, and threshold choices
 * stick across documents - a rigger who packs with rotation wants that for the next model too.  The page
 * size sticks only within one session: it is a property of one document's pages (its default is the
 * document's own side), so carrying it onto a different document would silently pack that one at
 * the previous model's size.  The session is remembered by hash code, never by reference, so this
 * object retains nothing of a closed document.
 *
 * A plain class rather than Compose state on purpose: the repack copies the returned value into its
 * record, so nothing observes this between packs and it stays unit-testable.
 */
class AtlasRepackSessionOptions {
	private var remembered: AtlasPackOptions? = null
	private var rememberedKeepPinned: Boolean = true
	private var rememberedSessionHash: Int? = null

	/**
	 * Whether the next repack keeps the pinned tiles where they are: the last pack's choice, or true
	 * before any pack.
	 *
	 * @return Boolean The keep-pinned choice.
	 */
	fun keepPinnedFor(): Boolean = rememberedKeepPinned

	/**
	 * The options the next repack should run with for [session], whose default maximum page size is
	 * [documentPageSize].
	 *
	 * @param EditorSession session          The document's session.
	 * @param Int           documentPageSize The document's own maximum page size.
	 * @return AtlasPackOptions The options to pack with.
	 */
	fun optionsFor(session: EditorSession, documentPageSize: Int): AtlasPackOptions {
		val sticky = remembered ?: return AtlasPackOptions(maxPageSize = documentPageSize)
		return if (rememberedSessionHash == session.hashCode()) sticky else sticky.copy(maxPageSize = documentPageSize)
	}

	/**
	 * Records what a pack for [session] ran with, making it the seed for the next.
	 *
	 * @param EditorSession    session    The document's session.
	 * @param AtlasPackOptions options    The options the pack used.
	 * @param Boolean          keepPinned Whether the pack kept the pinned tiles where they were.
	 */
	fun record(session: EditorSession, options: AtlasPackOptions, keepPinned: Boolean = true) {
		remembered = options
		rememberedKeepPinned = keepPinned
		rememberedSessionHash = session.hashCode()
	}
}