package net.semlang.refill

import org.junit.Assert.*
import org.junit.Test
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicBoolean

// TODO: Test that getting keyedInput() fails at some point if the key lists involved aren't the same (or when used
// as the input to a basic or key list node)

// TODO: Test multi-input setting and consistency
// (regression test: short-circuiting or in the wrong spot)

class TrickleTests {
    private val A = NodeName<Int>("a")
    private val B = NodeName<Int>("b")
    private val C = NodeName<Int>("c")
    private val D = NodeName<Int>("d")
    private val E = NodeName<Int>("e")

    private val A_KEYS = KeyListNodeName<Int>("aKeys")
    private val B_KEYS = KeyListNodeName<Int>("bKeys")
    private val C_KEYS = KeyListNodeName<Int>("cKeys")
    private val D_KEYS = KeyListNodeName<Int>("dKeys")
    private val E_KEYS = KeyListNodeName<Int>("eKeys")

    private val B_KEYED = KeyedNodeName<Int, Int>("bKeyed")
    private val C_KEYED = KeyedNodeName<Int, Int>("cKeyed")
    private val D_KEYED = KeyedNodeName<Int, Int>("dKeyed")
    private val E_KEYED = KeyedNodeName<Int, Int>("eKeyed")
    private val F_KEYED = KeyedNodeName<Int, Int>("fKeyed")

    @Test
    fun testTrickleBasic() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        // TODO: It would be nice if we could pass in the NodeName as an input
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createNode(C, aNode, { it + 3 })
        builder.createNode(D, bNode, cNode, { b, c -> b * c })

        val definition = builder.build()
        val instance = definition.instantiateRaw()

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

        val instance = builder.build().instantiateRaw()

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

        val instance = builder.build().instantiateRaw()

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

        val instance = builder.build().instantiateRaw()

        // Prepare two updates to B followed by one to D
        instance.setInput(A, 0)
        instance.setInput(C, 0)
        val firstBChange = instance.getNextSteps().filter { (it.valueId as ValueId.Nonkeyed).nodeName == B }.single()
        instance.setInput(A, 10)
        val secondBChange = instance.getNextSteps().filter { (it.valueId as ValueId.Nonkeyed).nodeName == B }.single()
        instance.setInput(C, 99)
        val dChange = instance.getNextSteps().filter { (it.valueId as ValueId.Nonkeyed).nodeName == D }.single()

        // Apply the first B change; no E computation should be suggested yet, and a B update should still be requested
        instance.reportResult(firstBChange.execute())
        assertEquals(setOf(B, D), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())
        // After reporting on D, we should still be waiting on the newer B
        instance.reportResult(dChange.execute())
        assertEquals(setOf(B), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())
        // Reporting the more recent version of B finally unlocks E
        instance.reportResult(secondBChange.execute())
        assertEquals(setOf(E), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())
        // Adding a new input value to C will retract the request for an update to E
        instance.setInput(C, 100)
        assertEquals(setOf(D), instance.getNextSteps().map { (it.valueId as ValueId.Nonkeyed).nodeName }.toSet())

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
        val instance1 = definition.instantiateRaw()
        val instance2 = definition.instantiateRaw()

        instance1.setInput(A, 3)
        val step = instance1.getNextSteps().single()
        val result = step.execute()

        instance2.reportResult(result)
    }

    fun testCanGetStepsWithInputsUndefined() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(B))
    }

    private fun inputsMissingOutcome(vararg valueIds: ValueId): NodeOutcome.Failure<Int> {
        return NodeOutcome.Failure(TrickleFailure(mapOf(), valueIds.toSet()))
    }

    fun testCannotGetStepsWithInputsUndefined2() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })
        val cNode = builder.createInputNode(C)

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        assertEquals(NodeOutcome.Computed(2), instance.getNodeOutcome(B))
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(C)), instance.getNodeOutcome(C))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotSetUnrecognizedNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(C, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotSetUnrecognizedKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(C_KEYS, listOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotAddToUnrecognizedKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(C_KEYS, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotRemoveFromUnrecognizedKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.removeKeyInput(C_KEYS, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotSetNonInputNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { it + 1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(B, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotSetNonInputKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        instance.setInput(B_KEYS, listOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotAddToNonInputKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(B_KEYS, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCannotRemoveFromNonInputKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        instance.removeKeyInput(B_KEYS, 1)
    }

    @Test
    fun testUncaughtExceptionInCalculation() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val bOutcome = instance.getNodeOutcome(B)
        if (bOutcome !is NodeOutcome.Failure) {
            fail()
        } else {
            val errors = bOutcome.failure.errors
            assertEquals(setOf(ValueId.Nonkeyed(B)), errors.keys)
            assertTrue(errors.values.single().message!!.contains("custom exception message"))
        }
    }

    @Test
    fun testUpstreamUncaughtException() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })
        val cNode = builder.createNode(C, bNode, { it * 3 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val cOutcome = instance.getNodeOutcome(C)
        if (cOutcome !is NodeOutcome.Failure) {
            fail()
        } else {
            val errors = cOutcome.failure.errors
            assertEquals(setOf(ValueId.Nonkeyed(B)), errors.keys)
            assertTrue(errors.values.single().message!!.contains("custom exception message"))
        }
    }

    @Test
    fun testUpstreamMixedMissingAndException() {
        val builder = TrickleDefinitionBuilder()

        val exception = java.lang.RuntimeException("simulated failure")

        val aNode = builder.createInputNode(A)
        val bNode = builder.createInputNode(B)
        val cNode = builder.createNode(C, aNode, { throw exception })
        val dNode = builder.createNode(D, bNode, cNode, { b, c -> b * c })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val outcome = instance.getNodeOutcome(D)
        assertEquals(
                NodeOutcome.Failure<Int>(TrickleFailure(
                        mapOf(ValueId.Nonkeyed(C) to exception),
                        setOf(ValueId.Nonkeyed(B)))),
                outcome)
    }

    // TODO: Should a catch block also apply to the node's own computation? Also, what if the node fails itself?
    @Test
    fun testCatchUpstreamException() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })
        val cNode = builder.createNode(C, bNode, { it * 3 }, { failures -> -1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val cOutcome = instance.getNodeOutcome(C)
        if (cOutcome !is NodeOutcome.Computed) {
            fail()
        } else {
            assertEquals(-1, cOutcome.value)
        }
        assertEquals(-1, instance.getNodeValue(C))
    }

    @Test
    fun testCatchUpstreamException2() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { throw RuntimeException("custom exception message") })
        val cNode = builder.createNode(C, bNode, { it + 1 })
        val dNode = builder.createNode(D, cNode, { it * 3 }, { failures -> -1 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 1)
        instance.completeSynchronously()
        val dOutcome = instance.getNodeOutcome(D)
        if (dOutcome !is NodeOutcome.Computed) {
            fail()
        } else {
            assertEquals(-1, dOutcome.value)
        }
        assertEquals(-1, instance.getNodeValue(D))
    }

    @Test
    fun testKeyListNode1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)

        val instance = builder.build().instantiateRaw()

        assertEquals(listOf<Int>(), instance.getNodeValue(A_KEYS))
        instance.addKeyInput(A_KEYS, 4)
        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 5)
        assertEquals(listOf(4, 3, 5), instance.getNodeValue(A_KEYS))
    }

    @Test
    fun testKeyListNode2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        // Note: This is not a realistic example; summing over a set (vs. a list) is usually not useful
        val bNode = builder.createNode(B, aKeys.listOutput(), { ints -> ints.sum() })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(0, instance.getNodeValue(B))
        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 4)
        instance.completeSynchronously()
        assertEquals(7, instance.getNodeValue(B))
    }

    @Test
    fun testKeyListNode3() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bKeys = builder.createKeyListNode(B_KEYS, aNode, { (1..it).toList() })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 4)
        instance.completeSynchronously()
        assertEquals(listOf(1, 2, 3, 4), instance.getNodeValue(B_KEYS))
        instance.setInput(A, 2)
        instance.completeSynchronously()
        assertEquals(listOf(1, 2), instance.getNodeValue(B_KEYS))
    }

    @Test
    fun testCatchingKeyListNode() {
        val builder = TrickleDefinitionBuilder()

        val aNode = builder.createInputNode(A)
        val bNode = builder.createNode(B, aNode, { error("Something went wrong") })
        val cKeys = builder.createKeyListNode(C_KEYS, bNode, { (1..it).toList() }, { _ -> listOf(-1) })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 4)
        instance.completeSynchronously()
        assertEquals(listOf(-1), instance.getNodeValue(C_KEYS))
    }

    @Test
    fun testKeyListInputBehavior() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)

        val instance = builder.build().instantiateRaw()

        // It's a set
        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 3)
        val result1 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3), result1)
        // It maintains order
        instance.addKeyInput(A_KEYS, 4)
        instance.addKeyInput(A_KEYS, 6)
        instance.addKeyInput(A_KEYS, 5)
        instance.addKeyInput(A_KEYS, 6) //ignored
        val result2 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 4, 6, 5), result2)
        // Keys can be removed, and lose their place in the ordering
        instance.removeKeyInput(A_KEYS, 4)
        val result3 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 6, 5), result3)
        instance.addKeyInput(A_KEYS, 4)
        val result4 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 6, 5, 4), result4)
        instance.removeKeyInput(A_KEYS, 8)
        val result7 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(3, 6, 5, 4), result7)
        // And the list can be replaced entirely, which resets the order
        instance.setInput(A_KEYS, listOf(4, 3, 5, 6))
        val result5 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf(4, 3, 5, 6), result5)
        instance.setInput(A_KEYS, listOf())
        val result6 = instance.getNodeValue(A_KEYS)
        assertEquals(listOf<Int>(), result6)
        // When setting the value, duplicates are lost
        instance.setInput(A_KEYS, listOf(1, 3, 2, 3, 4, 4, 1))
        assertEquals(listOf<Int>(1, 3, 2, 4), instance.getNodeValue(A_KEYS))

        // All results are defensive copies of the list, not views of the list
        assertEquals(listOf(3), result1)
        assertEquals(listOf(3, 4, 6, 5), result2)
        assertEquals(listOf(3, 6, 5), result3)
        assertEquals(listOf(3, 6, 5, 4), result4)
        assertEquals(listOf(3, 6, 5, 4), result7)
        assertEquals(listOf(4, 3, 5, 6), result5)
        assertEquals(listOf<Int>(), result6)
    }

    @Test
    fun testKeyedNode1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.completeSynchronously()
        assertEquals(2, instance.getNodeValue(B_KEYED, 1))
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(6, instance.getNodeValue(B_KEYED, 3))
        assertEquals(listOf(6, 2, 4), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedNode2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(listOf<Int>(), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedNode3() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bNode = builder.createInputNode(B)
        val cKeyed = builder.createKeyedNode(C_KEYED, aKeys, bNode, { a, b -> a*2 + b })

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 3)
        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.setInput(B, 1)
        instance.completeSynchronously()
        assertEquals(3, instance.getNodeValue(C_KEYED, 1))
        assertEquals(5, instance.getNodeValue(C_KEYED, 2))
        assertEquals(7, instance.getNodeValue(C_KEYED, 3))
        assertEquals(listOf(7, 3, 5), instance.getNodeValue(C_KEYED))
    }

    @Test
    fun testKeyedInputNode1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.addKeyInput(A_KEYS, 3)
        instance.setKeyedInput(B_KEYED, 1, 6)
        instance.setKeyedInput(B_KEYED, 2, 4)
        instance.setKeyedInput(B_KEYED, 3, 5)
        instance.completeSynchronously()
        assertEquals(6, instance.getNodeValue(B_KEYED, 1))
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(5, instance.getNodeValue(B_KEYED, 3))
        assertEquals(listOf(6, 4, 5), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedInputNode2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)
        val cKeyed = builder.createKeyedNode(C_KEYED, aKeys, bKeyed.keyedOutput(), { key, b -> key * b })

        val instance = builder.build().instantiateRaw()

        instance.addKeyInput(A_KEYS, 1)
        instance.addKeyInput(A_KEYS, 2)
        instance.addKeyInput(A_KEYS, 3)
        instance.setKeyedInput(B_KEYED, 1, 6)
        instance.setKeyedInput(B_KEYED, 2, 4)
        instance.setKeyedInput(B_KEYED, 3, 5)
        instance.completeSynchronously()
        assertEquals(6, instance.getNodeValue(C_KEYED, 1))
        assertEquals(8, instance.getNodeValue(C_KEYED, 2))
        assertEquals(15, instance.getNodeValue(C_KEYED, 3))
        assertEquals(listOf(6, 8, 15), instance.getNodeValue(C_KEYED))
        instance.setKeyedInput(B_KEYED, 1, 3)
        instance.completeSynchronously()
        assertEquals(3, instance.getNodeValue(C_KEYED, 1))
        assertEquals(listOf(3, 8, 15), instance.getNodeValue(C_KEYED))
    }

    @Test
    fun testKeyedValuesNotRecomputedWhenKeyOrderChanges() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        assertEquals(3, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        assertEquals(0, instance.getNextSteps().size)
        instance.setInput(A_KEYS, listOf(3, 2, 1))
        assertEquals(0, instance.getNextSteps().size)
        assertEquals(listOf(6, 4, 2), instance.getNodeValue(B_KEYED))
    }

    @Test
    fun testKeyedValuesNotRecomputedWhenSingleKeyAdded() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        assertEquals(3, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        assertEquals(0, instance.getNextSteps().size)
        instance.addKeyInput(A_KEYS, 4)
        assertEquals(1, instance.getNextSteps().size)
        assertEquals(ValueId.Keyed(B_KEYED, 4), instance.getNextSteps().single().valueId)
    }

    @Test
    fun testKeyedNodeOutputs1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})
        val c = builder.createNode(C, bKeyed.fullOutput(), { it.sum() })

        val instance = builder.build().instantiateRaw()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(2 + 4 + 6, instance.getNodeValue(C))
    }

    @Test
    fun testKeyedNodeOutputs2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2})
        val cKeyed = builder.createKeyedNode(C_KEYED, aKeys, bKeyed.keyedOutput(), { key, bValue -> bValue + 1 })

        val instance = builder.build().instantiateRaw()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(listOf(3, 5, 7), instance.getNodeValue(C_KEYED))
    }

    @Test
    fun testTrickleSyncIsLazy1() {
        val builder = TrickleDefinitionBuilder()

        val didUnnecessaryWork = AtomicBoolean(false)

        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, { it + 4 })
        val c = builder.createNode(C, a, { didUnnecessaryWork.set(true); -1 })

        val instance = builder.build().instantiateSync()

        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getOutcome(B))
        instance.setInput(A, 3)
        assertEquals(7, instance.getValue(B))
        assertFalse(didUnnecessaryWork.get())
    }

    @Test
    fun testTrickleSyncIsLazy2() {
        val builder = TrickleDefinitionBuilder()

        val didUnnecessaryWork = AtomicBoolean(false)

        val a = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createKeyedNode(B_KEYED, a, {
            if (it == 1) {
                didUnnecessaryWork.set(true)
                -1
            } else {
                0
            }
        })

        val instance = builder.build().instantiateSync()

        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getOutcome(B_KEYED, 1))
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getOutcome(B_KEYED, 2))
        instance.setInput(A_KEYS, listOf(1, 2))
        assertEquals(0, instance.getValue(B_KEYED, 2))
        assertFalse(didUnnecessaryWork.get())
    }

    @Test
    fun testBehaviorWhenNodeDependsOnlyOnKeyListInputs() {
        // Key lists default to true, so values can be computed without setting any inputs
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createNode(B, aKeys.listOutput(), { it.sum() })

        val instance = builder.build().instantiateRaw()

        assertEquals(NodeOutcome.NotYetComputed.get<Int>(), instance.getNodeOutcome(B))
        assertEquals(1, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(0, instance.getNodeValue(B))
    }

    @Test
    fun testGettingKeyedValuesForNonexistentKey() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getNodeOutcome(B_KEYED, 10))
    }

    @Test
    fun testGettingKeyedValuesForRemovedKey1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        instance.removeKeyInput(A_KEYS, 2)
        instance.completeSynchronously()
        assertEquals(listOf(2, 6), instance.getNodeValue(B_KEYED))
        // Reverts to "no such key"
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getNodeOutcome(B_KEYED, 2))
    }

    @Test
    fun testGettingKeyedValuesForRemovedKey2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedNode(B_KEYED, aKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(4, instance.getNodeValue(B_KEYED, 2))
        assertEquals(listOf(2, 4, 6), instance.getNodeValue(B_KEYED))
        instance.setInput(A_KEYS, listOf(1, 3))
        instance.completeSynchronously()
        assertEquals(listOf(2, 6), instance.getNodeValue(B_KEYED))
        // Reverts to "no such key"
        assertEquals(NodeOutcome.NoSuchKey.get<Int>(), instance.getNodeOutcome(B_KEYED, 2))
    }

    @Test
    fun testFullOutputsFromKeyedNodeWhenKeyListIsSometimesEmpty() {
        val builder = TrickleDefinitionBuilder()

        val a = builder.createInputNode(A)
        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val cKeyed = builder.createKeyedNode(C_KEYED, bKeys, a, { key, bVal -> key + bVal + 1 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(NodeOutcome.Computed(listOf<Int>()), instance.getNodeOutcome(C_KEYED))
        instance.addKeyInput(B_KEYS, 42)
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(C_KEYED, 42))
        instance.completeSynchronously()
        // TODO: This probably isn't good
//        assertEquals(NodeOutcome.Computed(listOf<Int>()), instance.getNodeOutcome(C_KEYED))
        // This would be preferable
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(C_KEYED))
    }

    @Test
    fun testNodeGetsHungUpByOtherNodeBecomingNonempty() {
        val builder = TrickleDefinitionBuilder()

        val a = builder.createInputNode(A)
        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val cKeyed = builder.createKeyedNode(C_KEYED, bKeys, a, { key, bVal -> key + bVal + 1 })
        val d = builder.createInputNode(D)
        val e = builder.createNode(E, cKeyed.fullOutput(), d, { cList, d -> cList.hashCode() + d })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(NodeOutcome.Computed(listOf<Int>()), instance.getNodeOutcome(C_KEYED))
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(D)), instance.getNodeOutcome(E))
        instance.setInput(D, 100)
        instance.completeSynchronously()
        assertEquals(101, instance.getNodeValue(E))

        instance.setInput(D, 1000)
        instance.addKeyInput(B_KEYS, 42)
        instance.completeSynchronously()
        // TODO: This isn't good! We'd like it to be something more recent or just "uncomputed". It also means the behavior
        // would be different if computation happened in between the two input changes above.
//        assertEquals(101, instance.getNodeValue(E))
        // This would be preferable:
        assertEquals(inputsMissingOutcome(ValueId.Nonkeyed(A)), instance.getNodeOutcome(E))
    }

    @Test
    fun testNodeGetsHungUpByKeyedInputNode() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)
        val c = builder.createNode(C, bKeyed.fullOutput(), { it.sum() + 1 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        assertEquals(1, instance.getNodeValue(C))
        instance.addKeyInput(A_KEYS, 1)
        instance.setKeyedInput(B_KEYED, 1, 30)
        instance.completeSynchronously()
        assertEquals(31, instance.getNodeValue(C))
        instance.addKeyInput(A_KEYS, 2)
        instance.setKeyedInput(B_KEYED, 2, 60)
        instance.addKeyInput(A_KEYS, 3)
        instance.completeSynchronously()
        // TODO: Also not good! If the addition of <2, 60> had been registered, the value would be 91, not 31.
//        assertEquals(31, instance.getNodeValue(C))
        // This would be preferable:
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 3)), instance.getNodeOutcome(C))
    }

    @Test
    fun testSettingKeyedInputBeforeKeyExists1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        // This should be ignored since that key doesn't exist currently
        instance.setKeyedInput(B_KEYED, 2, 10)
        instance.completeSynchronously()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 2)), instance.getNodeOutcome(B_KEYED, 2))
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 1), ValueId.Keyed(B_KEYED, 2), ValueId.Keyed(B_KEYED, 3)), instance.getNodeOutcome(B_KEYED))
    }

    @Test
    fun testSettingKeyedInputBeforeKeyExists2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        // This should be ignored since that key doesn't exist currently
        instance.setKeyedInput(B_KEYED, 2, 10)
//        instance.completeSynchronously()
        instance.setInput(A_KEYS, listOf(1, 2, 3))
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 2)), instance.getNodeOutcome(B_KEYED, 2))
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 1), ValueId.Keyed(B_KEYED, 2), ValueId.Keyed(B_KEYED, 3)), instance.getNodeOutcome(B_KEYED))
    }

    @Test(expected = IllegalStateException::class)
    fun testCannotUseNonInputKeyListAsKeyedInputSource() {
        val builder = TrickleDefinitionBuilder()

        val a = builder.createInputNode(A)
        val bKeys = builder.createKeyListNode(B_KEYS, a, { (0..it).toList() })
        val cKeyed = builder.createKeyedInputNode(C_KEYED, bKeys)
    }

    @Test
    fun testKeyedInputsGiveOutOfDateKeyedOutputsWhenKeysChange1() {
        val builder = TrickleDefinitionBuilder()

        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val dKeyed = builder.createKeyedInputNode(D_KEYED, bKeys)
        val eKeyed = builder.createKeyedNode(E_KEYED, bKeys, dKeyed.keyedOutput(), { k, e1 -> k + e1 })
        // TODO: Does it ever make sense for a keyed node to take its key source as an input? Are there problems that causes?
        // It must be accepting the full key list as the input here...

        val instance = builder.build().instantiateRaw()

        instance.setInput(B_KEYS, listOf(1, 2)) // op 3
        instance.setKeyedInput(D_KEYED, 1, 10) // op 4
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 2)), instance.getNodeOutcome(E_KEYED))

        instance.setKeyedInput(D_KEYED, 2, 20)
        instance.completeSynchronously()
        instance.addKeyInput(B_KEYS, 3)
        instance.completeSynchronously()

        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 3)), instance.getNodeOutcome(E_KEYED)) // op 8
    }

    @Test
    fun testKeyedInputsGiveOutOfDateKeyedOutputsWhenKeysChange2() {
        val builder = TrickleDefinitionBuilder()

        val bKeys = builder.createKeyListInputNode(B_KEYS)
        val dKeyed = builder.createKeyedInputNode(D_KEYED, bKeys)
        val eKeyed = builder.createKeyedNode(E_KEYED, bKeys, dKeyed.keyedOutput(), { k, e1 -> k + e1 })
        // TODO: Does it ever make sense for a keyed node to take its key source as an input? Are there problems that causes?
        // It must be accepting the full key list as the input here...

        val instance = builder.build().instantiateRaw()

        instance.setInput(B_KEYS, listOf(1, 2)) // op 3
        instance.setKeyedInput(D_KEYED, 1, 10) // op 4
        instance.completeSynchronously()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 2)), instance.getNodeOutcome(E_KEYED))

        instance.setKeyedInput(D_KEYED, 2, 20)
        instance.addKeyInput(B_KEYS, 3)
        instance.completeSynchronously()

        assertEquals(inputsMissingOutcome(ValueId.Keyed(D_KEYED, 3)), instance.getNodeOutcome(E_KEYED)) // op 8
    }

    @Test
    fun testSyncKeyedInputsBug() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateSync()

        instance.setInput(A_KEYS, listOf(5, 10, 6))
        instance.setKeyedInput(B_KEYED, 5, 10)
        assertEquals(NodeOutcome.Computed(10), instance.getOutcome(B_KEYED, 5))
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 10), ValueId.Keyed(B_KEYED, 6)), instance.getOutcome(B_KEYED))
    }

    @Test
    fun testRawKeyedInputsBug() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.setInput(A_KEYS, listOf(5, 10, 6))
        instance.setKeyedInput(B_KEYED, 5, 10)
        instance.getNextSteps()
        instance.getNextSteps()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 10), ValueId.Keyed(B_KEYED, 6)), instance.getNodeOutcome(B_KEYED))
    }

    @Test
    fun testKeyedInputsRemovedAfterKeyRemoved1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.getNodeOutcome(A_KEYS)
        instance.addKeyInput(A_KEYS, 0)
        instance.setKeyedInput(B_KEYED, 0, 57)
        instance.removeKeyInput(A_KEYS, 0)
        instance.completeSynchronously()
        instance.setInput(A_KEYS, listOf(0, 1))
        instance.setKeyedInput(B_KEYED, 1, 58)
        instance.getNextSteps()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 0)), instance.getNodeOutcome(B_KEYED, 0))
    }

    @Test
    fun testKeyedInputsRemovedAfterKeyRemoved2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val bKeyed = builder.createKeyedInputNode(B_KEYED, aKeys)

        val instance = builder.build().instantiateRaw()

        instance.getNodeOutcome(A_KEYS)
        instance.addKeyInput(A_KEYS, 0)
        instance.setKeyedInput(B_KEYED, 0, 57)
        instance.removeKeyInput(A_KEYS, 0)
        instance.setInput(A_KEYS, listOf(0, 1))
        instance.setKeyedInput(B_KEYED, 1, 58)
        instance.getNextSteps()
        assertEquals(inputsMissingOutcome(ValueId.Keyed(B_KEYED, 0)), instance.getNodeOutcome(B_KEYED, 0))
    }

    @Test
    fun testKeyedValuesClearedWhenInputListsInvalidated1() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createInputNode(B)
        val c = builder.createNode(C, b, { it + 1 })
        val dKeyed = builder.createKeyedInputNode(D_KEYED, aKeys)
        val eKeys = builder.createKeyListNode(E_KEYS, c, dKeyed.fullOutput(), { cVal, dList -> dList.map { it + cVal } })
        val fKeyed = builder.createKeyedNode(F_KEYED, eKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        instance.setInput(B, 6)
        instance.completeSynchronously()
        instance.addKeyInput(A_KEYS, 23)
        instance.completeSynchronously()

        instance.addKeyInput(A_KEYS, 29)
        instance.setKeyedInput(D_KEYED, 29, 20)
        instance.setKeyedInput(D_KEYED, 23, 31)
        instance.completeSynchronously()

        instance.setInput(A_KEYS, listOf(0))
        instance.completeSynchronously()
        assertEquals(NodeOutcome.NotYetComputed.get<Int>(), instance.getNodeOutcome(F_KEYED, 38))
    }


    @Test
    fun testKeyedValuesClearedWhenInputListsInvalidated2() {
        val builder = TrickleDefinitionBuilder()

        val aKeys = builder.createKeyListInputNode(A_KEYS)
        val b = builder.createInputNode(B)
        val c = builder.createNode(C, b, { it + 1 })
        val dKeyed = builder.createKeyedInputNode(D_KEYED, aKeys)
        val eKeys = builder.createKeyListNode(E_KEYS, c, dKeyed.fullOutput(), { cVal, dList -> dList.map { it + cVal } })
        val fKeyed = builder.createKeyedNode(F_KEYED, eKeys, { it * 2 })

        val instance = builder.build().instantiateRaw()

        instance.completeSynchronously()
        instance.setInput(B, 6)
        instance.completeSynchronously()
        instance.addKeyInput(A_KEYS, 23)
        instance.completeSynchronously()

        instance.addKeyInput(A_KEYS, 29)
        instance.setKeyedInput(D_KEYED, 29, 20)
        instance.setKeyedInput(D_KEYED, 23, 31)
//        instance.completeSynchronously()

        instance.setInput(A_KEYS, listOf(0))
        instance.completeSynchronously()
        assertEquals(NodeOutcome.NotYetComputed.get<Int>(), instance.getNodeOutcome(F_KEYED, 38))
    }

    @Test
    fun testArgumentEqualityCheck1() {
        val builder = TrickleDefinitionBuilder()

        var computationCount = 0
        // TODO: Next step is to figure out the right way to pass in "If this is equal, don't recompute outputs" to A
        val a = builder.createInputNode(A)
        val b = builder.createNode(B, a, {
            computationCount++
            it + 1
        })

        val instance = builder.build().instantiateRaw()

        instance.setInput(A, 10)
        assertEquals(0, computationCount)
        instance.completeSynchronously()
        assertEquals(1, computationCount)
        assertEquals(11, instance.getNodeValue(B))
        instance.setInput(A, 10)
        // There is a step for determining B, but it does not end up running the operation
        assertEquals(1, instance.getNextSteps().size)
        instance.completeSynchronously()
        assertEquals(1, computationCount)
        instance.setInput(A, 20)
        instance.setInput(A, 10)
        instance.completeSynchronously()
        assertEquals(1, computationCount)
    }
}
