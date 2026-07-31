package org.umamo.runtime.model

import org.umamo.format.cmo3.Cmo3TargetVersion
import org.umamo.format.moc3.moc.MocVersion

/**
 * The document's runtime-compatibility target: which runtime the rigger intends this puppet to run
 * on, selected in Properties > Document > Runtime.
 *
 * A target gates EDITING CONTROLS ONLY.  It never gates rendering (a document whose content already
 * exceeds the target keeps rendering everything), never gates saving to the native format or to
 * CMO3 (both always carry every feature), and only informs MOC3 export, where it selects the moc
 * version and drives the feature-strip confirmation.  This mirrors the official editor's own
 * "Model target version selection" behavior, which likewise keeps out-of-target data in the file.
 *
 * Ayagami is a third-party runtime (not an Umamo project) that is effectively Cubism 5.0
 * compatible at present per its maintainer; it has its own entry, rather than aliasing Cubism50,
 * so its capabilities can advance independently of the Cubism ladder.
 */
enum class RuntimeTarget(
	/**
	 * The verbatim product name shown in the target dropdown.  Cubism entries display this
	 * directly; NoTarget's phrasing and Ayagami's equivalence parenthetical are chrome, so those
	 * two resolve through the string catalog instead - with the name "Ayagami" itself kept
	 * verbatim inside the localized string, and its level fed from [cubismLevelText] so the label
	 * can never drift from the gating level.
	 */
	val displayName: String,
	/**
	 * The Cubism feature ceiling as major * 10 + minor (30, 33, 40, 42, 50, 53), or null for no
	 * ceiling at all.  [supports] compares features against this level.
	 */
	val cubismLevel: Int?,
) {
	/** No restriction - every feature Umamo models is authorable. */
	NoTarget("No Target", null),

	/** The Ayagami third-party runtime - effectively Cubism 5.0 at present (see the class docblock). */
	Ayagami("Ayagami", 50),

	Cubism30("Cubism 3.0", 30),
	Cubism33("Cubism 3.3", 33),
	Cubism40("Cubism 4.0", 40),
	Cubism42("Cubism 4.2", 42),
	Cubism50("Cubism 5.0", 50),
	Cubism53("Cubism 5.3", 53),
	;

	/**
	 * Whether this target's runtime supports authoring [feature].
	 *
	 * The thresholds follow the official Cubism Editor's "Model target version selection" dialog,
	 * which is the authority for edit gating even where the MOC3 section table disagrees (the new
	 * warp method is gated at 3.3 though the WARP_MODE section only exists from moc v3, and the
	 * reversed mask at 4.0 though its constant-flag bit exists from moc v1) - editor parity is the
	 * product requirement; the section-level nuances belong to export lowering.
	 *
	 * @param RuntimeFeature feature The feature to check.
	 * @return Boolean True when the feature may be authored under this target.
	 */
	fun supports(feature: RuntimeFeature): Boolean {
		val level = cubismLevel ?: return true
		return when (feature) {
			RuntimeFeature.WarpQuadTransform -> level >= 33
			RuntimeFeature.ReversedMask -> level >= 40
			RuntimeFeature.MeshWarpBlendShapes,
			RuntimeFeature.BlendShapeParameters,
			RuntimeFeature.MultiplyColor,
			RuntimeFeature.ScreenColor,
			-> level >= 42
			RuntimeFeature.ExtendedBlendShapes,
			RuntimeFeature.MotionSync,
			-> level >= 50
			RuntimeFeature.ExtendedBlendModes,
			RuntimeFeature.PartComposite,
			RuntimeFeature.ParameterRepeat,
			-> level >= 53
			RuntimeFeature.ArtPath -> false
		}
	}

	/**
	 * The Cubism ceiling as display text ("5.0"), or null for no ceiling.  Derived from
	 * [cubismLevel] so UI text can never drift from the gating level.
	 *
	 * @return String? The version text, or null when this target has no Cubism ceiling.
	 */
	fun cubismLevelText(): String? {
		val level = cubismLevel ?: return null
		return "${level / 10}.${level % 10}"
	}

	/**
	 * The features this target restricts, in [RuntimeFeature] declaration (version-tier) order -
	 * the "unsupported list" the Document > Runtime section shows.  Always derived from [supports],
	 * never a hardcoded per-target list.
	 *
	 * @return List The restricted features; empty when nothing is restricted.
	 */
	fun restrictedFeatures(): List<RuntimeFeature> = RuntimeFeature.entries.filterNot { feature -> supports(feature) }

	/**
	 * The moc version a MOC3 export of this target bakes: the matching version for a Cubism target,
	 * the latest for NoTarget (nothing restricted), and V50 for Ayagami (its effective level).
	 *
	 * @return MocVersion The bake target.
	 */
	fun mocVersion(): MocVersion =
		when (this) {
			NoTarget -> MocVersion.V53
			Ayagami -> MocVersion.V50
			Cubism30 -> MocVersion.V30
			Cubism33 -> MocVersion.V33
			Cubism40 -> MocVersion.V40
			Cubism42 -> MocVersion.V42
			Cubism50 -> MocVersion.V50
			Cubism53 -> MocVersion.V53
		}

	/**
	 * The CMO3 target version this target persists as, or null for NoTarget - a target-less document
	 * has no target VERSION and persists through the latest sentinel instead (see
	 * [cmo3TargetVersionNo]).  Ayagami has no CMO3 encoding of its own, so it persists at its
	 * effective Cubism level: the gating level survives a CMO3 round-trip even though the Ayagami
	 * identity cannot (a reopen shows Cubism 5.0; UMA will carry the identity).
	 *
	 * @return Cmo3TargetVersion? The CMO3-side version, or null for NoTarget.
	 */
	fun cmo3TargetVersion(): Cmo3TargetVersion? =
		when (this) {
			NoTarget -> null
			// Ayagami writes its effective Cubism level, in lockstep with cubismLevel and mocVersion.
			Ayagami -> Cmo3TargetVersion.V50
			Cubism30 -> Cmo3TargetVersion.V30
			Cubism33 -> Cmo3TargetVersion.V33
			Cubism40 -> Cmo3TargetVersion.V40
			Cubism42 -> Cmo3TargetVersion.V42
			Cubism50 -> Cmo3TargetVersion.V50
			Cubism53 -> Cmo3TargetVersion.V53
		}

	/**
	 * The raw `targetVersionNo` a CMO3 save persists for this target: the version's literal, or the
	 * "SDK(N/A)/Latest Cubism" sentinel for NoTarget.  Every target persists something.
	 *
	 * @return Int The value to write.
	 */
	fun cmo3TargetVersionNo(): Int =
		// CMO3: CModelSource field targetVersionNo.
		cmo3TargetVersion()?.versionNo ?: Cmo3TargetVersion.LATEST_VERSION_NO
}

/**
 * A version-gated authoring feature - the unit the runtime repository restricts.
 *
 * Tier source of truth: the official Cubism Editor's "Model target version selection" dialog (its
 * restriction list is transcribed per entry below), cross-cited to docs/format/MOC3.md where a moc
 * section backs the feature.  Entries are declared in ascending version-tier order so
 * [RuntimeTarget.restrictedFeatures] lists them oldest-tier first.
 *
 * Some entries are informational only: Umamo has no authoring surface (or no model representation)
 * for them yet, so they appear in the restricted list and, where representable, in
 * [unsupportedFeaturesInUse], but gate no control.
 *
 * Part-typed composite masks ([PartComposite.maskedByParts], an Umamo extension) are deliberately
 * NOT a feature here: the part reference decomposes into its descendant drawable ids at the
 * CMO3/MOC3 boundary, so the exported result works on every target and there is nothing to
 * restrict or strip.
 */
enum class RuntimeFeature {
	/**
	 * The bilinear (quad) warp interpolation mode, [Deformer.Warp.isQuadTransform].  Official
	 * dialog: "New warp deformer method - SDK3.3/Cubism3.3 or later".  (MOC3 §5.6 WARP_MODE s101,
	 * absent = triangle split.)
	 */
	WarpQuadTransform,

	/** Inverted clipping, [Drawable.invertMask] / [PartComposite.invertMask].  Official dialog: "Reversed Mask - SDK4.0+".  (MOC3 §5.5 constant-flag bit 3.) */
	ReversedMask,

	/** Blend shapes on art meshes and warp deformers.  Official dialog: "Blend Shape - SDK4.2+ (Form of ArtMesh and Warp Deformer)".  (MOC3 §5.6 blend-shape sections 115-136.) */
	MeshWarpBlendShapes,

	/** Blend-shape parameters ([ParameterKind.BLEND_SHAPE]) - same 4.2 tier as the forms they drive.  (MOC3 §5.5 Parameter.Types s114.) */
	BlendShapeParameters,

	/** The multiply tint on drawables, deformers, and composites.  Official dialog: "Multiply Color - SDK4.2+".  (MOC3 color tables s105-113.) */
	MultiplyColor,

	/** The screen tint on drawables, deformers, and composites.  Official dialog: "Screen Color - SDK4.2+".  (MOC3 color tables s105-113.) */
	ScreenColor,

	/**
	 * Blend shapes beyond meshes and warps - parts, rotation deformers, glue, and the scalar
	 * channels.  Official dialog: "Blend Shape - SDK5.0+ (Part, ArtMesh, Warp Deformer, Rotation
	 * Deformer, Glue)".  Only the rotation-deformer slice is modeled in [PuppetModel] today.
	 */
	ExtendedBlendShapes,

	/** Motion-sync (the motionsync3.json sidecar family).  Official dialog: "Motion-sync - SDK5.0+".  Informational only - not modeled. */
	MotionSync,

	/**
	 * The Cubism 5.3 blend-mode family: the 15 non-legacy color modes ([BlendMode.isLegacy] false)
	 * AND the [AlphaBlendMode] list.  Official dialog: "Blend mode - SDK5.3+".  (MOC3 §5.6 s153/s157
	 * packed blend.)
	 */
	ExtendedBlendModes,

	/** Offscreen drawing - [PartGroupMode.Isolated] plus [PartComposite].  Official dialog: "Offscreen drawing - SDK5.3+".  (MOC3 §5.6 sections 152-163.) */
	PartComposite,

	/** Parameter repeat flags.  A 5.3 moc feature (MOC3 §5.5 s54).  Informational only - not modeled. */
	ParameterRepeat,

	/** Vector-line art paths.  Official dialog: "ArtPath - SDK(N/A)/Latest Cubism only".  Informational only - CMO3 round-trip payload, not modeled. */
	ArtPath,
}

/**
 * Maps a CMO3 document's decoded target version to the runtime target it selects, with unknown or
 * absent values falling back to no restriction.
 *
 * @param Cmo3TargetVersion? version The decoded CMO3 target version, or null for unknown/absent.
 * @return RuntimeTarget The matching target, or [RuntimeTarget.NoTarget] for null.
 */
fun runtimeTargetOfCmo3Target(version: Cmo3TargetVersion?): RuntimeTarget =
	when (version) {
		null -> RuntimeTarget.NoTarget
		Cmo3TargetVersion.V30 -> RuntimeTarget.Cubism30
		Cmo3TargetVersion.V33 -> RuntimeTarget.Cubism33
		Cmo3TargetVersion.V40 -> RuntimeTarget.Cubism40
		Cmo3TargetVersion.V42 -> RuntimeTarget.Cubism42
		Cmo3TargetVersion.V50 -> RuntimeTarget.Cubism50
		Cmo3TargetVersion.V53 -> RuntimeTarget.Cubism53
	}

/**
 * Maps a baked moc's version byte to the runtime target it implies - the moc version is a hard
 * fact of the file, so an imported .moc3 starts at the matching Cubism target.
 *
 * @param MocVersion version The moc version.
 * @return RuntimeTarget The matching Cubism target.
 */
fun runtimeTargetOfMocVersion(version: MocVersion): RuntimeTarget =
	when (version) {
		MocVersion.V30 -> RuntimeTarget.Cubism30
		MocVersion.V33 -> RuntimeTarget.Cubism33
		MocVersion.V40 -> RuntimeTarget.Cubism40
		MocVersion.V42 -> RuntimeTarget.Cubism42
		MocVersion.V50 -> RuntimeTarget.Cubism50
		MocVersion.V53 -> RuntimeTarget.Cubism53
	}
