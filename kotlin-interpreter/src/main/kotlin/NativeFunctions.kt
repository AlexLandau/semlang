package semlang.interpreter

import semlang.api.FunctionId
import semlang.api.Package
import java.math.BigInteger
import java.util.*

typealias InterpreterCallback = (SemObject.FunctionBinding, List<SemObject>) -> SemObject

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>, InterpreterCallback) -> SemObject)

fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
    val map = HashMap<FunctionId, NativeFunction>()

    addIntegerFunctions(map)
    addNaturalFunctions(map)
    addListFunctions(map)
    addTryFunctions(map)
    addSequenceFunctions(map)

    return map
}

private fun addIntegerFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val integerPackage = Package(listOf("Integer"))

    // Integer.times
    val integerTimesId = FunctionId(integerPackage, "times")
    map.put(integerTimesId, NativeFunction(integerTimesId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value * right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.plus
    val integerPlusId = FunctionId(integerPackage, "plus")
    map.put(integerPlusId, NativeFunction(integerPlusId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value + right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.minus
    val integerMinusId = FunctionId(integerPackage, "minus")
    map.put(integerMinusId, NativeFunction(integerMinusId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value - right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.equals
    val integerEqualsId = FunctionId(integerPackage, "equals")
    map.put(integerEqualsId, NativeFunction(integerEqualsId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Boolean(left.value == right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Integer.fromNatural
    val integerFromNaturalId = FunctionId(integerPackage, "fromNatural")
    map.put(integerFromNaturalId, NativeFunction(integerFromNaturalId, { args: List<SemObject>, _: InterpreterCallback ->
        val natural = args[0]
        if (natural is SemObject.Natural) {
            SemObject.Integer(natural.value)
        } else {
            throw IllegalArgumentException()
        }
    }))
}

private fun addNaturalFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val naturalPackage = Package(listOf("Natural"))

    // Natural.times
    val naturalTimesId = FunctionId(naturalPackage, "times")
    map.put(naturalTimesId, NativeFunction(naturalTimesId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value * right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.plus
    val naturalPlusId = FunctionId(naturalPackage, "plus")
    map.put(naturalPlusId, NativeFunction(naturalPlusId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value + right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.equals
    val naturalEqualsId = FunctionId(naturalPackage, "equals")
    map.put(naturalEqualsId, NativeFunction(naturalEqualsId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Boolean(left.value == right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.min
    val naturalMinId = FunctionId(naturalPackage, "min")
    map.put(naturalMinId, NativeFunction(naturalMinId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value.min(right.value))
        } else {
            throw IllegalArgumentException()
        }
    }))

    // Natural.absoluteDifference
    val naturalAbsoluteDifferenceId = FunctionId(naturalPackage, "absoluteDifference")
    map.put(naturalAbsoluteDifferenceId, NativeFunction(naturalAbsoluteDifferenceId, { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural((left.value - right.value).abs())
        } else {
            throw IllegalArgumentException()
        }
    }))
}

private fun addListFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val listPackage = Package(listOf("List"))

    // List.empty
    val listEmptyId = FunctionId(listPackage, "empty")
    map.put(listEmptyId, NativeFunction(listEmptyId, { _: List<SemObject>, _: InterpreterCallback ->
        SemObject.SemList(ArrayList())
    }))

    // List.append
    val listAppendId = FunctionId(listPackage, "append")
    map.put(listAppendId, NativeFunction(listAppendId, { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0]
        val item = args[1]
        if (list is SemObject.SemList) {
            SemObject.SemList(list.contents + item)
        } else {
            throw IllegalArgumentException()
        }
    }))

    // List.size
    val listSizeId = FunctionId(listPackage, "size")
    map.put(listSizeId, NativeFunction(listSizeId, { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0]
        if (list is SemObject.SemList) {
            SemObject.Natural(BigInteger.valueOf(list.contents.size.toLong()))
        } else {
            throw IllegalArgumentException()
        }
    }))

    // List.get
    val listGetId = FunctionId(listPackage, "get")
    map.put(listGetId, NativeFunction(listGetId, { args: List<SemObject>, _: InterpreterCallback ->
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

private fun addTryFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val tryPackage = Package(listOf("Try"))

    // Try.assume
    val assumeId = FunctionId(tryPackage, "assume")
    map.put(assumeId, NativeFunction(assumeId, { args: List<SemObject>, _: InterpreterCallback ->
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

private fun addSequenceFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val sequencePackage = Package(listOf("Sequence"))
    val sequenceStructId = FunctionId.of("Sequence")

    // Sequence.get
    val getId = FunctionId(sequencePackage, "get")
    map.put(getId, NativeFunction(getId, { args: List<SemObject>, apply: InterpreterCallback ->
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
    val firstId = FunctionId(sequencePackage, "first")
    map.put(firstId, NativeFunction(firstId, SequenceFirst@ { args: List<SemObject>, apply: InterpreterCallback ->
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
