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
        definitions.add(interfac.getAdapterFunctionSignature())
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
    addDataFunctions(definitions)
    addThreadedFunctions(definitions)

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
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val typeT = Type.ParameterType(t)
    val typeU = Type.ParameterType(u)

    // List.append
    definitions.add(TypeSignature(EntityId.of("List", "append"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), typeT),
            outputType = Type.List(typeT)))

    // List.appendFront
    definitions.add(TypeSignature(EntityId.of("List", "appendFront"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), typeT),
            outputType = Type.List(typeT)))

    // List.concatenate
    definitions.add(TypeSignature(EntityId.of("List", "concatenate"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), Type.List(typeT)),
            outputType = Type.List(typeT)))

    // List.subList
    definitions.add(TypeSignature(EntityId.of("List", "subList"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), NativeStruct.NATURAL.getType(), NativeStruct.NATURAL.getType()),
            outputType = Type.Try(Type.List(typeT))))

    // List.map
    definitions.add(TypeSignature(EntityId.of("List", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType(listOf(typeT), typeU)),
            outputType = Type.List(typeU)))

    // List.flatMap
    definitions.add(TypeSignature(EntityId.of("List", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType(listOf(typeT), Type.List(typeU))),
            outputType = Type.List(typeU)))

    // List.filter
    definitions.add(TypeSignature(EntityId.of("List", "filter"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType(listOf(typeT), Type.BOOLEAN)),
            outputType = Type.List(typeT)))

    // List.reduce
    definitions.add(TypeSignature(EntityId.of("List", "reduce"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), typeU, Type.FunctionType(listOf(typeU, typeT), typeU)),
            outputType = typeU))

    // List.size
    definitions.add(TypeSignature(EntityId.of("List", "size"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT)),
            outputType = NativeStruct.NATURAL.getType()))

    // List.get
    definitions.add(TypeSignature(EntityId.of("List", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), NativeStruct.NATURAL.getType()),
            outputType = Type.Try(typeT)))
}

private fun addTryFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val typeT = Type.ParameterType(t)
    val typeU = Type.ParameterType(u)

    // Try.success
    definitions.add(TypeSignature(EntityId.of("Try", "success"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT),
            outputType = Type.Try(typeT)))

    // Try.failure
    definitions.add(TypeSignature(EntityId.of("Try", "failure"), typeParameters = listOf(t),
            argumentTypes = listOf(),
            outputType = Type.Try(typeT)))

    // Try.assume
    definitions.add(TypeSignature(EntityId.of("Try", "assume"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Try(typeT)),
            outputType = typeT))


    // Try.isSuccess
    definitions.add(TypeSignature(EntityId.of("Try", "isSuccess"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Try(typeT)),
            outputType = Type.BOOLEAN))

    // Try.map
    definitions.add(TypeSignature(EntityId.of("Try", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.Try(typeT), Type.FunctionType(listOf(typeT), typeU)),
            outputType = Type.Try(typeU)))

    // Try.flatMap
    definitions.add(TypeSignature(EntityId.of("Try", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.Try(typeT), Type.FunctionType(listOf(typeT), Type.Try(typeU))),
            outputType = Type.Try(typeU)))

    // Try.orElse
    definitions.add(TypeSignature(EntityId.of("Try", "orElse"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Try(typeT), typeT),
            outputType = typeT))

}

private fun addSequenceFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.ParameterType(t)

    val sequenceT = NativeInterface.SEQUENCE.getType()

    // Sequence.create
    // TODO: This should be library code in semlang in most cases, not native
    definitions.add(TypeSignature(EntityId.of("Sequence", "create"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT, Type.FunctionType(listOf(typeT), typeT)),
            outputType = sequenceT))

    // TODO: Consider adding BasicSequence functions here? Or unnecessary?
}

private fun addDataFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", TypeClass.Data)
    val typeT = Type.ParameterType(t)

    // Data.equals
    definitions.add(TypeSignature(EntityId.of("Data", "equals"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT, typeT),
            outputType = Type.BOOLEAN))
}

private fun addThreadedFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.ParameterType(t)

    // TextOut.print
    definitions.add(TypeSignature(EntityId.of("TextOut", "print"), typeParameters = listOf(),
            argumentTypes = listOf(NativeThreadedType.TEXT_OUT, NativeStruct.UNICODE_STRING.getType()),
            outputType = NativeThreadedType.TEXT_OUT))

    val listBuilderT = NativeThreadedType.LIST_BUILDER

    // ListBuilder constructor
    // TODO: For consistency with other APIs, this should just be "ListBuilder" and not "ListBuilder.create"
    definitions.add(TypeSignature(EntityId.of("ListBuilder", "create"), typeParameters = listOf(t),
            argumentTypes = listOf(),
            outputType = listBuilderT))

    // ListBuilder.append
    definitions.add(TypeSignature(EntityId.of("ListBuilder", "append"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT, typeT),
            outputType = listBuilderT))

    // ListBuilder.appendAll
    definitions.add(TypeSignature(EntityId.of("ListBuilder", "appendAll"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT, Type.List(typeT)),
            outputType = listBuilderT))

    // ListBuilder.build
    definitions.add(TypeSignature(EntityId.of("ListBuilder", "build"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT),
            outputType = Type.List(typeT)))
}

object NativeStruct {
    private val t = TypeParameter("T", null)
    private val u = TypeParameter("U", null)
    private val typeT = Type.ParameterType(t)
    private val typeU = Type.ParameterType(u)
    val NATURAL = Struct(
            EntityId.of("Natural"),
            false,
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
            false,
            CURRENT_NATIVE_MODULE_ID,
            listOf(t),
            listOf(
                    Member("base", typeT),
                    Member("successor", Type.FunctionType(listOf(typeT), typeT))
            ),
            null,
            listOf()
    )
    val UNICODE_CODE_POINT = Struct(
            EntityId.of("Unicode", "CodePoint"),
            false,
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
            false,
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
            false,
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
            false,
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
    private val t = TypeParameter("T", null)
    private val typeT = Type.ParameterType(t)
    val SEQUENCE = Interface(
            EntityId.of("Sequence"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(t),
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

object NativeThreadedType {
    private val t = TypeParameter("T", null)

    private val textOutId = EntityId.of("TextOut")
    val TEXT_OUT = Type.NamedType(ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, textOutId), EntityRef(null, textOutId), true)

    private val listBuilderId = EntityId.of("ListBuilder")
    val LIST_BUILDER = Type.NamedType(ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, listBuilderId), EntityRef(null, listBuilderId), true, listOf(Type.ParameterType(t)))
}

/**
 * An "opaque" type is a named type that doesn't represent a struct or interface and isn't
 * a native type like Integer or Boolean. This includes native threaded types.
 *
 * TODO: We should have a way for the module system to include ways to specify additional opaque types and
 * associated methods that must have a native implementation. (Obviously, these wouldn't be supported by
 * every type of runtime environment.)
 */
fun getNativeOpaqueTypes(): Map<EntityId, Type.NamedType> {
    val types = ArrayList<Type.NamedType>()

    types.add(NativeThreadedType.TEXT_OUT)
    types.add(NativeThreadedType.LIST_BUILDER)

    return types.associateBy { it.ref.id }
}
