package net.semlang.refill

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

// TODO: Add the ability to skip computations based on the equality of certain inputs (and other inputs remaining at the timestamp
// they were last time)

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

// TODO: Add optional "payloads" for key lists, to replace the removed keyed inputs feature

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

internal class KeyList<T> private constructor(val list: List<T>, val set: Set<T>) {
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

    // TODO: This could be much more efficient
    fun addAll(keys: Iterable<T>): KeyList<T> {
        var theList = this
        for (key in keys) {
            theList = theList.add(key)
        }
        return theList
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

    // TODO: This could be much more efficient
    fun removeAll(keys: Iterable<T>): KeyList<T> {
        var theList = this
        for (key in keys) {
            theList = theList.remove(key)
        }
        return theList
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

private data class TimestampedValue(private var timestamp: Long, private var latestConsistentTimestamp: Long, private var value: Any?, private var failure: TrickleFailure?) {
    fun set(newTimestamp: Long, newValue: Any?, newFailure: TrickleFailure?) {
        this.timestamp = newTimestamp
        this.latestConsistentTimestamp = newTimestamp
        this.value = newValue
        this.failure = newFailure
    }

    fun set(newTimestamp: Long, newValue: Any?) {
        this.timestamp = newTimestamp
        this.latestConsistentTimestamp = newTimestamp
        this.value = newValue
        this.failure = null
    }

    fun setFailure(newTimestamp: Long, newFailure: TrickleFailure) {
        this.timestamp = newTimestamp
        this.latestConsistentTimestamp = newTimestamp
        this.value = null
        this.failure = newFailure
    }

    fun setLatestConsistentTimestamp(latestConsistentTimestamp: Long) {
        this.latestConsistentTimestamp = latestConsistentTimestamp
    }

    fun getValue(): Any? {
        return value
    }

    fun getTimestamp(): Long {
        return timestamp
    }

    fun getLatestConsistentTimestamp(): Long {
        return latestConsistentTimestamp
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
    fun <T> editKeys(nodeName: KeyListNodeName<T>, keysAdded: List<T>, keysRemoved: List<T>): Long
}

sealed class TrickleInputChange {
    abstract val nodeName: GenericNodeName
    data class SetBasic<T>(override val nodeName: NodeName<T>, val value: T): TrickleInputChange()
    data class SetKeys<T>(override val nodeName: KeyListNodeName<T>, val value: List<T>): TrickleInputChange()
//    data class AddKey<T>(override val nodeName: KeyListNodeName<T>, val key: T): TrickleInputChange()
//    data class RemoveKey<T>(override val nodeName: KeyListNodeName<T>, val key: T): TrickleInputChange()
    // TODO: Add to fuzz testing
    // Note: Removals happen before additions. If a key is in both lists, it will be removed and then readded to the list (thus ending up in a different position).
    data class EditKeys<T>(override val nodeName: KeyListNodeName<T>, val keysAdded: List<T>, val keysRemoved: List<T>): TrickleInputChange()
}

interface TrickleRawInstance {
    val definition: TrickleDefinition
    fun setInputs(changes: List<TrickleInputChange>): Long
    fun <T> setInput(nodeName: NodeName<T>, value: T): Long
    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long
    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long
    fun getNextSteps(): List<TrickleStep>
    fun completeSynchronously()
    fun reportResult(result: TrickleStepResult)
    fun <T> getNodeValue(nodeName: NodeName<T>): T
    fun <T> getNodeValue(nodeName: KeyListNodeName<T>): List<T>
    fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>, key: K): V
    fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>): List<V>
    fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T>
    fun <T> getNodeOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>>
    fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V>
    fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>>
    // TODO: Maybe remove this if unused?
    fun getLastUpdateTimestamp(valueId: ValueId): Long
    fun getLatestTimestampWithValue(valueId: ValueId): Long
}

// TODO: Synchronize stuff
// TODO: Add methods to ask for a given result, but only after a given timestamp (block until available?)
class TrickleInstance internal constructor(override val definition: TrickleDefinition): TrickleRawInstance {
    // Used to ensure all results we receive originated from this instance
    class Id internal constructor()
    private val instanceId = Id()

    private val values = LinkedHashMap<ValueId, TimestampedValue>()
    private var valueListener: ((TrickleEvent<*>) -> Unit)? = null

    private var curTimestamp = 0L

    init {
        synchronized(this) {
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
    }

    @Synchronized
    private fun setValue(valueId: ValueId, newTimestamp: Long, newValue: Any?, newFailure: TrickleFailure?) {
        var valueHolder = values[valueId]
        if (valueHolder == null) {
            valueHolder = TimestampedValue(-1L, -1L, null, null)
            values[valueId] = valueHolder
        }
        valueHolder.set(newTimestamp, newValue, newFailure)
        if (valueListener != null) {
            val event: TrickleEvent<Any?> = if (newFailure == null) {
                TrickleEvent.Computed(valueId, newValue, newTimestamp)
            } else {
                TrickleEvent.Failure(valueId, newFailure, newTimestamp)
            }
            valueListener!!(event)
        }
    }

    @Synchronized
    fun setValueListener(listener: ((TrickleEvent<*>) -> Unit)?) {
        this.valueListener = listener
    }

    // TODO: Only invoke the listener for the final values of inputs after a set of changes
    @Synchronized
    override fun setInputs(changes: List<TrickleInputChange>): Long {
        for (change in changes) {
            checkHasValidInputNode(change)
        }

        /*
         * Coalesce related changes together so there is only one change per input node.
         *
         * The benefits of coalescing include making sure there is only one listener invocation (and therefore only one
         * externally visible value) of a given input node per timestamp.
         */
        val coalescedChanges = coalesceChanges(changes)

        val prospectiveNewTimestamp = curTimestamp + 1
        var somethingChanged = false
        for (change in coalescedChanges) {
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
    @Synchronized
    private fun applyChange(change: TrickleInputChange, newTimestamp: Long): Boolean {
        return when (change) {
            is TrickleInputChange.SetBasic<*> -> applySetBasicChange(change, newTimestamp)
            is TrickleInputChange.SetKeys<*> -> applySetKeysChange(change, newTimestamp)
//            is TrickleInputChange.AddKey<*> -> applyAddKeyChange(change, newTimestamp)
//            is TrickleInputChange.RemoveKey<*> -> applyRemoveKeyChange(change, newTimestamp)
            is TrickleInputChange.EditKeys<*> -> applyEditKeysChange(change, newTimestamp)
        }
    }

    @Synchronized
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
            is TrickleInputChange.EditKeys<*> -> {
                val node = definition.keyListNodes[change.nodeName]
                if (node == null) {
                    throw IllegalArgumentException("Unrecognized node name ${change.nodeName}")
                }
                if (node.operation != null) {
                    throw IllegalArgumentException("Cannot directly set the value of a non-input node ${change.nodeName}")
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
    @Synchronized
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
            pruneKeyedInputsForRemovedKeys(nodeName, removals, curTimestamp)

        }
        return curTimestamp
    }

    @Synchronized
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
            pruneKeyedInputsForRemovedKeys(nodeName, removals, newTimestamp)

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

//    @Synchronized
//    private fun <T> applyAddKeyChange(change: TrickleInputChange.AddKey<T>, newTimestamp: Long): Boolean {
//        val (nodeName, key) = change
//        val listValueId = ValueId.FullKeyList(nodeName)
//        val oldList = values[listValueId]!!.getValue() as KeyList<T>
//        if (!oldList.contains(key)) {
//            val newList = oldList.add(key)
//            setValue(listValueId, newTimestamp, newList, null)
//
//            val keyValueId = ValueId.KeyListKey(nodeName, key)
//            setValue(keyValueId, newTimestamp, true, null)
//            return true
//        }
//        return false
//    }

    @Synchronized
    private fun <T> applyEditKeysChange(change: TrickleInputChange.EditKeys<T>, newTimestamp: Long): Boolean {
        val (nodeName, keysToAdd, keysToRemove) = change
        val listValueId = ValueId.FullKeyList(nodeName)
        val oldList = values[listValueId]!!.getValue() as KeyList<T>

        val newList = oldList.removeAll(keysToRemove).addAll(keysToAdd)
//        val keysAdded = keysToAdd.filter { !oldList.contains(it) }
//        val keysRemoved = keysToRemove.filter { oldList.contains(it) }

//        val anyChangeMade = keysAdded.isNotEmpty() || keysRemoved.isNotEmpty()
        if (oldList != newList) {
            setValue(listValueId, newTimestamp, newList, null)

            val actuallyRemoved = HashSet<T>()
            for (key in keysToRemove) {
                if (oldList.contains(key) && !newList.contains(key)) {
                    val keyValueId = ValueId.KeyListKey(nodeName, key)
                    setValue(keyValueId, newTimestamp, false, null)
                    actuallyRemoved.add(key)
                }
            }
            for (key in keysToAdd) {
                if (!oldList.contains(key) && newList.contains(key)) {
                    val keyValueId = ValueId.KeyListKey(nodeName, key)
                    setValue(keyValueId, newTimestamp, true, null)
                }
            }
            pruneKeyedInputsForRemovedKeys(nodeName, actuallyRemoved, newTimestamp)
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

            pruneKeyedInputsForRemovedKeys(nodeName, setOf(key), curTimestamp)
        }
        return curTimestamp
    }

    // TODO: Should these APIs use lists instead of sets?
    @Synchronized
    fun <T> editKeys(nodeName: KeyListNodeName<T>, keysToAdd: List<T>, keysToRemove: List<T>): Long {
//        val node = definition.keyListNodes[nodeName]
//        if (node == null) {
//            throw IllegalArgumentException("Unrecognized node name $nodeName")
//        }
//        if (node.operation != null) {
//            throw IllegalArgumentException("Cannot directly modify the value of a non-input node $nodeName")
//        }

        return setInputs(listOf(TrickleInputChange.EditKeys(nodeName, keysToAdd, keysToRemove)))

//        val listValueId = ValueId.FullKeyList(nodeName)
//        val oldList = values[listValueId]!!.getValue() as KeyList<T>
//        if (oldList.contains(key)) {
//            curTimestamp++
//            val newList = oldList.remove(key)
//            setValue(listValueId, curTimestamp, newList, null)
//
//            val keyValueId = ValueId.KeyListKey(nodeName, key)
//            setValue(keyValueId, curTimestamp, false, null)
//
//            pruneKeyedInputsForRemovedKeys(nodeName, setOf(key), curTimestamp)
//        }
//        return curTimestamp
    }

//    @Synchronized
//    private fun <T> applyRemoveKeyChange(change: TrickleInputChange.RemoveKey<T>, newTimestamp: Long): Boolean {
//        val (nodeName, key) = change
//        val listValueId = ValueId.FullKeyList(nodeName)
//        val oldList = values[listValueId]!!.getValue() as KeyList<T>
//        if (oldList.contains(key)) {
//            val newList = oldList.remove(key)
//            setValue(listValueId, newTimestamp, newList, null)
//
//            val keyValueId = ValueId.KeyListKey(nodeName, key)
//            setValue(keyValueId, newTimestamp, false, null)
//
//            pruneKeyedInputsForRemovedKeys(nodeName, setOf(key), newTimestamp)
//
//            return true
//        }
//        return false
//    }

    @Synchronized
    private fun <T> pruneKeyedInputsForRemovedKeys(nodeName: KeyListNodeName<T>, keysRemoved: Set<T>, timestamp: Long) {
        // Prune values for keys that no longer exist in the key list
        // These will now return "NoSuchKey"
        for (valueId in values.keys.toList()) {
            if (valueId is ValueId.Keyed && keysRemoved.contains(valueId.key)) {
                // Check that the key is correct
                // TODO: We can store or memoize things so this is easier to determine...
                val keyedNodeName = valueId.nodeName
                val keySourceName = definition.keyedNodes.getValue(keyedNodeName).keySourceName
                if (keySourceName == nodeName) {
                    values.remove(valueId)
                    if (valueListener != null) {
                        val event = TrickleEvent.KeyRemoved<Any?>(valueId, timestamp)
                        valueListener!!(event)
                    }
                }
            }
        }
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

    This also has the job of updating "latest consistent" timestamps for values due to unrelated inputs changing.
     */
    // TODO: Propagating "input is missing" should be similar to propagating errors. But should they be different concepts
    // or a combined notion of failure? One difference is that "catch" should only apply to errors, not missing inputs...
    // TODO: Update all nodes's "consistent" timestamps to curTimestamp if they're up-to-date
    @Synchronized
    override fun getNextSteps(): List<TrickleStep> {
        val nextSteps = ArrayList<TrickleStep>()
        val timeStampIfUpToDate = HashMap<ValueId, Long>()
        fun updateLatestConsistentTimestamp(valueId: ValueId) {
            values[valueId]!!.setLatestConsistentTimestamp(curTimestamp)
        }
        for (nodeName in definition.topologicalOrdering) {
            when (nodeName) {
                is NodeName<*> -> {
                    val node = definition.nonkeyedNodes[nodeName]!!
                    val nodeValueId = ValueId.Nonkeyed(nodeName)
                    if (node.operation == null) {
                        val timestamp = values[nodeValueId]?.getTimestamp() ?: -1L
                        if (timestamp >= 0L) {
                            timeStampIfUpToDate[nodeValueId] = timestamp
                            updateLatestConsistentTimestamp(nodeValueId)
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
                                        timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                        updateLatestConsistentTimestamp(nodeValueId)
                                    }
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                    updateLatestConsistentTimestamp(nodeValueId)
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
                                    updateLatestConsistentTimestamp(nodeValueId)
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
                            updateLatestConsistentTimestamp(nodeValueId)
                            for (keyValueId in keyValueIds) {
                                timeStampIfUpToDate[keyValueId] = values[keyValueId]!!.getTimestamp()
                                updateLatestConsistentTimestamp(keyValueId)
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
                                        timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                        updateLatestConsistentTimestamp(nodeValueId)
                                    }
                                } else if (curValueTimestamp > maximumInputTimestamp) {
                                    error("This should never happen")
                                } else {
                                    timeStampIfUpToDate[nodeValueId] = maximumInputTimestamp
                                    updateLatestConsistentTimestamp(nodeValueId)
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
                                    updateLatestConsistentTimestamp(nodeValueId)
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

                        for (key in keyList.list) {
                            var anyInputNotUpToDate = false
                            var maximumInputTimestamp = values.getValue(ValueId.KeyListKey(keySourceName, key)).getTimestamp()
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
                                            timeStampIfUpToDate[keyedValueId] = maximumInputTimestamp
                                            updateLatestConsistentTimestamp(keyedValueId)
                                        }
                                    } else if (curValueTimestamp > maximumInputTimestamp) {
                                        error("This should never happen")
                                    } else {
                                        timeStampIfUpToDate[keyedValueId] = maximumInputTimestamp
                                        updateLatestConsistentTimestamp(keyedValueId)
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
                                        updateLatestConsistentTimestamp(keyedValueId)
                                    }
                                }
                            }
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
                                updateLatestConsistentTimestamp(fullListValueId)
                            } else {
                                val newList = ArrayList<Any?>()
                                for (key in keyList.list) {
                                    newList.add(values[ValueId.Keyed(nodeName, key)]!!.getValue())
                                }
                                setValue(fullListValueId, maximumInputTimestampAcrossAllKeys, newList, null)
                                timeStampIfUpToDate[fullListValueId] = maximumInputTimestampAcrossAllKeys
                                updateLatestConsistentTimestamp(fullListValueId)
                            }

                        }
                    }
                }
            }

        }

        return nextSteps
    }

    @Synchronized
    private fun combineFailures(inputFailures: List<TrickleFailure>): TrickleFailure {
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
    @Synchronized
    private fun getValueIdFromInput(input: TrickleInput<*>, key: Any? = null): ValueId {
        return when (input) {
            is TrickleBuiltNode -> ValueId.Nonkeyed(input.name)
            is TrickleInput.KeyList<*> -> ValueId.FullKeyList(input.name)
            is TrickleInput.Keyed<*, *> -> ValueId.Keyed(input.name, key)
            is TrickleInput.FullKeyedList<*, *> -> ValueId.FullKeyedList(input.name)
        }
    }

    // Explicitly not synchronized
    override fun completeSynchronously() {
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
    override fun reportResult(result: TrickleStepResult) {
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

    @Synchronized
    private fun reportBasicResult(valueId: ValueId.Nonkeyed, timestamp: Long, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { TrickleFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, timestamp, result, failure)
        }
    }

    @Synchronized
    private fun reportKeyedResult(valueId: ValueId.Keyed, timestamp: Long, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || timestamp > valueHolder.getTimestamp()) {
            val failure = error?.let { TrickleFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, timestamp, result, failure)
        }
        // TODO: For keyed nodes, also adjust the values/timestamps of the whole list (?)
    }

    @Synchronized
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
    override fun <T> getNodeValue(nodeName: NodeName<T>): T {
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
                val exception = IllegalStateException("Value for node $nodeName was not computed successfully: ${outcome.failure}")
                for (e in outcome.failure.errors.values) {
                    exception.addSuppressed(e)
                }
                throw exception
            }
            is NodeOutcome.NoSuchKey -> {
                error("This shouldn't happen in this particular function")
            }
        }
    }

    // TODO: Descriptive exceptions
    @Synchronized
    override fun <T> getNodeValue(nodeName: KeyListNodeName<T>): List<T> {
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    // TODO: Descriptive exceptions
    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>, key: K): V {
        return (getNodeOutcome(nodeName, key) as NodeOutcome.Computed).value
    }

    // TODO: Descriptive exceptions
    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>): List<V> {
        return (getNodeOutcome(nodeName) as NodeOutcome.Computed).value
    }

    @Synchronized
    override fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
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
    override fun <T> getNodeOutcome(nodeName: KeyListNodeName<T>): NodeOutcome<List<T>> {
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
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V> {
        val nodeDefinition = definition.keyedNodes[nodeName]
        if (nodeDefinition == null) {
            throw IllegalArgumentException("Unrecognized node name $nodeName")
        }
        val keyListId = ValueId.FullKeyList(nodeDefinition.keySourceName)
        val keyListValueHolder = values[keyListId]
        if (keyListValueHolder == null || keyListValueHolder.getValue() == null) {
            // The key list itself has not been successfully computed
            return NodeOutcome.NotYetComputed.get()
        }
        if (!(keyListValueHolder.getValue() as KeyList<K>).contains(key)) {
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
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>> {
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

    @Synchronized
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

    /**
     * This returns the last timestamp for which the given value ID had an update due to an input changing.
     *
     * This is the timestamp at which the current value was computed. (TODO: How will this work with equality-checking)
     *
     * This does not update as unrelated inputs nodes change.
     */
    @Synchronized
    override fun getLastUpdateTimestamp(valueId: ValueId): Long {
        val value = values[valueId]
        return if (value == null) {
            -1L
        } else {
            value.getTimestamp()
        }
    }

    /**
     * This returns the last timestamp that is consistent with the value ID's current value.
     *
     * This can update as unrelated input nodes change.
     */
    @Synchronized
    override fun getLatestTimestampWithValue(valueId: ValueId): Long {
        val value = values[valueId]
        return if (value == null) {
            -1L
        } else {
            value.getLatestConsistentTimestamp()
        }
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
    data class Computed<T>(val value: T): NodeOutcome<T>()
    data class Failure<T>(val failure: TrickleFailure): NodeOutcome<T>()
}

// For use with listeners
sealed class TrickleEvent<T> {
    abstract val valueId: ValueId
    abstract val timestamp: Long
    data class Computed<T>(override val valueId: ValueId, val value: T, override val timestamp: Long): TrickleEvent<T>()
    data class Failure<T>(override val valueId: ValueId, val failure: TrickleFailure, override val timestamp: Long): TrickleEvent<T>()
    data class KeyRemoved<T>(override val valueId: ValueId.Keyed, override val timestamp: Long): TrickleEvent<T>()
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

    // Note: equals() and hashCode() ignore the operation
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrickleStep

        if (valueId != other.valueId) return false
        if (timestamp != other.timestamp) return false
        if (instanceId != other.instanceId) return false

        return true
    }

    // Note: equals() and hashCode() ignore the operation
    override fun hashCode(): Int {
        var result = valueId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + instanceId.hashCode()
        return result
    }
}

data class TrickleStepResult internal constructor(
    val valueId: ValueId,
    val timestamp: Long,
    val instanceId: TrickleInstance.Id,
    val result: Any?,
    val error: Throwable?
)

// TODO: Add a method for turning this into a single Exception with a reasonable human-friendly summary
data class TrickleFailure(val errors: Map<ValueId, Throwable>, val missingInputs: Set<ValueId>)

// TODO: Fix error case when a key should be removed and then re-added
private fun coalesceChanges(changes: List<TrickleInputChange>): List<TrickleInputChange> {
    val changePerNode = LinkedHashMap<GenericNodeName, TrickleInputChange>()

    for (curChange in changes) {
        val existingChange = changePerNode[curChange.nodeName]
        if (existingChange == null) {
            changePerNode[curChange.nodeName] = curChange
        } else {
            val modifiedChange: TrickleInputChange = when (curChange) {
                is TrickleInputChange.SetBasic<*> -> {
                    // New change overwrites the old
                    curChange
                }
                is TrickleInputChange.SetKeys<*> -> {
                    // New change overwrites the old
                    curChange
                }
                is TrickleInputChange.EditKeys<*> -> {
                    val nodeName = curChange.nodeName as KeyListNodeName<Any?>
                    when (existingChange) {
                        is TrickleInputChange.SetBasic<*> -> error("Refill internal error")
                        is TrickleInputChange.SetKeys<*> -> {
                            var keys = KeyList.copyOf(existingChange.value)
                            keys = keys.removeAll(curChange.keysRemoved)
                            keys = keys.addAll(curChange.keysAdded)
                            TrickleInputChange.SetKeys(nodeName, keys.asList())
                        }
                        is TrickleInputChange.EditKeys<*> -> {
                            val keysRemoved = existingChange.keysRemoved + curChange.keysRemoved
                            val keysAdded = (existingChange.keysAdded - curChange.keysRemoved) + curChange.keysAdded
                            TrickleInputChange.EditKeys(nodeName, keysAdded, keysRemoved)
                        }
                    }
                }
            }
            changePerNode[curChange.nodeName] = modifiedChange
        }
    }
//    println("Coalesce changes result:")
//    println("  Input:  $changes")
//    println("  Output: ${changePerNode.values.toList()}")
    return changePerNode.values.toList()
}
