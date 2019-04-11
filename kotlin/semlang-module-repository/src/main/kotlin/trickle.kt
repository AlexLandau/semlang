package net.semlang.modules

import net.semlang.parser.parseConfigFileString

/*
 * Trickle: A Kotlin/JVM library (...if I choose to extract it) for defining asynchronous tasks in terms of each
 * other, going beyond RxJava by adding support for minimal recomputation when portions of the input change.
 */

// TODO: Concept to use: "Keys" that support equality, and a list of keys is used as the basis for maps and per-item steps
// A step can be in the "context" of a KeyList, in which case when it accepts other things in that context as inputs, you
// get the same key's equivalent (a single item) instead of the whole list

// TODO: "CatchNode" for error handling; nodes can raise an error in their output (one way or another) and the CatchNode
// will turn errors or successes into

// TODO: Synchronize stuff
class TrickleState(val definition: TrickleDefinition) {

    fun getResultOrNextSteps(): Something {

    }

    fun reportInputChanged() {

    }

    fun reportStepResult() {

    }
}

data class TrickleDefinition()

interface TrickleInput<T> {

}

class TrickleNode<T>(val name: String, val type: TrickleNode.Type<T>): TrickleInput<T> {
    sealed class Type<T> {
        data class Single<T>(val clazz: Class<T>): Type<T>()
        data class List<T>(val clazz: Class<T>): Type<kotlin.collections.List<T>>()
        data class Map<K, V>(val keyClass: Class<K>, val valueClass: Class<V>): Type<kotlin.collections.Map<K, V>>()
    }
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

fun <T> createInputNode(name: String): TrickleNode<T> {
}
fun <T, I1> createNode(name: String, input1: TrickleInput<I1>, fn: (I1) -> T): TrickleNode<T> {
}

class TrickleKeyNode<T>

fun <T> createKeyListInputNode(name: String): TrickleKeyNode<T> {
}

// TODO: This might want K in the type parameters
class TrickleKeyedNode<T> {
    // TODO: These should probably be getter-based?
    fun keyedOutput(): TrickleInput<T> {
    }
    fun fullOutput(): TrickleInput<List<T>> {
    }
}

fun <T> createKeyedInputNode(name: String, keySource: TrickleKeyNode<*>): TrickleKeyedNode<T> {
}
fun <T, K> createKeyedNode(name: String, keySource: TrickleKeyNode<K>, fn: (K) -> T): TrickleKeyedNode<T> {
}
fun <T, K, I1> createKeyedNode(name: String, keySource: TrickleKeyNode<K>, input1: TrickleInput<I1>, fn: (K, I1) -> T): TrickleKeyedNode<T> {
}
fun <T, K, I1, I2> createKeyedNode(name: String, keySource: TrickleKeyNode<K>, input1: TrickleInput<I1>, input2: TrickleInput<I2>, fn: (K, I1, I2) -> T): TrickleKeyedNode<T> {
}


data class SourceText(val id: String, val text: String)
fun getFilesParsingDefinition(): TrickleDefinition {
    val stringClass = kotlin.String::class.java
    val configTextInput = createInputNode<String>("configText")
    val parsedConfig = createNode("parsedConfig", configTextInput, { text ->
        // Parse the config
        parseConfigFileString(text)
    })
    val sourceFileUrls = createKeyListInputNode<String>("sourceFileUrls")
    val sourceTexts = createKeyedInputNode<SourceText>("sourceTexts", sourceFileUrls)

    val irs = createKeyedNode("irs", sourceFileUrls, sourceTexts.keyedOutput(), { fileUrl: String, sourceText: SourceText ->
        // Do something, make the IR
    })
    val typeSummaries = createKeyedNode("typeSummaries", sourceFileUrls, irs.keyedOutput(), parsedConfig, { key, ir, config ->
        // Do something, make the type summary
    })
}