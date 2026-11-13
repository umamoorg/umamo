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
 * Keeps Markdown bold and italics out of comments and docblocks.
 *
 * A comment says what it has to in plain words: `**text**`, `*text*`, and `_text_` are reported, and
 * autocorrect drops the markers and keeps the text.
 *
 * Each comment line is read by itself, and a marker only counts where it reads as emphasis: the
 * opening one at the start of a word and the closing one at the end of a word.  That leaves
 * multiplication (`a * b`, `w*d`), glob patterns, snake_case and double-underscore names, and the `_`
 * placeholder alone.  A backtick code span and a fenced code block are never read.
 */
public class NoCommentEmphasisRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-comment-emphasis"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports, and on approval strips, each run of Markdown emphasis in a comment.
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

		val approvedMarkers = ArrayList<IntRange>()

		for (emphasis in emphasisIn(node.text)) {
			val marker = node.text.substring(emphasis.opening.first, emphasis.opening.last + 1)

			emit(node.startOffset + emphasis.opening.first, "A comment takes no Markdown emphasis; drop the `$marker` markers", true)
				.ifAutocorrectAllowed {
					approvedMarkers.add(emphasis.opening)
					approvedMarkers.add(emphasis.closing)
				}
		}

		removeMarkers(node, approvedMarkers)
	}

	/**
	 * Finds every run of emphasis in a comment, as offsets into the comment's text.
	 *
	 * @param String commentText The whole comment, delimiters included.
	 * @return List<Emphasis> The marker pairs, in reading order.
	 */
	private fun emphasisIn(commentText: String): List<Emphasis> {
		val found = ArrayList<Emphasis>()
		var lineStart = 0
		var insideFence = false

		for (line in commentText.split('\n')) {
			val unframed = withoutCommentFraming(line)

			if (unframed.trimStart().startsWith(CODE_FENCE)) {
				insideFence = !insideFence
			} else if (!insideFence) {
				val prose = withoutCodeSpans(unframed)

				for (pattern in EMPHASIS_PATTERNS) {
					for (match in pattern.findAll(prose)) {
						val markerLength = match.groupValues[1].length
						val openingStart = lineStart + match.range.first
						val closingStart = lineStart + match.range.last + 1 - markerLength
						found.add(Emphasis(openingStart until openingStart + markerLength, closingStart until closingStart + markerLength))
					}
				}
			}

			lineStart += line.length + 1
		}

		return found.sortedBy { emphasis -> emphasis.opening.first }
	}

	/**
	 * Blanks out the comment delimiters on a comment line, keeping every character's position.
	 *
	 * @param String line One line of a comment.
	 * @return String The line with its opener or continuation asterisk, and its closer, turned to spaces.
	 */
	private fun withoutCommentFraming(line: String): String {
		val unframed = StringBuilder(line)
		val leader = LINE_LEADER.find(line)

		if (leader != null) {
			blank(unframed, leader.range)
		}

		val closer = line.lastIndexOf(BLOCK_COMMENT_END)

		if (closer >= 0 && line.substring(closer + BLOCK_COMMENT_END.length).isBlank()) {
			blank(unframed, closer until closer + BLOCK_COMMENT_END.length)
		}

		return unframed.toString()
	}

	/**
	 * Blanks out the inline code spans on a comment line, keeping every character's position.
	 *
	 * @param String line One line of a comment, its framing already blanked.
	 * @return String The line with each backtick span turned to spaces.
	 */
	private fun withoutCodeSpans(line: String): String {
		val prose = StringBuilder(line)

		for (span in CODE_SPAN.findAll(line)) {
			blank(prose, span.range)
		}

		return prose.toString()
	}

	/**
	 * Overwrites a range of a line with spaces.
	 *
	 * @param StringBuilder line The line being masked.
	 * @param IntRange range The characters to blank.
	 */
	private fun blank(line: StringBuilder, range: IntRange) {
		for (index in range) {
			line.setCharAt(index, ' ')
		}
	}

	/**
	 * Deletes emphasis markers from the comment's tree.
	 *
	 * @param ASTNode comment The comment or docblock node.
	 * @param List<IntRange> markers Each marker's offsets into the comment's text.
	 */
	private fun removeMarkers(comment: ASTNode, markers: List<IntRange>) {
		// Every leaf is found before any is rewritten: rewriting a leaf swaps a new node into the tree,
		// and a line comment or block comment is itself the one leaf all of its markers sit in.
		val markersByLeaf = LinkedHashMap<ASTNode, MutableList<IntRange>>()

		for (marker in markers) {
			val leaf = comment.findLeafElementAt(marker.first) ?: continue
			val offsetInLeaf = marker.first - (leaf.startOffset - comment.startOffset)
			val markerLength = marker.last - marker.first + 1

			if (offsetInLeaf >= 0 && offsetInLeaf + markerLength <= leaf.textLength) {
				markersByLeaf.getOrPut(leaf) { ArrayList() }.add(offsetInLeaf until offsetInLeaf + markerLength)
			}
		}

		for ((leaf, markersInLeaf) in markersByLeaf) {
			var remaining = leaf.text

			// From the end backward, so removing one marker never moves a marker still to be removed.
			for (marker in markersInLeaf.sortedByDescending { range -> range.first }) {
				remaining = remaining.removeRange(marker.first, marker.last + 1)
			}

			if (remaining.isEmpty()) {
				leaf.treeParent?.removeChild(leaf)
			} else {
				(leaf as LeafPsiElement).rawReplaceWithText(remaining)
			}
		}
	}

	/**
	 * One run of emphasis: where its opening and closing markers sit in the comment's text.
	 *
	 * @property IntRange opening The opening marker.
	 * @property IntRange closing The closing marker.
	 */
	private data class Emphasis(val opening: IntRange, val closing: IntRange)

	private companion object {
		/** The nodes that hold comment text. */
		val COMMENT_TYPES: Set<IElementType> = setOf(ElementType.EOL_COMMENT, ElementType.BLOCK_COMMENT, ElementType.KDOC)

		/** The fence that opens and closes a Markdown code block. */
		const val CODE_FENCE = "```"

		/** The end of a block comment or docblock. */
		const val BLOCK_COMMENT_END = "*/"

		/** What a comment line opens with before its prose: a comment or docblock opener, or a continuation asterisk. */
		val LINE_LEADER = Regex("""^\s*(?://+|/\*+|\*(?!/))""")

		/** An inline code span. */
		val CODE_SPAN = Regex("""`[^`]*`""")

		/**
		 * Emphasis written with asterisks, then with an underscore.  Group 1 is the marker, which closes
		 * the run it opened.  The opening marker follows a space, bracket, or quote and leads straight
		 * into a word; the closing one ends a word and is followed by a space or punctuation.  That is
		 * stricter than Markdown, so a product written without spaces (`(a)*(b)*(c)`) is not emphasis.
		 */
		val EMPHASIS_PATTERNS: List<Regex> =
			listOf(
				Regex("""(?<=^|[\s(\["'])(\*{1,3})(?=[\w"'(\[])[^*]+?(?<=[\w.,:;!?)"'\]])\1(?=$|[\s.,:;!?)\]"'])"""),
				Regex("""(?<=^|[\s(\["'])(_)(?=[A-Za-z0-9"'(\[])[^_]+?(?<=[A-Za-z0-9.,:;!?)"'\]])\1(?=$|[\s.,:;!?)\]"'])"""),
			)
	}
}