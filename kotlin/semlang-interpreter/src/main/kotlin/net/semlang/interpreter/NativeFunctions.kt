package net.semlang.interpreter

import net.semlang.api.*
import java.math.BigInteger
import java.util.*

typealias InterpreterCallback = (SemObject.FunctionBinding, List<SemObject>) -> SemObject

class NativeFunction(val id: EntityId, val apply: (List<SemObject>, InterpreterCallback) -> SemObject)

fun getNativeFunctions(): Map<EntityId, NativeFunction> {
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

fun toMap(list: ArrayList<NativeFunction>): Map<EntityId, NativeFunction> {
    val map = HashMap<EntityId, NativeFunction>()
    list.forEach { nativeFunction ->
        val previouslyThere = map.put(nativeFunction.id, nativeFunction)
        if (previouslyThere != null) {
            error("Defined two native functions with the ID " + nativeFunction.id)
        }
    }
    return map
}


private fun addBooleanFunctions(list: MutableList<NativeFunction>) {
    val booleanDot = fun(name: String) = EntityId.of("Boolean", name)

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

    // Boolean.all
    list.add(NativeFunction(booleanDot("all"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val allTrue = list.contents.all { obj ->
            val boolean = obj as? SemObject.Boolean ?: typeError()
            boolean.value
        }
        SemObject.Boolean(allTrue)
    }))

}

private fun addIntegerFunctions(list: MutableList<NativeFunction>) {
    val integerDot = fun(name: String) = EntityId.of("Integer", name)

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

    // Integer.sum
    list.add(NativeFunction(integerDot("sum"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val sum = list.contents.foldRight(BigInteger.ZERO, { semObj, sumSoFar ->
            val int = semObj as? SemObject.Integer ?: typeError()
            sumSoFar + int.value
        })
        SemObject.Integer(sum)
    }))
}

private fun addNaturalFunctions(list: MutableList<NativeFunction>) {
    val naturalDot = fun(name: String) = EntityId.of("Natural", name)

    // Natural.fromInteger
    list.add(NativeFunction(naturalDot("fromInteger"), { args: List<SemObject>, _: InterpreterCallback ->
        val integer = args[0] as? SemObject.Integer ?: typeError()
        if (integer.value >= BigInteger.ZERO) {
            SemObject.Try.Success(SemObject.Natural(integer.value))
        } else {
            SemObject.Try.Failure
        }
    }))

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

    // Natural.toPower
    list.add(NativeFunction(naturalDot("toPower"), { args: List<SemObject>, _: InterpreterCallback ->
        val base = args[0] as? SemObject.Natural ?: typeError()
        val exponent = args[1] as? SemObject.Natural ?: typeError()
        // Note: Currently this crashes if the exponent is greater than Integer.MAX_VALUE.
        // We could at least special-case when the base is 0 or 1.
        SemObject.Natural(base.value.pow(exponent.value.intValueExact()))
    }))

    // Natural.bitwiseAnd
    list.add(NativeFunction(naturalDot("bitwiseAnd"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Natural(left.value.and(right.value))
    }))

    // Natural.toBits
    list.add(NativeFunction(naturalDot("toBits"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        // TODO: Get a more efficient internal representation
        val length = left.value.bitLength()
        val bitList = ArrayList<SemObject>()
        if (length == 0) {
            // Return a single 0
            bitList.add(asBit(0))
        } else {
            for (i in (length - 1) downTo 0) {
                val bit = asBit(left.value.testBit(i))
                bitList.add(bit)
            }
        }
        val bitSemList = SemObject.SemList(bitList)
        SemObject.Struct(NativeStruct.BITS_BIG_ENDIAN, listOf(bitSemList))
    }))

    // Natural.toNBits
    list.add(NativeFunction(naturalDot("toNBits"), { args: List<SemObject>, _: InterpreterCallback ->
        val number = args[0] as? SemObject.Natural ?: typeError()
        val numBitsToOutput = (args[1] as? SemObject.Natural)?.value?.intValueExact() ?: typeError()
        // TODO: Get a more efficient internal representation
        val actualNumBits = number.value.bitLength()
        if (actualNumBits > numBitsToOutput) {
            SemObject.Try.Failure
        } else {
            val bitList = ArrayList<SemObject>()
            for (i in (numBitsToOutput - 1) downTo 0) {
                val bit = asBit(number.value.testBit(i))
                bitList.add(bit)
            }
            val bitSemList = SemObject.SemList(bitList)
            SemObject.Try.Success(SemObject.Struct(NativeStruct.BITS_BIG_ENDIAN, listOf(bitSemList)))
        }
    }))

    // Natural.fromBits
    list.add(NativeFunction(naturalDot("fromBits"), { args: List<SemObject>, _: InterpreterCallback ->
        val bitsStruct = args[0] as? SemObject.Struct ?: typeError()

        if (bitsStruct.struct.id != NativeStruct.BITS_BIG_ENDIAN.id) {
            typeError()
        }
        val bitsList = bitsStruct.objects[0] as? SemObject.SemList ?: typeError()
        val bitStructs = bitsList.contents

        val two = BigInteger.valueOf(2L)
        var number = BigInteger.ZERO
        bitStructs.forEach { bitStructArg ->
            number = number.times(two)
            val bitStruct = bitStructArg as? SemObject.Struct ?: typeError()
            val bitValue = bitStruct.objects[0] as? SemObject.Natural ?: typeError()
            number = number.plus(bitValue.value) // 1 or 0
        }
        SemObject.Natural(number)
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

    // Natural.lessThanOrEqualTo
    list.add(NativeFunction(naturalDot("lessThanOrEqualTo"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Boolean(left.value <= right.value)
    }))

    // Natural.greaterThanOrEqualTo
    list.add(NativeFunction(naturalDot("greaterThanOrEqualTo"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        SemObject.Boolean(left.value >= right.value)
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

private fun asBit(value: Int): SemObject.Struct {
    return SemObject.Struct(NativeStruct.BIT, listOf(asNatural(value)))
}

private fun asBit(value: Boolean): SemObject.Struct {
    return asBit(if (value) 1 else 0)
}

private fun asNatural(value: Int): SemObject.Natural {
    return SemObject.Natural(BigInteger.valueOf(value.toLong()))
}

private fun addListFunctions(list: MutableList<NativeFunction>) {
    val listDot = fun(name: String) = EntityId.of("List", name)

    // List.append
    list.add(NativeFunction(listDot("append"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val item = args[1]
        SemObject.SemList(list.contents + item)
    }))

    // List.appendFront
    list.add(NativeFunction(listDot("appendFront"), { args: List<SemObject>, _: InterpreterCallback ->
        val item = args[0]
        val list = args[1] as? SemObject.SemList ?: typeError()
        SemObject.SemList(listOf(item) + list.contents)
    }))

    // List.concatenate
    list.add(NativeFunction(listDot("concatenate"), { args: List<SemObject>, _: InterpreterCallback ->
        val list1 = args[0] as? SemObject.SemList ?: typeError()
        val list2 = args[1] as? SemObject.SemList ?: typeError()
        SemObject.SemList(list1.contents + list2.contents)
    }))

    // List.drop
    list.add(NativeFunction(listDot("drop"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val numToDrop = args[1] as? SemObject.Natural ?: typeError()
        SemObject.SemList(list.contents.drop(numToDrop.value.intValueExact()))
    }))

    // List.lastN
    list.add(NativeFunction(listDot("lastN"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val n = args[1] as? SemObject.Natural ?: typeError()
        SemObject.SemList(list.contents.takeLast(n.value.intValueExact()))
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

    // TODO: Find a way to keep in implementations for standard library functions
//    // List.first
//    list.add(NativeFunction(listDot("first"), { args: List<SemObject>, _: InterpreterCallback ->
//        val list = args[0] as? SemObject.SemList ?: typeError()
//        if (list.contents.isEmpty()) {
//            SemObject.Try.Failure
//        } else {
//            SemObject.Try.Success(list.contents[0])
//        }
//    }))
//    // List.last
//    list.add(NativeFunction(listDot("last"), { args: List<SemObject>, _: InterpreterCallback ->
//        val list = args[0] as? SemObject.SemList ?: typeError()
//        if (list.contents.isEmpty()) {
//            SemObject.Try.Failure
//        } else {
//            SemObject.Try.Success(list.contents.last())
//        }
//    }))

}

private fun addTryFunctions(list: MutableList<NativeFunction>) {
    val tryDot = fun(name: String) = EntityId.of("Try", name)

    // Try.success
    list.add(NativeFunction(tryDot("success"), { args: List<SemObject>, _: InterpreterCallback ->
        SemObject.Try.Success(args[0])
    }))

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

    // Try.isSuccess
    list.add(NativeFunction(tryDot("isSuccess"), { args: List<SemObject>, apply: InterpreterCallback ->
        val theTry = args[0] as? SemObject.Try ?: typeError()
        SemObject.Boolean(theTry is SemObject.Try.Success)
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

    // Try.flatMap
    list.add(NativeFunction(tryDot("flatMap"), { args: List<SemObject>, apply: InterpreterCallback ->
        val theTry = args[0] as? SemObject.Try ?: typeError()
        val theFunction = args[1] as? SemObject.FunctionBinding ?: typeError()
        when (theTry) {
            is SemObject.Try.Success -> {
                apply(theFunction, listOf(theTry.contents))
            }
            is SemObject.Try.Failure -> theTry
        }
    }))
}

private fun addSequenceFunctions(list: MutableList<NativeFunction>) {
    val sequenceDot = fun(name: String) = EntityId.of("Sequence", name)

    val basicSequenceDot = fun(name: String) = ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("BasicSequence", name))

    // Sequence.create
    list.add(NativeFunction(sequenceDot("create"), { args: List<SemObject>, _: InterpreterCallback ->
        val base = args[0]
        val successor = args[1] as? SemObject.FunctionBinding ?: typeError()

        val struct = SemObject.Struct(NativeStruct.BASIC_SEQUENCE, listOf(base, successor))

        // But now we need to turn that into an interface...
        SemObject.Instance(NativeInterface.SEQUENCE, listOf(
                SemObject.FunctionBinding(FunctionBindingTarget.Named(basicSequenceDot("get")), null, listOf(struct, null)),
                SemObject.FunctionBinding(FunctionBindingTarget.Named(basicSequenceDot("first")), null, listOf(struct, null))
        ))
    }))

    // BasicSequence.get(BasicSequence, index)
    list.add(NativeFunction(EntityId.of("BasicSequence", "get"), { args: List<SemObject>, apply: InterpreterCallback ->
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
    list.add(NativeFunction(EntityId.of("BasicSequence", "first"), BasicSequenceFirst@ { args: List<SemObject>, apply: InterpreterCallback ->
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
    val unicodeStringDot = fun(name: String) = EntityId.of("Unicode", "String", name)

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
