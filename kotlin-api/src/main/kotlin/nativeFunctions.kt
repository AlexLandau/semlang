package semlang.api

import java.util.*

data class TypeSignature(val id: FunctionId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<Type> = listOf())

fun getNativeFunctionDefinitions(): Map<FunctionId, TypeSignature> {
    val definitions = ArrayList<TypeSignature>()

    addIntegerFunctions(definitions)
    addNaturalFunctions(definitions)
    addListFunctions(definitions)

    return toMap(definitions)
}

private fun toMap(definitions: ArrayList<TypeSignature>): Map<FunctionId, TypeSignature> {
    val map = HashMap<FunctionId, TypeSignature>()
    definitions.forEach { signature ->
        if (map.containsKey(signature.id)) {
            error("Duplicate native function ID ${signature.id}")
        }
        map.put(signature.id, signature)
    }
    return map
}


private fun addIntegerFunctions(definitions: ArrayList<TypeSignature>) {
    val integerPackage = Package(listOf("Integer"))

    // Integer.times
    definitions.add(TypeSignature(FunctionId(integerPackage, "times"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.plus
    definitions.add(TypeSignature(FunctionId(integerPackage, "plus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.minus
    definitions.add(TypeSignature(FunctionId(integerPackage, "minus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.equals
    definitions.add(TypeSignature(FunctionId(integerPackage, "equals"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))

    // Integer.fromNatural
    definitions.add(TypeSignature(FunctionId(integerPackage, "fromNatural"), listOf(Type.NATURAL), Type.INTEGER))
}

private fun addNaturalFunctions(definitions: ArrayList<TypeSignature>) {
    val naturalPackage = Package(listOf("Natural"))

    // Natural.times
    definitions.add(TypeSignature(FunctionId(naturalPackage, "times"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.plus
    definitions.add(TypeSignature(FunctionId(naturalPackage, "plus"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.equals
    definitions.add(TypeSignature(FunctionId(naturalPackage, "equals"), listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))
}

private fun addListFunctions(definitions: ArrayList<TypeSignature>) {
    val listPackage = Package(listOf("List"))

    val paramT = Type.NamedType.forParameter("T")

    // List.empty
    definitions.add(TypeSignature(FunctionId(listPackage, "empty"), typeParameters = listOf(paramT),
            argumentTypes = listOf(),
            outputType = Type.List(paramT)))

    // List.append
    definitions.add(TypeSignature(FunctionId(listPackage, "append"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), paramT),
            outputType = Type.List(paramT)))

    // List.size
    definitions.add(TypeSignature(FunctionId(listPackage, "size"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT)),
            outputType = Type.NATURAL))
}