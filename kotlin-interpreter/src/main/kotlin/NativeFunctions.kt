package semlang.interpreter

import semlang.api.FunctionId
import semlang.api.Package
import semlang.api.Type
import java.util.*

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>) -> SemObject, val argTypes: List<Type>)

fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
    val map = HashMap<FunctionId, NativeFunction>()

    addIntegerFunctions(map)
    addNaturalFunctions(map)

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
    }, listOf(Type.INTEGER, Type.INTEGER)))

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
    }, listOf(Type.INTEGER, Type.INTEGER)))

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
    }, listOf(Type.INTEGER, Type.INTEGER)))

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
    }, listOf(Type.INTEGER, Type.INTEGER)))

    // Integer.fromNatural
    val integerFromNaturalId = FunctionId(integerPackage, "fromNatural")
    map.put(integerFromNaturalId, NativeFunction(integerFromNaturalId, { args: List<SemObject> ->
        val natural = args[0]
        if (natural is SemObject.Natural) {
            SemObject.Integer(natural.value)
        } else {
            throw IllegalArgumentException()
        }
    }, listOf(Type.NATURAL)))
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
    }, listOf(Type.NATURAL, Type.NATURAL)))

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
    }, listOf(Type.NATURAL, Type.NATURAL)))

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
    }, listOf(Type.NATURAL, Type.NATURAL)))
}