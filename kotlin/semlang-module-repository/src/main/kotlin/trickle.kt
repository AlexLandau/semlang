package net.semlang.modules

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
}
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

// TODO: Synchronize stuff
class TrickleInstance(val definition: TrickleDefinition) {
    val nonkeyedNodeValues = HashMap<NodeName<*>, Any>()
    val keyListValues = HashMap<NodeName<*>, KeyList<Any>>()
    val keyedNodeValues = HashMap<KeyedNodeName<*, *>, HashMap<Any, Any>>()

    init {
        for (node in definition.keyListNodes) {
            keyListValues[node.value.name] = KeyList()
        }
    }

    fun <T> setInput(nodeName: NodeName<T>, value: T) {
        nonkeyedNodeValues[nodeName] = value as Any
    }
    fun <T> addKey(nodeName: NodeName<T>, key: Any) {
        val added = keyListValues[nodeName]!!.add(key)
    }
    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T) {

    }

    fun completeSynchronously() {

    }

    fun <T> getNodeValue(nodeName: NodeName<T>): T? {
        return nonkeyedNodeValues[nodeName] as? T
    }
}

sealed class TrickleInstanceOutput {
    data class Result()
    data class NextSteps()
}

class TrickleDefinition(val nonkeyedNodes: Map<String, TrickleNode<*>>, val keyListNodes: Map<String, KeyListNode<*>>) {
    fun instantiate(): TrickleInstance {
        return TrickleInstance(this)
    }
}
class TrickleDefinitionBuilder {
    val nodes = HashMap<NodeName<*>, TrickleNode<*>>()

    fun <T> createInputNode(name: NodeName<T>): TrickleNode<T> {
        checkNameNotUsed(name)
        val node = TrickleNode<T>(name, listOf(), null)
        nodes[name] = node
        return node
    }

    private fun checkNameNotUsed(name: NodeName<*>) {
        if (nodes.containsKey(name)) {
            error("Cannot create two nodes with the same name '$name'")
        }
    }

    fun <T, I1> createNode(name: NodeName<T>, input1: TrickleInput<I1>, fn: (I1) -> T): TrickleNode<T> {
        return createNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1) })
    }
    fun <T, I1, I2> createNode(name: NodeName<T>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (I1, I2) -> T): TrickleNode<T> {
        return createNode(name, listOf(input1), { inputs -> fn(inputs[0] as I1, inputs[1] as I2) })
    }
    fun <T> createNode(name: NodeName<T>, inputs: List<TrickleInput<*>>, fn: (List<*>) -> T): TrickleNode<T> {
        checkNameNotUsed(name)
        val node = TrickleNode<T>(name, inputs, fn)
        nodes[name] = node
        return node
    }

    fun <T> createKeyListInputNode(name: NodeName<T>): TrickleKeyNode<T> {
        checkNameNotUsed(name)
    }

    fun <T> createKeyedInputNode(name: KeyedNodeName<*, T>, keySource: TrickleKeyNode<*>): TrickleKeyedNode<T> {
        checkNameNotUsed(name)
    }
    fun <T, K> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleKeyNode<K>, fn: (K) -> T): TrickleKeyedNode<T> {
        checkNameNotUsed(name)
    }
    fun <T, K, I1> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleKeyNode<K>, input1: TrickleInput<I1>, fn: (K, I1) -> T): TrickleKeyedNode<T> {
        checkNameNotUsed(name)
    }
    fun <T, K, I1, I2> createKeyedNode(name: KeyedNodeName<K, T>, keySource: TrickleKeyNode<K>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (K, I1, I2) -> T): TrickleKeyedNode<T> {
        checkNameNotUsed(name)
    }

    fun build(): TrickleDefinition {
    }

}

interface TrickleInput<T> {

}

class KeyListNode<T>(
    val name: NodeName<T>
)

class TrickleNode<T>(
    val name: NodeName<T>,
    val inputs: List<TrickleInput<*>>,
    val operation: ((List<*>) -> T)?
): TrickleInput<T> {
//    sealed class Type<T> {
//        data class Single<T>(val clazz: Class<T>): Type<T>()
//        data class List<T>(val clazz: Class<T>): Type<kotlin.collections.List<T>>()
//        data class Map<K, V>(val keyClass: Class<K>, val valueClass: Class<V>): Type<kotlin.collections.Map<K, V>>()
//    }
}

//class TrickleNodeBuilder<T>(val name: String, val type: TrickleNode.Type<T>) {
//    val inputs = ArrayList<TrickleNode<out Any>>()
//
//    fun <V> input(input: TrickleNode<V>): TrickleNodeBuilder<T> {
//        inputs.add(input)
//        return this
//    }
//
//    // You actually want to build one "component", not necessarily T itself
//    fun build(logic: () -> T) {
//
//    }
//}

class TrickleKeyNode<T>

// TODO: This might want K in the type parameters
class TrickleKeyedNode<T> {
    // TODO: These should probably be getter-based?
    fun keyedOutput(): TrickleInput<T> {
    }
    fun fullOutput(): TrickleInput<List<T>> {
    }
}

