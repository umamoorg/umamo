package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

/**
 * Puts two spaces after the period that ends a sentence in a comment or docblock.
 *
 * A sentence break is a period that closes a word, a bracket, or a code span, then exactly one space,
 * then what opens the next sentence: a capital letter, a docblock link, a code span, or a parenthesis
 * that opens onto a capital.  Autocorrect adds the second space.
 *
 * Where a period is not the end of a sentence the rule leaves it alone, and it is built to miss a real
 * break sooner than to widen a gap that is not one:
 *
 *  - an abbreviation (`e.g.`, `i.e.`, `etc.`, `vs.`) or any run of single letters and periods,
 *  - a single letter with a period, which is an initial far more often than the end of a sentence,
 *  - a number that opens its line, which is a list marker,
 *  - anything inside a backtick code span or a fenced code block.
 *
 * Only the period counts.  A question mark in a comment is nearly always a nullable type
 * (`@return Grid? The grid`), so it is never read as the end of a sentence.
 */
public class CommentSentenceSpacingRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:comment-sentence-spacing"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports, and on approval widens, each single space after a sentence-ending period in a comment.
	 *
	 * @param ASTNode node The node being visited; only a comment or a docblock is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType !in COMMENT_TYPES) {
			return
		}

		val approvedGaps = ArrayList<Int>()

		for (gap in sentenceGapsIn(node.text)) {
			emit(node.startOffset + gap, "A sentence in a comment ends with two spaces after its period", true)
				.ifAutocorrectAllowed {
					approvedGaps.add(gap)
				}
		}

		widenGaps(node, approvedGaps)
	}

	/**
	 * Finds every single space that follows a sentence-ending period, as offsets into the comment's text.
	 *
	 * @param String commentText The whole comment, delimiters included.
	 * @return List<Int> The offset of each such space, in reading order.
	 */
	private fun sentenceGapsIn(commentText: String): List<Int> {
		val gaps = ArrayList<Int>()
		var lineStart = 0
		var insideFence = false

		for (line in commentText.split('\n')) {
			val contentStart = LINE_LEADER.find(line)?.let { leader -> leader.range.last + 1 } ?: 0
			val content = line.substring(contentStart)

			if (content.trimStart().startsWith(CODE_FENCE)) {
				insideFence = !insideFence
			} else if (!insideFence) {
				val codeSpans = CODE_SPAN.findAll(content).map { span -> span.range }.toList()

				for (sentenceBreak in SENTENCE_BREAK.findAll(content)) {
					val period = sentenceBreak.range.first

					if (codeSpans.none { span -> period in span } && endsSentence(content, period)) {
						gaps.add(lineStart + contentStart + period + 1)
					}
				}
			}

			lineStart += line.length + 1
		}

		return gaps
	}

	/**
	 * Tells whether a period really ends a sentence, going by the word it closes.
	 *
	 * @param String content A comment line, past its opener or continuation asterisk.
	 * @param Int period The period's index in that line.
	 * @return Boolean False for an abbreviation, an initial, and a list marker.
	 */
	private fun endsSentence(content: String, period: Int): Boolean {
		val wordStart = content.lastIndexOfAny(charArrayOf(' ', '\t'), period) + 1
		val word = content.substring(wordStart, period + 1).trimStart('(', '[', '{', '"', '\'')

		if (word.lowercase() in ABBREVIATIONS || DOTTED_LETTERS.matches(word) || SINGLE_LETTER.matches(word)) {
			return false
		}

		// `1. First` at the head of a line numbers a list item.
		return !(LIST_NUMBER.matches(word) && content.substring(0, wordStart).isBlank())
	}

	/**
	 * Adds a second space at each approved gap.
	 *
	 * @param ASTNode comment The comment or docblock node.
	 * @param List<Int> gaps The offset of each single space, into the comment's text.
	 */
	private fun widenGaps(comment: ASTNode, gaps: List<Int>) {
		// Every leaf is found before any is rewritten: rewriting a leaf swaps a new node into the tree,
		// and a line comment or block comment is itself the one leaf all of its gaps sit in.
		val gapsByLeaf = LinkedHashMap<ASTNode, MutableList<Int>>()

		for (gap in gaps) {
			val leaf = comment.findLeafElementAt(gap) ?: continue
			val offsetInLeaf = gap - (leaf.startOffset - comment.startOffset)

			if (offsetInLeaf >= 0 && offsetInLeaf < leaf.textLength) {
				gapsByLeaf.getOrPut(leaf) { ArrayList() }.add(offsetInLeaf)
			}
		}

		for ((leaf, gapsInLeaf) in gapsByLeaf) {
			val widened = StringBuilder(leaf.text)

			// From the end backward, so widening one gap never moves a gap still to be widened.
			for (gap in gapsInLeaf.sortedDescending()) {
				widened.insert(gap, ' ')
			}

			(leaf as LeafPsiElement).rawReplaceWithText(widened.toString())
		}
	}

	private companion object {
		/** The nodes that hold comment text. */
		val COMMENT_TYPES: Set<IElementType> = setOf(ElementType.EOL_COMMENT, ElementType.BLOCK_COMMENT, ElementType.KDOC)

		/** The fence that opens and closes a Markdown code block. */
		const val CODE_FENCE = "```"

		/** What a comment line opens with before its prose: a comment or docblock opener, or a continuation asterisk. */
		val LINE_LEADER = Regex("""^\s*(?://+|/\*+|\*(?!/))""")

		/** An inline code span. */
		val CODE_SPAN = Regex("""`[^`]*`""")

		/**
		 * A period that closes a word, a bracket, a quote, or a code span, then exactly one space, then the
		 * opening of a sentence: a capital, a docblock link, a code span, or a parenthesis onto a capital.
		 */
		val SENTENCE_BREAK = Regex("""(?<=[\p{L}\p{N})\]`"'])\. (?=\p{Lu}|\[|`|\(\p{Lu})""")

		/** Letters separated by periods, such as `e.g.` or `U.S.`. */
		val DOTTED_LETTERS = Regex("""(?:\p{L}\.){2,}""")

		/** One letter and a period: an initial, or the letter of a lettered list. */
		val SINGLE_LETTER = Regex("""\p{L}\.""")

		/** A number and a period. */
		val LIST_NUMBER = Regex("""\p{N}+\.""")

		/** Abbreviations that end in a period without ending a sentence, in lowercase. */
		val ABBREVIATIONS: Set<String> =
			setOf(
				"etc.",
				"vs.",
				"cf.",
				"approx.",
				"incl.",
				"excl.",
				"resp.",
				"no.",
				"fig.",
				"eq.",
				"sec.",
				"ch.",
				"vol.",
				"ed.",
				"al.",
				"dr.",
				"mr.",
				"mrs.",
				"ms.",
				"st.",
				"jr.",
				"sr.",
				"inc.",
				"ltd.",
				"co.",
				"corp.",
			)
	}
}