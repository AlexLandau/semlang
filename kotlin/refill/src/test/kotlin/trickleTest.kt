import net.semlang.modules.NodeName
import net.semlang.modules.NodeOutcome
import net.semlang.modules.TrickleDefinitionBuilder
import org.junit.Assert.*
import org.junit.Test
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException

class TrickleTests {
    val A = NodeName<Int>("a")
    val B = NodeName<Int>("b")
    val C = NodeName<Int>("c")
    val D = NodeName<Int>("d")
    val E = NodeName<Int>("e")

    @Test
    fun testTrickleBasic() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        // TODO: It would be nice if we could pass in the NodeName as an input
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createNode(C, aNode, { it + 3 })
        builder.createNode(D, bNode, cNode, { b, c -> b * c })

        val definition = builder.build()
        val instance = definition.instantiate()

        instance.setInput(A, 0)
        instance.completeSynchronously()
        assertEquals(3, instance.getNodeValue(D))
        assertEquals(1, instance.getNodeValue(B))
        assertEquals(3, instance.getNodeValue(C))
        assertEquals(0, instance.getNodeValue(A))

        instance.setInput(A, 1)
        instance.completeSynchronously()
        assertEquals(8, instance.getNodeValue(D))
    }

    @Test
    fun testApplyingResultsAfterMultipleInputs() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiate()

        instance.setInput(A, 10)
        val olderStep = instance.getNextSteps().single()
        instance.setInput(A, 20)
        val newerStep = instance.getNextSteps().single()

        // Compute and apply the steps in order
        val olderResult = olderStep.execute()
        assertEquals(11, olderResult.result)
        instance.reportResult(olderResult)
        // TODO: What should we return if we ask for B here?
        val newerResult = newerStep.execute()
        assertEquals(21, newerResult.result)
        instance.reportResult(newerResult)

        assertEquals(0, instance.getNextSteps().size)
        // The result based on the newer input is returned
        assertEquals(21, instance.getNodeValue(B))
    }

    @Test
    fun testApplyingResultsOutOfOrderDoesNotMatter() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiate()

        instance.setInput(A, 10)
        val olderStep = instance.getNextSteps().single()
        instance.setInput(A, 20)
        val newerStep = instance.getNextSteps().single()

        // Compute and apply the steps out of order
        val newerResult = newerStep.execute()
        assertEquals(21, newerResult.result)
        instance.reportResult(newerResult)

        if (instance.getNextSteps().size > 0) {
            println("**** " + instance.getNextSteps())
        }
        assertEquals(0, instance.getNextSteps().size)

        val olderResult = olderStep.execute()
        assertEquals(11, olderResult.result)
        instance.reportResult(olderResult)

        if (instance.getNextSteps().size > 0) {
            println("**** " + instance.getNextSteps())
        }
        assertEquals(0, instance.getNextSteps().size)
        // The B node has the result based on the input set later, even though the result for the older input was the
        // most recently reported
        assertEquals(21, instance.getNodeValue(B))
    }

    @Test
    fun testCaseTryingToTrickTimestamps() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createInputNode(C)
        val dNode = builder.createNode(D, cNode, { it + 2 })
        val eNode = builder.createNode(E, bNode, dNode, { left, right -> left * right } )

        val instance = builder.build().instantiate()

        // Prepare two updates to B followed by one to D
        instance.setInput(A, 0)
        instance.setInput(C, 0)
        val firstBChange = instance.getNextSteps().filter { it.nodeName == B }.single()
        instance.setInput(A, 10)
        val secondBChange = instance.getNextSteps().filter { it.nodeName == B }.single()
        instance.setInput(C, 99)
        val dChange = instance.getNextSteps().filter { it.nodeName == D }.single()

        // Apply the first B change; no E computation should be suggested yet, and a B update should still be requested
        instance.reportResult(firstBChange.execute())
        assertEquals(setOf(B, D), instance.getNextSteps().map { it.nodeName }.toSet())
        // After reporting on D, we should still be waiting on the newer B
        instance.reportResult(dChange.execute())
        assertEquals(setOf(B), instance.getNextSteps().map { it.nodeName }.toSet())
        // Reporting the more recent version of B finally unlocks E
        instance.reportResult(secondBChange.execute())
        assertEquals(setOf(E), instance.getNextSteps().map { it.nodeName }.toSet())
        // Adding a new input value to C will retract the request for an update to E
        instance.setInput(C, 100)
        assertEquals(setOf(D), instance.getNextSteps().map { it.nodeName }.toSet())

        instance.completeSynchronously()
        assertTrue(instance.getNextSteps().isEmpty())
        assertEquals(1122, instance.getNodeValue(E))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotShareNodesBetweenBuilders() {
        val builder1 = TrickleDefinitionBuilder()
        val builder2 = TrickleDefinitionBuilder()

        val aNode1 = builder1.createInputNode(A)
        val bNode2 = builder2.createNode(B, aNode1, { it + 1 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotShareResultsBetweenInstances() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val definition = builder.build()
        val instance1 = definition.instantiate()
        val instance2 = definition.instantiate()

        instance1.setInput(A, 3)
        val step = instance1.getNextSteps().single()
        val result = step.execute()

        instance2.reportResult(result)
    }

    @Test(expected = IllegalStateException::class)
    fun testCannotGetStepsWithInputsUndefined() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiate()

        instance.getNextSteps()
    }

    @Test(expected = IllegalStateException::class)
    fun testCannotGetStepsWithInputsUndefined2() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createInputNode(C)

        val instance = builder.build().instantiate()

        instance.setInput(A, 1)
        instance.getNextSteps()
    }

    @Test
    fun testUncaughtExceptionInCalculation() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })

        val instance = builder.build().instantiate()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val bOutcome = instance.getNodeOutcome(B)
        println(bOutcome)
        if (bOutcome !is NodeOutcome.Failure) {
            fail()
        } else {
            val errors = bOutcome.failure.errors
            assertEquals(setOf(B), errors.keys)
            assertTrue(errors[B]!!.message!!.contains("custom exception message"))
        }
    }

    @Test
    fun testUpstreamUncaughtException() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })
        val cNode = builder.createNode(C, bNode, { it * 3 })

        val instance = builder.build().instantiate()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val cOutcome = instance.getNodeOutcome(C)
        if (cOutcome !is NodeOutcome.Failure) {
            fail()
        } else {
            val errors = cOutcome.failure.errors
            assertEquals(setOf(B), errors.keys)
            assertTrue(errors[B]!!.message!!.contains("custom exception message"))
        }
    }
}