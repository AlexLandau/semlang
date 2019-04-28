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

// TODO: Should catch methods also catch errors in the same node, vs. upstream nodes? Should outputs of parent nodes
// be added to their inputs?

// TODO: Specialty API for synchronous use, with getters for outputs that trigger evaluation of the requested nodes lazily

// TODO: APIs for asynchronous use, with listeners

class NodeName<T>(val name: String) {
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
class KeyedNodeName<K, T>(val name: String)//: NodeName<T>(name)
class KeyListNodeName<T>(val name: String)//: NodeName<List<T>>(name)

internal sealed class AnyNodeName {
    data class Basic(val name: NodeName<*>): AnyNodeName()
    data class KeyList(val name: KeyListNodeName<*>): AnyNodeName()
}

private class KeyList<T> private constructor(val list: List<T>, val set: Set<T>) {
    companion object {
        fun <T> copyOf(collection: Collection<T>): KeyList<T> {
            val list = ArrayList<T>()
            val set = HashSet<T>()
            for (item in collection) {
                if (!set.contains(item)) {
                    list.add(item)
                    set.add(item)
                }
            }
            return KeyList(list, set)
        }
        fun <T> empty(): KeyList<T> {
            return KeyList(emptyList(), emptySet())
        }
    }

    fun add(key: T): KeyList<T> {
        if (set.contains(key)) {
            return this
        }

        val listCopy = ArrayList<T>(list.size + 1)
        listCopy.addAll(list)
        listCopy.add(key)
        val setCopy = HashSet<T>(set.size + 1)
        setCopy.addAll(set)
        setCopy.add(key)
        return KeyList(listCopy, setCopy)
    }

    fun remove(key: T): KeyList<T> {
        if (!set.contains(key)) {
            return this
        }

        val listCopy = ArrayList<T>(list.size - 1)
        val setCopy = HashSet<T>(set.size - 1)
        for (element in list) {
            if (element != key) {
                listCopy.add(element)
                setCopy.add(element)
            }
        }
        return KeyList(listCopy, setCopy)
    }

    // Used to expose to users; TODO: but maybe we should also expose Set.contains somehow? Return a Collection or an immutable KeyList (vs. internal MutableKeyList)?
    // TODO: Should probably just do this at creation time...
    fun asList(): List<T> {
        return Collections.unmodifiableList(list)
    }
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

    fun getFailure(): TrickleFailure? {
        return failure
    }
}

// TODO: Internal or retain as public?
sealed class ValueId {
    data class Nonkeyed(val nodeName: NodeName<*>): ValueId()
    data class FullKeyList(val nodeName: KeyListNodeName<*>): ValueId()
}

// TODO: Synchronize stuff
// TODO: Add methods to ask for a given result, but only after a given timestamp (block until available?)
class TrickleInstance internal constructor(val definition: TrickleDefinition) {
    // Used to ensure all results we receive originated from this instance
    class Id internal constructor()
    private val instanceId = Id()

//    private val nonkeyedNodeValues: Map<NodeName<*>, TimestampedValue>
    private val values = LinkedHashMap<ValueId, TimestampedValue>()

    private var curTimestamp = 0L

    init {
//        val nonkeyedNodeValues = LinkedHashMap<NodeName<*>, TimestampedValue>()
//        for (node in definition.nonkeyedNodes) {
//            nonkeyedNodeValues[node.value.name] = TimestampedValue(-1L, null, null)
//        }
//        this.nonkeyedNodeValues = nonkeyedNodeValues

        // Key list input nodes start out as empty lists
        for (keyListNode in definition.keyListNodes.values) {
            if (keyListNode.inputs.isEmpty()) {
                setValue(ValueId.FullKeyList(keyListNode.name), 0L, KeyList.empty<Any>(), null)
            }
        }
    }

    @Synchronized
    private fun setValue(valueId: ValueId, newTimestamp: Long, newValue: Any?, newFailure: TrickleFailure?) {
        var valueHolder = values[valueId]
        if (valueHolder == null) {
            valueHolder = TimestampedValue(-1L, null, null)
            values[valueId] = valueHolder
        }
        valueHolder.set(newTimestamp, newValue, newFailure)
    }

    // TODO: Validate that the nodes in question are actually input nodes
    @Synchronized
    fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        curTimestamp++
        setValue(ValueId.Nonkeyed(nodeName), curTimestamp, value, null)
        return curTimestamp
    }
    // TODO: Validate that the nodes in question are actually input nodes
    @Synchronized
    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        curTimestamp++
        val keyList = KeyList.copyOf(list)
        setValue(ValueId.FullKeyList(nodeName), curTimestamp, keyList, null)
        return curTimestamp
    }


    // TODO: Validate that the nodes in question are actually input nodes
    // TODO: Don't bump the timestamp if nothing changed (e.g. key already existed)
    @Synchronized
    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        curTimestamp++
        val listValueId = ValueId.FullKeyList(nodeName)
        // TODO: This could be a problem when it comes to input types...
        val oldList = values[listValueId]!!.getValue() as KeyList<T> //?: KeyList<T>()
        val newList = oldList.add(key)
        setValue(listValueId, curTimestamp, newList, null)
        return curTimestamp
    }
    // TODO: Validate that the nodes in question are actually input nodes
    // TODO: Don't bump the timestamp if nothing changed (e.g. key did not exist)
    @Synchronized
    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        curTimestamp++
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T> //?: KeyList<T>()
        val newList = oldList.remove(key)
        setValue(listValueId, curTimestamp, newList, null)
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
        val timeStampIfUpToDate = HashMap<ValueId, Long>()
        for (anyNodeName in definition.topologicalOrdering) {
            when (anyNodeName) {
                is AnyNodeName.Basic -> {
                    val nodeName = anyNodeName.name
                    val node = definition.nonkeyedNodes[nodeName]!!
                    val nodeValueId = ValueId.Nonkeyed(nodeName)
                    if (node.inputs.isEmpty()) {
                        val timestamp = values[nodeValueId]?.getTimestamp() ?: -1L
                        if (timestamp >= 0L) {
                            timeStampIfUpToDate[nodeValueId] = timestamp
                        } else {
                            unsetInputs.add(nodeName)
                        }
                    } else {
                        var anyInputNotUpToDate = false
                        var maximumInputTimestamp = -1L
                        val inputValues = ArrayList<Any?>()
                        val inputFailures = ArrayList<TrickleFailure>()
                        for (input in node.inputs) {
                            val unkeyedInputValueId = getValueIdFromInput(input)
                            val timeStampMaybe = timeStampIfUpToDate[unkeyedInputValueId]
                            if (timeStampMaybe == null) {
                                anyInputNotUpToDate = true
                            } else {
                                maximumInputTimestamp = Math.max(maximumInputTimestamp, timeStampMaybe)
                                val contents = values[unkeyedInputValueId]!!
                                val failure = contents.getFailure()
                                if (failure != null) {
                                    inputFailures.add(failure)
                                } else {
                                    // Transform KeyLists into Lists
                                    if (input is TrickleInput.KeyList<*>) {
                                        inputValues.add((contents.getValue() as KeyList<*>).asList())
                                    } else {
                                        inputValues.add(contents.getValue())
                                    }
                                }
                            }
                        }
                        if (!anyInputNotUpToDate) {
                            // All inputs are up-to-date
                            if (inputFailures.isNotEmpty()) {
                                // Aggregate the failures for reporting
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    val newFailure = combineFailures(inputFailures)

                                    val onCatch = node.onCatch
                                    if (onCatch != null) {
                                        nextSteps.add(
                                            TrickleStep(nodeValueId, maximumInputTimestamp, instanceId, { onCatch(newFailure) })
                                        )
                                    } else {
                                        setValue(nodeValueId, maximumInputTimestamp, null, newFailure)
        //                                values[ValueId.Nonkeyed(nodeName)]!!.setFailure(maximumInputTimestamp, newFailure)
                                        timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                    }
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                }
                            } else {
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    // We should compute this (pass in the maximumInputTimestamp and the appropriate input values)

                                    val operation = node.operation ?: error("This was supposed to be an input node, I guess")
                                    nextSteps.add(
                                        TrickleStep(
                                            nodeValueId,
                                            maximumInputTimestamp,
                                            instanceId,
                                            { operation(inputValues) })
                                    )
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    // Report this as being up-to-date for future things
                                    timeStampIfUpToDate[nodeValueId] = curValueTimestamp
                                }
                            }
                        }
                    }
                }
                is AnyNodeName.KeyList -> {
                    val nodeName = anyNodeName.name
                    val node = definition.keyListNodes[nodeName]!!
                    val nodeValueId = ValueId.FullKeyList(nodeName)
                    if (node.inputs.isEmpty()) {
                        val timestamp = values[nodeValueId]?.getTimestamp() ?: -1L
                        if (timestamp >= 0L) {
                            timeStampIfUpToDate[nodeValueId] = timestamp
                        } else {
                            // Key lists are initialized to be empty
                            error("Key lists should have been initialized to be empty")
//                            unsetInputs.add(nodeName)
                        }
                    } else {
                        var anyInputNotUpToDate = false
                        var maximumInputTimestamp = -1L
                        val inputValues = ArrayList<Any?>()
                        val inputFailures = ArrayList<TrickleFailure>()
                        for (input in node.inputs) {
                            val unkeyedInputValueId = getValueIdFromInput(input)
                            val timeStampMaybe = timeStampIfUpToDate[unkeyedInputValueId]
                            if (timeStampMaybe == null) {
                                anyInputNotUpToDate = true
                            } else {
                                maximumInputTimestamp = Math.max(maximumInputTimestamp, timeStampMaybe)
                                val contents = values[unkeyedInputValueId]!!
                                val failure = contents.getFailure()
                                if (failure != null) {
                                    inputFailures.add(failure)
                                } else {
                                    // Transform KeyLists into Lists
                                    if (input is TrickleInput.KeyList<*>) {
                                        inputValues.add((contents.getValue() as KeyList<*>).asList())
                                    } else {
                                        inputValues.add(contents.getValue())
                                    }
                                }
                            }
                        }
                        if (!anyInputNotUpToDate) {
                            // All inputs are up-to-date
                            if (inputFailures.isNotEmpty()) {
                                // Aggregate the failures for reporting
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    val newFailure = combineFailures(inputFailures)

                                    val onCatch = node.onCatch
                                    if (onCatch != null) {
                                        nextSteps.add(
                                            TrickleStep(nodeValueId, maximumInputTimestamp, instanceId, { onCatch(newFailure) })
                                        )
                                    } else {
                                        setValue(nodeValueId, maximumInputTimestamp, null, newFailure)
                                        //                                values[ValueId.Nonkeyed(nodeName)]!!.setFailure(maximumInputTimestamp, newFailure)
                                        timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                    }
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                }
                            } else {
                                val curValueTimestamp = values[nodeValueId]?.getTimestamp()
                                if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                    // We should compute this (pass in the maximumInputTimestamp and the appropriate input values)

                                    val operation = node.operation ?: error("This was supposed to be an input node, I guess")
                                    nextSteps.add(
                                        TrickleStep(
                                            nodeValueId,
                                            maximumInputTimestamp,
                                            instanceId,
                                            { operation(inputValues) })
                                    )
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    // Report this as being up-to-date for future things
                                    timeStampIfUpToDate[nodeValueId] = curValueTimestamp
                                }
                            }
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
        val allSources = LinkedHashMap<ValueId, Throwable>()
        for (failure in inputFailures) {
            allSources.putAll(failure.errors)
        }
        return TrickleFailure(allSources)
    }

    // TODO: This should probably just be a function on TrickleInput
    private fun getValueIdFromInput(input: TrickleInput<*>): ValueId {
        if (input is TrickleNode<*>) {
            return ValueId.Nonkeyed(input.name)
        }
        if (input is TrickleInput.KeyList<*>) {
            return ValueId.FullKeyList(input.name)
        }
        error("Unhandled case getting node from input: ${input}")
    }

    fun completeSynchronously() {
        while (true) {
            val nextSteps = getNextSteps()
            if (nextSteps.isEmpty()) {
                return
            }
            for (step in nextSteps) {
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
        val valueHolder = values[result.valueId]
        if (valueHolder == null || result.timestamp > valueHolder.getTimestamp()) {
            val failure = result.error?.let { TrickleFailure(mapOf(result.valueId to it)) }
            val resultValue = if (result.valueId is ValueId.FullKeyList) {
                if (result.result == null) {
                    result.result
                } else {
                    KeyList.copyOf(result.result as List<*>)
                }
            } else {
                result.result
            }
            setValue(result.valueId, result.timestamp, resultValue, failure)
//            this.values[ValueId.Nonkeyed(result.nodeName)]!!.set(result.timestamp, result.result, failure)
        }
        // TODO: For key list nodes, also adjust the values/timestamps of the individual nodes (?)
        // TODO: For keyed nodes, also adjust the values/timestamps of the whole list
    }

    @Synchronized
    fun <T> getNodeValue(nodeName: NodeName<T>): T {
        val outcome = getNodeOutcome(nodeName)
        when (outcome) {
            is NodeOutcome.Computed -> {
                return outcome.value
            }
            NodeOutcome.NotYetComputed -> {
                throw IllegalStateException("Value for node $nodeName has not yet been computed")
            }
            is NodeOutcome.Failure -> {
                outcome.failure.errors.values
//                val e = IllegalStateException()
//                e.addSuppressed()
                val exception = IllegalStateException("Value for node $nodeName was not computed successfully: ${outcome.failure}")
                for (e in outcome.failure.errors.values) {
                    exception.addSuppressed(e)
                }
                throw exception
            }
        }
//        error("Value for node $nodeName was not computed successfully: $outcome")
//        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    @Synchronized
    fun <T> getNodeValue(nodeName: KeyListNodeName<T>): List<T> {
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    @Synchronized
    fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        if (!definition.nonkeyedNodes.containsKey(nodeName)) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val value = values[ValueId.Nonkeyed(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed as NodeOutcome<T>
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure<T>(failure)
        }
        return NodeOutcome.Computed<T>(value.getValue() as T)
    }

    @Synchronized
    fun <T> getNodeOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>> {
        if (!definition.keyListNodes.containsKey(nodeName)) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val value = values[ValueId.FullKeyList(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed as NodeOutcome<List<T>>
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure<List<T>>(failure)
        }
        var computedValue = value.getValue()
        if (computedValue is KeyList<*>) {
            computedValue = computedValue.asList()
        }
        return NodeOutcome.Computed<List<T>>((value.getValue() as KeyList<T>).asList())
    }
}

sealed class NodeOutcome<T> {
    object NotYetComputed: NodeOutcome<Any>()
    data class Computed<T>(val value: T): NodeOutcome<T>()
    data class Failure<T>(val failure: TrickleFailure): NodeOutcome<T>()
}

class TrickleStep internal constructor(
    val valueId: ValueId,
    val timestamp: Long,
    val instanceId: TrickleInstance.Id,
    val operation: () -> Any?
) {
    fun execute(): TrickleStepResult {
        try {
            val result = operation()
            return TrickleStepResult(valueId, timestamp, instanceId, result, null)
        } catch (t: Throwable) {
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return TrickleStepResult(valueId, timestamp, instanceId, null, t)
        }
    }

    override fun toString(): String {
        return "TrickleStep($valueId, $timestamp)"
    }

    fun isNode(name: NodeName<*>): Boolean {
        return valueId is ValueId.Nonkeyed && valueId.nodeName == name
    }
}

class TrickleStepResult internal constructor(
    val valueId: ValueId,
    val timestamp: Long,
    val instanceId: TrickleInstance.Id,
    val result: Any?,
    val error: Throwable?
) {

}

class TrickleDefinition internal constructor(internal val nonkeyedNodes: Map<NodeName<*>, TrickleNode<*>>,
                                             internal val keyListNodes: Map<KeyListNodeName<*>, TrickleKeyListNode<*>>,
                                             internal val topologicalOrdering: List<AnyNodeName>) {
    fun instantiate(): TrickleInstance {
        return TrickleInstance(this)
    }
}
class TrickleDefinitionBuilder {
    private val usedNodeNames = HashSet<String>()
    private val nodes = HashMap<NodeName<*>, TrickleNode<*>>()
    // TODO: Give this its own sealed class covering all the node name types
    private val topologicalOrdering = ArrayList<AnyNodeName>()
    private val keyListNodes = HashMap<KeyListNodeName<*>, TrickleKeyListNode<*>>()
//    private val keyedNodes = HashMap<KeyedNodeName<*, *>, TrickleKeyedNode<*>>()

    // Used to ensure all node inputs we receive originated from this builder
    class Id internal constructor()
    private val builderId = Id()

    fun <T> createInputNode(name: NodeName<T>): TrickleNode<T> {
        checkNameNotUsed(name.name)
        val node = TrickleNode<T>(name, builderId, listOf(), null, null)
        nodes[name] = node
        topologicalOrdering.add(AnyNodeName.Basic(name))
        return node
    }

    private fun checkNameNotUsed(name: String) {
        if (usedNodeNames.contains(name)) {
            error("Cannot create two nodes with the same name '$name'")
        }
        usedNodeNames.add(name)
    }

    fun <T, I1> createNode(name: NodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleNode<T> {
        return createNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T, I1, I2> createNode(name: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (I1, I2) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleNode<T> {
        return createNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, onCatch)
    }
    fun <T> createNode(name: NodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> T, onCatch: ((TrickleFailure) -> T)?): TrickleNode<T> {
        if (inputs.isEmpty()) {
            // TODO: We might want to allow this as long as fn and onCatch are null
            error("Use createInputNode to create a node with no inputs.")
        }
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = TrickleNode<T>(name, builderId, inputs, fn, onCatch)
        nodes[name] = node
        topologicalOrdering.add(AnyNodeName.Basic(name))
        return node
    }

    private fun validateBuilderIds(inputs: List<TrickleInput<*>>) {
        for (input in inputs) {
            if (builderId != input.builderId) {
                throw IllegalArgumentException("Cannot reuse nodes or inputs across builders")
            }
        }
    }

    fun <T> createKeyListInputNode(name: KeyListNodeName<T>): TrickleKeyListNode<T> {
        checkNameNotUsed(name.name)

        val node = TrickleKeyListNode<T>(name, builderId, listOf(), null, null)
        keyListNodes[name] = node
        topologicalOrdering.add(AnyNodeName.KeyList(name))
        return node
    }
    fun <T, I1> createKeyListNode(name: KeyListNodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> List<T>, onCatch: ((TrickleFailure) -> List<T>)? = null): TrickleKeyListNode<T> {
        return createKeyListNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T> createKeyListNode(name: KeyListNodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> List<T>, onCatch: ((TrickleFailure) -> List<T>)?): TrickleKeyListNode<T> {
        if (inputs.isEmpty()) {
            // TODO: We might want to allow this as long as fn and onCatch are null
            error("Use createInputNode to create a node with no inputs.")
        }
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = TrickleKeyListNode<T>(name, builderId, inputs, fn, onCatch)
//        val node = TrickleNode<T>(name, builderId, inputs, fn, onCatch)
        keyListNodes[name] = node
        topologicalOrdering.add(AnyNodeName.KeyList(name))
        return node
    }

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
        return TrickleDefinition(nodes, keyListNodes, topologicalOrdering)
    }

//    fun <T, I1> createCatchNode(nodeName: NodeName<T>, input1: TrickleInput<I1>, handleSuccess: (I1) -> T, handleError: (TrickleError) -> T): TrickleNode<T> {
//
//    }
//    fun <T, I1, I2> createCatchNode(nodeName: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, handleSuccess: (I1, I2) -> T, handleError: (TrickleError) -> T): TrickleNode<T> {
//
//    }

}

sealed class TrickleInput<T> {
    abstract val builderId: TrickleDefinitionBuilder.Id
    data class KeyList<T>(val name: KeyListNodeName<T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<List<T>>()
}

data class TrickleFailure(val errors: Map<ValueId, Throwable>) {

}

class TrickleNode<T> internal constructor(
    val name: NodeName<T>,
    override val builderId: TrickleDefinitionBuilder.Id,
    val inputs: List<TrickleInput<*>>,
    val operation: ((List<*>) -> T)?,
    val onCatch: ((TrickleFailure) -> T)?
): TrickleInput<T>() {
}

class TrickleKeyListNode<T>(
    val name: KeyListNodeName<T>,
    val builderId: TrickleDefinitionBuilder.Id,
    val inputs: List<TrickleInput<*>>,
    val operation: ((List<*>) -> List<T>)?,
    val onCatch: ((TrickleFailure) -> List<T>)?
) {
    fun listOutput(): TrickleInput<List<T>> {
        return TrickleInput.KeyList<T>(name, builderId)
    }
}

// TODO: This might want K in the type parameters
//class TrickleKeyedNode<T> {
//    // TODO: These should probably be getter-based?
//    fun keyedOutput(): TrickleInput<T> {
//    }
//    fun fullOutput(): TrickleInput<List<T>> {
//    }
//}

