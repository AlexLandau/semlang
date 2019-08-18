package net.semlang.refill

import java.util.ArrayList

internal class RecordingRawInstance(val delegate: TrickleInstance): TrickleRawInstance {
    override val definition: TrickleDefinition
        get() = delegate.definition
    // It would be nice to record these as actual objects, but that would take some effort
    val records = ArrayList<String>()

    @Synchronized
    override fun setInputs(changes: List<TrickleInputChange>): Long {
        records.add("Calling setInputs")
        records.add("  changes: $changes")
        val result = delegate.setInputs(changes)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        records.add("Calling setInput")
        records.add("  nodeName: $nodeName")
        records.add("  value: $value")
        val result = delegate.setInput(nodeName, value)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> setInput(nodeName: KeyMapNodeName<T>, list: List<T>): Long {
        records.add("Calling setInput")
        records.add("  nodeName: $nodeName")
        records.add("  list: $list")
        val result = delegate.setInput(nodeName, list)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> addKeyInput(nodeName: KeyMapNodeName<T>, key: T): Long {
        records.add("Calling addKeyInput")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.addKeyInput(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> removeKeyInput(nodeName: KeyMapNodeName<T>, key: T): Long {
        records.add("Calling removeKeyInput")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.removeKeyInput(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun getNextSteps(): List<TrickleStep> {
        records.add("Calling getNextSteps")
        val result = delegate.getNextSteps()
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun completeSynchronously() {
        records.add("Calling completeSynchronously")
        delegate.completeSynchronously()
        records.add("Done with completeSynchronously")
    }

    @Synchronized
    override fun reportResult(result: TrickleStepResult) {
        records.add("Calling reportResult")
        records.add("  result (arg): $result")
        delegate.reportResult(result)
    }

    @Synchronized
    override fun <T> getNodeValue(nodeName: NodeName<T>): T {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeValue(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> getNodeValue(nodeName: KeyMapNodeName<T>): List<T> {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeValue(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>, key: K): V {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.getNodeValue(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeValue(nodeName: KeyedNodeName<K, V>): List<V> {
        records.add("Calling getNodeValue")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeValue(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> getNodeOutcome(nodeName: NodeName<T>): NodeOutcome<T> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeOutcome(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <T> getNodeOutcome(nodeName: KeyMapNodeName<T>): NodeOutcome<List<T>> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeOutcome(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>, key: K): NodeOutcome<V> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        records.add("  key: $key")
        val result = delegate.getNodeOutcome(nodeName, key)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun <K, V> getNodeOutcome(nodeName: KeyedNodeName<K, V>): NodeOutcome<List<V>> {
        records.add("Calling getNodeOutcome")
        records.add("  nodeName: $nodeName")
        val result = delegate.getNodeOutcome(nodeName)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun getLastUpdateTimestamp(valueId: ValueId): Long {
        records.add("Calling getLastUpdateTimestamp")
        records.add("  valueId: $valueId")
        val result = delegate.getLastUpdateTimestamp(valueId)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    override fun getLatestTimestampWithValue(valueId: ValueId): Long {
        records.add("Calling getLatestTimestampWithValue")
        records.add("  valueId: $valueId")
        val result = delegate.getLatestTimestampWithValue(valueId)
        records.add("  result: $result")
        return result
    }

    @Synchronized
    fun getRecording(): List<String> {
        return records
    }
}
