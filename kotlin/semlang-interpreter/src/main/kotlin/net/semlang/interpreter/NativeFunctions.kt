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
    addListFunctions(list)
    addTryFunctions(list)
    addSequenceFunctions(list)
    addNativeThreadedFunctions(list)

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

    // TODO: Use as optimizations for the standard library
//    // Boolean.any
//    list.add(NativeFunction(booleanDot("any"), { args: List<SemObject>, _: InterpreterCallback ->
//        val list = args[0] as? SemObject.SemList ?: typeError()
//        val anyTrue = list.contents.any { obj ->
//            val boolean = obj as? SemObject.Boolean ?: typeError()
//            boolean.value
//        }
//        SemObject.Boolean(anyTrue)
//    }))
//
//    // Boolean.all
//    list.add(NativeFunction(booleanDot("all"), { args: List<SemObject>, _: InterpreterCallback ->
//        val list = args[0] as? SemObject.SemList ?: typeError()
//        val allTrue = list.contents.all { obj ->
//            val boolean = obj as? SemObject.Boolean ?: typeError()
//            boolean.value
//        }
//        SemObject.Boolean(allTrue)
//    }))

}

private fun addIntegerFunctions(list: MutableList<NativeFunction>) {
    val integerDot = fun(name: String) = EntityId.of("Integer", name)

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

    // Integer.times
    list.add(NativeFunction(integerDot("times"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        SemObject.Integer(left.value * right.value)
    }))

    // Integer.dividedBy
    list.add(NativeFunction(integerDot("dividedBy"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        if (right.value == BigInteger.ZERO) {
            SemObject.Try.Failure
        } else {
            SemObject.Try.Success(SemObject.Integer(left.value / right.value))
        }
    }))

    // Integer.modulo
    list.add(NativeFunction(integerDot("modulo"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Integer ?: typeError()
        val right = args[1] as? SemObject.Integer ?: typeError()
        if (right.value < BigInteger.ONE) {
            SemObject.Try.Failure
        } else {
            SemObject.Try.Success(SemObject.Integer(left.value.mod(right.value)))
        }
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

    // TODO: Future native library optimization
//    // Integer.sum
//    list.add(NativeFunction(integerDot("sum"), { args: List<SemObject>, _: InterpreterCallback ->
//        val list = args[0] as? SemObject.SemList ?: typeError()
//        val sum = list.contents.foldRight(BigInteger.ZERO, { semObj, sumSoFar ->
//            val int = semObj as? SemObject.Integer ?: typeError()
//            sumSoFar + int.value
//        })
//        SemObject.Integer(sum)
//    }))
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

    // List.subList
    list.add(NativeFunction(listDot("subList"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val startInclusive = (args[1] as? SemObject.Natural)?.value?.intValueExact() ?: typeError()
        val endExclusive = (args[2] as? SemObject.Natural)?.value?.intValueExact() ?: typeError()

        if (startInclusive > endExclusive || endExclusive > list.contents.size) {
            SemObject.Try.Failure
        } else {
            val sublist = list.contents.subList(startInclusive, endExclusive)
            SemObject.Try.Success(SemObject.SemList(sublist))
        }
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

    // Try.orElse
    list.add(NativeFunction(tryDot("orElse"), { args: List<SemObject>, apply: InterpreterCallback ->
        val theTry = args[0] as? SemObject.Try ?: typeError()
        val alternative = args[1]
        when (theTry) {
            is SemObject.Try.Success -> {
                theTry.contents
            }
            is SemObject.Try.Failure -> alternative
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

private fun addNativeThreadedFunctions(list: MutableList<NativeFunction>) {

    // TextOut.print
    list.add(NativeFunction(EntityId.of("TextOut", "print"), { args: List<SemObject>, _: InterpreterCallback ->
        val out = args[0] as? SemObject.TextOut ?: typeError()
        val text = args[1] as? SemObject.UnicodeString ?: typeError()

//        val struct = SemObject.Struct(NativeStruct.BASIC_SEQUENCE, listOf(base, successor))
//
//        // But now we need to turn that into an interface...
//        SemObject.Instance(NativeInterface.SEQUENCE, listOf(
//                SemObject.FunctionBinding(FunctionBindingTarget.Named(basicSequenceDot("get")), null, listOf(struct, null)),
//                SemObject.FunctionBinding(FunctionBindingTarget.Named(basicSequenceDot("first")), null, listOf(struct, null))
//        ))
        out.out.print(text.contents)
        out
    }))

}

private fun addStringFunctions(list: MutableList<NativeFunction>) {
    val unicodeStringDot = fun(name: String) = EntityId.of("Unicode", "String", name)

    // TODO: Future standard library optimization
    // Unicode.String.length
//    list.add(NativeFunction(unicodeStringDot("length"), { args: List<SemObject>, _: InterpreterCallback ->
//        val theString = args[0] as? SemObject.UnicodeString ?: typeError()
//        // TODO: At some point, we can have better internal string representations that aren't O(n) here
//        val codePointCount = theString.contents.codePointCount(0, theString.contents.length)
//        SemObject.Natural(BigInteger.valueOf(codePointCount.toLong()))
//    }))
}

private fun typeError(): Nothing {
    error("Runtime type error")
}
