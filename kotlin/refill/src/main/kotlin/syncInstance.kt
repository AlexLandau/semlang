package net.semlang.refill

import java.util.function.Predicate


// TODO: The TrickleInputReceiver methods here should probably just not return the timestamps to avoid confusion
// TODO: Should the Outcome methods here be a different type? I guess if inputs are not filled in, uncomputed is fine...
// TODO: Separate into an interface (to be returned by the TrickleDefinition) and a class
class TrickleSyncInstance(private val instance: TrickleInstance): TrickleInputReceiver {
    override fun setInputs(changes: List<TrickleInputChange>): Long {
        return instance.setInputs(changes)
    }

    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        return instance.setInput(nodeName, value)
    }

    override fun <K, V> setInput(nodeName: KeyMapNodeName<K, V>, map: Map<K, V>): Long {
        return instance.setInput(nodeName, map)
    }

    override fun <K, V> addKeyInput(nodeName: KeyMapNodeName<K, V>, key: K, value: V): Long {
        return instance.addKeyInput(nodeName, key, value)
    }

    override fun <K, V> removeKeyInput(nodeName: KeyMapNodeName<K, V>, key: K): Long {
        return instance.removeKeyInput(nodeName, key)
    }

    override fun <K, V> editKeys(nodeName: KeyMapNodeName<K, V>, keysAdded: Map<K, V>, keysRemoved: List<K>): Long {
        return instance.editKeys(nodeName, keysAdded, keysRemoved)
    }

    private fun <T> doComputationsFor(nodeName: NodeName<T>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.Nonkeyed(nodeName))
        computeRelevantValues(relevantValuesPred)
    }

    private fun <K, V> doComputationsFor(nodeName: KeyMapNodeName<K, V>) {
        val relevantValuesPred = instance.definition.getRelevantValuesPredicate(ValueId.FullKeyMap(nodeName))
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

    fun <K, V> getValue(nodeName: KeyMapNodeName<K, V>): Map<K, V> {
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

    fun <K, V> getOutcome(nodeName: KeyMapNodeName<K, V>): NodeOutcome<Map<K, V>> {
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
