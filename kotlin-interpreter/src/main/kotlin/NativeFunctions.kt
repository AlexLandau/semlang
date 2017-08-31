package net.semlang.interpreter

import net.semlang.api.*
import java.math.BigInteger
import java.util.*

typealias InterpreterCallback = (SemObject.FunctionBinding, List<SemObject>) -> SemObject

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>, InterpreterCallback) -> SemObject)

fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
    val list = ArrayList<NativeFunction>()

    addBooleanFunctions(list)
    addIntegerFunctions(list)
    addNaturalFunctions(list)
    addListFunctions(list)
    addTryFunctions(list)
    addSequenceFunctions(list)
    addStringFunctions(list)

    return toMap(list)
}

fun toMap(list: ArrayList<NativeFunction>): Map<FunctionId, NativeFunction> {
    val map = HashMap<FunctionId, NativeFunction>()
    list.forEach { nativeFunction ->
        val previouslyThere = map.put(nativeFunction.id, nativeFunction)
        if (previouslyThere != null) {
            error("Defined two native functions with the ID " + nativeFunction.id)
        }
    }
    return map
}


private fun addBooleanFunctions(list: MutableList<NativeFunction>) {
    val booleanDot = fun(name: String) = FunctionId(Package(listOf("Boolean")), name)

    // Boolean.not
    list.add(NativeFunction(booleanDot("not"), { args: List<SemObject>, _: InterpreterCallback ->
        val bool = args[0] as? SemObject.Boolean ?: typeError()
        SemObject.Boolean(!bool.value)
    }))

    // Boolean.and
    list.add(NativeFunction(booleanDot("and"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Boolean ?: typeError()
        val right = args[1] as? SemObject.Boolean ?: typeError()
        SemObject.Boolean(left.value && right.value)
    }))

    // Boolean.or
    list.add(NativeFunction(booleanDot("or"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Boolean ?: typeError()
        val right = args[1] as? SemObject.Boolean ?: typeError()
        SemObject.Boolean(left.value || right.value)
    }))

    // Boolean.any
    list.add(NativeFunction(booleanDot("any"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val anyTrue = list.contents.any { obj ->
            val boolean = obj as? SemObject.Boolean ?: typeError()
            boolean.value
        }
        SemObject.Boolean(anyTrue)
    }))

}

private fun addIntegerFunctions(list: MutableList<NativeFunction>) {
    val integerDot = fun(name: String) = FunctionId(Package(listOf("Integer")), name)

    // Integer.times
    list.add(NativeFunction(integerDot("times"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        SemObject.Integer(left.value * right.value)
    }))

    // Integer.plus
    list.add(NativeFunction(integerDot("plus"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        SemObject.Integer(left.value + right.value)
    }))

    // Integer.minus
    list.add(NativeFunction(integerDot("minus"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        SemObject.Integer(left.value - right.value)
    }))

    // Integer.equals
    list.add(NativeFunction(integerDot("equals"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        SemObject.Boolean(left.value == right.value)
    }))

    // Integer.lessThan
    list.add(NativeFunction(integerDot("lessThan"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        SemObject.Boolean(left.value < right.value)
    }))

    // Integer.greaterThan
    list.add(NativeFunction(integerDot("greaterThan"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        SemObject.Boolean(left.value > right.value)
    }))

    // Integer.fromNatural
    list.add(NativeFunction(integerDot("fromNatural"), { args: List<SemObject>, _: InterpreterCallback ->
        val natural = args[0] as? SemObject.Natural ?: typeError()
        SemObject.Integer(natural.value)
    }))
}

private fun addNaturalFunctions(list: MutableList<NativeFunction>) {
    val naturalDot = fun(name: String) = FunctionId(Package(listOf("Natural")), name)

    // Natural.times
    list.add(NativeFunction(naturalDot("times"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Natural(left.value * right.value)
    }))

    // Natural.plus
    list.add(NativeFunction(naturalDot("plus"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Natural(left.value + right.value)
    }))

    // Natural.divide
    list.add(NativeFunction(naturalDot("divide"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        if (right.value.toInt() == 0) {
            SemObject.Try.Failure
        } else {
            SemObject.Try.Success(SemObject.Natural(left.value / right.value))
        }
    }))

    // Natural.equals
    list.add(NativeFunction(naturalDot("equals"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Boolean(left.value == right.value)
    }))

    // Natural.lessThan
    list.add(NativeFunction(naturalDot("lessThan"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Boolean(left.value < right.value)
    }))

    // Natural.greaterThan
    list.add(NativeFunction(naturalDot("greaterThan"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Boolean(left.value > right.value)
    }))

    // Natural.max
    list.add(NativeFunction(naturalDot("max"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val max = list.contents.maxBy { semObj ->
            (semObj as? SemObject.Natural)?.value ?: error("Runtime type error: Expected Natural")
        }
        if (max == null) {
            SemObject.Try.Failure
        } else {
            SemObject.Try.Success(max)
        }
    }))

    // Natural.lesser
    list.add(NativeFunction(naturalDot("lesser"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Natural(left.value.min(right.value))
    }))

    // Natural.remainder
    list.add(NativeFunction(naturalDot("remainder"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Natural(left.value.remainder(right.value))
    }))

    // Natural.absoluteDifference
    list.add(NativeFunction(naturalDot("absoluteDifference"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Natural((left.value - right.value).abs())
    }))

    // Natural.rangeInclusive
    list.add(NativeFunction(naturalDot("rangeInclusive"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        if (left.value > right.value) {
            SemObject.SemList(listOf())
        } else {
            // This approach reduces issues around large numbers that are still close to each other
            val difference = right.value.minus(left.value).longValueExact()
            val range = (0..difference)
                    .asSequence()
                    .map { left.value.plus(BigInteger.valueOf(it)) }
                    .map { SemObject.Natural(it) }
                    .toList()
            SemObject.SemList(range)
        }
    }))
}

private fun addListFunctions(list: MutableList<NativeFunction>) {
    val listDot = fun(name: String) = FunctionId(Package(listOf("List")), name)

    // List.empty
    list.add(NativeFunction(listDot("empty"), { _: List<SemObject>, _: InterpreterCallback ->
        SemObject.SemList(ArrayList())
    }))

    // List.singleton
    list.add(NativeFunction(listDot("singleton"), { args: List<SemObject>, _: InterpreterCallback ->
        val element = args[0]
        SemObject.SemList(listOf(element))
    }))

    // List.append
    list.add(NativeFunction(listDot("append"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val item = args[1]
        SemObject.SemList(list.contents + item)
    }))

    // List.filter
    list.add(NativeFunction(listDot("filter"), { args: List<SemObject>, apply: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val filter = args[1] as? SemObject.FunctionBinding ?: typeError()
        val filtered = list.contents.filter { semObject ->
            val callbackResult = apply(filter, listOf(semObject))
            (callbackResult as? SemObject.Boolean)?.value ?: error("")
        }
        SemObject.SemList(filtered)
    }))

    // List.map
    list.add(NativeFunction(listDot("map"), { args: List<SemObject>, apply: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val mapping = args[1] as? SemObject.FunctionBinding ?: typeError()
        val mapped = list.contents.map { semObject ->
            apply(mapping, listOf(semObject))
        }
        SemObject.SemList(mapped)
    }))

    // List.reduce
    list.add(NativeFunction(listDot("reduce"), { args: List<SemObject>, apply: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        var result = args[1]
        val reducer = args[2] as? SemObject.FunctionBinding ?: typeError()
        list.contents.forEach { item ->
            result = apply(reducer, listOf(result, item))
        }
        result
    }))

    // List.size
    list.add(NativeFunction(listDot("size"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        SemObject.Natural(BigInteger.valueOf(list.contents.size.toLong()))
    }))

    // List.get
    list.add(NativeFunction(listDot("get"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val index = args[1] as? SemObject.Natural ?: typeError()
        try {
            SemObject.Try.Success(list.contents[index.value.toInt()])
        } catch (e: IndexOutOfBoundsException) {
            SemObject.Try.Failure
        }
    }))


    // List.last
    list.add(NativeFunction(listDot("last"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        if (list.contents.isEmpty()) {
            SemObject.Try.Failure
        } else {
            SemObject.Try.Success(list.contents.last())
        }
    }))

}

private fun addTryFunctions(list: MutableList<NativeFunction>) {
    val tryDot = fun(name: String) = FunctionId(Package(listOf("Try")), name)

    // Try.failure
    list.add(NativeFunction(tryDot("failure"), { _: List<SemObject>, _: InterpreterCallback ->
        SemObject.Try.Failure
    }))

    // Try.assume
    list.add(NativeFunction(tryDot("assume"), { args: List<SemObject>, _: InterpreterCallback ->
        val theTry = args[0] as? SemObject.Try ?: typeError()
        val success = theTry as? SemObject.Try.Success ?: throw IllegalStateException("Try.assume assumed incorrectly")
        success.contents
    }))

    // Try.map
    list.add(NativeFunction(tryDot("map"), { args: List<SemObject>, apply: InterpreterCallback ->
        val theTry = args[0] as? SemObject.Try ?: typeError()
        val theFunction = args[1] as? SemObject.FunctionBinding ?: typeError()
        when (theTry) {
            is SemObject.Try.Success -> {
                SemObject.Try.Success(apply(theFunction, listOf(theTry.contents)))
            }
            is SemObject.Try.Failure -> theTry
        }
    }))
}

private fun addSequenceFunctions(list: MutableList<NativeFunction>) {
    val sequenceDot = fun(name: String) = FunctionId(Package(listOf("Sequence")), name)

    val basicSequenceDot = fun(name: String) = FunctionId(Package(listOf("BasicSequence")), name)

    // Sequence.create
    list.add(NativeFunction(sequenceDot("create"), { args: List<SemObject>, _: InterpreterCallback ->
        val base = args[0]
        val successor = args[1] as? SemObject.FunctionBinding ?: typeError()

        val struct = SemObject.Struct(NativeStruct.BASIC_SEQUENCE, listOf(base, successor))

        // But now we need to turn that into an interface...
        SemObject.Instance(NativeInterface.SEQUENCE, struct, listOf(
                SemObject.FunctionBinding(basicSequenceDot("get"), listOf(struct, null)),
                SemObject.FunctionBinding(basicSequenceDot("first"), listOf(struct, null))
        ))
    }))

    // BasicSequence.get(BasicSequence, index)
    list.add(NativeFunction(basicSequenceDot("get"), { args: List<SemObject>, apply: InterpreterCallback ->
        val sequence = args[0] as? SemObject.Struct ?: typeError()
        val index = args[1] as? SemObject.Natural ?: typeError()
        if (sequence.struct.id != NativeStruct.BASIC_SEQUENCE.id) {
            typeError()
        }
        val successor = sequence.objects[1] as? SemObject.FunctionBinding ?: typeError()
        var value = sequence.objects[0]
        // TODO: Obscure error case: Value of index is greater than Long.MAX_VALUE
        for (i in 1..index.value.longValueExact()) {
            value = apply(successor, listOf(value))
        }
        value

    }))

    // BasicSequence.first
    list.add(NativeFunction(basicSequenceDot("first"), BasicSequenceFirst@ { args: List<SemObject>, apply: InterpreterCallback ->
        val sequence = args[0] as? SemObject.Struct ?: typeError()
        val predicate = args[1] as? SemObject.FunctionBinding ?: typeError()
        if (sequence.struct.id != NativeStruct.BASIC_SEQUENCE.id) {
            typeError()
        }
        val successor = sequence.objects[1] as? SemObject.FunctionBinding ?: typeError()
        var value = sequence.objects[0]
        while (true) {
            val passes = apply(predicate, listOf(value)) as? SemObject.Boolean ?: typeError()
            if (passes.value) {
                return@BasicSequenceFirst value
            }
            value = apply(successor, listOf(value))
        }
        error("Unreachable") // TODO: Better way to make Kotlin compile here?
    }))

}

private fun addStringFunctions(list: MutableList<NativeFunction>) {
    val unicodeStringDot = fun(name: String) = FunctionId(Package(listOf("Unicode", "String")), name)

    // Unicode.String.length
    list.add(NativeFunction(unicodeStringDot("length"), { args: List<SemObject>, _: InterpreterCallback ->
        val theString = args[0] as? SemObject.UnicodeString ?: typeError()
        // TODO: At some point, we can have better internal string representations that aren't O(n) here
        val codePointCount = theString.contents.codePointCount(0, theString.contents.length)
        SemObject.Natural(BigInteger.valueOf(codePointCount.toLong()))
    }))
}

private fun typeError(): Nothing {
    error("Runtime type error")
}
