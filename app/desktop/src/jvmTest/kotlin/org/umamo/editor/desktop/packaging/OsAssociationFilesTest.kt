package org.umamo.editor.desktop.packaging

import org.umamo.editor.desktop.isOpenableDocumentPath
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriterInfo
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds every operating-system registration of the `.uma` type to the codec.  The type is declared in places no
 * compiler connects - the tarball's freedesktop MIME entry and desktop entry, the Linux installers' desktop entry,
 * the desktop package's build script, and the Android manifest - and a registration that names another string, or a
 * magic rule that reads another offset, fails silently: the file manager just stops recognising the file.  The magic is not compared to a table here,
 * it is EVALUATED against the bytes the writer really produces (docs/format/UMA.md § 2).
 */
class OsAssociationFilesTest {
	/** Gradle runs a module's tests from the module directory, which is what these paths are relative to. */
	private val mimeEntry = File("resources/linux/umamo-uma.xml")
	private val desktopEntry = File("resources/linux/umamo.desktop")
	private val installedDesktopEntry = File("packaging/linux/umamo.desktop")
	private val buildScript = File("build.gradle.kts")
	private val androidManifest = File("../android/src/main/AndroidManifest.xml")

	/** One `match` rule of a shared-mime-info magic: a string expected at an offset, and the rules nested under it. */
	private class MagicRule(val offset: Int, val expected: ByteArray, val nested: List<MagicRule>)

	/**
	 * The bytes a shared-mime-info `string` value denotes: C-style escapes, of which the entry uses the octal form.
	 *
	 * @param String value The attribute's text.
	 * @return ByteArray The bytes to find.
	 */
	private fun magicBytesOf(value: String): ByteArray {
		val bytes = ByteArrayOutputStream()
		var position = 0
		while (position < value.length) {
			if (value[position] == '\\' && position + 3 < value.length && value.substring(position + 1, position + 4).all { digit -> digit in '0'..'7' }) {
				bytes.write(value.substring(position + 1, position + 4).toInt(8))
				position += 4
			} else {
				bytes.write(value[position].code)
				position++
			}
		}
		return bytes.toByteArray()
	}

	/**
	 * The `match` rules directly under [parent], each with its own nested rules.
	 *
	 * @param Element parent A `magic` or `match` element.
	 * @return List<MagicRule> The rules.
	 */
	private fun rulesUnder(parent: Element): List<MagicRule> {
		val rules = ArrayList<MagicRule>()
		val children = parent.childNodes
		for (childIndex in 0 until children.length) {
			val child = children.item(childIndex) as? Element ?: continue
			if (child.tagName == "match") {
				assertEquals("string", child.getAttribute("type"), "the matcher below only evaluates string rules")
				rules += MagicRule(child.getAttribute("offset").toInt(), magicBytesOf(child.getAttribute("value")), rulesUnder(child))
			}
		}
		return rules
	}

	/**
	 * Whether [content] satisfies any of [rules]: a rule holds when its bytes sit at its offset AND, when it has
	 * nested rules, one of those holds too - the shared MIME database's own semantics.
	 *
	 * @param ByteArray       content The file's bytes.
	 * @param List<MagicRule> rules   The alternatives.
	 * @return Boolean True when one matches.
	 */
	private fun matches(content: ByteArray, rules: List<MagicRule>): Boolean =
		rules.any { rule ->
			val end = rule.offset + rule.expected.size
			end <= content.size &&
				content.copyOfRange(rule.offset, end).contentEquals(rule.expected) &&
				(rule.nested.isEmpty() || matches(content, rule.nested))
		}

	/** The `mime-type` element of the committed entry. */
	private fun mimeTypeElement(): Element {
		val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mimeEntry)
		return document.getElementsByTagName("mime-type").item(0) as Element
	}

	/**
	 * A ZIP whose first entry is a stored `mimetype` holding [mimetype] - the shape KRA, EPUB, and OpenDocument
	 * share with UMA - or a plain ZIP when [mimetype] is null.
	 *
	 * @param String? mimetype The first entry's content, or null for none.
	 * @return ByteArray The archive.
	 */
	private fun zipAnnouncing(mimetype: String?): ByteArray {
		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { zip ->
			if (mimetype != null) {
				val content = mimetype.toByteArray(Charsets.US_ASCII)
				val entry = ZipEntry("mimetype")
				entry.method = ZipEntry.STORED
				entry.size = content.size.toLong()
				entry.compressedSize = content.size.toLong()
				entry.crc = CRC32().also { crc -> crc.update(content) }.value
				zip.putNextEntry(entry)
				zip.write(content)
				zip.closeEntry()
			}
			zip.putNextEntry(ZipEntry("content.txt"))
			zip.write("not a puppet".toByteArray())
			zip.closeEntry()
		}
		return bytes.toByteArray()
	}

	/** The magic recognises what the writer writes, and nothing that merely looks like it. */
	@Test
	fun theFreedesktopMagicMatchesARealUmaAndNothingElse() {
		val magic = mimeTypeElement().getElementsByTagName("magic").item(0) as Element
		val rules = rulesUnder(magic)
		val written = Uma.write(UmaModel.create(UmaWriterInfo("Umamo", "0.0.0-test")))

		assertTrue(Uma.matches(written), "the codec's own probe accepts the file")
		assertTrue(matches(written, rules), "and so does the freedesktop magic")
		assertFalse(matches(zipAnnouncing(null), rules), "a plain ZIP is not a UMA")
		assertFalse(matches(zipAnnouncing("application/x-krita"), rules), "nor is another format that announces itself the same way")
		// The shared database gives application/zip's bare-signature magic 60 and its ZIP-based formats (EPUB,
		// OpenDocument) 70.  Above 60 so an extensionless .uma is not read as a plain ZIP; below 80, the level at
		// which magic would outrank a file's NAME and a .uma renamed to .zip would stop opening as an archive.
		val priority = magic.getAttribute("priority").toInt()
		assertTrue(priority in 61..79, "priority $priority must sit above application/zip's 60 and below the name-outranking 80")
	}

	/** The entry names the codec's type, as a ZIP subtype, with the `.uma` glob. */
	@Test
	fun theFreedesktopEntryNamesTheCodecsType() {
		val mimeType = mimeTypeElement()

		assertEquals(Uma.MIME_TYPE, mimeType.getAttribute("type"))
		assertEquals("application/zip", (mimeType.getElementsByTagName("sub-class-of").item(0) as Element).getAttribute("type"))
		assertEquals("*.${Uma.kind.extension}", (mimeType.getElementsByTagName("glob").item(0) as Element).getAttribute("pattern"))
	}

	/** The desktop entry, the package's build script, and the Android manifest all name the same type and extension. */
	@Test
	fun everyRegistrationNamesTheSameType() {
		val desktopLines = desktopEntry.readLines()
		assertTrue("MimeType=${Uma.MIME_TYPE};" in desktopLines, "the desktop entry handles the type")
		assertTrue(desktopLines.any { line -> line.startsWith("Exec=") && line.endsWith("%f") }, "and takes the file as an argument, which Main reads")

		val script = buildScript.readText()
		assertTrue("val umaMimeType = \"${Uma.MIME_TYPE}\"" in script, "the package's file association names the type")
		assertTrue("val umaExtension = \"${Uma.kind.extension}\"" in script, "and the extension")
		assertEquals(2, Regex("fileAssociation\\(umaMimeType, umaExtension,").findAll(script).count(), "declared to the plugin for Windows and macOS")
		assertTrue("property(\"mime-type\", umaMimeType)" in script, "and to the Linux installers' jpackage")
		assertTrue("property(\"extension\", umaExtension)" in script, "with the extension")

		val manifest = androidManifest.readText()
		assertTrue("android:mimeType=\"${Uma.MIME_TYPE}\"" in manifest, "the Android intent filter names the type")
		assertTrue("android:pathSuffix=\".${Uma.kind.extension}\"" in manifest, "and the extension, for providers that report a generic type")
	}

	/**
	 * The desktop entry the Linux installers install hands the launcher the file it was asked to open - a path (%f),
	 * never a URI (%U), which Main would take for a path and fail to load - and reads exactly as the tarball's entry
	 * does in the menu, in English and in Japanese.
	 */
	@Test
	fun theInstalledDesktopEntryOpensTheFileAndMatchesTheTarballs() {
		val installedLines = installedDesktopEntry.readLines()
		assertTrue("MimeType=${Uma.MIME_TYPE};" in installedLines, "the installed entry handles the type")
		assertTrue("Exec=APPLICATION_LAUNCHER %f" in installedLines, "and starts jpackage's launcher with the file as a path")
		assertTrue(installedLines.none { line -> line.trimStart().startsWith("#") }, "no comments: jpackage fills its placeholders in anywhere")

		val shownKeys = Regex("^(Name|GenericName|Comment)(\\[[a-z_A-Z]+])?=")
		assertEquals(
			desktopEntry.readLines().filter { line -> shownKeys.containsMatchIn(line) },
			installedLines.filter { line -> shownKeys.containsMatchIn(line) },
			"the menu shows the same names and descriptions for both",
		)
	}

	/** A path the OS hands over is accepted by extension alone, however it is cased; the loader checks the content. */
	@Test
	fun aHandedOverPathIsAcceptedByItsExtension() {
		assertTrue(isOpenableDocumentPath("/home/rigger/Erica.uma"))
		assertTrue(isOpenableDocumentPath("C:\\Rigs\\ERICA.UMA"))
		assertTrue(isOpenableDocumentPath("/home/rigger/Erica.cmo3"))
		assertTrue(isOpenableDocumentPath("/home/rigger/Erica.moc3"))
		assertFalse(isOpenableDocumentPath("/home/rigger/Erica.psd"), "artwork is imported into a document, never opened as one")
		assertFalse(isOpenableDocumentPath("--some-flag"))
	}
}