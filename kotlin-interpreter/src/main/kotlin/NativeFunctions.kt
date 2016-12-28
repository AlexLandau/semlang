package semlang.interpreter

import semlang.api.FunctionId
import semlang.api.Package
import semlang.api.Type
import semlang.interpreter.SemObject
import java.util.*

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>) -> SemObject, val argTypes: List<Type>)

fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
    val map = HashMap<FunctionId, NativeFunction>()

    addIntegerFunctions(map)
    addNaturalFunctions(map)

    return map
}

private fun addIntegerFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val integerPackage = Package(listOf("integer"))

    // integer.times
    val integerTimesId = FunctionId(integerPackage, "times")
    map.put(integerTimesId, NativeFunction(integerTimesId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value * right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, listOf(Type.INTEGER, Type.INTEGER)))

    // integer.plus
    val integerPlusId = FunctionId(integerPackage, "plus")
    map.put(integerPlusId, NativeFunction(integerPlusId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value + right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, listOf(Type.INTEGER, Type.INTEGER)))

    // integer.equals
    val integerEqualsId = FunctionId(integerPackage, "equals")
    map.put(integerEqualsId, NativeFunction(integerEqualsId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Boolean(left.value == right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, listOf(Type.INTEGER, Type.INTEGER)))
}

private fun addNaturalFunctions(map: HashMap<FunctionId, NativeFunction>) {
    val naturalPackage = Package(listOf("natural"))

    // natural.times
    val naturalTimesId = FunctionId(naturalPackage, "times")
    map.put(naturalTimesId, NativeFunction(naturalTimesId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value * right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, listOf(Type.NATURAL, Type.NATURAL)))

    // natural.plus
    val naturalPlusId = FunctionId(naturalPackage, "plus")
    map.put(naturalPlusId, NativeFunction(naturalPlusId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Natural(left.value + right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, listOf(Type.NATURAL, Type.NATURAL)))

    // natural.equals
    val naturalEqualsId = FunctionId(naturalPackage, "equals")
    map.put(naturalEqualsId, NativeFunction(naturalEqualsId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Natural && right is SemObject.Natural) {
            SemObject.Boolean(left.value == right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, listOf(Type.NATURAL, Type.NATURAL)))
}