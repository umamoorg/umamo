package org.umamo.runtime.model

/**
 * The restricted features this document ACTUALLY USES under [target] - the strip diff a MOC3
 * export's "features will be stripped" confirmation lists.  A feature the target supports is never
 * reported, and neither is a restricted feature the document does not use.
 *
 * [RuntimeFeature.MotionSync], [RuntimeFeature.ParameterRepeat], and [RuntimeFeature.ArtPath] are
 * never reported: they are not representable in [PuppetModel] (motion-sync is a sidecar family,
 * the other two survive only as CMO3 round-trip payload), so an export dialog cannot warn about
 * them from the model alone.
 *
 * Latent state does not count as use: a non-isolated part's stored [Part.composite] is invisible
 * to both renderer and runtime, so only isolated parts' composites are scanned.
 *
 * @param RuntimeTarget target The target to diff against.
 * @return Set The restricted features in use; empty when the document fits the target.
 */
fun PuppetModel.unsupportedFeaturesInUse(target: RuntimeTarget): Set<RuntimeFeature> {
	val unsupportedFeatures = RuntimeFeature.entries.filterNot { feature -> target.supports(feature) }
	if (unsupportedFeatures.isEmpty()) {
		return emptySet()
	}
	// Materialized once for the whole scan rather than inside every feature branch.
	val activeComposites = parts.mapNotNull { part -> part.activeComposite }
	return unsupportedFeatures.filterTo(linkedSetOf()) { feature -> usesFeature(feature, activeComposites) }
}

/**
 * Whether the document uses [feature] at all, independent of any target.
 *
 * @param RuntimeFeature feature The feature to probe for.
 * @param List activeComposites The isolated parts' composites, precomputed by the caller.
 * @return Boolean True when the feature is present somewhere in the document.
 */
private fun PuppetModel.usesFeature(feature: RuntimeFeature, activeComposites: List<PartComposite>): Boolean =
	when (feature) {
		RuntimeFeature.WarpQuadTransform ->
			deformers.any { deformer -> deformer is Deformer.Warp && deformer.isQuadTransform }

		RuntimeFeature.ReversedMask ->
			drawables.any { drawable -> drawable.invertMask } || activeComposites.any { composite -> composite.invertMask }

		RuntimeFeature.MeshWarpBlendShapes ->
			drawables.any { drawable -> drawable.blendShapes.isNotEmpty() } ||
				deformers.any { deformer -> deformer is Deformer.Warp && deformer.blendShapes.isNotEmpty() }

		RuntimeFeature.BlendShapeParameters ->
			parameters.any { parameter -> parameter.kind == ParameterKind.BLEND_SHAPE }

		RuntimeFeature.MultiplyColor -> usesColorChannel(FormChannel.MULTIPLY_COLOR, ColorRgb.MultiplyIdentity)

		RuntimeFeature.ScreenColor -> usesColorChannel(FormChannel.SCREEN_COLOR, ColorRgb.ScreenIdentity)

		RuntimeFeature.ExtendedBlendShapes ->
			deformers.any { deformer -> deformer is Deformer.Rotation && deformer.blendShapes.isNotEmpty() }

		RuntimeFeature.ExtendedBlendModes ->
			drawables.any { drawable -> !drawable.blendMode.isLegacy || drawable.alphaBlendMode != AlphaBlendMode.Over } ||
				activeComposites.any { composite -> !composite.blendMode.isLegacy || composite.alphaBlendMode != AlphaBlendMode.Over }

		RuntimeFeature.PartComposite -> parts.any { part -> part.isIsolated }

		RuntimeFeature.MotionSync,
		RuntimeFeature.ParameterRepeat,
		RuntimeFeature.ArtPath,
		-> false
	}

/**
 * Whether any drawable, deformer, or isolated part tints through [channel] - a non-identity static
 * or a keyform track on that channel.
 *
 * @param FormChannel channel The color channel (multiply or screen).
 * @param ColorRgb identity That channel's identity color (leaves pixels unchanged).
 * @return Boolean True when the channel is in use somewhere.
 */
private fun PuppetModel.usesColorChannel(channel: FormChannel, identity: ColorRgb): Boolean {
	val isMultiply = channel == FormChannel.MULTIPLY_COLOR
	val drawableUses =
		drawables.any { drawable ->
			val staticColor = if (isMultiply) drawable.multiplyColor else drawable.screenColor
			staticColor != identity || drawable.channelGrids[channel] != null
		}
	val deformerUses =
		deformers.any { deformer ->
			val staticColor = if (isMultiply) deformer.multiplyColor else deformer.screenColor
			staticColor != identity || deformer.channelGrids[channel] != null
		}
	// A part's color track keys its composite, so it only counts while the part is isolated.
	val compositeUses =
		parts.any { part ->
			val composite = part.activeComposite ?: return@any false
			val staticColor = if (isMultiply) composite.multiplyColor else composite.screenColor
			staticColor != identity || part.channelGrids[channel] != null
		}
	return drawableUses || deformerUses || compositeUses
}
