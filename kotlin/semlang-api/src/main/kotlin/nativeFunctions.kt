package net.semlang.api

import java.util.*

val CURRENT_NATIVE_MODULE_VERSION = "0"
val NATIVE_MODULE_NAME = ModuleName("semlang", "lang")
val CURRENT_NATIVE_MODULE_ID = ModuleUniqueId(NATIVE_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION)

fun isNativeModule(module: ModuleName): Boolean {
    return module == NATIVE_MODULE_NAME
}
fun isNativeModule(id: ModuleUniqueId): Boolean {
    return isNativeModule(id.name)
}

/**
 * Note: This includes signatures for struct and union constructors.
 */
// TODO: Maybe rename?
fun getAllNativeFunctionLikeDefinitions(): Map<EntityId, FunctionSignature> {
    val definitions = ArrayList<FunctionSignature>()

    definitions.addAll(getNativeFunctionOnlyDefinitions().values)

    getNativeStructs().values.forEach { struct ->
        definitions.add(struct.getConstructorSignature())
    }

    getNativeUnions().values.forEach { union ->
        TODO()
    }

    return toMap(definitions)
}

// TODO: Rename
fun getNativeFunctionOnlyDefinitions(): Map<EntityId, FunctionSignature> {
    val definitions = ArrayList<FunctionSignature>()

    addBooleanFunctions(definitions)
    addIntegerFunctions(definitions)
    addListFunctions(definitions)
    addMaybeFunctions(definitions)
    addSequenceFunctions(definitions)
    addDataFunctions(definitions)
    addOpaqueTypeFunctions(definitions)

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

private fun addBooleanFunctions(definitions: ArrayList<FunctionSignature>) {

    // Boolean.not
    definitions.add(FunctionSignature.create(EntityId.of("Boolean", "not"), listOf(Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.and
    definitions.add(FunctionSignature.create(EntityId.of("Boolean", "and"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

    // Boolean.or
    definitions.add(FunctionSignature.create(EntityId.of("Boolean", "or"), listOf(Type.BOOLEAN, Type.BOOLEAN), Type.BOOLEAN))

}

private fun addIntegerFunctions(definitions: ArrayList<FunctionSignature>) {

    // Integer.times
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "times"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.plus
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "plus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.minus
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "minus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.dividedBy
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "dividedBy"), listOf(Type.INTEGER, Type.INTEGER), Type.Maybe(Type.INTEGER)))
    // Integer.modulo
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "modulo"), listOf(Type.INTEGER, Type.INTEGER), Type.Maybe(Type.INTEGER)))

    // Integer.equals
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "equals"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.lessThan
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "lessThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.greaterThan
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "greaterThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
}

private fun addListFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val typeT = Type.InternalParameterType(0)
    val typeU = Type.InternalParameterType(1)

    // List.append
    definitions.add(FunctionSignature.create(EntityId.of("List", "append"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), typeT),
            outputType = Type.List(typeT)))

    // List.appendFront
    definitions.add(FunctionSignature.create(EntityId.of("List", "appendFront"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), typeT),
            outputType = Type.List(typeT)))

    // List.concatenate
    definitions.add(FunctionSignature.create(EntityId.of("List", "concatenate"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(Type.List(typeT))),
            outputType = Type.List(typeT)))

    // List.subList
    definitions.add(FunctionSignature.create(EntityId.of("List", "subList"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), NativeStruct.NATURAL.getType(), NativeStruct.NATURAL.getType()),
            outputType = Type.Maybe(Type.List(typeT))))

    // List.map
    definitions.add(FunctionSignature.create(EntityId.of("List", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), typeU)),
            outputType = Type.List(typeU)))

    // List.flatMap
    definitions.add(FunctionSignature.create(EntityId.of("List", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), Type.List(typeU))),
            outputType = Type.List(typeU)))

    // List.filter
    definitions.add(FunctionSignature.create(EntityId.of("List", "filter"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), Type.BOOLEAN)),
            outputType = Type.List(typeT)))

    // List.reduce
    definitions.add(FunctionSignature.create(EntityId.of("List", "reduce"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.List(typeT), typeU, Type.FunctionType.create(false, listOf(), listOf(typeU, typeT), typeU)),
            outputType = typeU))

    // List.forEach
    definitions.add(FunctionSignature.create(EntityId.of("List", "forEach"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), Type.FunctionType.create(true, listOf(), listOf(typeT), NativeStruct.VOID.getType())),
            outputType = NativeStruct.VOID.getType()))

    // List.size
    definitions.add(FunctionSignature.create(EntityId.of("List", "size"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT)),
            outputType = NativeStruct.NATURAL.getType()))

    // List.get
    definitions.add(FunctionSignature.create(EntityId.of("List", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.List(typeT), NativeStruct.NATURAL.getType()),
            outputType = Type.Maybe(typeT)))
}

private fun addMaybeFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val typeT = Type.InternalParameterType(0)
    val typeU = Type.InternalParameterType(1)

    // Maybe.success
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "success"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT),
            outputType = Type.Maybe(typeT)))

    // Maybe.failure
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "failure"), typeParameters = listOf(t),
            argumentTypes = listOf(),
            outputType = Type.Maybe(typeT)))

    // Maybe.assume
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "assume"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Maybe(typeT)),
            outputType = typeT))


    // Maybe.isSuccess
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "isSuccess"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Maybe(typeT)),
            outputType = Type.BOOLEAN))

    // Maybe.map
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.Maybe(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), typeU)),
            outputType = Type.Maybe(typeU)))

    // Maybe.flatMap
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(Type.Maybe(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), Type.Maybe(typeU))),
            outputType = Type.Maybe(typeU)))

    // Maybe.orElse
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "orElse"), typeParameters = listOf(t),
            argumentTypes = listOf(Type.Maybe(typeT), typeT),
            outputType = typeT))

}

private fun addSequenceFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.InternalParameterType(0)

    val sequenceT = NativeStruct.SEQUENCE.getType(listOf(typeT))

    // Sequence.get
    definitions.add(FunctionSignature.create(EntityId.of("Sequence", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(sequenceT, NativeStruct.NATURAL.getType()),
            outputType = typeT))

    // Sequence.first
    definitions.add(FunctionSignature.create(EntityId.of("Sequence", "first"), typeParameters = listOf(t),
            argumentTypes = listOf(sequenceT, Type.FunctionType.create(false, listOf(), listOf(typeT), Type.BOOLEAN)),
            outputType = typeT))

    // TODO: Consider adding BasicSequence functions here? Or unnecessary?
}

private fun addDataFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", TypeClass.Data)
    val typeT = Type.InternalParameterType(0)

    // Data.equals
    definitions.add(FunctionSignature.create(EntityId.of("Data", "equals"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT, typeT),
            outputType = Type.BOOLEAN))
}

// TODO: Some of these should probably go elsewhere; at least TextOut should be in a separate module
private fun addOpaqueTypeFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.InternalParameterType(0)

    // TextOut.print
    definitions.add(FunctionSignature.create(EntityId.of("TextOut", "print"), typeParameters = listOf(),
            argumentTypes = listOf(NativeOpaqueType.TEXT_OUT.getType(), NativeStruct.STRING.getType()),
            outputType = NativeStruct.VOID.getType()))

    val listBuilderT = NativeOpaqueType.LIST_BUILDER.getType(listOf(typeT))

    // ListBuilder constructor
    definitions.add(FunctionSignature.create(EntityId.of("ListBuilder"), typeParameters = listOf(t),
            argumentTypes = listOf(),
            outputType = listBuilderT))

    // ListBuilder.append
    definitions.add(FunctionSignature.create(EntityId.of("ListBuilder", "append"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT, typeT),
            outputType = NativeStruct.VOID.getType()))

    // ListBuilder.appendAll
    definitions.add(FunctionSignature.create(EntityId.of("ListBuilder", "appendAll"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT, Type.List(typeT)),
            outputType = NativeStruct.VOID.getType()))

    // ListBuilder.build
    definitions.add(FunctionSignature.create(EntityId.of("ListBuilder", "build"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT),
            outputType = Type.List(typeT)))

    // Var constructor
    val varT = NativeOpaqueType.VAR.getType(listOf(typeT))
    definitions.add(FunctionSignature.create(EntityId.of("Var"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT),
            outputType = varT))

    // Var.get
    definitions.add(FunctionSignature.create(EntityId.of("Var", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(varT),
            outputType = typeT))

    // Var.set
    definitions.add(FunctionSignature.create(EntityId.of("Var", "set"), typeParameters = listOf(t),
            argumentTypes = listOf(varT, typeT),
            outputType = NativeStruct.VOID.getType()))

    // TODO: Maybe give these their own function in this file
    // Function.whileTrueDo
    definitions.add(FunctionSignature.create(EntityId.of("Function", "whileTrueDo"),
            argumentTypes = listOf(Type.FunctionType.create(true, listOf(), listOf(), Type.BOOLEAN), Type.FunctionType.create(true, listOf(), listOf(), NativeStruct.VOID.getType())),
            outputType = NativeStruct.VOID.getType()))

}

object NativeStruct {
    private val t = TypeParameter("T", null)
    private val typeT = Type.ParameterType(t)

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
                    AliasType.NotAliased,
                    EntityRef.of("Integer", "greaterThan"),
                    ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "greaterThan")),
                    listOf(TypedExpression.Variable(Type.INTEGER, AliasType.PossiblyAliased, "integer"),
                            TypedExpression.Literal(Type.INTEGER, AliasType.NotAliased, "-1")),
                    listOf(),
                    listOf()
            )),
            listOf()
    )
    val SEQUENCE = Struct(
            EntityId.of("Sequence"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(t),
            listOf(
                    Member("base", typeT),
                    Member("successor", Type.FunctionType.create(false, listOf(), listOf(typeT), typeT))
            ),
            null,
            listOf()
    )
    val CODE_POINT = Struct(
            EntityId.of("CodePoint"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("natural", NativeStruct.NATURAL.getType())
            ),
            // requires: value < 1114112
            TypedBlock(Type.BOOLEAN, listOf(), TypedExpression.NamedFunctionCall(
                    Type.BOOLEAN,
                    AliasType.NotAliased,
                    EntityRef.of("Integer", "lessThan"),
                    ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "lessThan")),
                    listOf(
                            TypedExpression.Follow(Type.INTEGER,
                                    AliasType.PossiblyAliased,
                                    TypedExpression.Variable(NativeStruct.NATURAL.getType(), AliasType.PossiblyAliased, "natural"),
                                    "integer"),
                            TypedExpression.Literal(Type.INTEGER, AliasType.NotAliased, "1114112")
                    ),
                    listOf(),
                    listOf()
            )),
            listOf()
    )
    val STRING = Struct(
            EntityId.of("String"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("codePoints", Type.List(CODE_POINT.getType()))
            ),
            null,
            listOf()
    )
    val VOID = Struct(
            EntityId.of("Void"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(),
            null,
            listOf()
    )
}

fun getNativeStructs(): Map<EntityId, Struct> {
    val structs = ArrayList<Struct>()

    structs.add(NativeStruct.NATURAL)
    structs.add(NativeStruct.SEQUENCE)
    structs.add(NativeStruct.CODE_POINT)
    structs.add(NativeStruct.STRING)
    structs.add(NativeStruct.VOID)

    return toMap(structs)
}

fun getNativeUnions(): Map<EntityId, Union> {
    val unions = ArrayList<Union>()

    return toMap(unions)
}

object NativeOpaqueType {
    private val t = TypeParameter("T", null)

    private val textOutId = EntityId.of("TextOut")
    val TEXT_OUT = OpaqueType(textOutId, CURRENT_NATIVE_MODULE_ID, listOf(), true)

    private val listBuilderId = EntityId.of("ListBuilder")
    val LIST_BUILDER = OpaqueType(listBuilderId, CURRENT_NATIVE_MODULE_ID, listOf(t), true)

    private val varId = EntityId.of("Var")
    val VAR = OpaqueType(varId, CURRENT_NATIVE_MODULE_ID, listOf(t), true)
}

/**
 * An "opaque" type is a named type that doesn't represent a struct or union and isn't
 * a native type like Integer or Boolean. This includes native reference types.
 *
 * TODO: We should have a way for the module system to include ways to specify additional opaque types and
 * associated methods that must have a native implementation. (Obviously, these wouldn't be supported by
 * every type of runtime environment.)
 */
fun getNativeOpaqueTypes(): Map<EntityId, OpaqueType> {
    val types = ArrayList<OpaqueType>()

    types.add(NativeOpaqueType.TEXT_OUT)
    types.add(NativeOpaqueType.LIST_BUILDER)
    types.add(NativeOpaqueType.VAR)

    return types.associateBy { it.id }
}
