package org.umamo.format.cmo3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the production model-graph walkers ([Cmo3Model.imageResources] and the reachability behind
 * [Cmo3GraphEditor.pruneUnreachableShared]) to the frozen reflective reference implementations in
 * [ReflectiveWalkReference], over every corpus model.  However the production walkers enumerate a
 * class's state, they must visit exactly the objects the declared-field walk visits — this is the
 * gate that keeps a walker change from silently over-pruning the shared pool or dropping an image
 * resource.  The samples are git-ignored, so a public build skips this gracefully.
 */
class WalkerParityTest {
	@Test
	fun productionWalkersMatchTheReflectiveReferenceOverTheCorpus() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping walker parity")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		if (files.isEmpty()) {
			println("cmo3.probe lists no readable samples; skipping")
			return
		}

		for (file in files) {
			val model = Cmo3.read(file.readBytes())

			// Image resources: the same objects in the same visit order, by identity.
			val expectedImages = ReflectiveWalkReference.imageResources(model.graph)
			val actualImages = model.imageResources()
			assertEquals(expectedImages.size, actualImages.size, "${file.name}: imageResources count")
			for (imageIndex in expectedImages.indices) {
				assertSame(
					expectedImages[imageIndex],
					actualImages[imageIndex],
					"${file.name}: imageResources[$imageIndex] identity",
				)
			}

			// Reachability: identical identity sets, so pruning keeps exactly the same entries.
			val expectedReachable = ReflectiveWalkReference.reachableObjects(model.graph)
			val actualReachable = model.edit().reachableObjects()
			assertEquals(expectedReachable.size, actualReachable.size, "${file.name}: reachable-set size")
			for (expectedObject in expectedReachable) {
				assertTrue(
					expectedObject in actualReachable,
					"${file.name}: object reachable to the reference walk but not the production walk: " +
						expectedObject::class.qualifiedName.orEmpty(),
				)
			}

			// The consequence that matters: the shared-pool survivor list is identical.
			val expectedSurvivors = model.graph.sharedOrder.filter { instance -> instance in expectedReachable }
			val actualSurvivors = model.graph.sharedOrder.filter { instance -> instance in actualReachable }
			assertEquals(expectedSurvivors.size, actualSurvivors.size, "${file.name}: shared survivor count")
			for (survivorIndex in expectedSurvivors.indices) {
				assertSame(
					expectedSurvivors[survivorIndex],
					actualSurvivors[survivorIndex],
					"${file.name}: shared survivor [$survivorIndex] identity",
				)
			}
		}
	}
}