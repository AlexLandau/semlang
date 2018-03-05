package net.semlang.api

import java.util.*

val NATIVE_MODULE_GROUP = "semlang"
val NATIVE_MODULE_NAME = "lang"
val CURRENT_NATIVE_MODULE_VERSION = "0"
val CURRENT_NATIVE_MODULE_ID = ModuleId(NATIVE_MODULE_GROUP, NATIVE_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION)

fun isNativeModule(module: ModuleId): Boolean {
    return module.group == NATIVE_MODULE_GROUP && module.module == NATIVE_MODULE_NAME
}

/**
 * Note: This includes signatures for struct, instance, and adapter constructors.
 */
// TODO: Maybe rename?
fun getAllNativeFunctionLikeDefinitions(): Map<EntityId, TypeSignature> {
    val definitions = ArrayList<TypeSignature>()

    definitions.addAll(getNativeFunctionOnlyDefinitions().values)

    getNativeStructs().values.forEach { struct ->
        definitions.add(struct.getConstructorSignature())
    }

    getNativeInterfaces().values.forEach { interfac ->
        definitions.add(interfac.getInstanceConstructorSignature())
        definitions.add(interfac.getAdapterConstructorSignature())
    }

    return toMap(definitions)
}

// TODO: Rename
fun getNativeFunctionOnlyDefinitions(): Map<EntityId, TypeSignature> {
    val definitions = ArrayList<TypeSignature>()

    addBooleanFunctions(definitions)
    addIntegerFunctions(definitions)
    addListFunctions(definitions)
    addTryFunctions(definitions)
    addSequenceFunctions(definitions)

    return toMap(definitions)
}

private fun <T: HasId> toMap(definitions: ArrayList<T>): Map<EntityId, T> {
    val map = HashMap<EntityId, T>()
    definitions.forEach { signature ->
        if (map.containsKey(signature.id)) {
            error("Duplicate native function ID ${signature.id}")
        }
        map.put(signature.id, signature)
    }
    return map
}

private fun addBooleanFunctions(definitions: ArrayList<TypeSignature>) {

    // Boolean.not
    definitions.add(TypeSignature(EntityId.of("Boolean", "not"), listOf(Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.and
    definitions.add(TypeSignature(EntityId.of("Boolean", "and"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.or
    definitions.add(TypeSignature(EntityId.of("Boolean", "or"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

}

private fun addIntegerFunctions(definitions: ArrayList<TypeSignature>) {

    // Integer.times
    definitions.add(TypeSignature(EntityId.of("Integer", "times"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.plus
    definitions.add(TypeSignature(EntityId.of("Integer", "plus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.minus
    definitions.add(TypeSignature(EntityId.of("Integer", "minus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.dividedBy
    definitions.add(TypeSignature(EntityId.of("Integer", "dividedBy"), listOf(Type.INTEGER, Type.INTEGER), Type.Try(Type.INTEGER)))
    // Integer.modulo
    definitions.add(TypeSignature(EntityId.of("Integer", "modulo"), listOf(Type.INTEGER, Type.INTEGER), Type.Try(Type.INTEGER)))

    // Integer.equals
    definitions.add(TypeSignature(EntityId.of("Integer", "equals"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.lessThan
    definitions.add(TypeSignature(EntityId.of("Integer", "lessThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.greaterThan
    definitions.add(TypeSignature(EntityId.of("Integer", "greaterThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
}

private fun addListFunctions(definitions: ArrayList<TypeSignature>) {
    val paramT = Type.ParameterType("T")
    val paramU = Type.ParameterType("U")

    // List.append
    definitions.add(TypeSignature(EntityId.of("List", "append"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.List(paramT), paramT),
            outputType = Type.List(paramT)))

    // List.appendFront
    definitions.add(TypeSignature(EntityId.of("List", "appendFront"), typeParameters = listOf("T"),
            argumentTypes = listOf(paramT, Type.List(paramT)),
            outputType = Type.List(paramT)))

    // List.concatenate
    definitions.add(TypeSignature(EntityId.of("List", "concatenate"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.List(paramT), Type.List(paramT)),
            outputType = Type.List(paramT)))

    // List.subList
    definitions.add(TypeSignature(EntityId.of("List", "subList"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.List(paramT), NativeStruct.NATURAL.getType(), NativeStruct.NATURAL.getType()),
            outputType = Type.Try(Type.List(paramT))))

    // TODO: Semantics of this are arguably different from last()... but I kind of like it that way
    // List.lastN
    definitions.add(TypeSignature(EntityId.of("List", "lastN"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.List(paramT), NativeStruct.NATURAL.getType()),
            outputType = Type.List(paramT)))

    // List.map
    definitions.add(TypeSignature(EntityId.of("List", "map"), typeParameters = listOf("T", "U"),
            argumentTypes = listOf(Type.List(paramT), Type.FunctionType(listOf(paramT), paramU)),
            outputType = Type.List(paramU)))

    // List.filter
    definitions.add(TypeSignature(EntityId.of("List", "filter"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.List(paramT), Type.FunctionType(listOf(paramT), Type.BOOLEAN)),
            outputType = Type.List(paramT)))

    // List.reduce
    definitions.add(TypeSignature(EntityId.of("List", "reduce"), typeParameters = listOf("T", "U"),
            argumentTypes = listOf(Type.List(paramT), paramU, Type.FunctionType(listOf(paramU, paramT), paramU)),
            outputType = paramU))

    // List.size
    definitions.add(TypeSignature(EntityId.of("List", "size"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.List(paramT)),
            outputType = NativeStruct.NATURAL.getType()))

    // List.get
    definitions.add(TypeSignature(EntityId.of("List", "get"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.List(paramT), NativeStruct.NATURAL.getType()),
            outputType = Type.Try(paramT)))
}

private fun addTryFunctions(definitions: ArrayList<TypeSignature>) {
    val paramT = Type.ParameterType("T")
    val paramU = Type.ParameterType("U")

    // Try.success
    definitions.add(TypeSignature(EntityId.of("Try", "success"), typeParameters = listOf("T"),
            argumentTypes = listOf(paramT),
            outputType = Type.Try(paramT)))

    // Try.failure
    definitions.add(TypeSignature(EntityId.of("Try", "failure"), typeParameters = listOf("T"),
            argumentTypes = listOf(),
            outputType = Type.Try(paramT)))

    // Try.assume
    definitions.add(TypeSignature(EntityId.of("Try", "assume"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.Try(paramT)),
            outputType = paramT))


    // Try.isSuccess
    definitions.add(TypeSignature(EntityId.of("Try", "isSuccess"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.Try(paramT)),
            outputType = Type.BOOLEAN))

    // Try.map
    definitions.add(TypeSignature(EntityId.of("Try", "map"), typeParameters = listOf("T", "U"),
            argumentTypes = listOf(Type.Try(paramT), Type.FunctionType(listOf(paramT), paramU)),
            outputType = Type.Try(paramU)))

    // Try.flatMap
    definitions.add(TypeSignature(EntityId.of("Try", "flatMap"), typeParameters = listOf("T", "U"),
            argumentTypes = listOf(Type.Try(paramT), Type.FunctionType(listOf(paramT), Type.Try(paramU))),
            outputType = Type.Try(paramU)))

    // Try.orElse
    definitions.add(TypeSignature(EntityId.of("Try", "orElse"), typeParameters = listOf("T"),
            argumentTypes = listOf(Type.Try(paramT), paramT),
            outputType = paramT))

}

private fun addSequenceFunctions(definitions: ArrayList<TypeSignature>) {
    val paramT = Type.ParameterType("T")

    val sequenceT = NativeInterface.SEQUENCE.getType()

    // Sequence.create
    // TODO: This should be library code in semlang in most cases, not native
    definitions.add(TypeSignature(EntityId.of("Sequence", "create"), typeParameters = listOf("T"),
            argumentTypes = listOf(paramT, Type.FunctionType(listOf(paramT), paramT)),
            outputType = sequenceT))

    // TODO: Consider adding BasicSequence functions here? Or unnecessary?
}

object NativeStruct {
    private val typeT = Type.ParameterType("T")
    private val typeU = Type.ParameterType("U")
    val NATURAL = Struct(
            EntityId.of("Natural"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("integer", Type.INTEGER)
            ),
            // requires: value > -1
            TypedBlock(Type.BOOLEAN, listOf(), TypedExpression.NamedFunctionCall(
                    Type.BOOLEAN,
                    EntityRef.of("Integer", "greaterThan"),
                    ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "greaterThan")),
                    listOf(TypedExpression.Variable(Type.INTEGER, "integer"),
                            TypedExpression.Literal(Type.INTEGER, "-1")),
                    listOf()
            )),
            listOf()
    )
    val BASIC_SEQUENCE = Struct(
            EntityId.of("BasicSequence"),
            CURRENT_NATIVE_MODULE_ID,
            listOf("T"),
            listOf(
                    Member("base", typeT),
                    Member("successor", Type.FunctionType(listOf(typeT), typeT))
            ),
            null,
            listOf()
    )
    val UNICODE_CODE_POINT = Struct(
            EntityId.of("Unicode", "CodePoint"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("natural", NativeStruct.NATURAL.getType())
            ),
            // requires: value < 1114112
            TypedBlock(Type.BOOLEAN, listOf(), TypedExpression.NamedFunctionCall(
                    Type.BOOLEAN,
                    EntityRef.of("Integer", "lessThan"),
                    ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "lessThan")),
                    listOf(
                            TypedExpression.Follow(Type.INTEGER,
                                    TypedExpression.Variable(NativeStruct.NATURAL.getType(), "natural"),
                                    "integer"),
                            TypedExpression.Literal(Type.INTEGER, "1114112")
                    ),
                    listOf()
            )),
            listOf()
    )
    val UNICODE_STRING = Struct(
            EntityId.of("Unicode", "String"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("codePoints", Type.List(UNICODE_CODE_POINT.getType()))
            ),
            null,
            listOf()
    )
    val BIT = Struct(
            EntityId.of("Bit"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("natural", NativeStruct.NATURAL.getType())
            ),
            // requires: value = 0 or value = 1
            TypedBlock(Type.BOOLEAN, listOf(), TypedExpression.NamedFunctionCall(
                    Type.BOOLEAN,
                    EntityRef.of("Boolean", "or"),
                    ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Boolean", "or")),
                    listOf(TypedExpression.NamedFunctionCall(
                            Type.BOOLEAN,
                            EntityRef.of("Integer", "equals"),
                            ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "equals")),
                            listOf(
                                    TypedExpression.Follow(Type.INTEGER,
                                            TypedExpression.Variable(NativeStruct.NATURAL.getType(), "natural"),
                                            "integer"),
                                    TypedExpression.Literal(Type.INTEGER, "0")
                            ),
                            listOf()
                        ), TypedExpression.NamedFunctionCall(
                            Type.BOOLEAN,
                            EntityRef.of("Integer", "equals"),
                            ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "equals")),
                            listOf(
                                    TypedExpression.Follow(Type.INTEGER,
                                            TypedExpression.Variable(NativeStruct.NATURAL.getType(), "natural"),
                                            "integer"),
                                    TypedExpression.Literal(Type.INTEGER, "1")
                            ),
                            listOf()
                        )
                    ),
                    listOf()
            )),
            listOf()
    )
    val BITS_BIG_ENDIAN = Struct(
            EntityId.of("BitsBigEndian"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("bits", Type.List(BIT.getType()))
            ),
            null,
            listOf()
    )
}

fun getNativeStructs(): Map<EntityId, Struct> {
    val structs = ArrayList<Struct>()

    structs.add(NativeStruct.NATURAL)
    structs.add(NativeStruct.BASIC_SEQUENCE)
    structs.add(NativeStruct.UNICODE_CODE_POINT)
    structs.add(NativeStruct.UNICODE_STRING)
    structs.add(NativeStruct.BIT)
    structs.add(NativeStruct.BITS_BIG_ENDIAN)

    return toMap(structs)
}

object NativeInterface {
    private val typeT = Type.ParameterType("T")
    val SEQUENCE = Interface(
            EntityId.of("Sequence"),
            CURRENT_NATIVE_MODULE_ID,
            listOf("T"),
            listOf(
                    Method("get", listOf(), listOf(Argument("index", NativeStruct.NATURAL.getType())), typeT),
                    Method("first", listOf(), listOf(Argument("condition", Type.FunctionType(listOf(typeT), Type.BOOLEAN))), typeT)
            ),
            listOf()
    )
}

fun getNativeInterfaces(): Map<EntityId, Interface> {
    val interfaces = ArrayList<Interface>()

    interfaces.add(NativeInterface.SEQUENCE)

    return toMap(interfaces)
}
