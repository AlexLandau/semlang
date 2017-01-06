package semlang.api

import java.util.*

data class TypeSignature(val id: FunctionId, val argumentTypes: List<Type>, val outputType: Type)

fun getNativeFunctionDefinitions(): Map<FunctionId, TypeSignature> {
    val definitions = HashMap<FunctionId, TypeSignature>()

    addIntegerFunctions(definitions)
    addNaturalFunctions(definitions)

    return definitions
}


private fun addIntegerFunctions(map: HashMap<FunctionId, TypeSignature>) {
    val integerPackage = Package(listOf("Integer"))

    // integer.times
    val integerTimesId = FunctionId(integerPackage, "times")
    map.put(integerTimesId, TypeSignature(integerTimesId, listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // integer.plus
    val integerPlusId = FunctionId(integerPackage, "plus")
    map.put(integerPlusId, TypeSignature(integerPlusId, listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // integer.minus
    val integerMinusId = FunctionId(integerPackage, "minus")
    map.put(integerMinusId, TypeSignature(integerMinusId, listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // integer.equals
    val integerEqualsId = FunctionId(integerPackage, "equals")
    map.put(integerEqualsId, TypeSignature(integerEqualsId, listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
}

private fun addNaturalFunctions(map: HashMap<FunctionId, TypeSignature>) {
    val naturalPackage = Package(listOf("Natural"))

    // natural.times
    val naturalTimesId = FunctionId(naturalPackage, "times")
    map.put(naturalTimesId, TypeSignature(naturalTimesId, listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // natural.plus
    val naturalPlusId = FunctionId(naturalPackage, "plus")
    map.put(naturalPlusId, TypeSignature(naturalPlusId, listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // natural.equals
    val naturalEqualsId = FunctionId(naturalPackage, "equals")
    map.put(naturalEqualsId, TypeSignature(naturalEqualsId, listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))
}