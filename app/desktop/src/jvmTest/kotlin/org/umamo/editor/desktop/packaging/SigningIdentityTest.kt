package org.umamo.editor.desktop.packaging

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds the release signing identities together.
 */
class SigningIdentityTest {
	/** Gradle runs a module's tests from the module directory, which is what these paths are relative to. */
	private val publicKey = File("packaging/umamo-signing-key.asc")
	private val releaseWorkflow = File("../../.github/workflows/release.yml")
	private val gradleProperties = File("../../gradle.properties")
	private val buildScript = File("build.gradle.kts")

	@Test
	fun theCommittedKeyIsThePinnedOne() {
		val fingerprint = primaryFingerprint(publicKey.readText())
		val workflow = releaseWorkflow.readText()

		assertTrue("UMAMO_SIGNING_KEY_FINGERPRINT: $fingerprint" in workflow, "the workflow pins the committed key, $fingerprint")
		assertTrue("UMAMO_SIGNING_PUBLIC_KEY: app/desktop/packaging/umamo-signing-key.asc" in workflow, "and imports it from there")
	}

	@Test
	fun noBuildSwitchesMacSigningOnByItself() {
		assertFalse("compose.desktop.mac.sign" in gradleProperties.readText(), "a local build would need the certificate")
		assertFalse(Regex("""signing\s*\{""").containsMatchIn(buildScript.readText()), "the release workflow switches signing on, from its command line")
	}

	/**
	 * The plugin copies the file-association icons into the app bundle after signing it, so a signed build re-signs the
	 * bundle when the plugin is done: with the same entitlements file the plugin signed with, which holds the three
	 * hardened-runtime exceptions a JVM needs and nothing more.
	 */
	@Test
	fun aSignedBundleIsResealedWithThePluginsEntitlements() {
		val script = buildScript.readText()
		val entitlements = File("packaging/macos/entitlements.plist").readText()

		assertTrue("entitlementsFile.set(project.file(\"packaging/macos/entitlements.plist\"))" in script, "the plugin signs with the project's file")
		assertTrue("runtimeEntitlementsFile.set(project.file(\"packaging/macos/entitlements.plist\"))" in script, "the runtime too")
		assertTrue("\"--entitlements\", entitlements.absolutePath" in script, "and so does the bundle's re-signing")
		assertTrue("tasks.matching { task -> task.name == \"createDistributable\" }" in script, "after the plugin's app image task")
		val keys = Regex("<key>([^<]+)</key>").findAll(entitlements).map { match -> match.groupValues[1] }.toSet()
		assertEquals(
			setOf(
				"com.apple.security.cs.allow-jit",
				"com.apple.security.cs.allow-unsigned-executable-memory",
				"com.apple.security.cs.disable-library-validation",
			),
			keys,
		)
	}

	@Test
	fun aPacketLengthIsReadInEitherHeaderFormat() {
		assertEquals(2 to 51, packetBody(byteArrayOf(0x98.toByte(), 51)), "old format, one-octet length")
		assertEquals(3 to 256, packetBody(byteArrayOf(0x99.toByte(), 0x01, 0x00)), "old format, two-octet length")
		assertEquals(2 to 51, packetBody(byteArrayOf(0xC6.toByte(), 51)), "new format, one-octet length")
		assertEquals(3 to 1723, packetBody(byteArrayOf(0xC6.toByte(), 0xC5.toByte(), 0xFB.toByte())), "new format, two-octet length")
	}

	/**
	 * The v4 fingerprint of the first key in an ASCII-armored OpenPGP public key block: SHA-1 over the octet 0x99, the
	 * public-key packet's body length as two octets, and that body (RFC 9580 § 5.5.4).
	 *
	 * @param String armored The armored key block.
	 * @return String The fingerprint as 40 uppercase hexadecimal digits, as gpg prints it.
	 */
	private fun primaryFingerprint(armored: String): String {
		val lines = armored.lines().map(String::trim)
		val begin = lines.indexOf("-----BEGIN PGP PUBLIC KEY BLOCK-----")
		val end = lines.indexOf("-----END PGP PUBLIC KEY BLOCK-----")
		// The armor headers end at the first blank line; the checksum line after the data starts with "=".
		val dataStart = begin + 1 + lines.subList(begin + 1, end).indexOfFirst(String::isEmpty) + 1
		val packets = Base64.getDecoder().decode(lines.subList(dataStart, end).filterNot { line -> line.startsWith("=") }.joinToString(""))

		val (bodyOffset, bodyLength) = packetBody(packets)
		assertEquals(6, packetTag(packets[0].toInt() and 0xFF), "the block starts with a public-key packet")
		assertEquals(4, packets[bodyOffset].toInt(), "a version 4 key, the version rpm and every current gpg read")
		val digest = MessageDigest.getInstance("SHA-1")
		digest.update(byteArrayOf(0x99.toByte(), (bodyLength shr 8).toByte(), bodyLength.toByte()))
		digest.update(packets, bodyOffset, bodyLength)

		return digest.digest().joinToString("") { octet -> "%02X".format(octet) }
	}

	/**
	 * The tag of an OpenPGP packet, from its first header octet, in the new format or the old (RFC 9580 § 4.2).
	 *
	 * @param Int header The packet's first octet, 0-255.
	 * @return Int The packet tag.
	 */
	private fun packetTag(header: Int): Int = if (header and 0x40 != 0) header and 0x3F else (header shr 2) and 0x0F

	/**
	 * Where the first packet's body starts and how long it is, read from its header in the new format or the old
	 * (RFC 9580 § 4.2).  A key packet never uses a partial or indeterminate length.
	 *
	 * @param ByteArray packets The packet sequence.
	 * @return Pair The body's offset, then its length in octets.
	 */
	private fun packetBody(packets: ByteArray): Pair<Int, Int> {
		val header = packets[0].toInt() and 0xFF
		check(header and 0x80 != 0) { "not an OpenPGP packet header: $header" }
		val octet = { index: Int -> packets[index].toInt() and 0xFF }

		return if (header and 0x40 != 0) {
			val first = octet(1)

			when {
				first < 192 -> 2 to first
				first < 224 -> 3 to ((first - 192) shl 8) + octet(2) + 192
				first == 255 -> 6 to ((octet(2) shl 24) or (octet(3) shl 16) or (octet(4) shl 8) or octet(5))
				else -> error("a partial length cannot start a key packet")
			}
		} else {
			when (header and 0x03) {
				0 -> 2 to octet(1)
				1 -> 3 to ((octet(1) shl 8) or octet(2))
				2 -> 5 to ((octet(1) shl 24) or (octet(2) shl 16) or (octet(3) shl 8) or octet(4))
				else -> error("an indeterminate length cannot start a key packet")
			}
		}
	}
}