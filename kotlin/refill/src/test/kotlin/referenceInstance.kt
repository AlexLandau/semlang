package net.semlang.refill

/**
 * A simple "reference" reimplementation of a basic instance API for use in fuzz testing. This recomputes all outputs
 * after every input change, instead of trying to minimize work in any way.
 *
 * Only the KeyList is shared with the main instance implementation.
 */
internal class ReferenceInstance(private val definition: TrickleDefinition) {
    private val basicInputs = HashMap<NodeName<Int>, Int?>()
    private val keyListInputs = HashMap<KeyListNodeName<Int>, KeyList<Int>>()
    private val keyedInputs = HashMap<KeyedNodeName<Int, Int>, HashMap<Int, Int>>()
    private val basicOutputs = HashMap<NodeName<Int>, Int>()
    private val keyListOutputs = HashMap<KeyListNodeName<Int>, KeyList<Int>>()
    private val keyedOutputs = HashMap<KeyedNodeName<Int, Int>, HashMap<Int, Int>>()

    init {
        for (keyListNode in definition.keyListNodes.values) {
            if (keyListNode.operation == null) {
                keyListInputs[keyListNode.name as KeyListNodeName<Int>] = KeyList.empty()
            }
        }
    }

    private fun recomputeState() {
        // TODO: Don't forget to pare keyed inputs down to the right set of keys
        for (nodeName in definition.topologicalOrdering) {
            when (nodeName) {
                is NodeName<*> -> {
                    nodeName as NodeName<Int>
                    if (isInput(nodeName)) {
                        // Do nothing
                    } else {
                        // TODO: Compute new value
                    }
                }
                is KeyListNodeName<*> -> {
                    nodeName as KeyListNodeName<Int>
                    if (isInput(nodeName)) {
                        // Do nothing
                    } else {
                        // TODO: Compute new value
                    }
                }
                is KeyedNodeName<*, *> -> {
                    nodeName as KeyedNodeName<Int, Int>
                    if (isInput(nodeName)) {
                        // TODO: Prune inputs
                    } else {
                        // TODO: Compute new value
                    }
                }
            }
        }
    }

    fun setInput(name: NodeName<Int>, value: Int) {
        basicInputs[name] = value
        recomputeState()
    }

    fun addKeyInput(name: KeyListNodeName<Int>, key: Int) {
        keyListInputs[name] = keyListInputs[name]!!.add(key)
        recomputeState()
    }

    fun removeKeyInput(name: KeyListNodeName<Int>, key: Int) {
        keyListInputs[name] = keyListInputs[name]!!.remove(key)
        recomputeState()
    }

    fun setInput(name: KeyListNodeName<Int>, value: List<Int>) {
        keyListInputs[name] = KeyList.copyOf(value)
        recomputeState()
    }

    fun setKeyedInput(name: KeyedNodeName<Int, Int>, key: Int, value: Int) {
        if (keyedInputs[name] == null) {
            keyedInputs[name] = HashMap()
        }
        keyedInputs[name]!!.put(key, value)
        recomputeState()
    }

    fun setInputs(changes: List<TrickleInputChange>) {
        for (change in changes) {
            when (change) {
                is TrickleInputChange.SetBasic<*> -> setInput(change.nodeName as NodeName<Int>, change.value as Int)
                is TrickleInputChange.SetKeys<*> -> setInput(change.nodeName as KeyListNodeName<Int>, change.value as List<Int>)
                is TrickleInputChange.AddKey<*> -> addKeyInput(change.nodeName as KeyListNodeName<Int>, change.key as Int)
                is TrickleInputChange.RemoveKey<*> -> removeKeyInput(change.nodeName as KeyListNodeName<Int>, change.key as Int)
                is TrickleInputChange.SetKeyed<*, *> -> setKeyedInput(change.nodeName as KeyedNodeName<Int, Int>, change.key as Int, change.value as Int)
            }
        }
    }

    fun getNodeOutcome(name: NodeName<Int>): NodeOutcome<Int> {
        if (isInput(name)) {
            return basicInputs[name]?.let { NodeOutcome.Computed(it) } ?: NodeOutcome.NotYetComputed.get()
        } else {
            return basicOutputs[name]?.let { NodeOutcome.Computed(it) } ?: NodeOutcome.NotYetComputed.get()
        }
    }

    fun getNodeOutcome(name: KeyListNodeName<Int>): NodeOutcome<List<Int>> {

    }

    fun getNodeOutcome(name: KeyedNodeName<Int, Int>): NodeOutcome<List<Int>> {

    }

    fun getNodeOutcome(name: KeyedNodeName<Int, Int>, key: Int): NodeOutcome<Int> {

    }

    private fun isInput(name: NodeName<Int>): Boolean {
        return definition.nonkeyedNodes[name]!!.operation == null
    }

    private fun isInput(name: KeyListNodeName<Int>): Boolean {
        return definition.keyListNodes[name]!!.operation == null
    }

    private fun isInput(name: KeyedNodeName<Int, Int>): Boolean {
        return definition.keyedNodes[name]!!.operation == null
    }
}