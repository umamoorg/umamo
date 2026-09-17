package org.umamo.format.uma.puppet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The puppet entry's enum values, each spelled in the file by its @SerialName (docs/format/UMA.md §4.1,
 * D18).  The serial names are the table: renaming a constant here or in the runtime model never changes a
 * file.  A value this reader does not know is a malformed entry, so a writer adding a value must raise the
 * entry's minVersion.
 */

/** UMA §4.1: a color blend mode, for drawables and part composites. */
@Serializable
public enum class UmaBlendMode {
	@SerialName("normal")
	Normal,

	@SerialName("additivePremultiplied")
	AdditivePremultiplied,

	@SerialName("multiplyPremultiplied")
	MultiplyPremultiplied,

	@SerialName("additive")
	Additive,

	@SerialName("additiveGlow")
	AdditiveGlow,

	@SerialName("darken")
	Darken,

	@SerialName("multiply")
	Multiply,

	@SerialName("colorBurn")
	ColorBurn,

	@SerialName("linearBurn")
	LinearBurn,

	@SerialName("lighten")
	Lighten,

	@SerialName("screen")
	Screen,

	@SerialName("colorDodge")
	ColorDodge,

	@SerialName("overlay")
	Overlay,

	@SerialName("softLight")
	SoftLight,

	@SerialName("hardLight")
	HardLight,

	@SerialName("linearLight")
	LinearLight,

	@SerialName("hue")
	Hue,

	@SerialName("color")
	Color,
}

/** UMA §4.1: an alpha blend mode, for drawables and part composites. */
@Serializable
public enum class UmaAlphaBlendMode {
	@SerialName("over")
	Over,

	@SerialName("atop")
	Atop,

	@SerialName("out")
	Out,

	@SerialName("conjoint")
	Conjoint,

	@SerialName("disjoint")
	Disjoint,
}

/** UMA §4.3: what a parameter drives. */
@Serializable
public enum class UmaParameterKind {
	@SerialName("normal")
	Normal,

	@SerialName("blendShape")
	BlendShape,
}

/** UMA §4.4: how a part groups its subtree for rendering. */
@Serializable
public enum class UmaGroupMode {
	@SerialName("passThrough")
	PassThrough,

	@SerialName("grouped")
	Grouped,

	@SerialName("isolated")
	Isolated,
}

/** UMA §4.2: the document's runtime-compatibility target. */
@Serializable
public enum class UmaRuntimeTarget {
	@SerialName("noTarget")
	NoTarget,

	@SerialName("ayagami")
	Ayagami,

	@SerialName("cubism30")
	Cubism30,

	@SerialName("cubism33")
	Cubism33,

	@SerialName("cubism40")
	Cubism40,

	@SerialName("cubism42")
	Cubism42,

	@SerialName("cubism50")
	Cubism50,

	@SerialName("cubism53")
	Cubism53,
}

/** UMA §4.5: a deformer's kind. */
@Serializable
public enum class UmaDeformerKind {
	@SerialName("warp")
	Warp,

	@SerialName("rotation")
	Rotation,
}