package org.umamo.ui.properties

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.properties_field_alpha_mode
import org.umamo.ui.resources.properties_field_invert_mask
import org.umamo.ui.resources.properties_field_masked_by
import org.umamo.ui.resources.properties_field_multiply_color
import org.umamo.ui.resources.properties_field_opacity
import org.umamo.ui.resources.properties_field_quad_transform
import org.umamo.ui.resources.properties_field_runtime_target
import org.umamo.ui.resources.properties_field_screen_color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the runtime-target gating: the pure option filters (supported values plus the current one),
 * and the section shapes - which rows each Data-tab section builds under a restrictive target.
 * Compose-free like PropertyTabRegistryTest: `rows(context)` is a pure call and row terms identify
 * the rows without composing them.
 */
class RuntimeGatingTest {
	private val drawableId = DrawableId("drawable")
	private val partId = PartId("part")

	private fun drawable(
		blendMode: BlendMode = BlendMode.Normal,
		alphaBlendMode: AlphaBlendMode = AlphaBlendMode.Over,
	): Drawable =
		Drawable(
			id = drawableId,
			name = drawableId.raw,
			parentDeformerId = null,
			blendMode = blendMode,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			alphaBlendMode = alphaBlendMode,
		)

	private fun puppetWith(target: RuntimeTarget, drawables: List<Drawable> = emptyList(), parts: List<Part> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = parts,
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { entry -> OrgChild.Drawable(entry.id) } + parts.map { entry -> OrgChild.Part(entry.id) },
			rootPartId = null,
			runtimeTarget = target,
		)

	private fun drawableContext(
		target: RuntimeTarget,
		blendMode: BlendMode = BlendMode.Normal,
		alphaBlendMode: AlphaBlendMode = AlphaBlendMode.Over,
	): PropertyContext {
		val selected = SelectionTarget.Drawable(drawableId)
		return PropertyContext(
			puppetWith(target, drawables = listOf(drawable(blendMode, alphaBlendMode))),
			Selection(setOf(selected), selected),
			selected,
			session = null,
		)
	}

	private fun isolatedPartContext(target: RuntimeTarget): PropertyContext {
		val selected = SelectionTarget.Part(partId)
		val isolated = Part(partId, "part", children = emptyList(), groupMode = PartGroupMode.Isolated)
		return PropertyContext(
			puppetWith(target, parts = listOf(isolated)),
			Selection(setOf(selected), selected),
			selected,
			session = null,
		)
	}

	@Test
	fun blendModeOptionsFilterToLegacyPlusCurrent() {
		val legacyOnly = blendModeDisplayOrder().filter { mode -> mode.isLegacy }
		assertEquals(legacyOnly, blendModeOptionsFor(RuntimeTarget.Cubism50, BlendMode.Normal))
		assertEquals(legacyOnly, blendModeOptionsFor(RuntimeTarget.Ayagami, BlendMode.Normal), "Ayagami is 5.0-level")

		// An unsupported stored value stays selectable, in its display-order position.
		val withCurrent = blendModeDisplayOrder().filter { mode -> mode.isLegacy || mode == BlendMode.Overlay }
		assertEquals(withCurrent, blendModeOptionsFor(RuntimeTarget.Cubism50, BlendMode.Overlay))

		assertEquals(blendModeDisplayOrder(), blendModeOptionsFor(RuntimeTarget.NoTarget, BlendMode.Normal))
		assertEquals(blendModeDisplayOrder(), blendModeOptionsFor(RuntimeTarget.Cubism53, BlendMode.Normal))
	}

	@Test
	fun partGroupModeOptionsExcludeIsolatedUnlessCurrent() {
		assertEquals(
			listOf(PartGroupModeKind.PassThrough, PartGroupModeKind.Grouped),
			partGroupModeOptionsFor(RuntimeTarget.Cubism50, PartGroupModeKind.Grouped),
		)
		assertEquals(
			PartGroupModeKind.entries.toList(),
			partGroupModeOptionsFor(RuntimeTarget.Cubism50, PartGroupModeKind.Isolated),
			"an already-isolated part keeps its mode selectable",
		)
		assertEquals(PartGroupModeKind.entries.toList(), partGroupModeOptionsFor(RuntimeTarget.Cubism53, PartGroupModeKind.Grouped))
	}

	@Test
	fun alphaBlendRowNeedsTheModeAndTheTargetOrAStoredValue() {
		assertTrue(showsAlphaBlendRow(RuntimeTarget.Cubism53, BlendMode.Normal, AlphaBlendMode.Over))
		assertTrue(showsAlphaBlendRow(RuntimeTarget.NoTarget, BlendMode.Normal, AlphaBlendMode.Over))
		assertFalse(
			showsAlphaBlendRow(RuntimeTarget.Cubism50, BlendMode.Normal, AlphaBlendMode.Over),
			"alpha modes are part of the 5.3 blend feature",
		)
		assertTrue(
			showsAlphaBlendRow(RuntimeTarget.Cubism50, BlendMode.Normal, AlphaBlendMode.Atop),
			"a stored out-of-target value never vanishes from its own control",
		)
		assertFalse(
			showsAlphaBlendRow(RuntimeTarget.Cubism53, BlendMode.AdditivePremultiplied, AlphaBlendMode.Over),
			"premultiplied modes ignore alpha",
		)
	}

	@Test
	fun alphaBlendModeOptionsFilterToOverPlusCurrent() {
		assertEquals(
			listOf(AlphaBlendMode.Over, AlphaBlendMode.Atop),
			alphaBlendModeOptionsFor(RuntimeTarget.Cubism50, AlphaBlendMode.Atop),
			"the stored value stays, and Over is the only forward move",
		)
		assertEquals(AlphaBlendMode.entries.toList(), alphaBlendModeOptionsFor(RuntimeTarget.Cubism53, AlphaBlendMode.Over))
	}

	@Test
	fun blendSectionRowsGateColorsAlphaAndReversedMaskByTier() {
		val cubism40Terms = BlendSection.rows(drawableContext(RuntimeTarget.Cubism40)).flatMap { row -> row.terms }
		assertFalse(Res.string.properties_field_multiply_color in cubism40Terms, "multiply color is 4.2+")
		assertFalse(Res.string.properties_field_screen_color in cubism40Terms, "screen color is 4.2+")
		assertFalse(Res.string.properties_field_alpha_mode in cubism40Terms, "alpha modes are 5.3+")
		assertTrue(Res.string.properties_field_invert_mask in cubism40Terms, "reversed mask is supported at 4.0")

		val cubism33Terms = BlendSection.rows(drawableContext(RuntimeTarget.Cubism33)).flatMap { row -> row.terms }
		assertFalse(Res.string.properties_field_invert_mask in cubism33Terms, "reversed mask is 4.0+")

		val noTargetTerms = BlendSection.rows(drawableContext(RuntimeTarget.NoTarget)).flatMap { row -> row.terms }
		assertTrue(Res.string.properties_field_multiply_color in noTargetTerms)
		assertTrue(Res.string.properties_field_alpha_mode in noTargetTerms)
		assertTrue(Res.string.properties_field_invert_mask in noTargetTerms)
	}

	@Test
	fun partSectionHidesTheCompositeBlockBelowFiveThree() {
		val gatedTerms = PartSection.rows(isolatedPartContext(RuntimeTarget.Cubism50)).flatMap { row -> row.terms }
		assertFalse(Res.string.properties_field_opacity in gatedTerms, "the composite block hides as one unit")
		assertFalse(Res.string.properties_field_masked_by in gatedTerms)

		val fullTerms = PartSection.rows(isolatedPartContext(RuntimeTarget.Cubism53)).flatMap { row -> row.terms }
		assertTrue(Res.string.properties_field_opacity in fullTerms, "5.3 keeps the composite editable")
		assertTrue(Res.string.properties_field_masked_by in fullTerms)
	}

	@Test
	fun deformerSectionGatesTheQuadTransformRow() {
		val warp =
			Deformer.Warp(
				id = DeformerId("warp"),
				name = "warp",
				parent = null,
				partId = null,
				rows = 2,
				columns = 2,
				isQuadTransform = false,
				geometryGrid = null,
			)
		val selected = SelectionTarget.Deformer(warp.id)

		fun contextFor(target: RuntimeTarget): PropertyContext {
			val puppet =
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = listOf(warp),
					drawables = emptyList(),
					rootChildren = emptyList(),
					rootPartId = null,
					runtimeTarget = target,
				)
			return PropertyContext(puppet, Selection(setOf(selected), selected), selected, session = null)
		}

		val cubism30Terms = DeformerSection.rows(contextFor(RuntimeTarget.Cubism30)).flatMap { row -> row.terms }
		assertFalse(Res.string.properties_field_quad_transform in cubism30Terms, "the new warp method is 3.3+")
		assertFalse(Res.string.properties_field_multiply_color in cubism30Terms, "deformer tints are 4.2+")

		val cubism33Terms = DeformerSection.rows(contextFor(RuntimeTarget.Cubism33)).flatMap { row -> row.terms }
		assertTrue(Res.string.properties_field_quad_transform in cubism33Terms)
	}

	@Test
	fun runtimeSectionBuildsTheSelectorAndTheListRow() {
		val rows = RuntimeSection.rows(drawableContext(RuntimeTarget.Cubism50))
		assertEquals(2, rows.size)
		assertTrue(Res.string.properties_field_runtime_target in rows.first().terms)
	}
}