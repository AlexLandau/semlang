package net.semlang.refill

import java.lang.IllegalArgumentException
import java.util.*
import kotlin.collections.LinkedHashMap

// A reimplementation based on equality of inputs...
class TrickleInstance2 internal constructor(val definition: TrickleDefinition) {
    // Used to ensure all results we receive originated from this instance
    class Id internal constructor() // TODO: This is sort of exposed by the result?
    private val instanceId = Id()

    private val values = LinkedHashMap<ValueId, ValueStore>()
    private class ValueStore(val inputs: List<Any?>, val value: Any?, val failure: TrickleFailure?)

    fun setInputs(changes: List<TrickleInputChange>): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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
    fun reportResult(result: TrickleStepResult2) {
        if (instanceId != result.instanceId) {
            throw IllegalArgumentException("Received a result that did not originate from this instance")
        }

        val valueId = result.valueId
        when (valueId) {
            is ValueId.Nonkeyed -> {
                reportBasicResult(valueId, result.inputs, result.result, result.error)
            }
            is ValueId.FullKeyList -> {
                reportKeyListResult(valueId, result.inputs, result.result, result.error)
            }
            is ValueId.KeyListKey -> TODO()
            is ValueId.Keyed -> {
                reportKeyedResult(valueId, result.inputs, result.result, result.error)
            }
            is ValueId.FullKeyedList -> TODO()
            else -> error("Unhandled valueId type")
        }
    }


    @Synchronized
    private fun setValue(valueId: ValueId, newInputs: List<Any?>, newValue: Any?, newFailure: TrickleFailure?) {
//        var valueHolder = values[valueId]
//        if (valueHolder == null) {
//            valueHolder = TimestampedValue(-1L, null, null)
//            values[valueId] = valueHolder
//        }
//        valueHolder.set(newTimestamp, newValue, newFailure)
        values[valueId] = ValueStore(newInputs, newValue, newFailure)
    }

    private fun reportBasicResult(valueId: ValueId.Nonkeyed, inputs: List<Any?>, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || ) {
            // Note: Conceptually, we only replace the values if they are not equals()-equal, but if they are equals()-equal
            // but not the same object, we also replace it just to avoid possible memory leaks.
            val failure = error?.let { TrickleFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, inputs, result, failure)
        }
    }

    private fun reportKeyedResult(valueId: ValueId.Keyed, inputs: List<Any?>, result: Any?, error: Throwable?) {
        val valueHolder = values[valueId]
        if (valueHolder == null || valueHolder.value !== result) {
            val failure = error?.let { TrickleFailure(mapOf(valueId to it), setOf()) }
            setValue(valueId, timestamp, result, failure)
        }
        // TODO: For keyed nodes, also adjust the values/timestamps of the whole list (?)
    }

    private fun reportKeyListResult(valueId: ValueId.FullKeyList, inputs: List<Any?>, result: Any?, error: Throwable?) {
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

}


class TrickleStepResult2 internal constructor(
    val valueId: ValueId,
    val inputs: List<Any?>,
    val instanceId: TrickleInstance2.Id,
    val result: Any?,
    val error: Throwable?
)
