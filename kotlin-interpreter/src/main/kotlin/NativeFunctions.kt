package semlang.interpreter

import semlang.api.FunctionId
import semlang.api.Package
import java.math.BigInteger
import java.util.*

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>) -> SemObject)

fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
    val map = HashMap<FunctionId, NativeFunction>()

    addIntegerFunctions(map)
    addNaturalFunctions(map)
    addListFunctions(map)
    addTryFunctions(map)

    return map
}

private fun addIntegerFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val integerPackage = Package(listOf("Integer"))

    // Integer.times
    val integerTimesId = FunctionId(integerPackage, "times")
    map.put(integerTimesId, NativeFunction(integerTimesId, { args: List<SemObject> ->
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
    map.put(integerPlusId, NativeFunction(integerPlusId, { args: List<SemObject> ->
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
    map.put(integerMinusId, NativeFunction(integerMinusId, { args: List<SemObject> ->
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
    map.put(integerEqualsId, NativeFunction(integerEqualsId, { args: List<SemObject> ->
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
    map.put(integerFromNaturalId, NativeFunction(integerFromNaturalId, { args: List<SemObject> ->
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
    map.put(naturalTimesId, NativeFunction(naturalTimesId, { args: List<SemObject> ->
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
    map.put(naturalPlusId, NativeFunction(naturalPlusId, { args: List<SemObject> ->
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
    map.put(naturalEqualsId, NativeFunction(naturalEqualsId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Boolean(left.value == right.value)
        } else {
            throw IllegalArgumentException()
        }
    }))
}

private fun addListFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val listPackage = Package(listOf("List"))

    // List.empty
    val listEmptyId = FunctionId(listPackage, "empty")
    map.put(listEmptyId, NativeFunction(listEmptyId, { args: List<SemObject> ->
        SemObject.SemList(ArrayList())
    }))

    // List.append
    val listAppendId = FunctionId(listPackage, "append")
    map.put(listAppendId, NativeFunction(listAppendId, { args: List<SemObject> ->
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
    map.put(listSizeId, NativeFunction(listSizeId, { args: List<SemObject> ->
        val list = args[0]
        if (list is SemObject.SemList) {
            SemObject.Natural(BigInteger.valueOf(list.contents.size.toLong()))
        } else {
            throw IllegalArgumentException()
        }
    }))

    // List.get
    val listGetId = FunctionId(listPackage, "get")
    map.put(listGetId, NativeFunction(listGetId, { args: List<SemObject> ->
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
    map.put(assumeId, NativeFunction(assumeId, { args: List<SemObject> ->
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
