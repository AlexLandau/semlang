package semlang.api

import java.util.*

data class TypeSignature(override val id: FunctionId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<Type> = listOf()): HasFunctionId

fun getNativeFunctionDefinitions(): Map<FunctionId, TypeSignature> {
    val definitions = ArrayList<TypeSignature>()

    addIntegerFunctions(definitions)
    addNaturalFunctions(definitions)
    addListFunctions(definitions)
    addTryFunctions(definitions)
    addSequenceFunctions(definitions)

    getNativeStructs().values.forEach { struct ->
        definitions.add(toTypeSignature(struct))
    }

    return toMap(definitions)
}

fun toTypeSignature(struct: Struct): TypeSignature {
    val argumentTypes = struct.members.map(Member::type)
    val typeParameters = struct.typeParameters.map { name -> Type.NamedType.forParameter(name) }
    val outputType = Type.NamedType(struct.id, typeParameters)

    return TypeSignature(struct.id, argumentTypes, outputType, typeParameters)
}

private fun <T: HasFunctionId> toMap(definitions: ArrayList<T>): Map<FunctionId, T> {
    val map = HashMap<FunctionId, T>()
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

    // List.get
    definitions.add(TypeSignature(FunctionId(listPackage, "get"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.NATURAL),
            outputType = Type.Try(paramT)))
}

private fun addTryFunctions(definitions: ArrayList<TypeSignature>) {
    val tryPackage = Package(listOf("Try"))

    val paramT = Type.NamedType.forParameter("T")

    // Try.assume
    definitions.add(TypeSignature(FunctionId(tryPackage, "assume"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.Try(paramT)),
            outputType = paramT))
}

private fun addSequenceFunctions(definitions: ArrayList<TypeSignature>) {
    val sequencePackage = Package(listOf("Sequence"))

    val paramT = Type.NamedType.forParameter("T")

    val sequenceType = Type.NamedType(FunctionId.of("Sequence"), listOf(paramT))

    // Sequence.get
    definitions.add(TypeSignature(FunctionId(sequencePackage, "get"), typeParameters = listOf(paramT),
            argumentTypes = listOf(sequenceType, Type.NATURAL),
            outputType = paramT))
}

fun getNativeStructs(): Map<FunctionId, Struct> {
    val structs = ArrayList<Struct>()

    val typeT = Type.NamedType.forParameter("T")

    structs.add(Struct(
            FunctionId.of("Sequence"),
            listOf("T"),
            listOf(
                    Member("base", typeT),
                    Member("successor", Type.FunctionType(listOf(typeT), typeT))
            )))

    return toMap(structs)
}
