package semlang.interpreter

import semlang.api.FunctionId
import semlang.api.Package
import java.math.BigInteger
import java.util.*

typealias InterpreterCallback = (SemObject.FunctionBinding, List<SemObject>) -> SemObject

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>, InterpreterCallback) -> SemObject)

fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
    val list = ArrayList<NativeFunction>()

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
}

private fun addListFunctions(list: MutableList<NativeFunction>) {
    val listDot = fun(name: String) = FunctionId(Package(listOf("List")), name)

    // List.empty
    list.add(NativeFunction(listDot("empty"), { _: List<SemObject>, _: InterpreterCallback ->
        SemObject.SemList(ArrayList())
    }))

    // List.append
    list.add(NativeFunction(listDot("append"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0]
        val item = args[1]
        if (list is SemObject.SemList) {
            SemObject.SemList(list.contents + item)
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
                // TODO: Obscure error case: Value of index is greater than Integer.MAX_VALUE
                for (i in 1..index.value.toInt()) {
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
}
