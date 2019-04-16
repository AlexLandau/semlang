import net.semlang.modules.NodeName
import net.semlang.modules.TrickleDefinitionBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.IllegalArgumentException

class TrickleTests {
    val A = NodeName<Int>("a")
    val B = NodeName<Int>("b")
    val C = NodeName<Int>("c")
    val D = NodeName<Int>("d")

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
}