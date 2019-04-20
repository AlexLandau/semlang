package net.semlang.modules

import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.*

/*
 * Trickle: A Kotlin/JVM library (...if I choose to extract it) for defining asynchronous tasks in terms of each
 * other, going beyond RxJava by adding support for minimal recomputation when portions of the input change.
 */

// TODO: Concept to use: "Keys" that support equality, and a list of keys is used as the basis for maps and per-item steps
// A step can be in the "context" of a KeyList, in which case when it accepts other things in that context as inputs, you
// get the same key's equivalent (a single item) instead of the whole list

// TODO: "CatchNode" for error handling; nodes can raise an error in their output (one way or another) and the CatchNode
// will turn errors or successes into

open class NodeName<T>(val name: String) {
    override fun equals(other: Any?): Boolean {
        if (other !is NodeName<*>) {
            return false
        }
        return Objects.equals(name, other.name)
    }
    override fun hashCode(): Int {
        return name.hashCode()
    }
    override fun toString(): String {
        return name
    }
}
/**
 * Note that KeyedNodeName uses the same equals() and hashCode() implementations as NodeName (and is interchangeable
 * with other NodeNames for those purposes), and only exists as a separate type to include the key type as a type
 * parameter.
 */
class KeyedNodeName<K, T>(name: String): NodeName<T>(name)

class KeyList<T> {
    fun add(key: T): Boolean {
        if (set.contains(key)) {
            return false
        }
        set.add(key)
        return list.add(key)
    }

    private val list = ArrayList<T>()
    private val set = HashSet<T>()
}

private data class TimestampedValue(private var timestamp: Long, private var value: Any?, private var failure: TrickleFailure?) {
    fun set(newTimestamp: Long, newValue: Any?, newFailure: TrickleFailure?) {
        this.timestamp = newTimestamp
        this.value = newValue
        this.failure = newFailure
    }

    fun set(newTimestamp: Long, newValue: Any?) {
        this.timestamp = newTimestamp
        this.value = newValue
        this.failure = null
    }

    fun setFailure(newTimestamp: Long, newFailure: TrickleFailure) {
        this.timestamp = newTimestamp
        this.value = null
        this.failure = newFailure
    }

    fun getValue(): Any? {
        return value
    }

    fun getTimestamp(): Long {
        return timestamp
    }

//    fun isError(): Boolean {
//        return error != null
//    }

    fun getFailure(): TrickleFailure? {
        return failure
    }
}

// TODO: Synchronize stuff
// TODO: Add methods to ask for a given result, but only after a given timestamp (block until available?)
class TrickleInstance internal constructor(val definition: TrickleDefinition) {
    // Used to ensure all results we receive originated from this instance
    class Id internal constructor()
    private val instanceId = Id()

//    val nonkeyedNodeValues = LinkedHashMap<NodeName<*>, Any>()
//    val keyListValues = LinkedHashMap<NodeName<*>, KeyList<Any>>()
//    val keyedNodeValues = LinkedHashMap<KeyedNodeName<*, *>, LinkedHashMap<Any, Any>>()
    private val nonkeyedNodeValues: Map<NodeName<*>, TimestampedValue>

//    val nonkeyedValueTimeStamps = HashMap<NodeName<*>, Long>()

    private var curTimestamp = 0L

    init {
        val nonkeyedNodeValues = LinkedHashMap<NodeName<*>, TimestampedValue>()
        for (node in definition.nonkeyedNodes) {
            nonkeyedNodeValues[node.value.name] = TimestampedValue(-1L, null, null)
        }
        this.nonkeyedNodeValues = nonkeyedNodeValues
    }

    @Synchronized
    fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        curTimestamp++
        nonkeyedNodeValues[nodeName]!!.set(curTimestamp, value)
        return curTimestamp
    }
//    fun <T> addKey(nodeName: NodeName<T>, key: Any) {
//        val added = keyListValues[nodeName]!!.add(key)
//    }
//    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T) {
//
//    }

    /*
    Go through each node in topological order
    For each one, we should have recorded the timestamp associated with its current value

    Next we look at the node's inputs (if no inputs, it is up-to-date unless its timestamp is -1 for uninitialized)
    For each input, we want to know 1) if that input is up-to-date, and 2) if so, its timestamp
    If any input is not up-to-date, the node is not ready to be recomputed
    If all inputs are up-to-date, then the maximum of the inputs' timestamps is the desired timestamp of this node
    So either this node is that timestamp (do nothing) or it is not (create a TrickleStep for that node with that timestamp)
    Then we record that this node is up-to-date or its timestamp

    How does this differ for keylist nodes and keyed nodes? A keylist node can have multiple timestamps for its constituent
    keys; keyed nodes only look at the keyed portions of the appropriate inputs. Nothing that fundamentally alters the
    above algorithm.

    This also has the job of propagating errors into failures throughout the graph.
     */
    @Synchronized
    fun getNextSteps(): List<TrickleStep> {
        val unsetInputs = ArrayList<NodeName<*>>()
        val nextSteps = ArrayList<TrickleStep>()
        val unkeyedTimeStampIfUpToDate = HashMap<NodeName<*>, Long>()
        val unkeyedFailures = HashMap<NodeName<*>, TrickleFailure>()
        for (nodeName in definition.topologicalOrdering) {
            println("unkeyedTimeStampIfUpToDate: $unkeyedTimeStampIfUpToDate")
            println("Dealing with node name $nodeName")
            // Treat differently if keyed or unkeyed
            // Pretend this works for now
            val node = definition.nonkeyedNodes[nodeName]!!
            if (node.inputs.isEmpty()) {
                println("Inputs are empty")
                val timestamp = nonkeyedNodeValues[nodeName]?.getTimestamp() ?: -1L
                println("timestamp: ")
                if (timestamp >= 0L) {
                    unkeyedTimeStampIfUpToDate[nodeName] = timestamp
                } else {
                    unsetInputs.add(nodeName)
                }
            } else {
                var anyInputNotUpToDate = false
                var maximumInputTimestamp = -1L
                val inputValues = ArrayList<Any?>()
                val inputFailures = ArrayList<TrickleFailure>()
                for (input in node.inputs) {
                    // TODO:
                    val unkeyedInputName: NodeName<*> = getNodeFromInput(input)
                    val timeStampMaybe = unkeyedTimeStampIfUpToDate[unkeyedInputName]
                    if (timeStampMaybe == null) {
                        anyInputNotUpToDate = true
                    } else {
                        maximumInputTimestamp = Math.max(maximumInputTimestamp, timeStampMaybe)
                        val contents = nonkeyedNodeValues[unkeyedInputName]!!
                        val failure = contents.getFailure()
                        if (failure != null) {
                            inputFailures.add(failure)
                        } else {
                            inputValues.add(contents.getValue())
                        }
                    }
                }
                println("Any input not up-to-date?: $anyInputNotUpToDate")
                if (!anyInputNotUpToDate) {
                    // All inputs are up-to-date
                    if (inputFailures.isNotEmpty()) {
                        // Aggregate the failures for reporting
                        val curValueTimestamp = nonkeyedNodeValues[nodeName]?.getTimestamp()
                        if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                            val newFailure = combineFailures(inputFailures)

                            val onCatch = node.onCatch
                            if (onCatch != null) {
                                nextSteps.add(
                                    TrickleStep(nodeName, maximumInputTimestamp, instanceId, { onCatch(newFailure) })
                                )
                            } else {
                                nonkeyedNodeValues[nodeName]!!.setFailure(maximumInputTimestamp, newFailure)
                                unkeyedTimeStampIfUpToDate[nodeName] = maximumInputTimestamp
                            }
                        } else if (curValueTimestamp > maximumInputTimestamp) {
                            error("This should never happen")
                        } else {
                            unkeyedTimeStampIfUpToDate[nodeName] = maximumInputTimestamp
                        }
                        // TODO: Might need to mark this as up-to-date so catching can happen downstream
                    } else {
                        val curValueTimestamp = nonkeyedNodeValues[nodeName]?.getTimestamp()
                        if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                            // We should compute this (pass in the maximumInputTimestamp and the appropriate input values)

                            val operation = node.operation ?: error("This was supposed to be an input node, I guess")
                            println("Preparing an operation binding with input values ${inputValues}; our node has ${node.inputs.size} inputs")
                            nextSteps.add(
                                TrickleStep(
                                    nodeName,
                                    maximumInputTimestamp,
                                    instanceId,
                                    { operation(inputValues) })
                            )
                        } else if (curValueTimestamp > maximumInputTimestamp) {
                            error("This should never happen")
                        } else {
                            // Report this as being up-to-date for future things
                            unkeyedTimeStampIfUpToDate[nodeName] = curValueTimestamp
                        }
                    }
                }
            }
        }
        if (unsetInputs.isNotEmpty()) {
            throw IllegalStateException("Cannot start the operation before all inputs are set. Unset inputs: $unsetInputs")
        }
        return nextSteps
    }

    private fun combineFailures(inputFailures: ArrayList<TrickleFailure>): TrickleFailure {
        val allSources = LinkedHashMap<NodeName<*>, Throwable>()
        for (failure in inputFailures) {
            allSources.putAll(failure.errors)
        }
        return TrickleFailure(allSources)
    }

    private fun getNodeFromInput(input: TrickleInput<*>): NodeName<*> {
        if (input is TrickleNode<*>) {
            return input.name
        }
        error("Unhandled case getting node from input: ${input}")
    }

//    private fun isUpToDate(input: TrickleInput<*>): Boolean {
//
//    }

    fun completeSynchronously() {
        while (true) {
            val nextSteps = getNextSteps()
            println("Next steps: $nextSteps")
            if (nextSteps.isEmpty()) {
                return
            }
            for (step in nextSteps) {
                println("Executing step ${step.nodeName}")
                val result = step.execute()
                this.reportResult(result)
            }
        }
    }

    @Synchronized
    fun reportResult(result: TrickleStepResult) {
        if (instanceId != result.instanceId) {
            throw IllegalArgumentException("Received a result that did not originate from this instance")
        }
        if (result.timestamp > this.nonkeyedNodeValues[result.nodeName]!!.getTimestamp()) {
            val failure = result.error?.let { TrickleFailure(mapOf(result.nodeName to it)) }
            this.nonkeyedNodeValues[result.nodeName]!!.set(result.timestamp, result.result, failure)
        }
//        this.nonkeyedValueTimeStamps[result.nodeName] = result.maximumInputTimestamp
    }

    @Synchronized
    fun <T> getNodeValue(nodeName: NodeName<T>): T {
//        return nonkeyedNodeValues[nodeName]?.getValue() as? T
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    @Synchronized
    fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        val value = nonkeyedNodeValues[nodeName]
        if (value == null) {
            return NodeOutcome.NotYetComputed as NodeOutcome<T>
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure<T>(failure)
        }
        return NodeOutcome.Computed<T>(value.getValue() as T)
    }
}

sealed class NodeOutcome<T> {
    object NotYetComputed: NodeOutcome<Any>()
    data class Computed<T>(val value: T): NodeOutcome<T>()
    data class Failure<T>(val failure: TrickleFailure): NodeOutcome<T>()
}

class TrickleStep internal constructor(
    val nodeName: NodeName<*>,
    val timestamp: Long,
    val instanceId: TrickleInstance.Id,
    val operation: () -> Any?
) {
    fun execute(): TrickleStepResult {
        try {
            val result = operation()
            return TrickleStepResult(nodeName, timestamp, instanceId, result, null)
        } catch (t: Throwable) {
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return TrickleStepResult(nodeName, timestamp, instanceId, null, t)
        }
    }

    override fun toString(): String {
        return "TrickleStep($nodeName, $timestamp)"
    }
}

class TrickleStepResult internal constructor(
    val nodeName: NodeName<*>,
    val timestamp: Long,
    val instanceId: TrickleInstance.Id,
    val result: Any?,
    val error: Throwable?
) {

}

class TrickleDefinition internal constructor(val nonkeyedNodes: Map<NodeName<*>, TrickleNode<*>>,
//                        val keyListNodes: Map<NodeName<*>, TrickleKeyListNode<*>>,
                        val topologicalOrdering: List<NodeName<*>>) {
    fun instantiate(): TrickleInstance {
        return TrickleInstance(this)
    }
}
class TrickleDefinitionBuilder {
    private val nodes = HashMap<NodeName<*>, TrickleNode<*>>()
    private val topologicalOrdering = ArrayList<NodeName<*>>()
//    private val keyListNodes = HashMap<NodeName<*>, TrickleKeyListNode<*>>()
//    private val keyedNodes = HashMap<KeyedNodeName<*, *>, TrickleKeyedNode<*>>()

    // Used to ensure all node inputs we receive originated from this builder
    class Id internal constructor()
    private val builderId = Id()

    fun <T> createInputNode(name: NodeName<T>): TrickleNode<T> {
        checkNameNotUsed(name)
        val node = TrickleNode<T>(name, builderId, listOf(), null, null)
        nodes[name] = node
        topologicalOrdering.add(name)
        return node
    }

    private fun checkNameNotUsed(name: NodeName<*>) {
        if (nodes.containsKey(name) /*|| keyListNodes.containsKey(name) || keyedNodes.containsKey(name)*/) {
            error("Cannot create two nodes with the same name '$name'")
        }
    }

    fun <T, I1> createNode(name: NodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleNode<T> {
        return createNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
//    fun <T, I1, I2> createNode(name: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (I1, I2) -> T): TrickleNode<T> {
//        return createNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, null)
//    }
    fun <T, I1, I2> createNode(name: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (I1, I2) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleNode<T> {
        return createNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, onCatch)
    }
    fun <T> createNode(name: NodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> T, onCatch: ((TrickleFailure) -> T)?): TrickleNode<T> {
        checkNameNotUsed(name)
        validateBuilderIds(inputs)
        val node = TrickleNode<T>(name, builderId, inputs, fn, onCatch)
        nodes[name] = node
        topologicalOrdering.add(name)
        return node
    }

    private fun validateBuilderIds(inputs: List<TrickleInput<*>>) {
        for (input in inputs) {
            if (builderId != input.builderId) {
                throw IllegalArgumentException("Cannot reuse nodes or inputs across builders")
            }
        }
    }

//    fun <T> createKeyListInputNode(name: NodeName<T>): TrickleKeyListNode<T> {
//        checkNameNotUsed(name)
//
//        val node = TrickleKeyListNode<T>(name)
//        keyListNodes[name] = node
//        return node
//    }
//
//    fun <T> createKeyedInputNode(name: KeyedNodeName<*, T>, keySource: TrickleKeyListNode<*>): TrickleKeyedNode<T> {
//        checkNameNotUsed(name)
//
//        val node = TrickleKeyedNode<T>()
//        keyedNodes[name] = node
//        return node
//    }
//    fun <T, K> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleKeyListNode<K>, fn: (K) -> T): TrickleKeyedNode<T> {
//        return createKeyedNode(name, keySource, listOf(), { key, list -> fn(key) })
//    }
//    fun <T, K, I1> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleKeyListNode<K>, input1: TrickleInput<I1>, fn: (K, I1) -> T): TrickleKeyedNode<T> {
//        return createKeyedNode(name, keySource, listOf(input1), { key, list -> fn(key, list[0] as I1) })
//    }
//    fun <T, K, I1, I2> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleKeyListNode<K>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (K, I1, I2) -> T): TrickleKeyedNode<T> {
//        return createKeyedNode(name, keySource, listOf(input1, input2), { key, list -> fn(key, list[0] as I1, list[1] as I2) })
//    }
//    fun <T, K> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleKeyListNode<K>, inputs: List<TrickleInput<*>>, fn: (K, List<*>) -> T): TrickleKeyedNode<T> {
//        checkNameNotUsed(name)
//        val node = TrickleKeyedNode<T>()
//        keyedNodes[name] = node
//        return node
//    }

    fun build(): TrickleDefinition {
        return TrickleDefinition(nodes, topologicalOrdering)
    }

//    fun <T, I1> createCatchNode(nodeName: NodeName<T>, input1: TrickleInput<I1>, handleSuccess: (I1) -> T, handleError: (TrickleError) -> T): TrickleNode<T> {
//
//    }
//    fun <T, I1, I2> createCatchNode(nodeName: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, handleSuccess: (I1, I2) -> T, handleError: (TrickleError) -> T): TrickleNode<T> {
//
//    }

}

interface TrickleInput<T> {
    val builderId: TrickleDefinitionBuilder.Id
}

class TrickleFailure(val errors: Map<NodeName<*>, Throwable>) {

}

class TrickleNode<T> internal constructor(
    val name: NodeName<T>,
    override val builderId: TrickleDefinitionBuilder.Id,
    val inputs: List<TrickleInput<*>>,
    val operation: ((List<*>) -> T)?,
    val onCatch: ((TrickleFailure) -> T)?
): TrickleInput<T> {
}

//class TrickleKeyListNode<T>(
//    val name: NodeName<T>
//)

// TODO: This might want K in the type parameters
//class TrickleKeyedNode<T> {
//    // TODO: These should probably be getter-based?
//    fun keyedOutput(): TrickleInput<T> {
//    }
//    fun fullOutput(): TrickleInput<List<T>> {
//    }
//}

