package semlang.api

import java.util.*

data class TypeSignature(override val id: FunctionId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<Type> = listOf()): HasFunctionId

fun getNativeContext(): ValidatedContext {
    return ValidatedContext.NATIVE_CONTEXT
}

fun getNativeFunctionDefinitions(): Map<FunctionId, TypeSignature> {
    val definitions = ArrayList<TypeSignature>()

    addBooleanFunctions(definitions)
    addIntegerFunctions(definitions)
    addNaturalFunctions(definitions)
    addListFunctions(definitions)
    addTryFunctions(definitions)
    addSequenceFunctions(definitions)

    getNativeStructs().values.forEach { struct ->
        definitions.add(toTypeSignature(struct))
    }

    getNativeInterfaces().values.forEach { interfac ->
        definitions.add(toInstanceConstructorSignature(interfac))
        definitions.add(toAdapterConstructorSignature(interfac))
    }

    return toMap(definitions)
}

private fun toTypeSignature(struct: Struct): TypeSignature {
    val argumentTypes = struct.members.map(Member::type)
    val typeParameters = struct.typeParameters.map { name -> Type.NamedType.forParameter(name) }
    val outputType = Type.NamedType(struct.id, typeParameters)

    return TypeSignature(struct.id, argumentTypes, outputType, typeParameters)
}

fun toInstanceConstructorSignature(interfac: Interface): TypeSignature {
    val explicitTypeParameters = interfac.typeParameters
    val dataTypeParameter = getUnusedTypeParameterName(explicitTypeParameters)
    val allTypeParameters = ArrayList(explicitTypeParameters)
    allTypeParameters.add(0, dataTypeParameter) // Data type parameter comes first

    val argumentTypes = ArrayList<Type>()
    val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
    argumentTypes.add(dataStructType)

    val adapterType = Type.NamedType(interfac.adapterId, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    argumentTypes.add(adapterType)

    val outputType = Type.NamedType(interfac.id, explicitTypeParameters.map { name -> Type.NamedType.forParameter(name) })

    return TypeSignature(interfac.id, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
}

private fun getUnusedTypeParameterName(explicitTypeParameters: List<String>): String {
    if (!explicitTypeParameters.contains("A")) {
        return "A"
    }
    var index = 2
    while (true) {
        val name = "A" + index
        if (!explicitTypeParameters.contains(name)) {
            return name
        }
        index++
    }
}

fun toAdapterConstructorSignature(interfac: Interface): TypeSignature {
    val explicitTypeParameters = interfac.typeParameters
    val dataTypeParameter = getUnusedTypeParameterName(explicitTypeParameters)
    val allTypeParameters = ArrayList(explicitTypeParameters)
    allTypeParameters.add(0, dataTypeParameter) // Data type parameter comes first

    val argumentTypes = ArrayList<Type>()
    val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
    interfac.methods.forEach { method ->
        argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
    }

    val outputType = Type.NamedType(interfac.adapterId, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })

    return TypeSignature(interfac.adapterId, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
}

private fun getInterfaceMethodReferenceType(intrinsicStructType: Type.NamedType, method: Method): Type {
    val argTypes = ArrayList<Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return Type.FunctionType(argTypes, method.returnType)
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

private fun addBooleanFunctions(definitions: ArrayList<TypeSignature>) {
    val booleanPackage = Package(listOf("Boolean"))

    // Boolean.not
    definitions.add(TypeSignature(FunctionId(booleanPackage, "not"), listOf(Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.and
    definitions.add(TypeSignature(FunctionId(booleanPackage, "and"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.or
    definitions.add(TypeSignature(FunctionId(booleanPackage, "or"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.any
    definitions.add(TypeSignature(FunctionId(booleanPackage, "any"), listOf(Type.List(Type.BOOLEAN)), Type.BOOLEAN))
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

    // Natural.divide
    definitions.add(TypeSignature(FunctionId(naturalPackage, "divide"), listOf(Type.NATURAL, Type.NATURAL), Type.Try(Type.NATURAL)))

    // Natural.remainder
    definitions.add(TypeSignature(FunctionId(naturalPackage, "remainder"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.equals
    definitions.add(TypeSignature(FunctionId(naturalPackage, "equals"), listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))

    // TODO: Resolve these conflicting definitions
    // Natural.max
    definitions.add(TypeSignature(FunctionId(naturalPackage, "max"), listOf(Type.List(Type.NATURAL)), Type.Try(Type.NATURAL)))
    // Natural.lesser
    definitions.add(TypeSignature(FunctionId(naturalPackage, "lesser"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.absoluteDifference
    definitions.add(TypeSignature(FunctionId(naturalPackage, "absoluteDifference"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.rangeInclusive
    definitions.add(TypeSignature(FunctionId(naturalPackage, "rangeInclusive"), listOf(Type.NATURAL, Type.NATURAL), Type.List(Type.NATURAL)))
}

private fun addListFunctions(definitions: ArrayList<TypeSignature>) {
    val listPackage = Package(listOf("List"))

    val paramT = Type.NamedType.forParameter("T")
    val paramU = Type.NamedType.forParameter("U")

    // List.empty
    definitions.add(TypeSignature(FunctionId(listPackage, "empty"), typeParameters = listOf(paramT),
            argumentTypes = listOf(),
            outputType = Type.List(paramT)))

    // List.singleton
    definitions.add(TypeSignature(FunctionId(listPackage, "singleton"), typeParameters = listOf(paramT),
            argumentTypes = listOf(paramT),
            outputType = Type.List(paramT)))

    // List.append
    definitions.add(TypeSignature(FunctionId(listPackage, "append"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), paramT),
            outputType = Type.List(paramT)))

    // List.map
    definitions.add(TypeSignature(FunctionId(listPackage, "map"), typeParameters = listOf(paramT, paramU),
            argumentTypes = listOf(Type.List(paramT), Type.FunctionType(listOf(paramT), paramU)),
            outputType = Type.List(paramU)))

    // List.filter
    definitions.add(TypeSignature(FunctionId(listPackage, "filter"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.FunctionType(listOf(paramT), Type.BOOLEAN)),
            outputType = Type.List(paramT)))

    // List.reduce
    definitions.add(TypeSignature(FunctionId(listPackage, "reduce"), typeParameters = listOf(paramT, paramU),
            argumentTypes = listOf(Type.List(paramT), paramU, Type.FunctionType(listOf(paramU, paramT), paramU)),
            outputType = paramU))

    // List.size
    definitions.add(TypeSignature(FunctionId(listPackage, "size"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT)),
            outputType = Type.NATURAL))

    // List.get
    definitions.add(TypeSignature(FunctionId(listPackage, "get"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.NATURAL),
            outputType = Type.Try(paramT)))

    // List.first TODO: To library
    definitions.add(TypeSignature(FunctionId(listPackage, "first"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT)),
            outputType = Type.Try(paramT)))

    // List.last TODO: To library
    definitions.add(TypeSignature(FunctionId(listPackage, "last"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT)),
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

    val sequenceT = Type.NamedType(FunctionId.of("Sequence"), listOf(paramT))

    // Sequence.create
    // TODO: This should be library code in semlang in most cases, not native
    definitions.add(TypeSignature(FunctionId(sequencePackage, "create"), typeParameters = listOf(paramT),
            argumentTypes = listOf(paramT, Type.FunctionType(listOf(paramT), paramT)),
            outputType = sequenceT))

    // TODO: Consider adding BasicSequence functions here? Or unnecessary?
}

object NativeStruct {
    private val typeT = Type.NamedType.forParameter("T")
    private val typeU = Type.NamedType.forParameter("U")
    val BASIC_SEQUENCE = Struct(
            FunctionId.of("BasicSequence"),
            listOf("T"),
            listOf(
                    Member("base", typeT),
                    Member("successor", Type.FunctionType(listOf(typeT), typeT))
            ),
            listOf()
    )
}

fun getNativeStructs(): Map<FunctionId, Struct> {
    val structs = ArrayList<Struct>()

    structs.add(NativeStruct.BASIC_SEQUENCE)

    return toMap(structs)
}

object NativeInterface {
    private val typeT = Type.NamedType.forParameter("T")
    val SEQUENCE = Interface(
            FunctionId.of("Sequence"),
            listOf("T"),
            listOf(
                    Method("get", listOf(), listOf(Argument("index", Type.NATURAL)), typeT),
                    Method("first", listOf(), listOf(Argument("condition", Type.FunctionType(listOf(typeT), Type.BOOLEAN))), typeT)
            ),
            listOf()
    )
}

fun getNativeInterfaces(): Map<FunctionId, Interface> {
    val interfaces = ArrayList<Interface>()

    interfaces.add(NativeInterface.SEQUENCE)

    return toMap(interfaces)
}
