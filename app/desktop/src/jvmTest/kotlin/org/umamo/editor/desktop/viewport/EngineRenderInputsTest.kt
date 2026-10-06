package org.umamo.editor.desktop.viewport

import org.umamo.edit.GridConfig
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.ModelUpdateKind
import org.umamo.render.puppet.OverlayColor
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.ui.viewport.AtlasPageBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the render inputs' publish rules: which changes bump which render version, that an identical
 * value is a no-op, that the atlas binding publishes without a bump (the loop bumps at apply), that
 * delivered artwork always bumps and drains in order, and how a model push is classified.  Pure: the
 * inputs hold no GL.
 */
class EngineRenderInputsTest {
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("Probe")
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadUvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val atlas = PuppetAtlas(pages = listOf(AtlasPage(16, 16)))
	private val textures = PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false)

	private fun drawableWith(mesh: DrawableMesh): Drawable =
		Drawable(
			id = probeId,
			name = "Probe",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = mesh,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(paramA, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(quadPositions.size)))),
				),
		)

	private fun modelWith(drawable: Drawable): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
			atlas = atlas,
		)

	private val model = modelWith(drawableWith(DrawableMesh(quadPositions, quadUvs, quadIndices)))

	private fun inputs(): EngineRenderInputs = EngineRenderInputs(model, textures)

	private fun assertBumps(inputs: EngineRenderInputs, puppet: Long, atlas: Long, message: String) {
		assertEquals(puppet, inputs.puppetRenderBump, "$message: puppet bump")
		assertEquals(atlas, inputs.atlasRenderBump, "$message: atlas bump")
	}

	@Test
	fun theShownSetIsSeededFromTheVisibleCascade() {
		val inputs = inputs()
		assertEquals(model.visibleDrawableIds(), inputs.shownDrawables)
		assertSame(model, inputs.model)
		assertSame(inputs.initialAtlasBinding, inputs.atlasBinding, "the published binding starts as the construction-time pair")
		assertSame(textures, inputs.initialAtlasBinding.textures)
		assertBumps(inputs, 0, 0, "nothing pushed yet")
	}

	@Test
	fun anIdenticalValueIsANoOp() {
		val inputs = inputs()
		inputs.setSelection(emptySet())
		inputs.setActiveSelection(null)
		inputs.setShownDrawables(model.visibleDrawableIds().toSet())
		inputs.gridColors = GridColors.Classic
		inputs.gridConfig = GridConfig()
		inputs.supersampleEnabled = true
		inputs.setSelectionHighlightColor(0.20f, 0.55f, 1.0f)
		inputs.setActiveSelectionHighlightColor(0.49f, 0.89f, 0.0f)
		inputs.setSourceLayerPlan(LayerDrawPlan.EMPTY)
		inputs.setMeshOverlay(null)
		inputs.setMeshOverlayPalette(MeshOverlayPalette.Classic)
		assertNull(inputs.setModel(model), "the same model instance is reported as no change")
		assertBumps(inputs, 0, 0, "identical values")
	}

	@Test
	fun aPuppetOnlyChangeBumpsThePuppetVersionAlone() {
		val inputs = inputs()
		inputs.setSelection(setOf(probeId))
		assertBumps(inputs, 1, 0, "selection")
		assertEquals(setOf(probeId), inputs.selection)
		inputs.setActiveSelection(probeId)
		assertBumps(inputs, 2, 0, "active selection")
		assertEquals(probeId, inputs.activeSelection)
		inputs.setShownDrawables(emptySet())
		assertBumps(inputs, 3, 0, "shown set")
		val plan = LayerDrawPlan(emptyMap(), emptyMap())
		inputs.setSourceLayerPlan(plan)
		assertBumps(inputs, 4, 0, "layer plan")
		assertSame(plan, inputs.sourceLayerPlan)
		inputs.setSelectionHighlightColor(1f, 0f, 0f)
		assertBumps(inputs, 5, 0, "selection highlight")
		assertEquals(listOf(1f, 0f, 0f), listOf(inputs.highlightRed, inputs.highlightGreen, inputs.highlightBlue))
		inputs.setActiveSelectionHighlightColor(0f, 1f, 0f)
		assertBumps(inputs, 6, 0, "active highlight")
		assertEquals(listOf(0f, 1f, 0f), listOf(inputs.activeHighlightRed, inputs.activeHighlightGreen, inputs.activeHighlightBlue))
	}

	@Test
	fun aGridOrQualityChangeBumpsBothVersions() {
		val inputs = inputs()
		inputs.gridColors = GridColors.Classic.copy(backgroundRed = 0.5f)
		assertBumps(inputs, 1, 1, "grid colors")
		inputs.gridConfig = GridConfig(scale = 50f)
		assertBumps(inputs, 2, 2, "grid config")
		inputs.supersampleEnabled = false
		assertBumps(inputs, 3, 3, "supersample")
		assertFalse(inputs.supersampleEnabled)
	}

	@Test
	fun theAtlasBindingPublishesWithoutABump() {
		val inputs = inputs()
		val binding = AtlasPageBinding(atlas, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false))
		inputs.setAtlasPages(binding)
		assertSame(binding, inputs.atlasBinding)
		assertBumps(inputs, 0, 0, "a binding publish leaves the versions to the loop's apply")
	}

	@Test
	fun deliveredArtworkAlwaysBumpsAndDrainsInOrder() {
		val inputs = inputs()
		val first = LayerRasterBatch(emptyMap())
		val second = LayerRasterBatch(emptyMap())
		inputs.deliverSourceLayerRasters(first)
		inputs.deliverSourceLayerRasters(second)
		assertBumps(inputs, 2, 0, "each delivery bumps")
		assertSame(first, inputs.pollRasterBatch())
		assertSame(second, inputs.pollRasterBatch())
		assertNull(inputs.pollRasterBatch(), "drained")
		inputs.deliverSourceLayerRasters(first)
		inputs.clearRasterBatches()
		assertNull(inputs.pollRasterBatch(), "cleared")
	}

	@Test
	fun theResizeQualityPolicyNeverBumps() {
		val inputs = inputs()
		inputs.supersampleWhileResizing = false
		assertFalse(inputs.supersampleWhileResizing)
		assertBumps(inputs, 0, 0, "the next resize simply picks the policy up")
	}

	@Test
	fun aModelPushIsClassifiedAndBumpsOnce() {
		val inputs = inputs()
		// The shape every preview push of a Grab has: a new mesh over the same uvs and indices, everything
		// else carried by reference.
		val drawable = model.drawables.single()
		val mesh = drawable.mesh!!
		val movedMesh = DrawableMesh(floatArrayOf(-50f, -50f, 50f, -50f, -50f, 50f, 50f, 50f), mesh.uvs, mesh.indices)
		val moved = model.copy(drawables = listOf(drawable.copy(mesh = movedMesh)))
		assertEquals(ModelUpdateKind.PositionsOnly, inputs.setModel(moved))
		assertSame(moved, inputs.model)
		assertBumps(inputs, 1, 0, "a positions-only push")
		val renamed = moved.copy(canvasWidth = 20f)
		assertEquals(ModelUpdateKind.Structural, inputs.setModel(renamed))
		assertSame(renamed, inputs.model)
		assertBumps(inputs, 2, 0, "a structural push")
		assertNull(inputs.setModel(renamed))
		assertBumps(inputs, 2, 0, "the same instance again")
	}

	/**
	 * The mesh overlay publishes by identity: a new instance bumps the puppet version once, the same one
	 * again does nothing, and an equal-looking new instance still counts (the producer hands back the
	 * same instance whenever nothing it shows changed, so a new one means new content).
	 */
	@Test
	fun aMeshOverlayPublishBumpsOnlyOnANewInstance() {
		val inputs = inputs()
		val first = overlayOverTheProbe()
		inputs.setMeshOverlay(first)
		assertSame(first, inputs.meshOverlay)
		assertBumps(inputs, 1, 0, "a first overlay")
		inputs.setMeshOverlay(first)
		assertBumps(inputs, 1, 0, "the same instance again")
		inputs.setMeshOverlay(overlayOverTheProbe())
		assertBumps(inputs, 2, 0, "a new instance over the same meshes")
		inputs.setMeshOverlay(null)
		assertNull(inputs.meshOverlay)
		assertBumps(inputs, 3, 0, "taking the overlay down")
		inputs.setMeshOverlay(null)
		assertBumps(inputs, 3, 0, "none again")
	}

	/** The palette publishes by equality: an equal copy is a no-op, a changed color bumps the puppet version. */
	@Test
	fun aMeshOverlayPaletteBumpsOnlyOnAChange() {
		val inputs = inputs()
		inputs.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy())
		assertBumps(inputs, 0, 0, "an equal palette, another instance")
		val magentaFaces = MeshOverlayPalette.Classic.copy(faceSelected = OverlayColor(1f, 0f, 1f, 1f))
		inputs.setMeshOverlayPalette(magentaFaces)
		assertEquals(magentaFaces, inputs.meshOverlayPalette)
		assertBumps(inputs, 1, 0, "a changed color")
	}

	/**
	 * An Edit overlay over the probe quad with nothing selected.
	 *
	 * @return MeshOverlay The overlay.
	 */
	private fun overlayOverTheProbe(): MeshOverlay =
		MeshOverlay(
			MeshOverlayKind.Edit,
			MeshOverlaySelectMode.Vertex,
			listOf(MeshOverlayMesh(probeId, 4, intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3), ByteArray(0), ByteArray(0), ByteArray(0), null, null, null)),
			MeshOverlaySizes(3.5f, 1f, 2.5f),
		)
}