package org.umamo.ui.model.mesh

import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterChoice
import org.umamo.edit.ParameterUnit
import org.umamo.edit.booleanValue
import org.umamo.edit.choiceValue
import org.umamo.edit.floatValue
import org.umamo.edit.intValue
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.ArtMeshSettings
import org.umamo.interop.art.mesh.MAXIMUM_ART_MESH_MINIMUM_ANGLE
import org.umamo.interop.art.mesh.MAXIMUM_ART_MESH_VERTEX_BUDGET
import org.umamo.interop.art.mesh.MINIMUM_ART_MESH_DEFORMATION_TOLERANCE
import org.umamo.interop.art.mesh.MINIMUM_ART_MESH_INTERIOR_THINNING_LIMIT

/*
 * The settings of Generate Mesh from Art as the operation strip shows them: a Preset row, one row per
 * mesher setting, Fill Holes, and Strand Rungs.  Every row is clamped into what the mesher accepts, so
 * no value typed into the strip can make the settings refuse.  A preset fills in the density rows
 * (spacings, margins, minimum outline points, minimum angle, strand width) and leaves the threshold, the
 * budget and its interior thinning limit, Fill Holes, Strand Rungs, and the deformation tolerance -
 * which is about the rig, not density - as they were.
 *
 * The values a run used are remembered for the rest of the app session (never saved), and a first run
 * starts from the preset the settings name.
 */

/** The parameter keys the Generate Mesh from Art rows carry (the strip maps them to strings). */
internal object MeshGenerationParameterKeys {
	const val PRESET = "meshGenerate.preset"
	const val ALPHA_THRESHOLD = "meshGenerate.alphaThreshold"
	const val OUTLINE_SPACING = "meshGenerate.outlineSpacing"
	const val INTERIOR_SPACING = "meshGenerate.interiorSpacing"
	const val OUTER_MARGIN = "meshGenerate.outerMargin"
	const val INNER_MARGIN = "meshGenerate.innerMargin"
	const val MINIMUM_MARGIN = "meshGenerate.minimumMargin"
	const val MINIMUM_OUTLINE_POINTS = "meshGenerate.minimumOutlinePoints"
	const val MINIMUM_ANGLE = "meshGenerate.minimumAngle"
	const val VERTEX_BUDGET = "meshGenerate.vertexBudget"
	const val FILL_HOLES = "meshGenerate.fillHoles"
	const val STRAND_RUNGS = "meshGenerate.strandRungs"
	const val STRAND_WIDTH = "meshGenerate.strandWidth"
	const val DEFORMATION_TOLERANCE = "meshGenerate.deformationTolerance"
	const val INTERIOR_THINNING_LIMIT = "meshGenerate.interiorThinningLimit"

	/** The prefix a Preset entry's label key carries in front of its [ArtMeshPreset.key]. */
	const val PRESET_CHOICE_PREFIX = "meshGenerate.preset."
}

/** The widest spacing the strip offers, in art pixels. */
private const val MAXIMUM_SPACING: Float = 512f

/** The widest outer margin the strip offers, in art pixels. */
private const val MAXIMUM_OUTER_MARGIN: Float = 64f

/** The deepest inner margin the strip offers, in art pixels. */
private const val MAXIMUM_INNER_MARGIN: Float = 256f

/** The narrowest strand width the strip offers, in art pixels. */
private const val SMALLEST_STRAND_WIDTH: Float = 1f

/** The widest strand width the strip offers, in art pixels. */
private const val MAXIMUM_STRAND_WIDTH: Float = 256f

/** The smallest minimum margin the mesher accepts, in art pixels. */
private const val SMALLEST_MINIMUM_MARGIN: Float = 0.5f

/** The most outline points one ring may be asked for. */
private const val MAXIMUM_OUTLINE_POINTS: Int = 256

/** The largest deformation tolerance the strip offers, in art pixels. */
private const val MAXIMUM_DEFORMATION_TOLERANCE: Float = 64f

/** The largest interior thinning limit the strip offers, in outline spacings: past it the outline keeps its spacing to the end, as near as makes no difference. */
private const val MAXIMUM_INTERIOR_THINNING_LIMIT: Float = 64f

/** The smallest vertex budget the strip offers. */
private const val SMALLEST_VERTEX_BUDGET: Int = 16

/** The fewest outline points one ring may be asked for: a ring needs three to enclose anything. */
private const val SMALLEST_OUTLINE_POINTS: Int = 3

/** The largest alpha threshold, and the cap on the atlas's own. */
private const val MAXIMUM_ALPHA_THRESHOLD: Int = 255

/**
 * One decimal row of the strip: its key (also its label key), its range, the step its field nudges by,
 * and its unit.  The range is written once, here, for both the row and the clamp.
 *
 * @property String        key     The row's key.
 * @property Double        minimum The smallest value, also what a value that is not finite falls back to.
 * @property Double        maximum The largest value.
 * @property Float         step    The step the field nudges by.
 * @property ParameterUnit unit    The row's unit.
 */
private class DecimalRow(val key: String, val minimum: Double, val maximum: Double, val step: Float, val unit: ParameterUnit) {
	/**
	 * A value clamped into the range; one that is not finite falls back to the floor.
	 *
	 * @param Double value The value.
	 * @return Double The clamped value.
	 */
	fun clamp(value: Double): Double = (if (value.isFinite()) value else minimum).coerceIn(minimum, maximum)

	/**
	 * The row showing a value.
	 *
	 * @param Double value The value, already clamped.
	 * @return OperatorParameter The row.
	 */
	fun row(value: Double): OperatorParameter = OperatorParameter.FloatParameter(key, key, value.toFloat(), minimum.toFloat(), maximum.toFloat(), step, unit)

	/**
	 * The value the strip's rows carry for this row.
	 *
	 * @param List<OperatorParameter> parameters The strip's rows.
	 * @param Double                  fallback   The value when no row carries one.
	 * @return Double The value, not yet clamped.
	 */
	fun read(parameters: List<OperatorParameter>, fallback: Double): Double = parameters.floatValue(key, fallback.toFloat()).toDouble()
}

/** The Outline Spacing row, art pixels. */
private val OUTLINE_SPACING_ROW = DecimalRow(MeshGenerationParameterKeys.OUTLINE_SPACING, 1.0, MAXIMUM_SPACING.toDouble(), 1f, ParameterUnit.Pixels)

/** The Interior Spacing row, art pixels. */
private val INTERIOR_SPACING_ROW = DecimalRow(MeshGenerationParameterKeys.INTERIOR_SPACING, 1.0, MAXIMUM_SPACING.toDouble(), 1f, ParameterUnit.Pixels)

/** The Outer Margin row, art pixels. */
private val OUTER_MARGIN_ROW = DecimalRow(MeshGenerationParameterKeys.OUTER_MARGIN, 0.0, MAXIMUM_OUTER_MARGIN.toDouble(), 1f, ParameterUnit.Pixels)

/** The Inner Margin row, art pixels. */
private val INNER_MARGIN_ROW = DecimalRow(MeshGenerationParameterKeys.INNER_MARGIN, 0.0, MAXIMUM_INNER_MARGIN.toDouble(), 1f, ParameterUnit.Pixels)

/** The Minimum Margin row, art pixels, from the smallest the mesher accepts. */
private val MINIMUM_MARGIN_ROW = DecimalRow(MeshGenerationParameterKeys.MINIMUM_MARGIN, SMALLEST_MINIMUM_MARGIN.toDouble(), MAXIMUM_OUTER_MARGIN.toDouble(), 0.5f, ParameterUnit.Pixels)

/** The Minimum Angle row, degrees; 0 refines nothing. */
private val MINIMUM_ANGLE_ROW = DecimalRow(MeshGenerationParameterKeys.MINIMUM_ANGLE, 0.0, MAXIMUM_ART_MESH_MINIMUM_ANGLE, 1f, ParameterUnit.Degrees)

/** The Interior Thinning Limit row, outline spacings. */
private val INTERIOR_THINNING_LIMIT_ROW = DecimalRow(MeshGenerationParameterKeys.INTERIOR_THINNING_LIMIT, MINIMUM_ART_MESH_INTERIOR_THINNING_LIMIT, MAXIMUM_INTERIOR_THINNING_LIMIT.toDouble(), 0.5f, ParameterUnit.None)

/** The Strand Width row, art pixels. */
private val STRAND_WIDTH_ROW = DecimalRow(MeshGenerationParameterKeys.STRAND_WIDTH, SMALLEST_STRAND_WIDTH.toDouble(), MAXIMUM_STRAND_WIDTH.toDouble(), 1f, ParameterUnit.Pixels)

/** The Deformation Tolerance row, art pixels. */
private val DEFORMATION_TOLERANCE_ROW = DecimalRow(MeshGenerationParameterKeys.DEFORMATION_TOLERANCE, MINIMUM_ART_MESH_DEFORMATION_TOLERANCE, MAXIMUM_DEFORMATION_TOLERANCE.toDouble(), 0.1f, ParameterUnit.Pixels)

/**
 * What one Generate Mesh from Art run uses: the preset its rows were last seeded from, and the
 * settings the mesher receives.
 *
 * @property ArtMeshPreset   preset   The preset the Preset row shows.
 * @property ArtMeshSettings settings The mesher settings, already clamped.
 */
internal data class MeshGenerationOptions(val preset: ArtMeshPreset, val settings: ArtMeshSettings) {
	companion object {
		/**
		 * The options a preset stands for, as they are.
		 *
		 * @param ArtMeshPreset preset The preset.
		 * @return MeshGenerationOptions The options.
		 */
		fun of(preset: ArtMeshPreset): MeshGenerationOptions = MeshGenerationOptions(preset, preset.settings)
	}
}

/**
 * The settings clamped into what the strip offers and the mesher accepts, with the alpha threshold
 * never below the atlas's own (art the atlas would not draw is never meshed as art).
 *
 * @param ArtMeshSettings settings              The settings to clamp.
 * @param Int             minimumAlphaThreshold The atlas's alpha threshold.
 * @return ArtMeshSettings The clamped settings.
 */
internal fun clampedMeshSettings(settings: ArtMeshSettings, minimumAlphaThreshold: Int): ArtMeshSettings =
	clampedMeshSettingsOf(
		alphaThreshold = settings.alphaThreshold,
		outlineSpacing = settings.outlineSpacing,
		interiorSpacing = settings.interiorSpacing,
		outerMargin = settings.outerMargin,
		innerMargin = settings.innerMargin,
		minimumMargin = settings.minimumMargin,
		minimumOutlinePoints = settings.minimumOutlinePoints,
		vertexBudget = settings.vertexBudget,
		fillHoles = settings.fillHoles,
		strandRungs = settings.strandRungs,
		strandWidth = settings.strandWidth,
		minimumAngle = settings.minimumAngle,
		deformationTolerance = settings.deformationTolerance,
		interiorThinningLimit = settings.interiorThinningLimit,
		minimumAlphaThreshold = minimumAlphaThreshold,
	)

/**
 * Mesher settings from raw row values, each clamped into what the strip offers before the settings
 * are built (they refuse some of the values a row can carry); a value that is not finite falls back to
 * its range's floor.
 *
 * @param Int     alphaThreshold        The threshold.
 * @param Double  outlineSpacing        The outline spacing, art pixels.
 * @param Double  interiorSpacing       The interior spacing.
 * @param Double  outerMargin           The outer margin.
 * @param Double  innerMargin           The inner margin.
 * @param Double  minimumMargin         The minimum margin.
 * @param Int     minimumOutlinePoints  The minimum outline points.
 * @param Int     vertexBudget          The vertex budget.
 * @param Boolean fillHoles             Whether holes are filled.
 * @param Boolean strandRungs           Whether thin strands are meshed as rung strips.
 * @param Double  strandWidth           The strand width, art pixels.
 * @param Double  minimumAngle          The minimum angle, degrees.
 * @param Double  deformationTolerance  The deformation tolerance, art pixels.
 * @param Double  interiorThinningLimit The interior thinning limit, outline spacings.
 * @param Int     minimumAlphaThreshold The atlas's alpha threshold, the threshold's floor.
 * @return ArtMeshSettings The settings.
 */
private fun clampedMeshSettingsOf(
	alphaThreshold: Int,
	outlineSpacing: Double,
	interiorSpacing: Double,
	outerMargin: Double,
	innerMargin: Double,
	minimumMargin: Double,
	minimumOutlinePoints: Int,
	vertexBudget: Int,
	fillHoles: Boolean,
	strandRungs: Boolean,
	strandWidth: Double,
	minimumAngle: Double,
	deformationTolerance: Double,
	interiorThinningLimit: Double,
	minimumAlphaThreshold: Int,
): ArtMeshSettings {
	val minimumPoints = minimumOutlinePoints.coerceIn(SMALLEST_OUTLINE_POINTS, MAXIMUM_OUTLINE_POINTS)

	return ArtMeshSettings(
		alphaThreshold = alphaThreshold.coerceIn(alphaThresholdFloor(minimumAlphaThreshold), MAXIMUM_ALPHA_THRESHOLD),
		outlineSpacing = OUTLINE_SPACING_ROW.clamp(outlineSpacing),
		interiorSpacing = INTERIOR_SPACING_ROW.clamp(interiorSpacing),
		outerMargin = OUTER_MARGIN_ROW.clamp(outerMargin),
		innerMargin = INNER_MARGIN_ROW.clamp(innerMargin),
		minimumMargin = MINIMUM_MARGIN_ROW.clamp(minimumMargin),
		minimumOutlinePoints = minimumPoints,
		vertexBudget = vertexBudget.coerceIn(maxOf(SMALLEST_VERTEX_BUDGET, minimumPoints), MAXIMUM_ART_MESH_VERTEX_BUDGET),
		fillHoles = fillHoles,
		strandRungs = strandRungs,
		strandWidth = STRAND_WIDTH_ROW.clamp(strandWidth),
		minimumAngle = MINIMUM_ANGLE_ROW.clamp(minimumAngle),
		deformationTolerance = DEFORMATION_TOLERANCE_ROW.clamp(deformationTolerance),
		interiorThinningLimit = INTERIOR_THINNING_LIMIT_ROW.clamp(interiorThinningLimit),
	)
}

/**
 * The alpha threshold's floor: the atlas's own, kept inside the threshold's range.
 *
 * @param Int minimumAlphaThreshold The atlas's alpha threshold.
 * @return Int The floor.
 */
private fun alphaThresholdFloor(minimumAlphaThreshold: Int): Int = minimumAlphaThreshold.coerceIn(1, MAXIMUM_ALPHA_THRESHOLD)

/**
 * The strip's rows for a run with [options].
 *
 * @param MeshGenerationOptions options               The options the rows show.
 * @param Int                   minimumAlphaThreshold The atlas's alpha threshold, the threshold row's floor.
 * @return List<OperatorParameter> The rows.
 */
internal fun meshGenerationParameters(options: MeshGenerationOptions, minimumAlphaThreshold: Int): List<OperatorParameter> {
	val settings = clampedMeshSettings(options.settings, minimumAlphaThreshold)

	return listOf(
		OperatorParameter.ChoiceParameter(
			MeshGenerationParameterKeys.PRESET,
			MeshGenerationParameterKeys.PRESET,
			options.preset.key,
			ArtMeshPreset.entries.map { preset -> ParameterChoice(preset.key, MeshGenerationParameterKeys.PRESET_CHOICE_PREFIX + preset.key) },
		),
		OperatorParameter.IntParameter(MeshGenerationParameterKeys.ALPHA_THRESHOLD, MeshGenerationParameterKeys.ALPHA_THRESHOLD, settings.alphaThreshold, alphaThresholdFloor(minimumAlphaThreshold), MAXIMUM_ALPHA_THRESHOLD),
		OUTLINE_SPACING_ROW.row(settings.outlineSpacing),
		INTERIOR_SPACING_ROW.row(settings.interiorSpacing),
		OUTER_MARGIN_ROW.row(settings.outerMargin),
		INNER_MARGIN_ROW.row(settings.innerMargin),
		MINIMUM_MARGIN_ROW.row(settings.minimumMargin),
		OperatorParameter.IntParameter(MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS, MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS, settings.minimumOutlinePoints, SMALLEST_OUTLINE_POINTS, MAXIMUM_OUTLINE_POINTS),
		MINIMUM_ANGLE_ROW.row(settings.minimumAngle),
		OperatorParameter.IntParameter(MeshGenerationParameterKeys.VERTEX_BUDGET, MeshGenerationParameterKeys.VERTEX_BUDGET, settings.vertexBudget, SMALLEST_VERTEX_BUDGET, MAXIMUM_ART_MESH_VERTEX_BUDGET, step = 16),
		INTERIOR_THINNING_LIMIT_ROW.row(settings.interiorThinningLimit),
		OperatorParameter.BooleanParameter(MeshGenerationParameterKeys.FILL_HOLES, MeshGenerationParameterKeys.FILL_HOLES, settings.fillHoles),
		OperatorParameter.BooleanParameter(MeshGenerationParameterKeys.STRAND_RUNGS, MeshGenerationParameterKeys.STRAND_RUNGS, settings.strandRungs),
		STRAND_WIDTH_ROW.row(settings.strandWidth),
		DEFORMATION_TOLERANCE_ROW.row(settings.deformationTolerance),
	)
}

/**
 * The options the strip's rows describe, over [fallback] for anything a row does not carry, clamped.
 *
 * @param List<OperatorParameter> parameters            The strip's rows.
 * @param MeshGenerationOptions   fallback              The options the first run used.
 * @param Int                     minimumAlphaThreshold The atlas's alpha threshold.
 * @return MeshGenerationOptions The options.
 */
internal fun meshGenerationOptionsOf(parameters: List<OperatorParameter>, fallback: MeshGenerationOptions, minimumAlphaThreshold: Int): MeshGenerationOptions {
	val base = fallback.settings
	val settings =
		clampedMeshSettingsOf(
			alphaThreshold = parameters.intValue(MeshGenerationParameterKeys.ALPHA_THRESHOLD, base.alphaThreshold),
			outlineSpacing = OUTLINE_SPACING_ROW.read(parameters, base.outlineSpacing),
			interiorSpacing = INTERIOR_SPACING_ROW.read(parameters, base.interiorSpacing),
			outerMargin = OUTER_MARGIN_ROW.read(parameters, base.outerMargin),
			innerMargin = INNER_MARGIN_ROW.read(parameters, base.innerMargin),
			minimumMargin = MINIMUM_MARGIN_ROW.read(parameters, base.minimumMargin),
			minimumOutlinePoints = parameters.intValue(MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS, base.minimumOutlinePoints),
			vertexBudget = parameters.intValue(MeshGenerationParameterKeys.VERTEX_BUDGET, base.vertexBudget),
			fillHoles = parameters.booleanValue(MeshGenerationParameterKeys.FILL_HOLES, base.fillHoles),
			strandRungs = parameters.booleanValue(MeshGenerationParameterKeys.STRAND_RUNGS, base.strandRungs),
			strandWidth = STRAND_WIDTH_ROW.read(parameters, base.strandWidth),
			minimumAngle = MINIMUM_ANGLE_ROW.read(parameters, base.minimumAngle),
			deformationTolerance = DEFORMATION_TOLERANCE_ROW.read(parameters, base.deformationTolerance),
			interiorThinningLimit = INTERIOR_THINNING_LIMIT_ROW.read(parameters, base.interiorThinningLimit),
			minimumAlphaThreshold = minimumAlphaThreshold,
		)
	val preset = ArtMeshPreset.fromKey(parameters.choiceValue(MeshGenerationParameterKeys.PRESET, fallback.preset.key))

	return MeshGenerationOptions(preset, settings)
}

/**
 * The rows re-seeded from a preset: the Preset row shows it and the density rows (the strand width
 * among them) take its numbers, while the threshold, the budget, the interior thinning limit, Fill Holes, Strand Rungs, and the
 * deformation tolerance keep what the rows held.
 *
 * @param List<OperatorParameter> parameters            The rows as they stand.
 * @param ArtMeshPreset           preset                The preset to seed from.
 * @param Int                     minimumAlphaThreshold The atlas's alpha threshold.
 * @return List<OperatorParameter> The re-seeded rows.
 */
internal fun reseededMeshGenerationParameters(parameters: List<OperatorParameter>, preset: ArtMeshPreset, minimumAlphaThreshold: Int): List<OperatorParameter> {
	val current = meshGenerationOptionsOf(parameters, MeshGenerationOptions.of(preset), minimumAlphaThreshold).settings
	val seeded =
		preset.settings.copy(
			alphaThreshold = current.alphaThreshold,
			vertexBudget = current.vertexBudget,
			fillHoles = current.fillHoles,
			strandRungs = current.strandRungs,
			deformationTolerance = current.deformationTolerance,
			interiorThinningLimit = current.interiorThinningLimit,
		)

	return meshGenerationParameters(MeshGenerationOptions(preset, seeded), minimumAlphaThreshold)
}

/**
 * The options Generate Mesh from Art remembers between runs for the rest of the app session - never
 * saved, the same for every document (every value is in art pixels).  Before any run it starts from the
 * preset the settings name.
 */
internal class MeshGenerationSessionOptions {
	private var remembered: MeshGenerationOptions? = null

	/**
	 * The options the next run starts from.
	 *
	 * @param String? seedPresetKey The preset key the settings hold, or null.
	 * @return MeshGenerationOptions The remembered options, or the seeded preset's.
	 */
	fun optionsFor(seedPresetKey: String?): MeshGenerationOptions = remembered ?: MeshGenerationOptions.of(ArtMeshPreset.fromKey(seedPresetKey))

	/**
	 * Remembers the options a run landed with.
	 *
	 * @param MeshGenerationOptions options The options.
	 */
	fun record(options: MeshGenerationOptions) {
		remembered = options
	}
}