package org.umamo.testkit

import org.junit.AssumptionViolatedException
import java.io.File

/**
 * Ends the calling test as skipped, with the reason in its report.
 *
 * A test that cannot reach what it verifies (its corpus sample is absent, its frame never arrived) must
 * not simply return: a returned test reports PASSED, and a run that verified nothing then reads exactly
 * like one that verified everything.  This works from anywhere under the test, a helper included.
 *
 * @param String reason Why the test cannot run, as the report will state it.
 * @return Nothing Never returns: the calling test ends here.
 */
fun skipTest(reason: String): Nothing = throw AssumptionViolatedException(reason)

/**
 * Resolves the file or directory a `-D` property names, skipping the calling test when there is none.
 *
 * @param String property The system property that carries the path.
 * @return File The file or directory, which exists.
 */
fun requireSample(property: String): File = System.getProperty(property)?.let(::File)?.takeIf { file -> file.exists() } ?: skipTest("absent -D$property")