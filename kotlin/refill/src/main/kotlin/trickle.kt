package net.semlang.modules

import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.*
import java.util.function.Predicate

/*
 * Trickle: A Kotlin/JVM library (...if I choose to extract it) for defining asynchronous tasks in terms of each
 * other, going beyond RxJava by adding support for minimal recomputation when portions of the input change.
 *
 * There's already a Java library named Trickle, so the name is going to change.
 */

// TODO: Should catch methods also catch errors in the same node, vs. upstream nodes? Should outputs of parent nodes
// be added to their inputs?

// TODO: APIs for asynchronous use, with listeners and/or "wait for the version of this that is up-to-date as of this timestamp"

// TODO: Currently this hangs on to references of keyed values associated with keys that have been removed; finding a way
// to avoid that would be good

// TODO: It might be nice to be able to compose keylists within keylists, on the general principle that composability is good
// (Say e.g. one keylist represents directories, a composed keylist could represent a list of files within each of those
// directories; and then as opposed to a flat keylist of all files, this would allow per-file computations that could then
// be filtered back into a per-directory view before being brought back to a global list.)

// TODO: Add the ability to make multiple input changes with a single timestamp, e.g. adding both a key and a keyed input
// based on that key

// TODO: Probably out of scope, but I think we could get typings on the input setters if code were either generated from
// a spec or based on an annotation processor

// TODO: Should we use names as the arguments to builders instead of the outputs of previous builder calls? Still use
// the topological-ordering-as-you-go, but rely on runtime checks of inputs instead of compile-time?

sealed class GenericNodeName
class NodeName<T>(val name: String): GenericNodeName() {
    override fun toString(): String {
        return name
    }
}
class KeyListNodeName<T>(val name: String): GenericNodeName() {
    override fun toString(): String {
        return name
    }
}
class KeyedNodeName<K, T>(val name: String): GenericNodeName() {
    override fun toString(): String {
        return name
    }
}


// TODO: Internal or retain as public?
sealed class ValueId {
    data class Nonkeyed(val nodeName: NodeName<*>): ValueId()
    data class FullKeyList(val nodeName: KeyListNodeName<*>): ValueId()
    // Note: Stored values associated with KeyListKeys are booleans
    data class KeyListKey(val nodeName: KeyListNodeName<*>, val key: Any?): ValueId()
    data class Keyed(val nodeName: KeyedNodeName<*, *>, val key: Any?): ValueId()
    data class FullKeyedList(val nodeName: KeyedNodeName<*, *>): ValueId()
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

    fun contains(key: T): Boolean {
        return set.contains(key)
    }

    override fun toString(): String {
        return list.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (other is KeyList<*>) {
            return list.equals(other.list)
        } else {
            return false
        }
    }

    override fun hashCode(): Int {
        return list.hashCode()
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

interface TrickleInputReceiver {
    fun setInputs(changes: List<TrickleInputChange>): Long
    fun <T> setInput(nodeName: NodeName<T>, value: T): Long
    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long
    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long
}

sealed class TrickleInputChange {
    data class SetBasic<T>(val nodeName: NodeName<T>, val value: T): TrickleInputChange()
    data class SetKeys<T>(val nodeName: KeyListNodeName<T>, val value: List<T>): TrickleInputChange()
    data class AddKey<T>(val nodeName: KeyListNodeName<T>, val key: T): TrickleInputChange()
    data class RemoveKey<T>(val nodeName: KeyListNodeName<T>, val key: T): TrickleInputChange()
    data class SetKeyed<K, T>(val nodeName: KeyedNodeName<K, T>, val key: K, val value: T): TrickleInputChange()
}

// TODO: Synchronize stuff
// TODO: Add methods to ask for a given result, but only after a given timestamp (block until available?)
class TrickleInstance internal constructor(val definition: TrickleDefinition): TrickleInputReceiver {
    // Used to ensure all results we receive originated from this instance
    class Id internal constructor()
    private val instanceId = Id()

//    private val nonkeyedNodeValues: Map<NodeName<*>, TimestampedValue>
    private val values = LinkedHashMap<ValueId, TimestampedValue>()

    private var curTimestamp = 0L

    init {
        for (unkeyedNode in definition.nonkeyedNodes.values) {
            if (unkeyedNode.operation == null) {
                val valueId = ValueId.Nonkeyed(unkeyedNode.name)
                setValue(valueId, 0L, null, TrickleFailure(mapOf(), setOf(valueId)))
            }
        }
        // Key list input nodes start out as empty lists
        for (keyListNode in definition.keyListNodes.values) {
            if (keyListNode.operation == null) {
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

    @Synchronized
    override fun setInputs(changes: List<TrickleInputChange>): Long {
        for (change in changes) {
            checkHasValidInputNode(change)
        }

        val prospectiveNewTimestamp = curTimestamp + 1
        var somethingChanged = false
        for (change in changes) {
            somethingChanged = applyChange(change, prospectiveNewTimestamp) || somethingChanged
        }
        if (somethingChanged) {
            curTimestamp = prospectiveNewTimestamp
        }
        return curTimestamp
    }

    /**
     * Returns true iff something changed. (This may be the case if, e.g., a key is added to a key list that is already
     * in the list.)
     */
    private fun applyChange(change: TrickleInputChange, newTimestamp: Long): Boolean {
        return when (change) {
            is TrickleInputChange.SetBasic<*> -> applySetBasicChange(change, newTimestamp)
            is TrickleInputChange.SetKeys<*> -> applySetKeysChange(change, newTimestamp)
            is TrickleInputChange.AddKey<*> -> applyAddKeyChange(change, newTimestamp)
            is TrickleInputChange.RemoveKey<*> -> applyRemoveKeyChange(change, newTimestamp)
            is TrickleInputChange.SetKeyed<*, *> -> applySetKeyedChange(change, newTimestamp)
        }
    }

    private fun checkHasValidInputNode(change: TrickleInputChange) {
        val unused = when (change) {
            is TrickleInputChange.SetBasic<*> -> {
                val node = definition.nonkeyedNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
                }
                null
            }
            is TrickleInputChange.SetKeys<*> -> {
                val node = definition.keyListNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
                }
                null
            }
            is TrickleInputChange.AddKey<*> -> {
                val node = definition.keyListNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
                }
                null
            }
            is TrickleInputChange.RemoveKey<*> -> {
                val node = definition.keyListNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
                }
                null
            }
            is TrickleInputChange.SetKeyed<*, *> -> {
                val node = definition.keyedNodes[change.nodeName]
                // TODO: Write tests for these error cases
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly modify the value of a non-input node ${change.nodeName}")
                }
                null
            }
        }
    }

    // TODO: Switch these over to calling the function for a list of changes to reduce duplicate code paths
    @Synchronized
    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        val node = definition.nonkeyedNodes[nodeName]
        if (node == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        if (node.operation != null) {
            throw IllegalArgumentException("Cannot directly set the value of a non-input node $nodeName")
        }
        curTimestamp++
        setValue(ValueId.Nonkeyed(nodeName), curTimestamp, value, null)
        return curTimestamp
    }

    // TODO: Currently we don't check equality here but we may want to in the future
    private fun <T> applySetBasicChange(change: TrickleInputChange.SetBasic<T>, newTimestamp: Long): Boolean {
        setValue(ValueId.Nonkeyed(change.nodeName), newTimestamp, change.value, null)
        return true
    }

    @Synchronized
    override fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        val node = definition.keyListNodes[nodeName]
        if (node == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        if (node.operation != null) {
            throw IllegalArgumentException("Cannot directly set the value of a non-input node $nodeName")
        }
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>
        val newList = KeyList.copyOf(list)

        if (oldList.list != newList.list) {
            curTimestamp++
            setValue(listValueId, curTimestamp, newList, null)

            val additions = HashSet(newList.set)
            additions.removeAll(oldList.set)
            val removals = HashSet(oldList.set)
            removals.removeAll(newList.set)

            for (addition in additions) {
                val keyValueId = ValueId.KeyListKey(nodeName, addition)
                setValue(keyValueId, curTimestamp, true, null)
            }
            for (removal in removals) {
                val keyValueId = ValueId.KeyListKey(nodeName, removal)
                setValue(keyValueId, curTimestamp, false, null)
            }
        }
        return curTimestamp
    }

    private fun <T> applySetKeysChange(change: TrickleInputChange.SetKeys<T>, newTimestamp: Long): Boolean {
        val (nodeName, value) = change
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>
        val newList = KeyList.copyOf(value)

        if (oldList.list != newList.list) {
            setValue(listValueId, newTimestamp, newList, null)

            val additions = HashSet(newList.set)
            additions.removeAll(oldList.set)
            val removals = HashSet(oldList.set)
            removals.removeAll(newList.set)

            for (addition in additions) {
                val keyValueId = ValueId.KeyListKey(nodeName, addition)
                setValue(keyValueId, newTimestamp, true, null)
            }
            for (removal in removals) {
                val keyValueId = ValueId.KeyListKey(nodeName, removal)
                setValue(keyValueId, newTimestamp, false, null)
            }
            return true
        }
        return false
    }

    @Synchronized
    override fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        val node = definition.keyListNodes[nodeName]
        if (node == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        if (node.operation != null) {
            throw IllegalArgumentException("Cannot directly modify the value of a non-input node $nodeName")
        }
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>
        if (!oldList.contains(key)) {
            curTimestamp++
            val newList = oldList.add(key)
            setValue(listValueId, curTimestamp, newList, null)

            val keyValueId = ValueId.KeyListKey(nodeName, key)
            setValue(keyValueId, curTimestamp, true, null)
        }
        return curTimestamp
    }

    private fun <T> applyAddKeyChange(change: TrickleInputChange.AddKey<T>, newTimestamp: Long): Boolean {
        val (nodeName, key) = change
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>
        if (!oldList.contains(key)) {
            val newList = oldList.add(key)
            setValue(listValueId, newTimestamp, newList, null)

            val keyValueId = ValueId.KeyListKey(nodeName, key)
            setValue(keyValueId, newTimestamp, true, null)
            return true
        }
        return false
    }

    @Synchronized
    override fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        val node = definition.keyListNodes[nodeName]
        if (node == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        if (node.operation != null) {
            throw IllegalArgumentException("Cannot directly modify the value of a non-input node $nodeName")
        }
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>
        if (oldList.contains(key)) {
            curTimestamp++
            val newList = oldList.remove(key)
            setValue(listValueId, curTimestamp, newList, null)

            val keyValueId = ValueId.KeyListKey(nodeName, key)
            setValue(keyValueId, curTimestamp, false, null)
        }
        return curTimestamp
    }

    private fun <T> applyRemoveKeyChange(change: TrickleInputChange.RemoveKey<T>, newTimestamp: Long): Boolean {
        val (nodeName, key) = change
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>
        if (oldList.contains(key)) {
            val newList = oldList.remove(key)
            setValue(listValueId, newTimestamp, newList, null)

            val keyValueId = ValueId.KeyListKey(nodeName, key)
            setValue(keyValueId, newTimestamp, false, null)
            return true
        }
        return false
    }

    @Synchronized
    override fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long {
        val node = definition.keyedNodes[nodeName]
        // TODO: Write tests for these error cases
        if (node == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        if (node.operation != null) {
            throw IllegalArgumentException("Cannot directly modify the value of a non-input node $nodeName")
        }

        // If the key doesn't exist, ignore this
        val keyListValueId = ValueId.FullKeyList(node.keySourceName)
        val keyListValueHolder = values[keyListValueId]!!

        if (keyListValueHolder.getValue() == null) {
            error("Internal error: The key list should be an input and therefore should already have a value holder")
        } else if (!(keyListValueHolder.getValue() as KeyList<K>).contains(key)) {
            // Ignore this input
            return curTimestamp
        }

        val keyedValueId = ValueId.Keyed(nodeName, key)

        curTimestamp++
        setValue(keyedValueId, curTimestamp, value, null)
        return curTimestamp
    }

    private fun <K, T> applySetKeyedChange(change: TrickleInputChange.SetKeyed<K, T>, newTimestamp: Long): Boolean {
        val (nodeName, key, value) = change
        val node = definition.keyedNodes[nodeName]!!

        // If the key doesn't exist, ignore this
        val keyListValueId = ValueId.FullKeyList(node.keySourceName)
        val keyListValueHolder = values[keyListValueId]!!

        if (keyListValueHolder.getValue() == null) {
            error("Internal error: The key list should be an input and therefore should already have a value holder")
        } else if (!(keyListValueHolder.getValue() as KeyList<K>).contains(key)) {
            // Ignore this input
            return false
        }

        val keyedValueId = ValueId.Keyed(nodeName, key)

        // TODO: Maybe don't register a change in some cases if the new value is the same or value-equals
        setValue(keyedValueId, newTimestamp, value, null)
        return true
    }

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
    // TODO: Propagating "input is missing" should be similar to propagating errors. But should they be different concepts
    // or a combined notion of failure? One difference is that "catch" should only apply to errors, not missing inputs...
    @Synchronized
    fun getNextSteps(): List<TrickleStep> {
        val nextSteps = ArrayList<TrickleStep>()
        val timeStampIfUpToDate = HashMap<ValueId, Long>()
        for (nodeName in definition.topologicalOrdering) {
            when (nodeName) {
                is NodeName<*> -> {
                    val node = definition.nonkeyedNodes[nodeName]!!
                    val nodeValueId = ValueId.Nonkeyed(nodeName)
                    if (node.operation == null) {
                        val timestamp = values[nodeValueId]?.getTimestamp() ?: -1L
                        if (timestamp >= 0L) {
                            timeStampIfUpToDate[nodeValueId] = timestamp
                        }
                    } else {
                        var anyInputNotUpToDate = false
                        var maximumInputTimestamp = -1L
                        val inputValues = ArrayList<Any?>()
                        val inputFailures = ArrayList<TrickleFailure>()
                        for (input in node.inputs) {
                            val inputValueId = getValueIdFromInput(input)
                            val timeStampMaybe = timeStampIfUpToDate[inputValueId]
                            if (timeStampMaybe == null) {
                                anyInputNotUpToDate = true
                            } else {
                                maximumInputTimestamp = Math.max(maximumInputTimestamp, timeStampMaybe)
                                val contents = values[inputValueId]!!
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
                is KeyListNodeName<*> -> {
                    val node = definition.keyListNodes[nodeName]!!
                    val nodeValueId = ValueId.FullKeyList(nodeName)
                    // TODO: We can store things differently to make this more efficient
                    val keyValueIds = values.keys.filter { it is ValueId.KeyListKey && it.nodeName == nodeName }
                    if (node.operation == null) {
                        val timestamp = values[nodeValueId]?.getTimestamp() ?: -1L
                        if (timestamp >= 0L) {
                            timeStampIfUpToDate[nodeValueId] = timestamp
                            for (keyValueId in keyValueIds) {
                                timeStampIfUpToDate[keyValueId] = values[keyValueId]!!.getTimestamp()
                            }
                        } else {
                            // Key lists are initialized to be empty
                            error("Key lists should have been initialized to be empty")
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
                is KeyedNodeName<*, *> -> {
                    val node = definition.keyedNodes[nodeName]!!
                    val keySourceName = node.keySourceName
                    val fullKeyListId = ValueId.FullKeyList(keySourceName)

                    val isKeySourceUpToDate = timeStampIfUpToDate.containsKey(fullKeyListId)
                    if (isKeySourceUpToDate) {
                        var anyKeyedValueNotUpToDate = false
                        var maximumInputTimestampAcrossAllKeys = timeStampIfUpToDate[fullKeyListId]!!
                        val allInputFailuresAcrossAllKeys = ArrayList<TrickleFailure>()

                        val keyListHolder = values[fullKeyListId]!!
                        val keyList = if (keyListHolder.getFailure() != null) {
                            allInputFailuresAcrossAllKeys.add(keyListHolder.getFailure()!!)
                            KeyList.empty<Any?>()
                        } else {
                            keyListHolder.getValue() as KeyList<*>
                        }
//                        val keyList = values[fullKeyListId]!!.getValue() as KeyList<*>

                        for (key in keyList.list) {
                            if (node.operation == null) {
                                // This keyed node is an input
                                val keyedInputValueId = ValueId.Keyed(nodeName, key)

                                val timestamp = values[keyedInputValueId]?.getTimestamp() ?: -1L
                                if (timestamp >= 0L) {
                                    timeStampIfUpToDate[keyedInputValueId] = timestamp
                                    val failure = values[keyedInputValueId]!!.getFailure()
                                    if (failure != null) {
                                        allInputFailuresAcrossAllKeys.add(failure)
                                    }
                                } else {
//                                    anyKeyedValueNotUpToDate = true

                                    val failure = TrickleFailure(mapOf(), setOf(keyedInputValueId))

                                    setValue(keyedInputValueId, keyListHolder.getTimestamp(), null, failure)
                                    timeStampIfUpToDate[keyedInputValueId] = keyListHolder.getTimestamp()

                                    allInputFailuresAcrossAllKeys.add(failure)
                                }
                                maximumInputTimestampAcrossAllKeys = Math.max(maximumInputTimestampAcrossAllKeys, timestamp)
                            } else {

                                var anyInputNotUpToDate = false
                                var maximumInputTimestamp = -1L
                                val inputValues = ArrayList<Any?>()
                                val inputFailures = ArrayList<TrickleFailure>()
                                for (input in node.inputs) {
                                    val unkeyedInputValueId = getValueIdFromInput(input, key)
                                    val timeStampMaybe = timeStampIfUpToDate[unkeyedInputValueId]
                                    if (timeStampMaybe == null) {
                                        anyInputNotUpToDate = true
                                        anyKeyedValueNotUpToDate = true
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
                                maximumInputTimestampAcrossAllKeys =
                                    Math.max(maximumInputTimestampAcrossAllKeys, maximumInputTimestamp)
                                allInputFailuresAcrossAllKeys.addAll(inputFailures)
                                val keyedValueId = ValueId.Keyed(nodeName, key)
                                if (!anyInputNotUpToDate) {
                                    // All inputs are up-to-date
                                    if (inputFailures.isNotEmpty()) {
                                        // Aggregate the failures for reporting
                                        val curValueTimestamp = values[keyedValueId]?.getTimestamp()
                                        if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                            val newFailure = combineFailures(inputFailures)

                                            val onCatch = node.onCatch
                                            if (onCatch != null) {
                                                nextSteps.add(
                                                    TrickleStep(
                                                        keyedValueId,
                                                        maximumInputTimestamp,
                                                        instanceId,
                                                        { onCatch(newFailure) })
                                                )
                                                anyKeyedValueNotUpToDate = true
                                            } else {
                                                setValue(keyedValueId, maximumInputTimestamp, null, newFailure)
                                                //                                values[ValueId.Nonkeyed(nodeName)]!!.setFailure(maximumInputTimestamp, newFailure)
                                                timeStampIfUpToDate[keyedValueId] = maximumInputTimestamp
                                            }
                                        } else if (curValueTimestamp > maximumInputTimestamp) {
                                            error("This should never happen")
                                        } else {
                                            timeStampIfUpToDate[keyedValueId] = maximumInputTimestamp
                                        }
                                    } else {
                                        val curValueTimestamp = values[keyedValueId]?.getTimestamp()
                                        if (curValueTimestamp == null || curValueTimestamp < maximumInputTimestamp) {
                                            // We should compute this (pass in the maximumInputTimestamp and the appropriate input values)

                                            val operation = node.operation as (Any?, List<*>) -> Any?
                                            nextSteps.add(
                                                TrickleStep(
                                                    keyedValueId,
                                                    maximumInputTimestamp,
                                                    instanceId,
                                                    { operation(key, inputValues) })
                                            )
                                            anyKeyedValueNotUpToDate = true
                                        } else if (curValueTimestamp > maximumInputTimestamp) {
                                            error("This should never happen")
                                        } else {
                                            // Report this as being up-to-date for future things
                                            timeStampIfUpToDate[keyedValueId] = curValueTimestamp
                                        }
                                    }
                                }
                            }
//                            TODO()
                        }
                        // TODO: Remove values for non-up-to-date keys

                        // If every key value is up-to-date, but the full keyed list is not, update the keyed list and prune old values
                        if (!anyKeyedValueNotUpToDate) {
                            // All keyed values are up-to-date; update the full list
                            val fullListValueId = ValueId.FullKeyedList(nodeName)
                            if (allInputFailuresAcrossAllKeys.isNotEmpty()) {
                                val combinedFailure = combineFailures(allInputFailuresAcrossAllKeys)
                                setValue(fullListValueId, maximumInputTimestampAcrossAllKeys, null, combinedFailure)
                                timeStampIfUpToDate[fullListValueId] = maximumInputTimestampAcrossAllKeys
                            } else {
                                val newList = ArrayList<Any?>()
                                for (key in keyList.list) {
                                    newList.add(values[ValueId.Keyed(nodeName, key)]!!.getValue())
                                }
                                setValue(fullListValueId, maximumInputTimestampAcrossAllKeys, newList, null)
                                timeStampIfUpToDate[fullListValueId] = maximumInputTimestampAcrossAllKeys
                            }

                        }
                        // Prune values for keys that no longer exist in the key list
                        // These will now return "NoSuchKey"
                        for (valueId in values.keys.toList()) {
                            if (valueId is ValueId.Keyed && valueId.nodeName == nodeName && !keyList.set.contains(valueId.key)) {
                                values.remove(valueId)
                            }
                        }
                    }
                }
            }

        }

        return nextSteps
    }

    private fun combineFailures(inputFailures: ArrayList<TrickleFailure>): TrickleFailure {
        val allErrors = LinkedHashMap<ValueId, Throwable>()
        val allMissingInputs = LinkedHashSet<ValueId>()
        for (failure in inputFailures) {
            allErrors.putAll(failure.errors)
            allMissingInputs.addAll(failure.missingInputs)
        }
        return TrickleFailure(allErrors, allMissingInputs)
    }

    // TODO: This should probably just be a function on TrickleInput
    // TODO: This doesn't really work for keyed node outputs: we also need to be able to get both things tied to a key
    // and the list containing all the keys
    private fun getValueIdFromInput(input: TrickleInput<*>, key: Any? = null): ValueId {
        return when (input) {
            is TrickleBuiltNode -> ValueId.Nonkeyed(input.name)
            is TrickleInput.KeyList<*> -> ValueId.FullKeyList(input.name)
            is TrickleInput.Keyed<*, *> -> ValueId.Keyed(input.name, key)
            is TrickleInput.FullKeyedList<*, *> -> ValueId.FullKeyedList(input.name)
        }
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

        val valueId = result.valueId
        when (valueId) {
            is ValueId.Nonkeyed -> {
                reportBasicResult(valueId, result.timestamp, result.result, result.error)
            }
            is ValueId.FullKeyList -> {
                reportKeyListResult(valueId, result.timestamp, result.result, result.error)
            }
            is ValueId.KeyListKey -> TODO()
            is ValueId.Keyed -> {
                reportKeyedResult(valueId, result.timestamp, result.result, result.error)
            }
            is ValueId.FullKeyedList -> TODO()
            else -> error("Unhandled valueId type")
        }
    }

    private fun reportBasicResult(valueId: ValueId.Nonkeyed, timestamp: Long, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { TrickleFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, timestamp, result, failure)
        }
    }

    private fun reportKeyedResult(valueId: ValueId.Keyed, timestamp: Long, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { TrickleFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, timestamp, result, failure)
        }
        // TODO: For keyed nodes, also adjust the values/timestamps of the whole list (?)
    }

    private fun reportKeyListResult(valueId: ValueId.FullKeyList, timestamp: Long, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { TrickleFailure(mapOf(valueId to it), setOf()) }
            if (result == null) {
                setValue(valueId, timestamp, null, failure)
            } else {
                val newList = KeyList.copyOf(result as List<*>)
                val oldList = valueHolder?.getValue() as? KeyList<*> ?: KeyList.empty<Any?>()
                setValue(valueId, timestamp, newList, failure)

                val additions = HashSet(newList.set)
                additions.removeAll(oldList.set)
                val removals = HashSet(oldList.set)
                removals.removeAll(newList.set)

                for (addition in additions) {
                    val keyValueId = ValueId.KeyListKey(valueId.nodeName, addition)
                    setValue(keyValueId, timestamp, true, failure)
                }
                for (removal in removals) {
                    val keyValueId = ValueId.KeyListKey(valueId.nodeName, removal)
                    setValue(keyValueId, timestamp, false, failure)
                }
            }
        }
    }

    @Synchronized
    fun <T> getNodeValue(nodeName: NodeName<T>): T {
        val outcome = getNodeOutcome(nodeName)
        when (outcome) {
            is NodeOutcome.Computed -> {
                return outcome.value
            }
            is NodeOutcome.NotYetComputed -> {
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
//            is NodeOutcome.InputMissing -> {
//                throw IllegalStateException("Missing inputs for node $nodeName: ${outcome.inputsNeeded}")
//            }
            is NodeOutcome.NoSuchKey -> {
                error("This shouldn't happen in this particular function")
            }
        }
//        error("Value for node $nodeName was not computed successfully: $outcome")
//        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    // TODO: Descriptive exceptions
    @Synchronized
    fun <T> getNodeValue(nodeName: KeyListNodeName<T>): List<T> {
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    // TODO: Descriptive exceptions
    @Synchronized
    fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>, key: K): V {
        return (getNodeOutcome(nodeName, key) as NodeOutcome.Computed).value
    }

    // TODO: Descriptive exceptions
    @Synchronized
    fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>): List<V> {
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    @Synchronized
    fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        if (!definition.nonkeyedNodes.containsKey(nodeName)) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val value = values[ValueId.Nonkeyed(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        return NodeOutcome.Computed(value.getValue() as T)
    }

    @Synchronized
    fun <T> getNodeOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>> {
        if (!definition.keyListNodes.containsKey(nodeName)) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val value = values[ValueId.FullKeyList(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        var computedValue = value.getValue()
        if (computedValue is KeyList<*>) {
            computedValue = computedValue.asList()
        }
        return NodeOutcome.Computed((value.getValue() as KeyList<T>).asList())
    }

    @Synchronized
    fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V> {
        val nodeDefinition = definition.keyedNodes[nodeName]
        if (nodeDefinition == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val keyListId = ValueId.FullKeyList(nodeDefinition.keySourceName)
        val keyListValueHolder = values[keyListId]
        if (keyListValueHolder?.getValue() != null && !(keyListValueHolder.getValue() as KeyList<K>).contains(key)) {
            return NodeOutcome.NoSuchKey.get()
        }
        val value = values[ValueId.Keyed(nodeName, key)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        return NodeOutcome.Computed(value.getValue() as V)
    }

    @Synchronized
    fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>> {
        val nodeDefinition = definition.keyedNodes[nodeName]
        if (nodeDefinition == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }

        val value = values[ValueId.FullKeyedList(nodeName)]
        if (value == null) {
            return NodeOutcome.NotYetComputed.get()
        }
        val failure = value.getFailure()
        if (failure != null) {
            return NodeOutcome.Failure(failure)
        }
        return NodeOutcome.Computed(value.getValue() as List<V>)
    }

    internal fun printStoredState() {
        println("*** Raw instance state ***")
        println("  Current timestamp: $curTimestamp")
        val sortedValueIds = ArrayList(values.keys)
        sortedValueIds.sortBy { it.toString() }
        for (valueId in sortedValueIds) {
            val valueHolder = values[valueId]
            if (valueHolder != null) {
                println("  ${valueId}: ${valueHolder.getValue()}, ${valueHolder.getFailure()} (${valueHolder.getTimestamp()})")
            }
        }
        println("**************************")
    }
}

// TODO: The TrickleInputReceiver methods here should probably just not return the timestamps to avoid confusion
// TODO: Should the Outcome methods here be a different type? I guess if inputs are not filled in, uncomputed is fine...
class TrickleSyncInstance(private val instance: TrickleInstance): TrickleInputReceiver {
    override fun setInputs(changes: List<TrickleInputChange>): Long {
        return instance.setInputs(changes)
    }

    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        return instance.setInput(nodeName, value)
    }

    override fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        return instance.setInput(nodeName, list)
    }

    override fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        return instance.addKeyInput(nodeName, key)
    }

    override fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        return instance.removeKeyInput(nodeName, key)
    }

    override fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long {
        return instance.setKeyedInput(nodeName, key, value)
    }

    private fun <T> doComputationsFor(nodeName: NodeName<T>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.Nonkeyed(nodeName))
        computeRelevantValues(relevantValuesPred)
    }

    private fun <T> doComputationsFor(nodeName: KeyListNodeName<T>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.FullKeyList(nodeName))
        computeRelevantValues(relevantValuesPred)
    }

    private fun <K, V> doComputationsFor(nodeName: KeyedNodeName<K, V>, key: K) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.Keyed(nodeName, key))
        computeRelevantValues(relevantValuesPred)
    }

    private fun <K, V> doComputationsFor(nodeName: KeyedNodeName<K, V>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.FullKeyedList(nodeName))
        computeRelevantValues(relevantValuesPred)
    }

    private fun computeRelevantValues(relevantValueIds: Predicate<ValueId>) {
        while (true) {
            val nextSteps = instance.getNextSteps()
            val relevantNextSteps = nextSteps.filter { relevantValueIds.test(it.valueId) }
            if (relevantNextSteps.isEmpty()) {
                return
            }
            for (step in relevantNextSteps) {
                instance.reportResult(step.execute())
            }
        }
    }

    fun <T> getValue(nodeName: NodeName<T>): T {
        doComputationsFor(nodeName)
        return instance.getNodeValue(nodeName)
    }

    fun <T> getValue(nodeName: KeyListNodeName<T>): List<T> {
        doComputationsFor(nodeName)
        return instance.getNodeValue(nodeName)
    }

    fun <K, V> getValue(nodeName: KeyedNodeName<K, V>, key: K): V {
        doComputationsFor(nodeName, key)
        return instance.getNodeValue(nodeName, key)
    }

    fun <K, V> getValue(nodeName: KeyedNodeName<K, V>): List<V> {
        doComputationsFor(nodeName)
        return instance.getNodeValue(nodeName)
    }

    fun <T> getOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        doComputationsFor(nodeName)
        return instance.getNodeOutcome(nodeName)
    }

    fun <T> getOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>> {
        doComputationsFor(nodeName)
        return instance.getNodeOutcome(nodeName)
    }

    fun <K, V> getOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V> {
        doComputationsFor(nodeName, key)
        return instance.getNodeOutcome(nodeName, key)
    }

    fun <K, V> getOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>> {
        doComputationsFor(nodeName)
        return instance.getNodeOutcome(nodeName)
    }
}

sealed class NodeOutcome<T> {
    class NotYetComputed<T> private constructor(): NodeOutcome<T>() {
        companion object {
            private val INSTANCE = NotYetComputed<Any>()
            fun <T> get(): NotYetComputed<T> {
                return INSTANCE as NotYetComputed<T>
            }
            override fun toString(): String {
                return "NotYetComputed"
            }
            override fun equals(other: Any?): Boolean {
                return other is NotYetComputed<*>
            }
            override fun hashCode(): Int {
                return 309803249
            }
        }
    }
    class NoSuchKey<T> private constructor(): NodeOutcome<T>() {
        companion object {
            private val INSTANCE = NoSuchKey<Any>()
            fun <T> get(): NoSuchKey<T> {
                return INSTANCE as NoSuchKey<T>
            }
            override fun toString(): String {
                return "NoSuchKey"
            }
            override fun equals(other: Any?): Boolean {
                return other is NoSuchKey<*>
            }
            override fun hashCode(): Int {
                return 892753869
            }
        }
    }
//    data class InputMissing<T>(val inputsNeeded: Set<ValueId>): NodeOutcome<T>()
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
}

class TrickleStepResult internal constructor(
    val valueId: ValueId,
    val timestamp: Long,
    val instanceId: TrickleInstance.Id,
    val result: Any?,
    val error: Throwable?
)

class TrickleDefinition internal constructor(internal val nonkeyedNodes: Map<NodeName<*>, TrickleNode<*>>,
                                             internal val keyListNodes: Map<KeyListNodeName<*>, TrickleKeyListNode<*>>,
                                             internal val keyedNodes: Map<KeyedNodeName<*, *>, TrickleKeyedNode<*, *>>,
                                             internal val topologicalOrdering: List<GenericNodeName>) {
    fun instantiateRaw(): TrickleInstance {
        return TrickleInstance(this)
    }
    fun instantiateSync(): TrickleSyncInstance {
        return TrickleSyncInstance(TrickleInstance(this))
    }

    // TODO: This actually wants to be a Predicate, because we might want all keys within a group
    // TODO: This might benefit from caching
    internal fun getRelevantValuesPredicate(valueId: ValueId): Predicate<ValueId> {
        val relevantValues = HashSet<ValueId>()
        val keyedNamesWithAllKeysRelevant = HashSet<KeyedNodeName<*, *>>()
        val toAdd = HashSet<ValueId>()
        toAdd.add(valueId)

        while (toAdd.isNotEmpty()) {
            val curId = toAdd.first()
            toAdd.remove(curId)
            if (relevantValues.contains(curId)) {
                continue
            }
            relevantValues.add(curId)

            val inputs = when (curId) {
                is ValueId.Nonkeyed -> nonkeyedNodes.getValue(curId.nodeName).inputs
                is ValueId.FullKeyList -> keyListNodes.getValue(curId.nodeName).inputs
                is ValueId.KeyListKey -> keyListNodes.getValue(curId.nodeName).inputs
                is ValueId.Keyed -> keyedNodes.getValue(curId.nodeName).inputs
                is ValueId.FullKeyedList -> keyedNodes.getValue(curId.nodeName).inputs
            }
            for (input in inputs) {
                when (input) {
                    is TrickleBuiltNode -> toAdd.add(ValueId.Nonkeyed(input.name))
                    is TrickleInput.KeyList<*> -> toAdd.add(ValueId.FullKeyList(input.name))
                    is TrickleInput.Keyed<*, *> -> {
                        if (curId is ValueId.Keyed) {
                            toAdd.add(ValueId.Keyed(input.name, curId.key))
                        } else if (curId is ValueId.FullKeyedList) {
                            toAdd.add(ValueId.FullKeyedList(input.name))
                        } else {
                            error("This shouldn't happen")
                        }
                    }
                    is TrickleInput.FullKeyedList<*, *> -> toAdd.add(ValueId.FullKeyedList(input.name))
                }
            }
            // TODO: Also add a section for "keyed nodes rely on their inputs"
            if (curId is ValueId.FullKeyedList) {
                keyedNamesWithAllKeysRelevant.add(curId.nodeName)
                val keySourceName = keyedNodes.getValue(curId.nodeName).keySourceName
                toAdd.add(ValueId.FullKeyList(keySourceName))
            }
            if (curId is ValueId.Keyed) {
                val keySourceName = keyedNodes.getValue(curId.nodeName).keySourceName
                toAdd.add(ValueId.FullKeyList(keySourceName))
            }
        }

        return Predicate {
//            true
            relevantValues.contains(it) || (it is ValueId.Keyed && keyedNamesWithAllKeysRelevant.contains(it.nodeName))
        }
    }

    fun toMultiLineString(): String {
        val sb = StringBuilder()
        for (nodeName in topologicalOrdering) {
            when (nodeName) {
                is NodeName<*> -> {
                    val node = nonkeyedNodes.getValue(nodeName)
                    sb.append("Basic   ")
                    sb.append(nodeName)
                    if (node.operation != null) {
                        sb.append("(")
                        sb.append(node.inputs.map { it.toString() }.joinToString(", "))
                        sb.append(")")
                    }
                }
                is KeyListNodeName<*> -> {
                    val node = keyListNodes.getValue(nodeName)
                    sb.append("KeyList ")
                    sb.append(nodeName)
                    if (node.operation != null) {
                        sb.append("(")
                        sb.append(node.inputs.map { it.toString() }.joinToString(", "))
                        sb.append(")")
                    }
                }
                is KeyedNodeName<*, *> -> {
                    val node = keyedNodes.getValue(nodeName)
                    sb.append("Keyed   ")
                    sb.append(nodeName)
                    sb.append("<")
                    sb.append(node.keySourceName)
                    sb.append(">")
                    if (node.operation != null) {
                        sb.append("(")
                        sb.append(node.inputs.map { it.toString() }.joinToString(", "))
                        sb.append(")")
                    }
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
class TrickleDefinitionBuilder {
    private val usedNodeNames = HashSet<String>()
    private val nodes = HashMap<NodeName<*>, TrickleNode<*>>()
    // TODO: Give this its own sealed class covering all the node name types
    private val topologicalOrdering = ArrayList<GenericNodeName>()
    private val keyListNodes = HashMap<KeyListNodeName<*>, TrickleKeyListNode<*>>()
    private val keyedNodes = HashMap<KeyedNodeName<*, *>, TrickleKeyedNode<*, *>>()

    // Used to ensure all node inputs we receive originated from this builder
    class Id internal constructor()
    private val builderId = Id()

    fun <T> createInputNode(name: NodeName<T>): TrickleBuiltNode<T> {
        checkNameNotUsed(name.name)
        val node = TrickleNode<T>(name, listOf(), null, null)
        nodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltNode(name, builderId)
    }

    private fun checkNameNotUsed(name: String) {
        if (usedNodeNames.contains(name)) {
            error("Cannot create two nodes with the same name '$name'")
        }
        usedNodeNames.add(name)
    }

    fun <T, I1> createNode(name: NodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltNode<T> {
        return createNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T, I1, I2> createNode(name: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (I1, I2) -> T, onCatch: ((TrickleFailure) -> T)? = null): TrickleBuiltNode<T> {
        return createNode(name, listOf(input1, input2), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) }, onCatch)
    }
    fun <T> createNode(name: NodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> T, onCatch: ((TrickleFailure) -> T)?): TrickleBuiltNode<T> {
        if (inputs.isEmpty()) {
            // TODO: We might want to allow this as long as fn and onCatch are null
            error("Use createInputNode to create a node with no inputs.")
        }
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = TrickleNode<T>(name, inputs, fn, onCatch)
        nodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltNode(name, builderId)
    }

    private fun validateBuilderIds(inputs: List<TrickleInput<*>>) {
        for (input in inputs) {
            if (builderId != input.builderId) {
                throw IllegalArgumentException("Cannot reuse nodes or inputs across builders")
            }
        }
    }

    fun <T> createKeyListInputNode(name: KeyListNodeName<T>): TrickleBuiltKeyListNode<T> {
        checkNameNotUsed(name.name)

        val node = TrickleKeyListNode<T>(name, listOf(), null, null)
        keyListNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyListNode(name, builderId)
    }
    fun <T, I1> createKeyListNode(name: KeyListNodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> List<T>, onCatch: ((TrickleFailure) -> List<T>)? = null): TrickleBuiltKeyListNode<T> {
        return createKeyListNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) }, onCatch)
    }
    fun <T> createKeyListNode(name: KeyListNodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> List<T>, onCatch: ((TrickleFailure) -> List<T>)?): TrickleBuiltKeyListNode<T> {
        if (inputs.isEmpty()) {
            // TODO: We might want to allow this as long as fn and onCatch are null
            error("Use createInputNode to create a node with no inputs.")
        }
        checkNameNotUsed(name.name)
        validateBuilderIds(inputs)
        val node = TrickleKeyListNode<T>(name, inputs, fn, onCatch)
        keyListNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyListNode(name, builderId)
    }

    fun <K, T> createKeyedInputNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>): TrickleBuiltKeyedNode<K, T> {
        checkNameNotUsed(name.name)

        if (keyListNodes[keySource.name]!!.operation != null) {
            error("Keyed input nodes can only use input key lists as their key sources, but ${keySource.name} is not an input.")
        }

        val node = TrickleKeyedNode(name, keySource.name, listOf(), null, null)
        keyedNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyedNode(name, builderId)
    }
    fun <K, T> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, fn: (K) -> T): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(), { key, list -> fn(key) })
    }
    fun <K, T, I1> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, input1: TrickleInput<I1>, fn: (K, I1) -> T): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1), { key, list -> fn(key, list[0] as I1) })
    }
    fun <T, K, I1, I2> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (K, I1, I2) -> T): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1, input2), { key, list -> fn(key, list[0] as I1, list[1] as I2) })
    }
    fun <T, K, I1, I2, I3> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, input3: TrickleInput<I3>, fn: (K, I1, I2, I3) -> T): TrickleBuiltKeyedNode<K, T> {
        return createKeyedNode(name, keySource, listOf(input1, input2, input3), { key, list -> fn(key, list[0] as I1, list[1] as I2, list[2] as I3) })
    }
    fun <K, T> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleBuiltKeyListNode<K>, inputs: List<TrickleInput<*>>, fn: (K, List<*>) -> T): TrickleBuiltKeyedNode<K, T> {
        checkNameNotUsed(name.name)
        // TODO: Support onCatch in keyed nodes
        val node = TrickleKeyedNode(name, keySource.name, inputs, fn, null)
        keyedNodes[name] = node
        topologicalOrdering.add(name)
        return TrickleBuiltKeyedNode(name, builderId)
    }

    fun build(): TrickleDefinition {
        return TrickleDefinition(nodes, keyListNodes, keyedNodes, topologicalOrdering)
    }

}

class TrickleBuiltNode<T>(val name: NodeName<T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<T>() {
    override fun toString(): String {
        return name.toString()
    }
}
class TrickleBuiltKeyListNode<T>(val name: KeyListNodeName<T>, val builderId: TrickleDefinitionBuilder.Id) {
    fun listOutput(): TrickleInput<List<T>> {
        return TrickleInput.KeyList<T>(name, builderId)
    }
    override fun toString(): String {
        return name.toString()
    }
}
class TrickleBuiltKeyedNode<K, T>(val name: KeyedNodeName<K, T>, val builderId: TrickleDefinitionBuilder.Id) {
    fun keyedOutput(): TrickleInput<T> {
        return TrickleInput.Keyed(name, builderId)
    }
    fun fullOutput(): TrickleInput<List<T>> {
        return TrickleInput.FullKeyedList(name, builderId)
    }
    override fun toString(): String {
        return name.toString()
    }
}
sealed class TrickleInput<T> {
    abstract val builderId: TrickleDefinitionBuilder.Id
    data class KeyList<T>(val name: KeyListNodeName<T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<List<T>>() {
        override fun toString(): String {
            return name.toString()
        }
    }
    data class Keyed<K, T>(val name: KeyedNodeName<K, T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<T>() {
        override fun toString(): String {
            return "$name (keyed)"
        }
    }
    data class FullKeyedList<K, T>(val name: KeyedNodeName<K, T>, override val builderId: TrickleDefinitionBuilder.Id): TrickleInput<List<T>>() {
        override fun toString(): String {
            return "$name (full list)"
        }
    }
}

// TODO: Add a method for turning this into a single Exception with a reasonable human-friendly summary
data class TrickleFailure(val errors: Map<ValueId, Throwable>, val missingInputs: Set<ValueId>)

internal class TrickleNode<T> internal constructor(
    val name: NodeName<T>,
    val inputs: List<TrickleInput<*>>,
    val operation: ((List<*>) -> T)?,
    val onCatch: ((TrickleFailure) -> T)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

internal class TrickleKeyListNode<T>(
    val name: KeyListNodeName<T>,
    val inputs: List<TrickleInput<*>>,
    val operation: ((List<*>) -> List<T>)?,
    val onCatch: ((TrickleFailure) -> List<T>)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}

internal class TrickleKeyedNode<K, T>(
    val name: KeyedNodeName<K, T>,
    val keySourceName: KeyListNodeName<K>,
    val inputs: List<TrickleInput<*>>,
    val operation: ((K, List<*>) -> T)?,
    val onCatch: ((TrickleFailure) -> T)?
) {
    init {
        if (operation == null && inputs.isNotEmpty()) {
            error("Internal error: When operation is null (input node), inputs should be empty")
        }
    }
}
