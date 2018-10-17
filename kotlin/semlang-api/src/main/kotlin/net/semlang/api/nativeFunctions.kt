package net.semlang.api

import java.util.*

val NATIVE_MODULE_GROUP = "semlang"
val NATIVE_MODULE_MODULE = "lang"
val CURRENT_NATIVE_MODULE_VERSION = "0"
val NATIVE_MODULE_NAME = ModuleName(NATIVE_MODULE_GROUP, NATIVE_MODULE_MODULE)
val CURRENT_NATIVE_MODULE_ID = ModuleUniqueId(NATIVE_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION)

fun isNativeModule(module: ModuleName): Boolean {
    return module == NATIVE_MODULE_NAME
}
fun isNativeModule(id: ModuleUniqueId): Boolean {
    return isNativeModule(id.name)
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
    addMaybeFunctions(definitions)
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
    definitions.add(TypeSignature.create(EntityId.of("Boolean", "not"), listOf(Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.and
    definitions.add(TypeSignature.create(EntityId.of("Boolean", "and"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.or
    definitions.add(TypeSignature.create(EntityId.of("Boolean", "or"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

}

private fun addIntegerFunctions(definitions: ArrayList<TypeSignature>) {

    // Integer.times
    definitions.add(TypeSignature.create(EntityId.of("Integer", "times"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.plus
    definitions.add(TypeSignature.create(EntityId.of("Integer", "plus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.minus
    definitions.add(TypeSignature.create(EntityId.of("Integer", "minus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.dividedBy
    definitions.add(TypeSignature.create(EntityId.of("Integer", "dividedBy"), listOf(Type.INTEGER, Type.INTEGER), Type.Maybe(Type.INTEGER)))
    // Integer.modulo
    definitions.add(TypeSignature.create(EntityId.of("Integer", "modulo"), listOf(Type.INTEGER, Type.INTEGER), Type.Maybe(Type.INTEGER)))

    // Integer.equals
    definitions.add(TypeSignature.create(EntityId.of("Integer", "equals"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.lessThan
    definitions.add(TypeSignature.create(EntityId.of("Integer", "lessThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.greaterThan
    definitions.add(TypeSignature.create(EntityId.of("Integer", "greaterThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
}

private fun addListFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val thr = TypeParameter("Thr", TypeClass.Threaded)
    val typeT = Type.InternalParameterType(0)
    val typeU = Type.InternalParameterType(1)
    val typeThr = Type.InternalParameterType(1)

    // List.append
    definitions.add(TypeSignature.create(EntityId.of("List", "append"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), typeT),
            outputType = Type.List(typeT)))

    // List.appendFront
    definitions.add(TypeSignature.create(EntityId.of("List", "appendFront"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), typeT),
            outputType = Type.List(typeT)))

    // List.concatenate
    definitions.add(TypeSignature.create(EntityId.of("List", "concatenate"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), Type.List(typeT)),
            outputType = Type.List(typeT)))

    // List.subList
    definitions.add(TypeSignature.create(EntityId.of("List", "subList"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), NativeStruct.NATURAL.getType(), NativeStruct.NATURAL.getType()),
            outputType = Type.Maybe(Type.List(typeT))))

    // List.map
    definitions.add(TypeSignature.create(EntityId.of("List", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType.create(listOf(), listOf(typeT), typeU)),
            outputType = Type.List(typeU)))

    // List.flatMap
    definitions.add(TypeSignature.create(EntityId.of("List", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType.create(listOf(), listOf(typeT), Type.List(typeU))),
            outputType = Type.List(typeU)))

    // List.filter
    definitions.add(TypeSignature.create(EntityId.of("List", "filter"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType.create(listOf(), listOf(typeT), Type.BOOLEAN)),
            outputType = Type.List(typeT)))

    // List.reduce
    definitions.add(TypeSignature.create(EntityId.of("List", "reduce"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), typeU, Type.FunctionType.create(listOf(), listOf(typeU, typeT), typeU)),
            outputType = typeU))

    // List.reduceThreaded
    definitions.add(TypeSignature.create(EntityId.of("List", "reduceThreaded"), typeParameters = listOf(t, thr),
            argumentTypes = listOf(Type.List(typeT), typeThr, Type.FunctionType.create(listOf(), listOf(typeThr, typeT), typeThr)),
            outputType = typeThr))

    // List.size
    definitions.add(TypeSignature.create(EntityId.of("List", "size"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT)),
            outputType = NativeStruct.NATURAL.getType()))

    // List.get
    definitions.add(TypeSignature.create(EntityId.of("List", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), NativeStruct.NATURAL.getType()),
            outputType = Type.Maybe(typeT)))
}

private fun addMaybeFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val typeT = Type.InternalParameterType(0)
    val typeU = Type.InternalParameterType(1)

    // Maybe.success
    definitions.add(TypeSignature.create(EntityId.of("Maybe", "success"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT),
            outputType = Type.Maybe(typeT)))

    // Maybe.failure
    definitions.add(TypeSignature.create(EntityId.of("Maybe", "failure"), typeParameters = listOf(t),
            argumentTypes = listOf(),
            outputType = Type.Maybe(typeT)))

    // Maybe.assume
    definitions.add(TypeSignature.create(EntityId.of("Maybe", "assume"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Maybe(typeT)),
            outputType = typeT))


    // Maybe.isSuccess
    definitions.add(TypeSignature.create(EntityId.of("Maybe", "isSuccess"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Maybe(typeT)),
            outputType = Type.BOOLEAN))

    // Maybe.map
    definitions.add(TypeSignature.create(EntityId.of("Maybe", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.Maybe(typeT), Type.FunctionType.create(listOf(), listOf(typeT), typeU)),
            outputType = Type.Maybe(typeU)))

    // Maybe.flatMap
    definitions.add(TypeSignature.create(EntityId.of("Maybe", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.Maybe(typeT), Type.FunctionType.create(listOf(), listOf(typeT), Type.Maybe(typeU))),
            outputType = Type.Maybe(typeU)))

    // Maybe.orElse
    definitions.add(TypeSignature.create(EntityId.of("Maybe", "orElse"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Maybe(typeT), typeT),
            outputType = typeT))

}

private fun addSequenceFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.InternalParameterType(0)

    val sequenceT = NativeStruct.SEQUENCE.getType(listOf(typeT))

    // Sequence.get
    definitions.add(TypeSignature.create(EntityId.of("Sequence", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(sequenceT, NativeStruct.NATURAL.getType()),
            outputType = typeT))

    // Sequence.first
    definitions.add(TypeSignature.create(EntityId.of("Sequence", "first"), typeParameters = listOf(t),
            argumentTypes = listOf(sequenceT, Type.FunctionType.create(listOf(), listOf(typeT), Type.BOOLEAN)),
            outputType = typeT))

    // TODO: Consider adding BasicSequence functions here? Or unnecessary?
}

private fun addDataFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", TypeClass.Data)
    val typeT = Type.InternalParameterType(0)

    // Data.equals
    definitions.add(TypeSignature.create(EntityId.of("Data", "equals"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT, typeT),
            outputType = Type.BOOLEAN))
}

private fun addThreadedFunctions(definitions: ArrayList<TypeSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.InternalParameterType(0)

    // TextOut.print
    definitions.add(TypeSignature.create(EntityId.of("TextOut", "print"), typeParameters = listOf(),
            argumentTypes = listOf(NativeThreadedType.TEXT_OUT.getType(), NativeStruct.UNICODE_STRING.getType()),
            outputType = NativeThreadedType.TEXT_OUT.getType()))

    val listBuilderT = NativeThreadedType.LIST_BUILDER.getType(listOf(typeT))

    // ListBuilder constructor
    // TODO: For consistency with other APIs, this should just be "ListBuilder" and not "ListBuilder.create"
    definitions.add(TypeSignature.create(EntityId.of("ListBuilder", "create"), typeParameters = listOf(t),
            argumentTypes = listOf(),
            outputType = listBuilderT))

    // ListBuilder.append
    definitions.add(TypeSignature.create(EntityId.of("ListBuilder", "append"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT, typeT),
            outputType = listBuilderT))

    // ListBuilder.appendAll
    definitions.add(TypeSignature.create(EntityId.of("ListBuilder", "appendAll"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT, Type.List(typeT)),
            outputType = listBuilderT))

    // ListBuilder.build
    definitions.add(TypeSignature.create(EntityId.of("ListBuilder", "build"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT),
            outputType = Type.List(typeT)))
}

object NativeStruct {
    private val t = TypeParameter("T", null)
    private val typeT = Type.ParameterType(t)

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
                    listOf(),
                    listOf()
            )),
            listOf()
    )
    val SEQUENCE = Struct(
            EntityId.of("Sequence"),
            false,
            CURRENT_NATIVE_MODULE_ID,
            listOf(t),
            listOf(
                    Member("base", typeT),
                    Member("successor", Type.FunctionType.create(listOf(), listOf(typeT), typeT))
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
                    listOf(),
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
    // TODO: Can we move Bit and BitsBigEndian into the standard library?
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
                            listOf(),
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
                            listOf(),
                            listOf()
                        )
                    ),
                    listOf(),
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
    structs.add(NativeStruct.SEQUENCE)
    structs.add(NativeStruct.UNICODE_CODE_POINT)
    structs.add(NativeStruct.UNICODE_STRING)
    structs.add(NativeStruct.BIT)
    structs.add(NativeStruct.BITS_BIG_ENDIAN)

    return toMap(structs)
}

object NativeInterface {
}

fun getNativeInterfaces(): Map<EntityId, Interface> {
    val interfaces = ArrayList<Interface>()

    return toMap(interfaces)
}

fun getNativeUnions(): Map<EntityId, Union> {
    val unions = ArrayList<Union>()

    return toMap(unions)
}

object NativeThreadedType {
    private val t = TypeParameter("T", null)

    private val textOutId = EntityId.of("TextOut")
    val TEXT_OUT = OpaqueType(textOutId, CURRENT_NATIVE_MODULE_ID, listOf(), true)

    private val listBuilderId = EntityId.of("ListBuilder")
    val LIST_BUILDER = OpaqueType(listBuilderId, CURRENT_NATIVE_MODULE_ID, listOf(t), true)
}

/**
 * An "opaque" type is a named type that doesn't represent a struct or interface and isn't
 * a native type like Integer or Boolean. This includes native threaded types.
 *
 * TODO: We should have a way for the module system to include ways to specify additional opaque types and
 * associated methods that must have a native implementation. (Obviously, these wouldn't be supported by
 * every type of runtime environment.)
 */
fun getNativeOpaqueTypes(): Map<EntityId, OpaqueType> {
    val types = ArrayList<OpaqueType>()

    types.add(NativeThreadedType.TEXT_OUT)
    types.add(NativeThreadedType.LIST_BUILDER)

    return types.associateBy { it.id }
}
