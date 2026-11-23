package org.umamo.interop.fidelity

import org.umamo.interop.EntityDiff
import org.umamo.interop.GlueDiff
import org.umamo.interop.PuppetDiff
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.PuppetModel
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Objects

/*
 * The coverage gate for the model diff.
 *
 * The CMO3 export writes only what diffPuppetModels reports, so a model property the diff does not compare can be
 * neither written nor reported: a trip through CMO3 loses it without a notice.  Every property of the model's
 * classes is therefore placed in one of three tables, and each placement is checked against the diff itself with a
 * one-property edit of a fixture model.
 *
 * A notice cannot stand in for a diff field.  The CMO3 lowerings raise every notice from a diff entry, so a property
 * the diff does not see has no notice either, and what a NotCarried entry names instead is the known export loss
 * that tracks it on the fidelity gates, or why its loss is acceptable.
 */

/** The diff's entity lists, each the token for an entity created or deleted under its id. */
internal enum class DiffCategory { PARAMETERS, PARAMETER_GROUPS, PARTS, DEFORMERS, DRAWABLES, GLUES, ATLAS_TILES }

/** Where a model property stands with respect to the CMO3 export's diff. */
internal sealed interface DiffPlacement {
	/**
	 * The diff reports an edit to the property.
	 *
	 * @property Set reportedAs The tokens the edit is reported as, at least: diff field constants, or the DiffCategory of an entity the property keys or lists.
	 */
	data class Compared(val reportedAs: Set<Enum<*>>) : DiffPlacement

	/**
	 * Nothing is lost when the diff skips the property: it is rebuilt from compared state, or it is a cache.
	 *
	 * @property String reason Why nothing is lost.
	 */
	data class Derived(val reason: String) : DiffPlacement

	/**
	 * A CMO3 export drops the property.
	 *
	 * @property String reason What CMO3 lacks, or why the loss is acceptable.
	 * @property KnownExportLoss? loss The entry that excuses the loss on the fidelity gates, or null for a loss the gates do not see.
	 */
	data class NotCarried(val reason: String, val loss: KnownExportLoss?) : DiffPlacement
}

/**
 * One property's placement, with the edit that proves it.
 *
 * @property String property The property's backing field name.
 * @property DiffPlacement placement Where it stands.
 * @property Function? mutate An edit of the fixture's instance that changes this property alone, or null for a cache, which no edit reaches.
 */
internal class PropertyCoverage<T : Any>(val property: String, val placement: DiffPlacement, val mutate: ((T) -> T)?)

/**
 * One class's coverage table.
 *
 * @property Class type The class whose properties the table places.
 * @property FixtureRoute route Where the fixture model holds its one instance of the class.
 * @property List entries One entry per property.
 */
internal class ClassCoverage<T : Any>(val type: Class<*>, val route: FixtureRoute<T>, val entries: List<PropertyCoverage<T>>) {
	/** The class's name as the model spells it, nesting included: Deformer.Warp. */
	val label: String = type.name.substringAfterLast('.').replace('$', '.')
}

/** Builds one class's entries; see [coverageOf]. */
internal class CoverageTableBuilder<T : Any> {
	val entries = ArrayList<PropertyCoverage<T>>()

	/**
	 * Places a property the diff compares.
	 *
	 * @param String property The property's name.
	 * @param Array reportedAs The tokens an edit to it is reported as.
	 * @param Function mutate The edit that changes it alone.
	 */
	fun compared(property: String, vararg reportedAs: Enum<*>, mutate: T.() -> T) {
		entries.add(PropertyCoverage(property, DiffPlacement.Compared(reportedAs.toSet()), mutate))
	}

	/**
	 * Places a property that is rebuilt from compared state, or a cache, which goes without an edit.
	 *
	 * @param String property The property's name.
	 * @param String reason Why nothing is lost.
	 * @param Function? mutate The edit that changes it alone, or null for a cache.
	 */
	fun derived(property: String, reason: String, mutate: (T.() -> T)? = null) {
		entries.add(PropertyCoverage(property, DiffPlacement.Derived(reason), mutate))
	}

	/**
	 * Places a property a CMO3 export drops.
	 *
	 * @param String property The property's name.
	 * @param String reason What CMO3 lacks, or why the loss is acceptable.
	 * @param KnownExportLoss? loss The entry that excuses the loss on the fidelity gates, if they see it.
	 * @param Function mutate The edit that changes it alone.
	 */
	fun notCarried(property: String, reason: String, loss: KnownExportLoss? = null, mutate: T.() -> T) {
		entries.add(PropertyCoverage(property, DiffPlacement.NotCarried(reason, loss), mutate))
	}
}

/**
 * One class's coverage table.
 *
 * @param Class type The class whose properties it places.
 * @param FixtureRoute route Where the fixture holds its instance.
 * @param Function build Adds the entries.
 * @return ClassCoverage The table.
 */
internal fun <T : Any> coverageOf(type: Class<*>, route: FixtureRoute<T>, build: CoverageTableBuilder<T>.() -> Unit): ClassCoverage<T> =
	ClassCoverage(type, route, CoverageTableBuilder<T>().apply(build).entries)

private const val PLACEMENT_HELP =
	"place it Compared (the diff field that reports an edit to it), Derived (why nothing is lost without one), " +
		"or NotCarried (the known export loss that tracks it, or why its loss is acceptable), with an edit that changes it alone"

/**
 * The fields that hold a class's state: every instance field it declares, a delegate's included.
 *
 * @param Class type The class.
 * @return List The fields, made readable.
 */
private fun stateFields(type: Class<*>): List<Field> =
	type.declaredFields.filter { field -> !Modifier.isStatic(field.modifiers) && !field.isSynthetic }.onEach { field -> field.trySetAccessible() }

/**
 * What is wrong with the table's membership: a property it does not place, an entry naming no property, a property
 * placed twice, or an entry without an edit that is not a cache.
 *
 * @return List One line per problem, empty when every property is placed once.
 */
internal fun ClassCoverage<*>.placementProblems(): List<String> {
	val fieldNames = stateFields(type).map { field -> field.name }
	val entryNames = entries.map { entry -> entry.property }
	val problems = ArrayList<String>()

	for (fieldName in fieldNames) {
		if (fieldName !in entryNames) {
			problems.add("$label.$fieldName is in no coverage table: $PLACEMENT_HELP")
		}
	}

	for (entryName in entryNames.distinct()) {
		if (entryName !in fieldNames) {
			problems.add("$label.$entryName has a coverage entry, but $label has no such property")
		}

		if (entryNames.count { name -> name == entryName } > 1) {
			problems.add("$label.$entryName is placed more than once")
		}
	}

	for (entry in entries) {
		if (entry.mutate == null && entry.placement !is DiffPlacement.Derived) {
			problems.add("$label.${entry.property} has no edit, which only a Derived cache may go without")
		}
	}

	return problems
}

/**
 * What is wrong with the table's edits: one that changes another property too, or none, or that the fixture route
 * does not carry into the model.  A cache is not compared, since rebuilding an instance always gives it a new one.
 *
 * @param PuppetModel fixture The fixture model.
 * @return List One line per problem, empty when every edit changes its own property alone.
 */
internal fun <T : Any> ClassCoverage<T>.mutationProblems(fixture: PuppetModel): List<String> {
	val original = route.read(fixture)

	if (original.javaClass != type) {
		return listOf("the fixture's $label is a ${original.javaClass.name}")
	}

	val caches = entries.filter { entry -> entry.mutate == null }.map { entry -> entry.property }.toSet()
	val fields = stateFields(type).filter { field -> field.name !in caches }
	val problems = ArrayList<String>()

	for (entry in entries) {
		val mutate = entry.mutate ?: continue
		val edited = mutate(original)
		val changed = fields.filter { field -> !Objects.deepEquals(field.get(original), field.get(edited)) }.map { field -> field.name }

		if (changed != listOf(entry.property)) {
			val what = if (changed.isEmpty()) "nothing" else changed.joinToString()
			problems.add("$label.${entry.property}: its edit changes $what, where it must change ${entry.property} alone")
		}

		if (route.read(route.write(fixture, edited)) !== edited) {
			problems.add("$label.${entry.property}: the fixture route does not carry the edit into the model")
		}
	}

	return problems
}

/**
 * Where the diff disagrees with the table: a Compared property whose edit the diff does not report as listed, or a
 * Derived or NotCarried one whose edit it now reports, which must move to Compared.
 *
 * @param PuppetModel fixture The fixture model.
 * @return List One line per problem, empty when the diff agrees with every placement.
 */
internal fun <T : Any> ClassCoverage<T>.diffProblems(fixture: PuppetModel): List<String> {
	val problems = ArrayList<String>()

	for (entry in entries) {
		val mutate = entry.mutate ?: continue
		val edited = route.write(fixture, mutate(route.read(fixture)))
		val reported = diffPuppetModels(fixture, edited).reportedTokens()

		when (val placement = entry.placement) {
			is DiffPlacement.Compared -> {
				if (!reported.containsAll(placement.reportedAs)) {
					val actual = if (reported.isEmpty()) "nothing" else tokenNames(reported)
					problems.add("$label.${entry.property} is placed Compared as ${tokenNames(placement.reportedAs)}, but the diff reports its edit as $actual")
				}
			}

			is DiffPlacement.Derived, is DiffPlacement.NotCarried -> {
				if (reported.isNotEmpty()) {
					val kind = if (placement is DiffPlacement.Derived) "Derived" else "NotCarried"
					problems.add("$label.${entry.property} is placed $kind, but the diff now reports its edit as ${tokenNames(reported)}: place it Compared")
				}
			}
		}
	}

	return problems
}

/**
 * Names diff tokens the way the source spells them: PartField.VISIBLE.
 *
 * @param Set tokens The tokens.
 * @return String Their names, comma-separated.
 */
private fun tokenNames(tokens: Set<Enum<*>>): String = tokens.joinToString { token -> "${token.declaringJavaClass.simpleName}.${token.name}" }

/**
 * Everything the diff reports, as tokens: each changed field, each document field, and the category of every entity
 * created or deleted.
 *
 * @return Set The tokens.
 */
internal fun PuppetDiff.reportedTokens(): Set<Enum<*>> {
	val tokens = LinkedHashSet<Enum<*>>()
	tokens.addEntityDiffs(parameters, DiffCategory.PARAMETERS)
	tokens.addEntityDiffs(parameterGroups, DiffCategory.PARAMETER_GROUPS)
	tokens.addEntityDiffs(parts, DiffCategory.PARTS)
	tokens.addEntityDiffs(deformers, DiffCategory.DEFORMERS)
	tokens.addEntityDiffs(drawables, DiffCategory.DRAWABLES)
	tokens.addEntityDiffs(atlasTiles, DiffCategory.ATLAS_TILES)

	for (glue in glues) {
		when (glue) {
			is GlueDiff.Changed -> tokens.addAll(glue.fields)
			is GlueDiff.Created, is GlueDiff.Deleted -> tokens.add(DiffCategory.GLUES)
		}
	}

	tokens.addAll(document)

	return tokens
}

/**
 * Adds one category's diffs as tokens.
 *
 * @param List diffs The category's diffs.
 * @param DiffCategory category The token for a created or deleted entity.
 */
private fun MutableSet<Enum<*>>.addEntityDiffs(diffs: List<EntityDiff<*, Enum<*>>>, category: DiffCategory) {
	for (diff in diffs) {
		when (diff) {
			is EntityDiff.Changed<*, *> -> diff.fields.forEach { field -> add(field as Enum<*>) }
			is EntityDiff.Created, is EntityDiff.Deleted -> add(category)
		}
	}
}