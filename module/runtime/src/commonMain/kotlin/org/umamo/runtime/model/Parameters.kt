package org.umamo.runtime.model

/**
 * The two parameter kinds Cubism distinguishes: a NORMAL parameter drives keyform-grid
 * interpolation (circle points on the slider); a BLEND_SHAPE parameter drives additive
 * [BlendShapeBinding] deltas (square points).
 *
 * CMO3: CParameterSource field paramType (NORMAL | MORPH_TARGET).  MOC3 v4+: Parameter types,
 * section index 114 (0 normal, 1 blend-shape).
 */
enum class ParameterKind {
	NORMAL,
	BLEND_SHAPE,
}

/**
 * An animation axis, e.g. `ParamAngleX`.  Drives keyform blending at runtime.
 *
 * [id] is the format-level identifier (`ParamAngleX`, …) - kept verbatim for interop, never
 * localised.  [name] is the user-facing display name (the editor's `cdi3`-style label), free to
 * localise.
 */
data class Parameter(
	val id: ParameterId,
	val name: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val kind: ParameterKind = ParameterKind.NORMAL,
	/**
	 * Whether the axis WRAPS at its limits instead of clamping - a value past [max] re-enters at [min].
	 *
	 * Used for continuously-cycling rigs (a spinning wheel, a looping tail).  MOC3 §5.5 s54.  The
	 * evaluator does not honor it yet, so it feeds the runtime-target feature scan and MOC3
	 * round-trip fidelity rather than driving deformation.
	 */
	val repeat: Boolean = false,
)

/**
 * A LINKED ("combined") pair of parameters that the editor presents as a single 2D pad instead of two
 * independent sliders - e.g. head "Angle X" + "Angle Y" shown as one grid control.  [horizontal] drives
 * the X axis (Cubism's first/topmost member of the pair), [vertical] drives the Y axis (the next member
 * in document order).  Both remain ordinary, independently-keyable [Parameter]s; this record only states
 * that they should be scrubbed together as a 2D unit.
 *
 * @property ParameterId horizontal The X-axis parameter (the pair's first/topmost member).
 * @property ParameterId vertical   The Y-axis parameter (the pair's second/bottommost member).
 */
data class ParameterLink(
	val horizontal: ParameterId,
	val vertical: ParameterId,
)

/**
 * One node of the parameter-panel group tree - Cubism's "CParameterGroup" organisation, the
 * collapsible groups the editor sorts parameters into.  A node is either a leaf [Param] (one
 * parameter axis) or a [Group] (a named group holding ordered child nodes, which may nest).  The
 * tree records panel layout only; the flat [Parameter] list remains the authoritative source of axes
 * (live values, ranges, links).  A model with no groups has an empty tree, and callers fall back to
 * rendering the flat list.
 */
sealed interface ParameterNode {
	/**
	 * A leaf node binding one parameter axis into the panel tree by its id.
	 *
	 * @property ParameterId id The parameter this leaf represents.
	 */
	data class Param(val id: ParameterId) : ParameterNode

	/**
	 * A named group of parameters, holding its [children] in editor panel order; groups may nest.
	 *
	 * @property ParameterGroupId id            The group's stable identifier (display names are not unique).
	 * @property String           name          The group's display label (user data - never localised).
	 * @property Boolean          initiallyOpen The editor's saved expanded state (CMO3 folderIsOpened).
	 * @property List             children      The ordered child nodes (parameters and/or nested groups).
	 */
	data class Group(
		val id: ParameterGroupId,
		val name: String,
		val initiallyOpen: Boolean,
		val children: List<ParameterNode>,
	) : ParameterNode
}

/**
 * This model's parameter tree, with a group-less model's flat parameter list as root leaves - the shape the
 * panel shows either way, for callers that need a tree to edit or to write out.
 *
 * @return List<ParameterNode> The tree, or one root leaf per parameter in list order when the model has no groups.
 */
fun PuppetModel.materializedParameterTree(): List<ParameterNode> = parameterTree.ifEmpty { parameters.map { parameter -> ParameterNode.Param(parameter.id) } }