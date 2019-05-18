import net.semlang.modules.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*
import kotlin.collections.ArrayList

// There's an issue around the following structure, it looks like:
// 1) Inputs are a basic node and a key list
// 2) There's also a keyed node that is based on the key list and takes the basic node as an input
// 3) Another node depends on the keyed node's full output (or that output is checked directly)
// The consequence appears to be something like: the final node in the chain takes on a value due to the keyed node
// "having a full value" so long as the key list is empty, but it stops getting updated when a key is added to the list.

// TODO: Also add keyed input nodes
class TrickleFuzzTests {
    @Test
    fun specificTest1() {
        runSpecificTest(1, 0)
    }

    private fun runSpecificTest(definitionSeed: Int, operationsSeed: Int) {
        val definition = getFuzzedDefinition(definitionSeed)

        try {
            val descriptionBeforeGen = definition.toMultiLineString()
            val script = getOperationsScript(definition, operationsSeed)
            val descriptionAfterGen = definition.toMultiLineString()
            if (descriptionBeforeGen != descriptionAfterGen) {
                throw RuntimeException("The description changed; before:\n$descriptionBeforeGen\nAfter:\n$descriptionAfterGen")
            }

            try {
                checkRawInstance1(definition.instantiateRaw(), script.operations)
                checkRawInstance2(definition.instantiateRaw(), script.operations)
                checkSyncInstance(definition.instantiateSync(), script.operations)
            } catch (t: Throwable) {
                throw RuntimeException(
                    "Operations script: \n${script.operations.withIndex().joinToString("\n")}",
                    t
                )
            }
        } catch (t: Throwable) {
            throw RuntimeException("Definition:\n" + definition.toMultiLineString(), t)
        }
    }

    @Test
    fun runFuzzTests() {
        for (definitionSeed in 0..99) {
            val definition = getFuzzedDefinition(definitionSeed)

            try {
                for (operationsSeed in 0..9) {
                    try {
                        val descriptionBeforeGen = definition.toMultiLineString()
                        val script = getOperationsScript(definition, operationsSeed)
                        val descriptionAfterGen = definition.toMultiLineString()
                        if (descriptionBeforeGen != descriptionAfterGen) {
                            throw RuntimeException("The description changed; before:\n$descriptionBeforeGen\nAfter:\n$descriptionAfterGen")
                        }

                        try {
                            checkRawInstance1(definition.instantiateRaw(), script.operations)
                            checkRawInstance2(definition.instantiateRaw(), script.operations)
                            checkSyncInstance(definition.instantiateSync(), script.operations)
                        } catch (t: Throwable) {
                            throw RuntimeException(
                                "Operations script: \n${script.operations.withIndex().joinToString("\n")}",
                                t
                            )
                        }
                    } catch (t: Throwable) {
                        throw RuntimeException(
                            "Error with definitionSeed $definitionSeed and operationsSeed $operationsSeed",
                            t
                        )
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Definition:\n" + definition.toMultiLineString(), t)
            }
        }
    }

    private fun checkSyncInstance(instance: TrickleSyncInstance, operations: List<FuzzOperation>) {
        for (op in operations) {
            val unused: Any = when (op) {
                is FuzzOperation.SetBasic -> {
                    instance.setInput(op.name, op.value)
                }
                is FuzzOperation.AddKey -> {
                    instance.addKeyInput(op.name, op.value)
                }
                is FuzzOperation.RemoveKey -> {
                    instance.removeKeyInput(op.name, op.value)
                }
                is FuzzOperation.SetKeyList -> {
                    instance.setInput(op.name, op.value)
                }
                is FuzzOperation.SetKeyed -> {
                    instance.setKeyedInput(op.name, op.key, op.value)
                }
                is FuzzOperation.CheckBasic -> {
                    assertEquals(op.outcome, instance.getOutcome(op.name))
                }
                is FuzzOperation.CheckKeyList -> {
                    assertEquals(op.outcome, instance.getOutcome(op.name))
                }
                is FuzzOperation.CheckKeyedList -> {
                    assertEquals(op.outcome, instance.getOutcome(op.name))
                }
                is FuzzOperation.CheckKeyedValue -> {
                    assertEquals(op.outcome, instance.getOutcome(op.name, op.key))
                }
            }
        }
    }

    private fun checkRawInstance1(instance: TrickleInstance, operations: List<FuzzOperation>) {
        // Handle cases where a node we query depends only on key list inputs
        instance.completeSynchronously()
        for (op in operations) {
            val unused: Any = when (op) {
                is FuzzOperation.SetBasic -> {
                    instance.setInput(op.name, op.value)
                    instance.completeSynchronously()
                }
                is FuzzOperation.AddKey -> {
                    instance.addKeyInput(op.name, op.value)
                    instance.completeSynchronously()
                }
                is FuzzOperation.RemoveKey -> {
                    instance.removeKeyInput(op.name, op.value)
                    instance.completeSynchronously()
                }
                is FuzzOperation.SetKeyList -> {
                    instance.setInput(op.name, op.value)
                    instance.completeSynchronously()
                }
                is FuzzOperation.SetKeyed -> {
                    instance.setKeyedInput(op.name, op.key, op.value)
                    instance.completeSynchronously()
                }
                is FuzzOperation.CheckBasic -> {
                    assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                }
                is FuzzOperation.CheckKeyList -> {
                    assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                }
                is FuzzOperation.CheckKeyedList -> {
                    assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                }
                is FuzzOperation.CheckKeyedValue -> {
                    assertEquals(op.outcome, instance.getNodeOutcome(op.name, op.key))
                }
            }
        }
    }

    private fun checkRawInstance2(instance: TrickleInstance, operations: List<FuzzOperation>) {
        for ((opIndex, op) in operations.withIndex()) {
            try {
                val unused: Any = when (op) {
                    is FuzzOperation.SetBasic -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.AddKey -> {
                        instance.addKeyInput(op.name, op.value)
                    }
                    is FuzzOperation.RemoveKey -> {
                        instance.removeKeyInput(op.name, op.value)
                    }
                    is FuzzOperation.SetKeyList -> {
                        instance.setInput(op.name, op.value)
                    }
                    is FuzzOperation.SetKeyed -> {
                        instance.setKeyedInput(op.name, op.key, op.value)
                    }
                    is FuzzOperation.CheckBasic -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyList -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedList -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name))
                    }
                    is FuzzOperation.CheckKeyedValue -> {
                        instance.completeSynchronously()
                        assertEquals(op.outcome, instance.getNodeOutcome(op.name, op.key))
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Failed on operation #$opIndex: #$op", t)
            }
        }
    }

}

sealed class FuzzOperation {
    data class SetBasic(val name: NodeName<Int>, val value: Int) : FuzzOperation()
    data class AddKey(val name: KeyListNodeName<Int>, val value: Int) : FuzzOperation()
    data class RemoveKey(val name: KeyListNodeName<Int>, val value: Int) : FuzzOperation()
    data class SetKeyList(val name: KeyListNodeName<Int>, val value: List<Int>) : FuzzOperation()
    data class SetKeyed(val name: KeyedNodeName<Int, Int>, val key: Int, val value: Int) : FuzzOperation()
    data class CheckBasic(val name: NodeName<Int>, val outcome: NodeOutcome<Int>) : FuzzOperation()
    data class CheckKeyList(val name: KeyListNodeName<Int>, val outcome: NodeOutcome<List<Int>>) : FuzzOperation()
    data class CheckKeyedList(val name: KeyedNodeName<Int, Int>, val outcome: NodeOutcome<List<Int>>) : FuzzOperation()
    data class CheckKeyedValue(val name: KeyedNodeName<Int, Int>, val key: Int, val outcome: NodeOutcome<Int>) : FuzzOperation()
}

class FuzzOperationScript(val operations: List<FuzzOperation>, val states: List<Map<ValueId, Any>>) {
}

private fun getOperationsScript(definition: TrickleDefinition, operationsSeed: Int): FuzzOperationScript {
    val rawInstance = definition.instantiateRaw()
    // Handle cases where a node we query depends only on key list inputs
    rawInstance.completeSynchronously()
    val random = Random(operationsSeed.toLong())
    val operations = ArrayList<FuzzOperation>()
    val states = ArrayList<Map<ValueId, Any>>()
    for (i in 1..15) {
        operations.add(getRandomOperation(rawInstance, definition, random))
        rawInstance.completeSynchronously()
        states.add(getState(rawInstance))
    }
    return FuzzOperationScript(operations, states)
}

fun getState(instance: TrickleInstance): Map<ValueId, Any> {
    // TODO: Implement (these full states will be used in evaluating
    return mapOf()
}

private fun getRandomOperation(rawInstance: TrickleInstance, definition: TrickleDefinition, random: Random): FuzzOperation {
    val roll = random.nextDouble()
    if (roll < 0.5) {
        // Do a random input operation
        val allInputNames = getAllInputNodes(definition)
        val inputName = allInputNames.getAtRandom(random, { error("This shouldn't be empty") })
        // TODO: Add multi-setting when that's a thing
        when (inputName) {
            is AnyNodeName.Basic -> {
                val nodeName = inputName.name as NodeName<Int>
                val valueToSet = random.nextInt(100)
                rawInstance.setInput(nodeName, valueToSet)
                return FuzzOperation.SetBasic(nodeName, valueToSet)
            }
            is AnyNodeName.KeyList -> {
                val nodeName = inputName.name as KeyListNodeName<Int>
                val keyListRoll = random.nextDouble()
                if (keyListRoll < 0.3) {
                    val valueToAdd = random.nextInt(100)
                    rawInstance.addKeyInput(nodeName, valueToAdd)
                    return FuzzOperation.AddKey(nodeName, valueToAdd)
                } else if (keyListRoll < 0.55) {
                    // Pick an existing key to delete
                    val existingListOutcome = rawInstance.getNodeOutcome(nodeName)
                    val valueToRemove = when (existingListOutcome) {
                        is NodeOutcome.NotYetComputed -> 0
                        is NodeOutcome.Computed -> existingListOutcome.value.getAtRandom(random, { 2 })
                        is NodeOutcome.Failure -> 1
                        is NodeOutcome.NoSuchKey -> 3
                    }
                    rawInstance.removeKeyInput(nodeName, valueToRemove)
                    return FuzzOperation.RemoveKey(nodeName, valueToRemove)
                } else if (keyListRoll < 0.6) {
                    // Pick a random key to maybe delete if it's there
                    val valueToRemove = random.nextInt(100)
                    rawInstance.removeKeyInput(nodeName, valueToRemove)
                    return FuzzOperation.RemoveKey(nodeName, valueToRemove)
                } else {
                    val newList = createRandomListProducingFunction(random)(listOf(random.nextInt(100)))
                    rawInstance.setInput(nodeName, newList)
                    return FuzzOperation.SetKeyList(nodeName, newList)
                }
            }
            is AnyNodeName.Keyed -> {
                val nodeName = inputName.name as KeyedNodeName<Int, Int>
                val keySourceName = definition.keyedNodes[nodeName]!!.keySourceName
                val curKeyListOutcome = rawInstance.getNodeOutcome(keySourceName)
                val curKeyList = when (curKeyListOutcome) {
                    is NodeOutcome.NotYetComputed -> listOf(0)
                    is NodeOutcome.Computed -> curKeyListOutcome.value as List<Int>
                    is NodeOutcome.Failure -> listOf(-1)
                    is NodeOutcome.NoSuchKey -> listOf(2)
                }
                // Look at the _ to find a thing to set
                val keyedRoll = random.nextDouble()
                val key = if (keyedRoll < 0.05) {
                    // Use a random key value
                    random.nextInt(100)
                } else {
                    // Look up an existing key
                    curKeyList.getAtRandom(random, { 1 })
                }

                val valueToSet = random.nextInt(100)
                rawInstance.setKeyedInput(nodeName, key, valueToSet)
                return FuzzOperation.SetKeyed(nodeName, key, valueToSet)
            }
            else -> error("Unexpected situation")
        }
    } else {
        // Do a random observation of a node outcome
        val randomNode = definition.topologicalOrdering.getAtRandom(random, { error("This shouldn't be empty") })
        when (randomNode) {
            is AnyNodeName.Basic -> {
                val nodeName = randomNode.name as NodeName<Int>
                val outcome = rawInstance.getNodeOutcome(nodeName)
                return FuzzOperation.CheckBasic(nodeName, outcome)
            }
            is AnyNodeName.KeyList -> {
                val nodeName = randomNode.name as KeyListNodeName<Int>
                val outcome = rawInstance.getNodeOutcome(nodeName)
                return FuzzOperation.CheckKeyList(nodeName, outcome)
            }
            is AnyNodeName.Keyed -> {
                val nodeName = randomNode.name as KeyedNodeName<Int, Int>
                val checkTypeRoll = random.nextDouble()
                if (checkTypeRoll < 0.5) {
                    // Check all values
                    val outcome = rawInstance.getNodeOutcome(nodeName)
                    return FuzzOperation.CheckKeyedList(nodeName, outcome)
                } else {
                    // Check one value
                    val keySourceName = definition.keyedNodes[nodeName]!!.keySourceName
                    val keySourceOutcome = rawInstance.getNodeOutcome(keySourceName)
                    val key = when (keySourceOutcome) {
                        is NodeOutcome.NotYetComputed -> 0
                        is NodeOutcome.Computed -> keySourceOutcome.value.getAtRandom(random, { 2 }) as Int
                        is NodeOutcome.Failure -> 1
                        is NodeOutcome.NoSuchKey -> 3
                    }
                    val outcome = rawInstance.getNodeOutcome(nodeName, key)
                    return FuzzOperation.CheckKeyedValue(nodeName, key, outcome)
                }
            }
        }
    }
}

private fun getAllInputNodes(definition: TrickleDefinition): List<AnyNodeName> {
    val result = ArrayList<AnyNodeName>()
    // Use topologicalOrdering so nodes end up in the same order in each run
    for (nodeName in definition.topologicalOrdering) {
        val operation = when (nodeName) {
            is AnyNodeName.Basic -> definition.nonkeyedNodes[nodeName.name]!!.operation
            is AnyNodeName.KeyList -> definition.keyListNodes[nodeName.name]!!.operation
            is AnyNodeName.Keyed -> definition.keyedNodes[nodeName.name]!!.operation
        }
        if (operation == null) {
            result.add(nodeName)
        }
    }
    return result
}

private fun getFuzzedDefinition(seed: Int): TrickleDefinition {
    return FuzzedDefinitionBuilder(seed).run()
}

private class FuzzedDefinitionBuilder(seed: Int) {
    val random = Random(seed.toLong())
    val numNodes = 5 + random.nextInt(6)

    val builder = TrickleDefinitionBuilder()
    val existingNodes = ArrayList<AnyNodeName>()
    val existingKeyListNodes = ArrayList<TrickleBuiltKeyListNode<Int>>()
    val existingKeyedNodes = HashMap<KeyListNodeName<*>, ArrayList<TrickleInput<*>>>()

    val unkeyedInputs = ArrayList<TrickleInput<*>>()

    fun run(): TrickleDefinition {
        for (i in 1..numNodes) {
            val curNodeRoll = random.nextDouble()
            if (existingNodes.size < 2) {
                makeInputNode(curNodeRoll, i)
            } else {
                if (curNodeRoll < 0.2) {
                    makeInputNode(random.nextDouble(), i)
                } else if (curNodeRoll < 0.5) {
                    makeBasicNode(i)
                } else if (curNodeRoll < 0.7) {
                    makeKeyListNode(i)
                } else {
                    if (existingKeyListNodes.isEmpty()) {
                        makeKeyListNode(i)
                    } else {
                        makeKeyedNode(i)
                    }
                }
            }
        }
        if (existingNodes.size != numNodes) {
            error("I probably screwed something up here")
        }
        return builder.build()
    }

    private fun makeBasicNode(i: Int) {
        val name = NodeName<Int>("basic$i")

        // Decide how many inputs to have (between one and four)
        val maxInputs = Math.min(unkeyedInputs.size, 4)
        val numInputs = random.nextInt(maxInputs) + 1
        val inputs = unkeyedInputs.randomSubset(random, numInputs)

        val fn = createRandomIntProducingFunction(random)
        val onCatch = null // TODO: Create throwing methods and onCatch methods

        val node = builder.createNode(name, inputs, fn, onCatch)
        existingNodes.add(AnyNodeName.Basic(name))
        unkeyedInputs.add(node)
    }

    private fun makeKeyListNode(i: Int) {
        val name = KeyListNodeName<Int>("list$i")

        // Decide how many inputs to have (between one and two)
        val maxInputs = Math.min(unkeyedInputs.size, 2)
        val numInputs = random.nextInt(maxInputs) + 1
        val inputs = unkeyedInputs.randomSubset(random, numInputs)

        val fn = createRandomListProducingFunction(random)
        val onCatch = null

        val node = builder.createKeyListNode(name, inputs, fn, onCatch)
        existingNodes.add(AnyNodeName.KeyList(name))
        unkeyedInputs.add(node.listOutput())
        existingKeyListNodes.add(node)
        existingKeyedNodes[name] = ArrayList()
    }

    private fun makeKeyedNode(i: Int) {
        val name = KeyedNodeName<Int, Int>("keyed$i")

        val keySource = existingKeyListNodes.getAtRandom(random, { error("This shouldn't be empty") })

        val possibleInputs = ArrayList<TrickleInput<*>>()
        possibleInputs.addAll(unkeyedInputs)
        possibleInputs.addAll(existingKeyedNodes[keySource.name]!!)
        // Decide how many inputs to have (between zero and four)
        val maxInputs = Math.min(unkeyedInputs.size, 5)
        val numInputs = random.nextInt(maxInputs)
        val inputs = possibleInputs.randomSubset(random, numInputs)

        val listOnlyFn = createRandomIntProducingFunction(random)
        val fn = { key: Int, list: List<*> ->
            listOnlyFn(list + key)
        }

        val node = builder.createKeyedNode(name, keySource, inputs, fn)
        existingNodes.add(AnyNodeName.Keyed(name))
        existingKeyedNodes[keySource.name]!!.add(node.keyedOutput())
        unkeyedInputs.add(node.fullOutput())
    }

    private fun makeInputNode(curNodeRoll: Double, i: Int) {
        if (curNodeRoll < 0.6) {
            val name = NodeName<Int>("basicInput$i")
            val node = builder.createInputNode(name)
            existingNodes.add(AnyNodeName.Basic(name))
            unkeyedInputs.add(node)
        } else {
            val name = KeyListNodeName<Int>("listInput$i")
            val node = builder.createKeyListInputNode(name)
            existingNodes.add(AnyNodeName.KeyList(name))
            existingKeyListNodes.add(node)
            unkeyedInputs.add(node.listOutput())
            existingKeyedNodes[name] = ArrayList()
        }
    }
}

private fun createRandomIntProducingFunction(random: Random): (List<*>) -> Int {
    val inputsToInts: (List<*>) -> List<Int> = { incomingValues ->
        incomingValues.map { value ->
            if (value is List<*>) {
                value.hashCode()
            } else {
                value as Int
            }
        }
    }

    val dieRoll = random.nextDouble()
    if (dieRoll < 0.7) {
        val toAdd = -50 + random.nextInt(101)
        return { list ->
            val ints = inputsToInts(list)
            ints.hashCode() + toAdd
        }
    } else {
        val toMultiply = 2 + random.nextInt(5)
        return { list ->
            val ints = inputsToInts(list)
            ints.hashCode() * toMultiply
        }
    }
}

private fun createRandomListProducingFunction(random: Random): (List<*>) -> List<Int> {
    // Start by reducing its inputs to a number
    val turnToInt = createRandomIntProducingFunction(random)
    val dieRoll = random.nextDouble()

    if (dieRoll < 0.5) {
        // Low numbers
        return {
            val int = turnToInt(it)
            val bounded = (int % 10)
            (0 until bounded).toList()
        }
    } else {
        // More numbers...
        val defaultStart = random.nextInt(100)
        return {
            val int = turnToInt(it)
            val listRng = Random(int.toLong())
            val start = defaultStart - 2 + listRng.nextInt(5)
            val end = defaultStart + 6 + listRng.nextInt(5)
            val numbers = (start until end).toList()
            numbers.randomSubset(listRng, listRng.nextInt(numbers.size))
        }
    }

}

private fun <E> List<E>.getAtRandom(random: Random, ifZero: () -> E): E {
    if (this.size == 0) {
        return ifZero()
    }
    if (this.size == 1) {
        return this[0]
    }
    val index = random.nextInt(this.size)
    return this[index]
}

private fun <E> List<E>.randomSubset(random: Random, n: Int): List<E> {
    if (n >= this.size) {
        return ArrayList(this)
    }
    if (n == 0) {
        return listOf()
    }
    val indices = LinkedHashSet<Int>()
    while (indices.size < n) {
        indices.add(random.nextInt(this.size))
    }
    val subset = ArrayList<E>(n)
    for (index in indices) {
        subset.add(this[index])
    }
    return subset
}
