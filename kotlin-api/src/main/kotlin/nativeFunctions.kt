package semlang.api

import java.util.*

data class TypeSignature(val id: FunctionId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<String> = listOf())

fun getNativeFunctionDefinitions(): Map<FunctionId, TypeSignature> {
    val definitions = HashMap<FunctionId, TypeSignature>()

    addIntegerFunctions(definitions)
    addNaturalFunctions(definitions)

    return definitions
}


private fun addIntegerFunctions(map: HashMap<FunctionId, TypeSignature>) {
    val integerPackage = Package(listOf("Integer"))

    // Integer.times
    val integerTimesId = FunctionId(integerPackage, "times")
    map.put(integerTimesId, TypeSignature(integerTimesId, listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.plus
    val integerPlusId = FunctionId(integerPackage, "plus")
    map.put(integerPlusId, TypeSignature(integerPlusId, listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.minus
    val integerMinusId = FunctionId(integerPackage, "minus")
    map.put(integerMinusId, TypeSignature(integerMinusId, listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.equals
    val integerEqualsId = FunctionId(integerPackage, "equals")
    map.put(integerEqualsId, TypeSignature(integerEqualsId, listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))

    // Integer.fromNatural
    val integerFromNaturalId = FunctionId(integerPackage, "fromNatural")
    map.put(integerFromNaturalId, TypeSignature(integerFromNaturalId, listOf(Type.NATURAL), Type.INTEGER))
}

private fun addNaturalFunctions(map: HashMap<FunctionId, TypeSignature>) {
    val naturalPackage = Package(listOf("Natural"))

    // Natural.times
    val naturalTimesId = FunctionId(naturalPackage, "times")
    map.put(naturalTimesId, TypeSignature(naturalTimesId, listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.plus
    val naturalPlusId = FunctionId(naturalPackage, "plus")
    map.put(naturalPlusId, TypeSignature(naturalPlusId, listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.equals
    val naturalEqualsId = FunctionId(naturalPackage, "equals")
    map.put(naturalEqualsId, TypeSignature(naturalEqualsId, listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))
}