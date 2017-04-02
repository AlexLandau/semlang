package semlang.interpreter

import semlang.api.FunctionId
import semlang.api.Package
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

    return toMap(list)
}

fun toMap(list: ArrayList<NativeFunction>): Map<FunctionId, NativeFunction> {
    val map = HashMap<FunctionId, NativeFunction>()
    list.forEach { nativeFunction ->
        map.put(nativeFunction.id, nativeFunction)
    }
    return map
}


private fun addBooleanFunctions(list: MutableList<NativeFunction>) {
    val booleanDot = fun(name: String) = FunctionId(Package(listOf("Boolean")), name)

    // Boolean.or
    list.add(NativeFunction(booleanDot("or"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Boolean && right is SemObject.Boolean) {
            SemObject.Boolean(left.value || right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))
}

private fun addIntegerFunctions(list: MutableList<NativeFunction>) {
    val integerDot = fun(name: String) = FunctionId(Package(listOf("Integer")), name)

    // Integer.times
    list.add(NativeFunction(integerDot("times"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value * right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.plus
    list.add(NativeFunction(integerDot("plus"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value + right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.minus
    list.add(NativeFunction(integerDot("minus"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value - right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.equals
    list.add(NativeFunction(integerDot("equals"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Boolean(left.value == right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.fromNatural
    list.add(NativeFunction(integerDot("fromNatural"), { args: List<SemObject>, _: InterpreterCallback ->
        val natural = args[0]
        if (natural is SemObject.Natural) {
            SemObject.Integer(natural.value)
        } else {
            throw IllegalArgumentException()
        }
    }))
}

private fun addNaturalFunctions(list: MutableList<NativeFunction>) {
    val naturalDot = fun(name: String) = FunctionId(Package(listOf("Natural")), name)

    // Natural.times
    list.add(NativeFunction(naturalDot("times"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value * right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.plus
    list.add(NativeFunction(naturalDot("plus"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value + right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.equals
    list.add(NativeFunction(naturalDot("equals"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Boolean(left.value == right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.max
    list.add(NativeFunction(naturalDot("min"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0]
        if (list is SemObject.SemList) {
            val max = list.contents.maxBy { semObj ->
                (semObj as? SemObject.Natural)?.value ?: error("Runtime type error: Expected Natural")
            }
            if (max == null) {
                SemObject.Try.Failure;
            } else {
                SemObject.Try.Success(max);
            }
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.min
    list.add(NativeFunction(naturalDot("min"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value.min(right.value))
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.remainder
    list.add(NativeFunction(naturalDot("remainder"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value.remainder(right.value))
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.absoluteDifference
    list.add(NativeFunction(naturalDot("absoluteDifference"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural((left.value - right.value).abs())
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.rangeInclusive
    list.add(NativeFunction(naturalDot("rangeInclusive"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
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
//            SemObject.Natural((left.value - right.value).abs())
        } else {
            throw IllegalArgumentException()
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
        val list = args[0]
        val filter = args[1]
        if (list is SemObject.SemList
                && filter is SemObject.FunctionBinding) {
            val filtered = list.contents.filter { semObject ->
                val callbackResult = apply(filter, listOf(semObject))
                (callbackResult as? SemObject.Boolean)?.value ?: error("")
            }
            SemObject.SemList(filtered)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // List.reduce
    list.add(NativeFunction(listDot("reduce"), { args: List<SemObject>, apply: InterpreterCallback ->
        val list = args[0]
        var result = args[1]
        val reducer = args[2]
        if (list is SemObject.SemList
                && reducer is SemObject.FunctionBinding) {
            list.contents.forEach { item ->
                result = apply(reducer, listOf(result, item))
            }
            result
        } else {
            throw IllegalArgumentException()
        }
    }))

    // List.size
    list.add(NativeFunction(listDot("size"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0]
        if (list is SemObject.SemList) {
            SemObject.Natural(BigInteger.valueOf(list.contents.size.toLong()))
        } else {
            throw IllegalArgumentException()
        }
    }))

    // List.get
    list.add(NativeFunction(listDot("get"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0]
        val index = args[1]
        if (list is SemObject.SemList && index is SemObject.Natural) {
            try {
                SemObject.Try.Success(list.contents[index.value.toInt()])
            } catch (e: IndexOutOfBoundsException) {
                SemObject.Try.Failure
            }
        } else {
            throw IllegalArgumentException()
        }
    }))
}

private fun addTryFunctions(list: MutableList<NativeFunction>) {
    val tryDot = fun(name: String) = FunctionId(Package(listOf("Try")), name)

    // Try.assume
    list.add(NativeFunction(tryDot("assume"), { args: List<SemObject>, _: InterpreterCallback ->
        val theTry = args[0]
        if (theTry is SemObject.Try) {
            if (theTry is SemObject.Try.Success) {
                theTry.contents
            } else {
                throw IllegalStateException("Try.assume assumed incorrectly")
            }
        } else {
            throw IllegalArgumentException()
        }
    }))
}

private fun addSequenceFunctions(list: MutableList<NativeFunction>) {
    val sequenceStructId = FunctionId.of("Sequence")
    val sequenceDot = fun(name: String) = FunctionId(Package(listOf("Sequence")), name)

    // Sequence.get
    list.add(NativeFunction(sequenceDot("get"), { args: List<SemObject>, apply: InterpreterCallback ->
        val sequence = args[0]
        val index = args[1]
        if (sequence is SemObject.Struct
                && index is SemObject.Natural
                && sequence.struct.id == sequenceStructId) {
            val successor = sequence.objects[1]
            if (successor is SemObject.FunctionBinding) {
                var value = sequence.objects[0]
                // TODO: Obscure error case: Value of index is greater than Long.MAX_VALUE
                for (i in 1..index.value.longValueExact()) {
                    value = apply(successor, listOf(value))
                }
                value
            } else {
                error("")
            }
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Sequence.first
    list.add(NativeFunction(sequenceDot("first"), SequenceFirst@ { args: List<SemObject>, apply: InterpreterCallback ->
        val sequence = args[0]
        val predicate = args[1]
        if (sequence is SemObject.Struct
                && predicate is SemObject.FunctionBinding
                && sequence.struct.id == sequenceStructId) {
            val successor = sequence.objects[1]
            if (successor is SemObject.FunctionBinding) {
                var value = sequence.objects[0]
                while (true) {
                    val passes = apply(predicate, listOf(value))
                    if (passes !is SemObject.Boolean) {
                        error("")
                    }
                    if (passes.value) {
                        return@SequenceFirst value
                    }
                    value = apply(successor, listOf(value))
                }
                error("Unreachable") // TODO: Better way to make Kotlin compile here?
            } else {
                error("")
            }
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Sequence.map
    // TODO: This might actually be easier as a Semlang function?
//    list.add(NativeFunction(sequenceDot("map"), SequenceFirst@ { args: List<SemObject>, apply: InterpreterCallback ->
//        val sequence = args[0] as? SemObject.Struct ?: typeError()
//        if (sequence.struct.id != sequenceStructId) typeError()
//        val predicate = args[1] as? SemObject.FunctionBinding ?: typeError()
//
//
//
//        SemObject.Struct(sequenceStructId, initialValue, function)
//    }))
}

private fun typeError(): Nothing {
    error("Runtime type error")
}
