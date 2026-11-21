package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.drawable.CoordType
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.AlphaComposition
import org.umamo.format.cmo3.model.gen.CAffecterSourceSet
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CEditableMeshExtension
import org.umamo.format.cmo3.model.gen.CGlueForm
import org.umamo.format.cmo3.model.gen.CGlueSource
import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterGroupSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsInput
import org.umamo.format.cmo3.model.gen.CPhysicsOutput
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.gen.ColorComposition
import org.umamo.format.cmo3.model.gen.KeyformGridAccessKey
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.gen.KeyformOnGrid
import org.umamo.format.cmo3.model.gen.TextureState
import org.umamo.format.cmo3.model.gen.Type
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CColor
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.displayNameOf
import org.umamo.interop.cmo3.editableMeshOf
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.mutableGraphListOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.findParameterGroup
import org.umamo.runtime.model.partByDrawable

/*
 * The structural half of the CMO3 export reconcile: set membership.  Creations synthesize an
 * IDENTITY SHELL only (fresh guid/id, the texture/extension chain a duplicate shares with its
 * source) and are appended to their source set; the caller then routes the new entity through the
 * ordinary Changed lowering with every field flagged, so field writes flow through the one tested
 * path rather than a parallel synthesis copy.  Deletions remove the source AND strip its guid from
 * every parts-panel child list (a removed guid the org rebuild would otherwise preserve as an
 * unknown entry); the dangling cross-references (masks, axes, glues) fall out of the model-driven
 * Changed diffs, and the shared pool is pruned once at the end.
 */
internal class Cmo3StructureLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	private val drawableBindings: Map<String, Cmo3DrawableTextureBinding> = emptyMap(),
) {
	private val modelSource = context.modelSource
	private val editor = context.editor
	private val edited = context.edited

	/** True once any deletion ran - the caller prunes the shared pool exactly once at the end. */
	var deletedAnything: Boolean = false
		private set

	/**
	 * The shared CoordType every fresh editable mesh gets.
	 *
	 * CMO3: GEditableMesh2 field coordType - corpus meshes always carry a CoordType with coordName
	 * "Basic Coord"; one instance is reused so the writer hoists a single shared def.
	 */
	private val basicCoordType: CoordType by lazy {
		CoordType().apply { coordName = "Basic Coord" }
	}

	/**
	 * Removes every matching element from a graph collection field.
	 *
	 * @param Any? current The field's current value.
	 * @param Function matches Picks the elements to remove.
	 * @return Boolean True when anything was removed.
	 */
	private fun removeFromCollection(current: Any?, matches: (Any?) -> Boolean): Boolean {
		val mutable = mutableGraphListOf(current) ?: return false

		return mutable.removeAll(matches)
	}

	/**
	 * Strips [uuid] from every parts-panel child list (all user parts plus the synthetic root).
	 *
	 * @param String? uuid The guid to strip, or null to do nothing.
	 */
	private fun stripFromChildLists(uuid: String?) {
		if (uuid == null) {
			return
		}

		val parts = index.userPartSources + listOfNotNull(index.rootPartSource)

		for (part in parts) {
			mutableGraphListOf(part._childGuids)?.removeAll { entry -> uuidOf(entry) == uuid }
		}
	}

	/**
	 * Synthesizes the identity shell for a created parameter and appends it in document order (the
	 * PARAMETER_ORDER lowering re-sorts afterwards).
	 *
	 * @param Parameter editedParameter The created parameter.
	 * @return Boolean True on success.
	 */
	fun synthesizeParameter(editedParameter: Parameter): Boolean {
		val sourceSet = modelSource.parameterSourceSet as? CParameterSourceSet

		if (sourceSet == null) {
			notices.unsupported(ExportEntityCategory.Parameter, editedParameter.id.raw, ExportNoticeReason.NoSourceSetToCreateInto)

			return false
		}

		val template = index.parameterSources.firstOrNull()
		val fresh =
			CParameterSource().apply {
				// CMO3: CParameterSource - identity plus the editor conventions cloned from a sibling
				// (decimal places, snap epsilon, the explicit paramType).
				guid = freshGuidLike(template?.guid as? Guid, "CParameterGuid")
				id = freshIdLike(template?.id as? Id, "CParameterId", editedParameter.id.raw)
				decimalPlaces = template?.decimalPlaces ?: 3 // Documented default from surveying 50+ CMO3 files.
				snapEpsilon = template?.snapEpsilon ?: 0.001f // Documented default from surveying 50+ CMO3 files.
				minValue = editedParameter.min
				maxValue = editedParameter.max
				defaultValue = editedParameter.default
				isRepeat = editedParameter.repeat
				// CMO3: CParameterSource fields paramType / description - a Type element plus the
				// (possibly empty) description string on every corpus parameter, never null.
				paramType = if (editedParameter.kind == ParameterKind.BLEND_SHAPE) Type.MORPH_TARGET else template?.paramType ?: Type.NORMAL
				description = "" // Unhandled right now.
				name = editedParameter.name
				combined = false // This is handled in Cmo3DocumentLowering.lowerParameterOrderAndLinks().
				parentGroupGuid = index.rootParameterGroup?.guid
			}
		editor.appendToGraphList(sourceSet, "CParameterSourceSet", "_sources", sourceSet._sources, fresh, GraphListKind.CArrayList) { sourceSet._sources = it }

		return true
	}

	/**
	 * Removes a deleted parameter's source, and reports any physics setting the graph retained that still reads
	 * or drives it: Umamo does not model physics, so the settings are written back as they were and the notice
	 * is what tells the rigger they now name a parameter the file lacks.
	 *
	 * @param ParameterId parameterId The deleted parameter.
	 */
	fun deleteParameter(parameterId: ParameterId) {
		val sourceSet = modelSource.parameterSourceSet as? CParameterSourceSet ?: return
		val deleted = elementsOf(sourceSet._sources).filterIsInstance<CParameterSource>().firstOrNull { entry -> idStrOf(entry.id) == parameterId.raw }

		if (removeFromCollection(sourceSet._sources) { entry ->
				entry is CParameterSource && idStrOf(entry.id) == parameterId.raw
			}
		) {
			deletedAnything = true
		}

		val settingNames = deleted?.guid?.let(::physicsSettingsNaming).orEmpty()

		if (settingNames.isNotEmpty()) {
			notices.unsupported(ExportEntityCategory.Parameter, parameterId.raw, ExportNoticeReason.PhysicsNamesDeletedParameter(settingNames))
		}
	}

	/**
	 * The names of the physics settings whose inputs or outputs name the parameter whose guid is [parameterGuid].
	 *
	 * @param Any parameterGuid The parameter source's guid.
	 * @return List<String> The settings' names, in the graph's order.
	 */
	private fun physicsSettingsNaming(parameterGuid: Any): List<String> {
		val uuid = uuidOf(parameterGuid)

		/**
		 * Tells whether a reference names the parameter, by identity or by guid.
		 *
		 * @param Any? reference A guid reference found in the physics settings.
		 * @return Boolean True when it is the parameter's.
		 */
		fun names(reference: Any?): Boolean = reference === parameterGuid || (uuid != null && uuidOf(reference) == uuid)

		// CMO3: CModelSource field physicsSettingsSourceSet -> CPhysicsSettingsSourceSet field _sourceCubismPhysics
		// -> CPhysicsSettingsSource fields inputs / outputs -> CPhysicsInput field source and CPhysicsOutput field
		// destination, each the parameter source's own guid object (721 of 721 in the corpus,
		// Cmo3PhysicsReferenceProbeTest).
		val physicsSet = modelSource.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet ?: return emptyList()

		return elementsOf(physicsSet._sourceCubismPhysics)
			.filterIsInstance<CPhysicsSettingsSource>()
			.filter { setting ->
				elementsOf(setting.inputs).filterIsInstance<CPhysicsInput>().any { input -> names(input.source) } ||
					elementsOf(setting.outputs).filterIsInstance<CPhysicsOutput>().any { output -> names(output.destination) }
			}.map { setting -> displayNameOf(setting.name, idStrOf(setting.id)) }
	}

	/**
	 * Synthesizes the identity shell for a created parameter group.
	 *
	 * @param ParameterGroupId groupId The created group's id.
	 * @return Boolean True on success.
	 */
	fun synthesizeParameterGroup(groupId: ParameterGroupId): Boolean {
		val groupSet = modelSource.parameterGroupSet as? CParameterGroupSet

		if (groupSet == null) {
			notices.unsupported(ExportEntityCategory.ParameterGroup, groupId.raw, ExportNoticeReason.NoSourceSetToCreateInto)

			return false
		}

		val editedGroup = findParameterGroup(edited.parameterTree, groupId)

		if (editedGroup == null) {
			notices.unsupported(ExportEntityCategory.ParameterGroup, groupId.raw, ExportNoticeReason.CreatedGroupIsNotInTheEditedTree)

			return false
		}

		val template = index.groupSources.firstOrNull() ?: index.rootParameterGroup
		val fresh =
			CParameterGroup().apply {
				// CMO3: CParameterGroup - identity shell; name/open state/children flow through the
				// ordinary group lowering.
				guid = freshGuidLike(template?.guid as? Guid, "CParameterGroupGuid")
				id = freshIdLike(template?.id as? Id, "CParameterGroupId", groupId.raw)
				name = editedGroup.name
				// CMO3: CParameterGroup fields description / labelColor - present on every corpus
				// group, never null.
				description = ""
				labelColor = undefinedLabelColor()
				folderIsOpened = editedGroup.initiallyOpen
				_childGuids = CArrayList<Any?>()
				parentGroupGuid = index.rootParameterGroup?.guid
			}
		editor.appendToGraphList(groupSet, "CParameterGroupSet", "_groups", groupSet._groups, fresh, GraphListKind.CArrayList) { groupSet._groups = it }

		return true
	}

	/**
	 * Removes a deleted parameter group's source.
	 *
	 * @param ParameterGroupId groupId The deleted group.
	 */
	fun deleteParameterGroup(groupId: ParameterGroupId) {
		val groupSet = modelSource.parameterGroupSet as? CParameterGroupSet ?: return

		if (removeFromCollection(groupSet._groups) { entry ->
				entry is CParameterGroup && idStrOf(entry.id) == groupId.raw
			}
		) {
			deletedAnything = true
		}
	}

	/**
	 * Synthesizes the identity shell for a session-created drawable (an Object-mode duplicate):
	 * fresh guid/id and editable mesh, the texture binding SHARED with its texture source (the
	 * atlas slot both render from; no new CAFF entry).  Geometry, keyforms, and every flat field
	 * flow through the ordinary Changed lowering afterwards.
	 *
	 * @param Drawable editedDrawable The created drawable.
	 * @return Boolean True on success.
	 */
	fun synthesizeDrawable(editedDrawable: Drawable): Boolean {
		val subject = editedDrawable.id.raw
		val sourceSet = modelSource.drawableSourceSet as? CDrawableSourceSet

		if (sourceSet == null) {
			notices.unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.NoSourceSetToCreateInto)

			return false
		}

		val textureSource = editedDrawable.textureSourceId?.let { index.drawableByIdStr[it.raw] }
		val binding = drawableBindings[subject]

		if (textureSource == null && binding == null) {
			notices.unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.CreatedDrawableHasNoTextureSource)

			return false
		}

		val templateMesh = textureSource?.let(::editableMeshOf)
		val ownerPartId = edited.partByDrawable()[editedDrawable.id]
		val ownerGuid = ownerPartId?.let { index.partByIdStr[it.raw]?.guid } ?: index.rootPartSource?.guid
		val fresh = CArtMeshSource()
		fresh.apply {
			guid = freshGuidLike(textureSource?.guid as? Guid, "CDrawableGuid")
			id = freshIdLike(textureSource?.id as? Id, "CDrawableId", subject)
			parentGuid = ownerGuid
			// CMO3: ACParameterControllableSource fields keyformMorphTargetSet / labelColor and
			// CArtMeshSource field userData - present on every corpus drawable, never omitted.
			keyformMorphTargetSet = emptyMorphTargetSet()
			labelColor = undefinedLabelColor()
			userData = ""
			// CMO3: ACDrawableSource fields icon32 / icon16 - the drawable's own thumbnails of its
			// texture patch, minted by whoever built the binding; a drawable with no binding (a clone
			// of a source with no art of its own) carries none.
			icon32 = binding?.icon32
			icon16 = binding?.icon16

			if (textureSource != null) {
				// CMO3: CArtMeshSource identity shell.  The texture and texture-input extension are
				// the SOURCE drawable's own objects (the writer hoists the shared references), so the
				// duplicate samples the same atlas slot without new image-chain entries.
				texture = textureSource.texture
				textureState = textureSource.textureState
			} else {
				// CMO3: the fresh-graph binding - the page's SHARED GTexture2D plus a per-drawable
				// CTextureInput_TextureAtlasRegion, so UVs stay in the atlas frame (hasAtlasRegion); or, for
				// art never packed, the raster texture over its model image, in the MODEL_IMAGE state every
				// corpus unpacked drawable carries.
				texture = binding!!.texture
				textureState =
					if (binding.isUnpacked) {
						TextureState.MODEL_IMAGE
					} else {
						TextureState.TEXTURE_ATLAS
					}
			}

			_extensions =
				CArrayList<Any?>().apply {
					if (textureSource != null) {
						elementsOf(textureSource._extensions)
							.filterIsInstance<CTextureInputExtension>()
							.firstOrNull()
							?.let(::add)
					} else {
						add(freshTextureInputExtension(fresh, binding!!, editedDrawable.name))
					}

					add(
						CEditableMeshExtension().apply {
							guid = freshGuidLike(null, "CExtensionGuid")
							// CMO3: ACExtension field _owner - the owning drawable source; every
							// corpus extension carries the backref.
							_owner = fresh
							editableMesh =
								GEditableMesh2().apply {
									// CMO3: GEditableMesh2 field meshGuid - kind GEditableMeshGuid
									// (com.live2d.type.GEditableMeshGuid); no corpus file ever emits a
									// CMeshGuid tag, so that kind would author an unimportable class.
									meshGuid = freshGuidLike(templateMesh?.meshGuid as? Guid, "GEditableMeshGuid")
									// CMO3: GEditableMesh2 field coordType - corpus meshes always
									// carry "Basic Coord" (never null).
									coordType = templateMesh?.coordType ?: basicCoordType
									useDelaunayTriangulation = false
								}
						},
					)
				}
		}
		editor.appendToGraphList(sourceSet, "CDrawableSourceSet", "_sources", sourceSet._sources, fresh, GraphListKind.CArrayList) { sourceSet._sources = it }

		return true
	}

	/**
	 * Builds the per-drawable texture-input extension for a fresh-graph binding: both input kinds
	 * (model image + atlas region), with the one the document's display mode samples made active,
	 * mirroring the editor's packed drawables; or, for an unpacked binding, the model image alone,
	 * active in either mode, as the editor writes a drawable it never packed.
	 *
	 * @param CArtMeshSource             owner       The drawable source under construction.
	 * @param Cmo3DrawableTextureBinding binding     The drawable's texture web (page texture + patch, or an
	 *   unpacked drawable's raster texture + model image).
	 * @param String                     subjectName The drawable's display name, naming it in the display-mode notice.
	 * @return CTextureInputExtension The fresh extension.
	 */
	private fun freshTextureInputExtension(
		owner: CArtMeshSource,
		binding: Cmo3DrawableTextureBinding,
		subjectName: String,
	): CTextureInputExtension {
		val extension = CTextureInputExtension()
		val atlasRegion =
			binding.textureAtlasGuid?.let { atlasGuid ->
				atlasRegionInput(extension, atlasGuid, checkNotNull(binding.inputImageLocalToCanvasTransform))
			}
		val modelImage =
			binding.modelImageGuid?.let { imageGuid ->
				CTextureInput_ModelImage().apply {
					// CMO3: CTextureInput_ModelImage field _modelImageGuid.
					optionalTransformOnCanvas = CAffine()
					_owner = extension
					_modelImageGuid = imageGuid
				}
			}

		// A layer-mode document whose created drawable has no model image keeps the atlas region active:
		// it is the only input such a drawable has, and leaving the live pointer there is what keeps the
		// file self-consistent.  The notice names the gap, matching what the document lowering reports
		// for an EXISTING drawable in the same state - one reason, reported the same way from both paths.
		if (edited.rendersFromSourceLayers && modelImage == null) {
			notices.unsupported(ExportEntityCategory.Drawable, subjectName, ExportNoticeReason.NoTextureInputForDisplayMode)
		}

		return extension.apply {
			guid = freshGuidLike(null, "CExtensionGuid")
			_owner = owner
			_textureInputs =
				CArrayList<Any?>().apply {
					modelImage?.let(::add)
					atlasRegion?.let(::add)
				}
			// CMO3: CTextureInputExtension field currentTextureInputData - which input the document's
			// display mode samples.  A drawable created in a layer-mode document must point at its model
			// image, or the file's flag and its drawables disagree; an unpacked drawable has only its model
			// image, which is current in both modes.
			currentTextureInputData =
				if (edited.rendersFromSourceLayers || atlasRegion == null) {
					modelImage ?: atlasRegion
				} else {
					atlasRegion
				}
		}
	}

	/**
	 * Synthesizes the identity shell for a created part: the super web plus a one-cell keyform grid
	 * with a single CPartForm, so static draw-order/opacity/composite writes have a form to land in
	 * (the field lowering only fills EXISTING part forms).  Panel placement flows from the parent's
	 * CHILDREN rebuild.
	 *
	 * @param Part editedPart The created part.
	 * @return Boolean True on success.
	 */
	fun synthesizePart(editedPart: Part): Boolean {
		val sourceSet = modelSource.partSourceSet as? CPartSourceSet

		if (sourceSet == null) {
			notices.unsupported(ExportEntityCategory.Part, editedPart.id.raw, ExportNoticeReason.NoSourceSetToCreateInto)

			return false
		}

		val template = index.userPartSources.firstOrNull() ?: index.rootPartSource
		val part = CPartSource()
		val formGuid = freshGuidLike(null, "CFormGuid")
		val form =
			CPartForm().apply {
				// CMO3: CPartForm - the one default-cell form (drawOrder/opacity/colors).
				guid = formGuid
				_source = part
				notes = ""
				drawOrder = editedPart.drawOrder
				opacity = 1f
				multiplyColor = identityMultiplyColor()
				screenColor = identityScreenColor()
			}
		part.apply {
			localName = editedPart.name
			isVisible = true
			keyformGridSource =
				KeyformGridSource().apply {
					// CMO3: KeyformGridSource - one unkeyed cell (empty access key) like the blank's parts.
					keyformsOnGrid =
						ArrayList<Any?>(
							mutableListOf(
								KeyformOnGrid().apply {
									accessKey =
										KeyformGridAccessKey().apply {
											_keyOnParameterList = ArrayList<Any?>()
										}
									keyformGuid = formGuid
								},
							),
						)
					keyformBindings = ArrayList<Any?>()
				}
			keyformMorphTargetSet = emptyMorphTargetSet()
			_extensions = CArrayList<Any?>()
			labelColor = undefinedLabelColor()
			guid = freshGuidLike(template?.guid as? Guid, "CPartGuid")
			id = freshIdLike(template?.id as? Id, "CPartId", editedPart.id.raw)
			keyforms = CArrayList<Any?>(mutableListOf(form))
			defaultOrder_forEditor = editedPart.drawOrder
			partsEditColor = CColor()
			_childGuids = CArrayList<Any?>()
			clipGuidList = CArrayList<Any?>()
			colorComposition = ColorComposition.NORMAL
			alphaComposition = AlphaComposition.OVER
		}
		editor.appendToGraphList(sourceSet, "CPartSourceSet", "_sources", sourceSet._sources, part, GraphListKind.CArrayList) { sourceSet._sources = it }

		return true
	}

	/**
	 * Synthesizes the identity shell for a created deformer (warp or rotation).  Lattice/angle
	 * fields, parent/part wiring, and the whole keyform web flow through the Changed lowering.
	 *
	 * @param Deformer editedDeformer The created deformer.
	 * @return Boolean True on success.
	 */
	fun synthesizeDeformer(editedDeformer: Deformer): Boolean {
		val sourceSet = modelSource.deformerSourceSet as? CDeformerSourceSet

		if (sourceSet == null) {
			notices.unsupported(ExportEntityCategory.Deformer, editedDeformer.id.raw, ExportNoticeReason.NoSourceSetToCreateInto)

			return false
		}

		val fresh: ACDeformerSource =
			when (editedDeformer) {
				is Deformer.Warp -> {
					val template =
						index.deformerSources.filterIsInstance<CWarpDeformerSource>().firstOrNull()
					CWarpDeformerSource().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CDeformerGuid")
						id = freshIdLike(template?.id as? Id, "CDeformerId", editedDeformer.id.raw)
						keyforms = CArrayList<Any?>()
					}
				}

				is Deformer.Rotation -> {
					val template =
						index.deformerSources.filterIsInstance<CRotationDeformerSource>().firstOrNull()
					CRotationDeformerSource().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CDeformerGuid")
						id = freshIdLike(template?.id as? Id, "CDeformerId", editedDeformer.id.raw)
						keyforms = CArrayList<Any?>()
						// CMO3: CRotationDeformerSource fields handleLengthOnCanvas /
						// circleRadiusOnCanvas - editor gizmo sizing only; cloned from a sibling when
						// one exists, else nominal defaults (no runtime consumes them).
						handleLengthOnCanvas = template?.handleLengthOnCanvas ?: 100f
						circleRadiusOnCanvas = template?.circleRadiusOnCanvas ?: 20f
					}
				}
			}
		fresh.apply {
			localName = editedDeformer.name
			isVisible = true
			isLocked = !editedDeformer.isSelectable
			keyformMorphTargetSet = emptyMorphTargetSet()
			_extensions = CArrayList<Any?>()
			labelColor = undefinedLabelColor()
		}
		editor.appendToGraphList(sourceSet, "CDeformerSourceSet", "_sources", sourceSet._sources, fresh, GraphListKind.CArrayList) { sourceSet._sources = it }

		return true
	}

	/**
	 * Synthesizes the identity shell for a created glue.  Pair weights/uids and the intensity
	 * track flow through the Changed lowering (lowerGluePairsFor + lowerGlue).
	 *
	 * @param Glue editedGlue The created glue.
	 * @param Int  ordinal    The glue's ordinal among same-pair glues (the diff key).
	 * @return Boolean True on success.
	 */
	fun synthesizeGlue(editedGlue: Glue, ordinal: Int): Boolean {
		val subject = "${editedGlue.meshA.raw}~${editedGlue.meshB.raw}"
		val sourceSet = modelSource.affecterSourceSet as? CAffecterSourceSet

		if (sourceSet == null) {
			notices.unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.NoSourceSetToCreateInto)

			return false
		}

		val meshAGuid = index.drawableByIdStr[editedGlue.meshA.raw]?.guid as? Guid
		val meshBGuid = index.drawableByIdStr[editedGlue.meshB.raw]?.guid as? Guid

		if (meshAGuid == null || meshBGuid == null) {
			notices.unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.GluedDrawableHasNoSource)

			return false
		}

		val template = index.glueSources.firstOrNull()
		val ownerPartId = edited.partByDrawable()[editedGlue.meshA]
		val ownerPart = ownerPartId?.let { index.partByIdStr[it.raw] } ?: index.rootPartSource
		val ownerGuid = ownerPart?.guid
		val fresh = CGlueSource()
		val glueGuid = freshGuidLike(template?.guid as? Guid, "CAffecterGuid")
		val formGuid = freshGuidLike(null, "CFormGuid")
		val form =
			CGlueForm().apply {
				// CMO3: CGlueForm - the one default-cell form; every corpus glue carries at least
				// this cell, and the intensity lowering writes only into existing forms.
				guid = formGuid
				isAnimatedForm = false
				isLocalAnimatedForm = false
				_source = fresh
				notes = ""
				intensity = 1f
			}
		fresh.apply {
			localName = ""
			isVisible = true
			// CMO3: ACParameterControllableSource field parentGuid - the owning part, like the
			// glued drawables' own panel parent (corpus glues always sit under a part).
			parentGuid = ownerGuid
			keyformGridSource =
				KeyformGridSource().apply {
					// CMO3: KeyformGridSource - one unkeyed cell (empty access key), the corpus
					// glue default; an intensity track later rebuilds this web via writeGridWeb.
					keyformsOnGrid =
						ArrayList<Any?>(
							mutableListOf(
								KeyformOnGrid().apply {
									accessKey =
										KeyformGridAccessKey().apply {
											_keyOnParameterList = ArrayList<Any?>()
										}
									keyformGuid = formGuid
								},
							),
						)
					keyformBindings = ArrayList<Any?>()
				}
			keyformMorphTargetSet = emptyMorphTargetSet()
			_extensions = CArrayList<Any?>()
			labelColor = undefinedLabelColor()
			guid = glueGuid
			// The model keys glues by mesh pair + ordinal (they carry no id of their own), so the
			// minted idstr only needs uniqueness within the document.
			id = freshIdLike(template?.id as? Id, "CAffecterId", "Glue_${editedGlue.meshA.raw}_${editedGlue.meshB.raw}_$ordinal")
			// CMO3: ACAffecterSource field targetDeformerGuid - the editor's fixed root-deformer
			// sentinel (uuid identical in every corpus file; the editor writes it even for glues
			// whose meshes have real deformer parents).
			targetDeformerGuid = rootDeformerSentinel()
			// CMO3: CGlueSource fields targetArtMeshA_guid / targetArtMeshB_guid - identity refs
			// to the glued drawables' own guid objects.
			targetArtMeshA_guid = meshAGuid
			targetArtMeshB_guid = meshBGuid
			// CMO3: CGlueSource field tabPosOnCanvas - the glue tab marker, never null; the editor
			// stacks them at x = -100 down the canvas's left margin.
			tabPosOnCanvas =
				GVector2().apply {
					x = -100f
					y = 100f * (ordinal + 1)
				}
			keyforms = CArrayList<Any?>(mutableListOf(form))
		}
		editor.appendToGraphList(sourceSet, "CAffecterSourceSet", "_sources", sourceSet._sources, fresh, GraphListKind.CArrayList) { sourceSet._sources = it }

		// CMO3: CPartSource field _childGuids - the owning part lists the glue's CAffecterGuid
		// after its drawables and deformers; the editor's load-time verify repairs (and 5.4
		// crashes on) an affecter absent from the part hierarchy.
		if (ownerPart != null) {
			editor.appendToGraphList(ownerPart, "CPartSource", "_childGuids", ownerPart._childGuids, glueGuid, GraphListKind.CArrayList) { ownerPart._childGuids = it }
		}

		return true
	}

	/**
	 * Removes a deleted drawable's source and strips its guid from every panel child list.
	 *
	 * @param DrawableId drawableId The deleted drawable.
	 */
	fun deleteDrawable(drawableId: DrawableId) {
		val sourceSet = modelSource.drawableSourceSet as? CDrawableSourceSet ?: return
		val source = index.drawableByIdStr[drawableId.raw]

		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/**
	 * Removes a deleted part's source and strips its guid from every panel child list.
	 *
	 * @param String partIdRaw The deleted part's raw id.
	 */
	fun deletePart(partIdRaw: String) {
		val sourceSet = modelSource.partSourceSet as? CPartSourceSet ?: return
		val source = index.partByIdStr[partIdRaw]

		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/**
	 * Removes a deleted deformer's source and strips its guid from every panel child list.
	 *
	 * @param String deformerIdRaw The deleted deformer's raw id.
	 */
	fun deleteDeformer(deformerIdRaw: String) {
		val sourceSet = modelSource.deformerSourceSet as? CDeformerSourceSet ?: return
		val source = index.deformerByIdStr[deformerIdRaw]

		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/**
	 * Removes a deleted glue's source, resolved by mesh pair plus ordinal.
	 *
	 * @param DrawableId meshA The glue's first mesh.
	 * @param DrawableId meshB The glue's second mesh.
	 * @param Int ordinal Which glue of that pair, counted from zero.
	 */
	fun deleteGlue(meshA: DrawableId, meshB: DrawableId, ordinal: Int) {
		val sourceSet = modelSource.affecterSourceSet as? CAffecterSourceSet ?: return
		val matching =
			index.glueSources.filter { glueSource ->
				index.drawableIdStrByUuid[uuidOf(glueSource.targetArtMeshA_guid)] == meshA.raw &&
					index.drawableIdStrByUuid[uuidOf(glueSource.targetArtMeshB_guid)] == meshB.raw
			}
		val victim = matching.getOrNull(ordinal) ?: return

		if (removeFromCollection(sourceSet._sources) { entry -> entry === victim }) {
			stripFromChildLists(uuidOf(victim.guid))
			deletedAnything = true
		}
	}
}