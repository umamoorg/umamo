package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfig
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfigProperty
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.SafeEnumValueParser
import org.ec4j.core.model.PropertyType
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Holds a function's docblock tags to its signature.
 *
 * The house docblock lists one `@param Type name description` per parameter in signature order, then
 * `@return` when the function returns a value, then any other tags.  This rule only looks at a function
 * that has a docblock; whether a function must have one is not its business.
 *
 * Under the `strict` mode a docblock carries the whole block: every parameter has its `@param`, and a
 * declared return type other than `Unit` has its `@return`.  Under `drift_only` a docblock may carry no
 * tags at all, and the rule only reports tags that disagree with the signature.  In both modes a
 * docblock that has `@param` tags has one for every parameter, in order, a function that returns
 * nothing has no `@return`, and the tags keep their order.
 *
 * Nothing here can be autocorrected: a missing tag needs a description only a person can write.
 */
public class DocblockTagsRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:docblock-tags"),
		about = About(maintainer = "Umamo"),
		usesEditorConfigProperties = setOf(DOCBLOCK_TAGS_MODE_PROPERTY),
	),
	RuleAutocorrectApproveHandler {
	private var mode = DOCBLOCK_TAGS_MODE_PROPERTY.defaultValue

	/**
	 * Reads how much of the tag block the file's sources are held to.
	 *
	 * @param EditorConfig editorConfig The file's resolved .editorconfig properties.
	 */
	override fun beforeFirstNode(editorConfig: EditorConfig) {
		mode = editorConfig[DOCBLOCK_TAGS_MODE_PROPERTY]
	}

	/**
	 * Reports each way a documented function's tags disagree with its signature.
	 *
	 * @param ASTNode node The node being visited; only a function with a docblock is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.FUN) {
			return
		}

		val docblock = node.findChildByType(ElementType.KDOC) ?: return
		val tags = tagsOf(docblock)

		checkParameters(node, tags, emit)
		checkReturn(node, tags, emit)
		checkOrder(tags, emit)
	}

	/**
	 * Reports parameters without a tag, and `@param` tags without a parameter or out of order.
	 *
	 * @param ASTNode function The FUN node.
	 * @param List<Tag> tags The docblock's tags, in reading order.
	 * @param Function3 emit Reports a violation.
	 */
	private fun checkParameters(
		function: ASTNode,
		tags: List<Tag>,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		val parameters = function.findChildByType(ElementType.VALUE_PARAMETER_LIST)?.getChildren(null).orEmpty().filter { child -> child.elementType == ElementType.VALUE_PARAMETER }
		val parameterNames = parameters.map { parameter -> nameOf(parameter) }
		val typeParameterNames = typeParameterNamesOf(function)
		val parameterTags = ArrayList<ParameterTag>()

		for (tag in tags.filter { tag -> tag.name == PARAM_TAG }) {
			val subject = subjectOf(tag.body)

			// `@param T The element type.` documents a type parameter, which has no place in the signature order.
			if (subject.name !in parameterNames && subject.firstWord in typeParameterNames) {
				continue
			}

			parameterTags.add(ParameterTag(tag, subject))
		}

		if (parameterTags.isEmpty() && mode == DocblockTagsMode.drift_only) {
			return
		}

		val taggedNames = parameterTags.map { parameterTag -> parameterTag.subject.name }

		// A tag written without its type still documents its parameter; it is reported once, as that.
		val untypedNames = parameterTags.map { parameterTag -> parameterTag.subject.firstWord }.filter { firstWord -> firstWord in parameterNames }
		var consistent = true

		for ((parameterIndex, parameter) in parameters.withIndex()) {
			if (parameterNames[parameterIndex] !in taggedNames && parameterNames[parameterIndex] !in untypedNames) {
				consistent = false
				emit(parameter.startOffset, "The docblock has no `@param` for `${parameterNames[parameterIndex]}`", false)
			}
		}

		val seenNames = HashSet<String?>()

		for (parameterTag in parameterTags) {
			val problem = problemWith(parameterTag.subject, parameterNames, seenNames)

			if (problem != null) {
				consistent = false
				emit(parameterTag.tag.offset, problem, false)
			}

			seenNames.add(parameterTag.subject.name)
		}

		// Only worth saying once every tag names a real parameter exactly once.
		if (consistent && taggedNames != parameterNames) {
			val firstMisplaced = parameterTags.withIndex().first { (tagIndex, parameterTag) -> parameterTag.subject.name != parameterNames[tagIndex] }.value
			emit(firstMisplaced.tag.offset, "`@param` tags follow the signature order; `${firstMisplaced.subject.name}` is out of place", false)
		}
	}

	/**
	 * Says what is wrong with one `@param` tag's subject, if anything.
	 *
	 * @param Subject subject The type and name the tag opens with.
	 * @param List<String> parameterNames The function's parameter names.
	 * @param Set<String?> seenNames The names earlier tags of this docblock already documented.
	 * @return String? The violation message, or null when the tag names a parameter for the first time.
	 */
	private fun problemWith(subject: Subject, parameterNames: List<String>, seenNames: Set<String?>): String? {
		if (subject.name in parameterNames) {
			return if (subject.name in seenNames) "`@param` for `${subject.name}` appears more than once" else null
		}

		if (subject.firstWord in parameterNames) {
			return "`@param` is written `@param Type name description`; `${subject.firstWord}` is missing its type"
		}

		return "`@param` names `${subject.name ?: subject.firstWord}`, which is not a parameter of this function"
	}

	/**
	 * Reports a missing `@return` on a function that returns a value, and a `@return` on one that does not.
	 *
	 * @param ASTNode function The FUN node.
	 * @param List<Tag> tags The docblock's tags, in reading order.
	 * @param Function3 emit Reports a violation.
	 */
	private fun checkReturn(
		function: ASTNode,
		tags: List<Tag>,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		val returnType = returnTypeOf(function)
		val returnTag = tags.firstOrNull { tag -> tag.name == RETURN_TAG }

		if (returnType != null && returnType.text !in VALUELESS_TYPES) {
			if (returnTag == null && mode == DocblockTagsMode.strict) {
				emit(returnType.startOffset, "The docblock has no `@return` for the `${returnType.text}` this function returns", false)
			}

			return
		}

		// Without a declared type, only an expression body can return a value, and nothing here can tell what.
		val returnsNothing = returnType?.text == UNIT_TYPE || (returnType == null && function.findChildByType(ElementType.EQ) == null)

		if (returnTag != null && returnsNothing) {
			emit(returnTag.offset, "`@return` documents a value, and this function returns none", false)
		}
	}

	/**
	 * Reports the first tag that breaks the order: `@param` tags, then `@return`, then every other tag.
	 *
	 * @param List<Tag> tags The docblock's tags, in reading order.
	 * @param Function3 emit Reports a violation.
	 */
	private fun checkOrder(tags: List<Tag>, emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision) {
		var highestRank = 0

		for (tag in tags) {
			val rank = rankOf(tag)

			if (rank < highestRank) {
				emit(tag.offset, "Docblock tags go `@param`, then `@return`, then the rest; `@${tag.name}` is out of place", false)

				return
			}

			highestRank = rank
		}
	}

	/**
	 * Places a tag in the house order.
	 *
	 * @param Tag tag A docblock tag.
	 * @return Int Zero for `@param`, one for `@return`, two for anything else.
	 */
	private fun rankOf(tag: Tag): Int =
		when (tag.name) {
			PARAM_TAG -> 0
			RETURN_TAG -> 1
			else -> 2
		}

	/**
	 * Reads a parameter's declared name.
	 *
	 * @param ASTNode parameter A VALUE_PARAMETER node.
	 * @return String The name, without the backticks an escaped name is written in.
	 */
	private fun nameOf(parameter: ASTNode): String = parameter.findChildByType(ElementType.IDENTIFIER)?.text.orEmpty().trim('`')

	/**
	 * Lists a function's type parameter names.
	 *
	 * @param ASTNode function The FUN node.
	 * @return Set<String> The names, empty for a function that is not generic.
	 */
	private fun typeParameterNamesOf(function: ASTNode): Set<String> =
		function
			.findChildByType(ElementType.TYPE_PARAMETER_LIST)
			?.getChildren(null)
			.orEmpty()
			.filter { child -> child.elementType == ElementType.TYPE_PARAMETER }
			.mapNotNull { typeParameter -> typeParameter.findChildByType(ElementType.IDENTIFIER)?.text }
			.toSet()

	/**
	 * Finds a function's declared return type.
	 *
	 * @param ASTNode function The FUN node.
	 * @return ASTNode? The TYPE_REFERENCE after the parameter list, or null when none is declared.
	 */
	private fun returnTypeOf(function: ASTNode): ASTNode? {
		// A receiver type is a TYPE_REFERENCE too, but it stands before the parameter list.
		var child = function.findChildByType(ElementType.VALUE_PARAMETER_LIST)?.treeNext

		while (child != null) {
			if (child.elementType == ElementType.TYPE_REFERENCE) {
				return child
			}

			if (child.elementType == ElementType.BLOCK || child.elementType == ElementType.EQ) {
				return null
			}

			child = child.treeNext
		}

		return null
	}

	/**
	 * Reads the tags out of a docblock.
	 *
	 * @param ASTNode docblock The KDOC node.
	 * @return List<Tag> Each tag with the text on its line, in reading order.
	 */
	private fun tagsOf(docblock: ASTNode): List<Tag> {
		val tags = ArrayList<Tag>()
		var lineStart = 0
		var insideFence = false

		for (line in docblock.text.split('\n')) {
			val content = LINE_CONTENT.find(line)

			if (content != null) {
				val prose = content.groupValues[1]

				// A code sample may open a line with an annotation, which is not a tag.
				if (prose.startsWith(CODE_FENCE)) {
					insideFence = !insideFence
				} else if (!insideFence) {
					val tag = TAG.find(prose)

					if (tag != null) {
						val offset = docblock.startOffset + lineStart + content.groups[1]!!.range.first
						tags.add(Tag(tag.groupValues[1], tag.groupValues[2].removeSuffix(DOCBLOCK_END).trim(), offset))
					}
				}
			}

			lineStart += line.length + 1
		}

		return tags
	}

	/**
	 * Reads the type and the name a `@param` tag opens with.
	 *
	 * A type may hold spaces inside its brackets, and a function type written out has them around its
	 * arrow, so the type runs to the first space outside any bracket that is not part of an arrow.
	 *
	 * @param String body The tag's text after `@param`.
	 * @return Subject The first word, and the word after the type.
	 */
	private fun subjectOf(body: String): Subject {
		val words = ArrayList<String>()
		var position = 0

		while (position < body.length) {
			while (position < body.length && body[position].isWhitespace()) {
				position++
			}

			val wordStart = position
			var depth = 0

			while (position < body.length && (depth > 0 || !body[position].isWhitespace())) {
				when (body[position]) {
					'<', '(', '[' -> {
						depth++
					}

					')', ']' -> {
						depth--
					}

					// The `>` of an arrow closes nothing.
					'>' -> {
						if (position == 0 || body[position - 1] != '-') {
							depth--
						}
					}
				}

				position++
			}

			if (position > wordStart) {
				words.add(body.substring(wordStart, position))
			}
		}

		// Walk past a written-out function type: its modifiers, then `(…) -> Result` however often it nests.
		var typeEnd = 0

		while (typeEnd < words.size && (words[typeEnd] in TYPE_MODIFIERS || words[typeEnd].startsWith('@'))) {
			typeEnd++
		}

		typeEnd++

		while (typeEnd + 1 < words.size && words[typeEnd] == ARROW) {
			typeEnd += 2
		}

		return Subject(words.firstOrNull(), words.getOrNull(typeEnd))
	}

	/**
	 * One docblock tag.
	 *
	 * @property String name The tag's name, without the `@`.
	 * @property String body The text after the name on the tag's line.
	 * @property Int offset Where the tag starts in the file.
	 */
	private class Tag(val name: String, val body: String, val offset: Int)

	/**
	 * What a `@param` tag opens with.
	 *
	 * @property String? firstWord The first word, which is the type when the tag is written the house way.
	 * @property String? name The word after the type, which is the parameter's name.
	 */
	private class Subject(val firstWord: String?, val name: String?)

	/**
	 * A `@param` tag that documents a value parameter.
	 *
	 * @property Tag tag The tag.
	 * @property Subject subject The type and name it opens with.
	 */
	private class ParameterTag(val tag: Tag, val subject: Subject)

	private companion object {
		const val PARAM_TAG = "param"
		const val RETURN_TAG = "return"
		const val UNIT_TYPE = "Unit"
		const val ARROW = "->"

		/** The fence that opens and closes a code block in a docblock. */
		const val CODE_FENCE = "```"

		/** The closer a one-line docblock ends its only line with. */
		const val DOCBLOCK_END = "*/"

		/** Declared return types that leave nothing for `@return` to document. */
		val VALUELESS_TYPES: Set<String> = setOf(UNIT_TYPE, "Nothing")

		/** Words that may stand in front of a function type written out in a tag. */
		val TYPE_MODIFIERS: Set<String> = setOf("suspend")

		/** A docblock line past its opener or continuation asterisk; group 1 is what the line says. */
		val LINE_CONTENT = Regex("""^\s*(?:/\*\*|\*(?!/))?\s*(.*)$""")

		/** A tag at the start of a line: group 1 its name, group 2 the rest of the line. */
		val TAG = Regex("""^@([a-z][A-Za-z]*)(?:\s+(.*))?$""")
	}
}

/**
 * How much of the tag block a documented function carries.  The values are lowercase because an
 * .editorconfig value is read that way.
 */
@Suppress("EnumEntryName", "ktlint:standard:enum-entry-name-case")
public enum class DocblockTagsMode {
	/** Every parameter has its `@param`, and a returned value has its `@return`. */
	strict,

	/** A docblock may carry no tags; the ones it has agree with the signature. */
	drift_only,
}

/**
 * The .editorconfig property that picks the mode for a set of files, `umamo_docblock_tags_mode`.  It is
 * `strict` unless a section says otherwise.
 */
public val DOCBLOCK_TAGS_MODE_PROPERTY: EditorConfigProperty<DocblockTagsMode> =
	EditorConfigProperty(
		type =
			PropertyType.LowerCasingPropertyType(
				"umamo_docblock_tags_mode",
				"How much of the house tag block a documented function carries: 'strict' for all of it, 'drift_only' to only hold the tags present to the signature.",
				SafeEnumValueParser(DocblockTagsMode::class.java),
				DocblockTagsMode.entries.map { entry -> entry.name }.toSet(),
			),
		defaultValue = DocblockTagsMode.strict,
	)