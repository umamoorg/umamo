package org.umamo.interop.fidelity

import org.umamo.format.moc3.model.BlendShapeTarget

/** The corpus gates that compare an export against the document it should reproduce. */
internal enum class FidelityGate(val label: String) {
	/** A CMO3 exported to MOC3, against the editor's own bake of the same model. */
	CMO3_TO_MOC3_VS_EDITOR("CMO3 to MOC3 against the editor's bake"),

	/** A MOC3 imported and exported again, as written. */
	MOC3_ROUND_TRIP("MOC3 to MOC3"),

	/** A MOC3 converted to CMO3, re-imported, and exported to MOC3 again, against the source. */
	MOC3_CMO3_MOC3_LOOP("MOC3 to CMO3 to MOC3"),
}

/**
 * The losses an export is known to have today, each excused on the gates it is seen on and nowhere else.
 *
 * The list only shrinks: an entry that none of its gates sees any more fails them, so the change that
 * removed the loss also removes the entry.  An entry describes a kind of divergence, never a corpus rig.
 *
 * @property Set gates The gates the loss is seen on.
 * @property String removedWhen The change that retires the entry.
 * @property Function matcher Whether a divergence is this loss.
 */
internal enum class KnownExportLoss(val gates: Set<FidelityGate>, val removedWhen: String, private val matcher: (MocDivergence) -> Boolean) {
	/** A blend shape on a part, which the CMO3 import does not ingest and no CMO3 lowering writes. */
	PART_BLEND_SHAPES(
		setOf(FidelityGate.CMO3_TO_MOC3_VS_EDITOR, FidelityGate.MOC3_CMO3_MOC3_LOOP),
		"the CMO3 import and export carry blend shapes on parts",
		{ divergence ->
			divergence.kind == MocObjectKind.BLEND_SHAPE &&
				divergence.aspect == DivergenceAspect.Missing &&
				divergence.id.startsWith("${BlendShapeTarget.PART}:")
		},
	),

	/** A recorded export scale other than the canvas width, which a trip through CMO3 resets. */
	PIXELS_PER_UNIT(
		setOf(FidelityGate.MOC3_CMO3_MOC3_LOOP),
		"a CMO3 export keeps the scale a MOC3-origin document recorded",
		{ divergence ->
			val aspect = divergence.aspect
			divergence.kind == MocObjectKind.CANVAS && aspect is DivergenceAspect.Attribute && aspect.name == MocAttribute.PIXELS_PER_UNIT
		},
	),

	/** A glue id, which the CMO3 import leaves unset and the MOC3 export then synthesizes. */
	GLUE_IDS(
		setOf(FidelityGate.CMO3_TO_MOC3_VS_EDITOR, FidelityGate.MOC3_CMO3_MOC3_LOOP),
		"a glue keeps its authored id and a new one is named after its meshes",
		{ divergence ->
			val aspect = divergence.aspect
			divergence.kind == MocObjectKind.GLUE && aspect is DivergenceAspect.Attribute && aspect.name == MocAttribute.ID
		},
	),

	/** All-zero multiply and screen color delta rows, written into a target whose bakes carry none. */
	ZERO_COLOR_ROWS(
		setOf(FidelityGate.MOC3_ROUND_TRIP, FidelityGate.MOC3_CMO3_MOC3_LOOP),
		"the MOC3 export writes the blend delta color region only for a target whose bakes have it",
		{ divergence ->
			val aspect = divergence.aspect
			divergence.kind == MocObjectKind.BLEND_SHAPE &&
				aspect is DivergenceAspect.ChannelGained &&
				aspect.allZero &&
				(aspect.channel == MocChannel.MULTIPLY_COLOR_DELTA || aspect.channel == MocChannel.SCREEN_COLOR_DELTA)
		},
	),

	/** Art mesh UVs that differ from the editor's bake on a CMO3-origin export. */
	UV_PACKING(
		setOf(FidelityGate.CMO3_TO_MOC3_VS_EDITOR),
		"the CMO3-origin UVs are explained and either fixed or shown to be a valid packing",
		{ divergence ->
			val aspect = divergence.aspect
			divergence.kind == MocObjectKind.ART_MESH && aspect is DivergenceAspect.Attribute && aspect.name == MocAttribute.UVS
		},
	),
	;

	/**
	 * Whether [divergence] is this loss, on any gate.
	 *
	 * @param MocDivergence divergence The divergence to classify.
	 * @return Boolean True when the divergence is of this kind.
	 */
	fun matches(divergence: MocDivergence): Boolean = matcher(divergence)
}

/**
 * Sorts one gate's divergences into the known losses it excuses and the failures it does not.
 *
 * @property FidelityGate gate The gate whose run this is.
 */
internal class FidelityLedger(private val gate: FidelityGate) {
	/**
	 * How often one known loss was seen.
	 *
	 * @property Int divergences The divergences it excused.
	 * @property Set objects The objects they were on, by rig.
	 * @property Set rigs The rigs they were in.
	 */
	private class Tally(var divergences: Int = 0, val objects: MutableSet<String> = HashSet(), val rigs: MutableSet<String> = HashSet())

	private val unexpected = ArrayList<String>()
	private val tallies = LinkedHashMap<KnownExportLoss, Tally>()
	private var recordedRigs = 0

	/**
	 * Records one rig's divergences against the known losses.
	 *
	 * @param String rig The corpus file the divergences came from.
	 * @param List divergences Everything the comparison found on it.
	 */
	fun record(rig: String, divergences: List<MocDivergence>) {
		recordedRigs++

		for (divergence in divergences) {
			val loss = KnownExportLoss.entries.firstOrNull { entry -> gate in entry.gates && entry.matches(divergence) }

			if (loss == null) {
				val elsewhere = KnownExportLoss.entries.firstOrNull { entry -> entry.matches(divergence) }
				val hint = if (elsewhere == null) "" else " [the known loss ${elsewhere.name}, which is not expected on this gate]"
				unexpected.add("$rig: ${divergence.describe()}$hint")

				continue
			}

			val tally = tallies.getOrPut(loss) { Tally() }
			tally.divergences++
			tally.objects.add("$rig\u0001${divergence.kind}\u0001${divergence.id}")
			tally.rigs.add(rig)
		}
	}

	/**
	 * What this gate's run got wrong: every stale entry, then every divergence no known loss excuses.
	 *
	 * A stale entry is one this gate is listed for that no rig showed.  It is only judged once a rig was
	 * recorded, since a run that compared nothing has already said so elsewhere.
	 *
	 * @return List One line per problem, empty when the run holds.
	 */
	fun problems(): List<String> {
		val stale =
			if (recordedRigs == 0) {
				emptyList()
			} else {
				KnownExportLoss.entries
					.filter { entry -> gate in entry.gates && entry !in tallies }
					.map { entry ->
						"known loss ${entry.name} is no longer seen on ${gate.label}: once ${entry.removedWhen}, " +
							"take this gate off its entry, and remove the entry when no gate is left"
					}
			}

		return stale + unexpected
	}

	/**
	 * One line per known loss this gate excused, with how often and where, for the test log.
	 *
	 * @return List The tally lines.
	 */
	fun tallyLines(): List<String> =
		tallies.map { (loss, tally) ->
			"[fidelity] ${gate.label}: known loss ${loss.name} excused ${tally.divergences} divergences on ${tally.objects.size} objects in ${tally.rigs.size} rigs"
		}
}