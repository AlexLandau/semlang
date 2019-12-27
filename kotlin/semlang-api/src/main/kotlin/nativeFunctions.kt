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
    definitions.add(FunctionSignature.create(EntityId.of("Boolean", "not"), listOf(NativeOpaqueType.BOOLEAN.getType()), NativeOpaqueType.BOOLEAN.getType()))

    // Boolean.and
    definitions.add(FunctionSignature.create(EntityId.of("Boolean", "and"), listOf(NativeOpaqueType.BOOLEAN.getType(), NativeOpaqueType.BOOLEAN.getType()), NativeOpaqueType.BOOLEAN.getType()))

    // Boolean.or
    definitions.add(FunctionSignature.create(EntityId.of("Boolean", "or"), listOf(NativeOpaqueType.BOOLEAN.getType(), NativeOpaqueType.BOOLEAN.getType()), NativeOpaqueType.BOOLEAN.getType()))

}

private fun addIntegerFunctions(definitions: ArrayList<FunctionSignature>) {

    // Integer.times
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "times"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.INTEGER.getType()))

    // Integer.plus
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "plus"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.INTEGER.getType()))

    // Integer.minus
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "minus"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.INTEGER.getType()))

    // Integer.dividedBy
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "dividedBy"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.MAYBE.getType(NativeOpaqueType.INTEGER.getType())))
    // Integer.modulo
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "modulo"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.MAYBE.getType(NativeOpaqueType.INTEGER.getType())))

    // Integer.equals
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "equals"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.BOOLEAN.getType()))
    // Integer.lessThan
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "lessThan"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.BOOLEAN.getType()))
    // Integer.greaterThan
    definitions.add(FunctionSignature.create(EntityId.of("Integer", "greaterThan"), listOf(NativeOpaqueType.INTEGER.getType(), NativeOpaqueType.INTEGER.getType()), NativeOpaqueType.BOOLEAN.getType()))
}

private fun addListFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val typeT = Type.InternalParameterType(0)
    val typeU = Type.InternalParameterType(1)

    fun listType(t: Type): Type {
        return NativeOpaqueType.LIST.getType(t)
    }

    // List.append
    definitions.add(FunctionSignature.create(EntityId.of("List", "append"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(typeT), typeT),
            outputType = listType(typeT)))

    // List.appendFront
    definitions.add(FunctionSignature.create(EntityId.of("List", "appendFront"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(typeT), typeT),
            outputType = listType(typeT)))

    // List.concatenate
    definitions.add(FunctionSignature.create(EntityId.of("List", "concatenate"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(listType(typeT))),
            outputType = listType(typeT)))

    // List.subList
    definitions.add(FunctionSignature.create(EntityId.of("List", "subList"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(typeT), NativeStruct.NATURAL.getType(), NativeStruct.NATURAL.getType()),
            outputType = NativeOpaqueType.MAYBE.getType(listType(typeT))))

    // List.map
    definitions.add(FunctionSignature.create(EntityId.of("List", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(listType(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), typeU)),
            outputType = listType(typeU)))

    // List.flatMap
    definitions.add(FunctionSignature.create(EntityId.of("List", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(listType(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), listType(typeU))),
            outputType = listType(typeU)))

    // List.filter
    definitions.add(FunctionSignature.create(EntityId.of("List", "filter"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), NativeOpaqueType.BOOLEAN.getType())),
            outputType = listType(typeT)))

    // List.reduce
    definitions.add(FunctionSignature.create(EntityId.of("List", "reduce"), typeParameters = listOf(t, u),
            argumentTypes = listOf(listType(typeT), typeU, Type.FunctionType.create(false, listOf(), listOf(typeU, typeT), typeU)),
            outputType = typeU))

    // List.forEach
    definitions.add(FunctionSignature.create(EntityId.of("List", "forEach"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(typeT), Type.FunctionType.create(true, listOf(), listOf(typeT), NativeStruct.VOID.getType())),
            outputType = NativeStruct.VOID.getType()))

    // List.size
    definitions.add(FunctionSignature.create(EntityId.of("List", "size"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(typeT)),
            outputType = NativeStruct.NATURAL.getType()))

    // List.get
    definitions.add(FunctionSignature.create(EntityId.of("List", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(listType(typeT), NativeStruct.NATURAL.getType()),
            outputType = NativeOpaqueType.MAYBE.getType(typeT)))
}

private fun addMaybeFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val u = TypeParameter("U", null)
    val typeT = Type.InternalParameterType(0)
    val typeU = Type.InternalParameterType(1)

    fun maybeType(t: Type): Type {
        return NativeOpaqueType.MAYBE.getType(t)
    }

    // Maybe.success
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "success"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT),
            outputType = maybeType(typeT)))

    // Maybe.failure
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "failure"), typeParameters = listOf(t),
            argumentTypes = listOf(),
            outputType = maybeType(typeT)))

    // Maybe.assume
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "assume"), typeParameters = listOf(t),
            argumentTypes = listOf(maybeType(typeT)),
            outputType = typeT))


    // Maybe.isSuccess
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "isSuccess"), typeParameters = listOf(t),
            argumentTypes = listOf(maybeType(typeT)),
            outputType = NativeOpaqueType.BOOLEAN.getType()))

    // Maybe.map
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "map"), typeParameters = listOf(t, u),
            argumentTypes = listOf(maybeType(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), typeU)),
            outputType = maybeType(typeU)))

    // Maybe.flatMap
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "flatMap"), typeParameters = listOf(t, u),
            argumentTypes = listOf(maybeType(typeT), Type.FunctionType.create(false, listOf(), listOf(typeT), maybeType(typeU))),
            outputType = maybeType(typeU)))

    // Maybe.orElse
    definitions.add(FunctionSignature.create(EntityId.of("Maybe", "orElse"), typeParameters = listOf(t),
            argumentTypes = listOf(maybeType(typeT), typeT),
            outputType = typeT))

}

private fun addSequenceFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.InternalParameterType(0)

    val sequenceT = NativeStruct.SEQUENCE.getType(typeT)

    // Sequence.get
    definitions.add(FunctionSignature.create(EntityId.of("Sequence", "get"), typeParameters = listOf(t),
            argumentTypes = listOf(sequenceT, NativeStruct.NATURAL.getType()),
            outputType = typeT))

    // Sequence.first
    definitions.add(FunctionSignature.create(EntityId.of("Sequence", "first"), typeParameters = listOf(t),
            argumentTypes = listOf(sequenceT, Type.FunctionType.create(false, listOf(), listOf(typeT), NativeOpaqueType.BOOLEAN.getType())),
            outputType = typeT))

    // TODO: Consider adding BasicSequence functions here? Or unnecessary?
}

private fun addDataFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", TypeClass.Data)
    val typeT = Type.InternalParameterType(0)

    // Data.equals
    definitions.add(FunctionSignature.create(EntityId.of("Data", "equals"), typeParameters = listOf(t),
            argumentTypes = listOf(typeT, typeT),
            outputType = NativeOpaqueType.BOOLEAN.getType()))
}

// TODO: Some of these should probably go elsewhere; at least TextOut should be in a separate module
private fun addOpaqueTypeFunctions(definitions: ArrayList<FunctionSignature>) {
    val t = TypeParameter("T", null)
    val typeT = Type.InternalParameterType(0)

    // TextOut.print
    definitions.add(FunctionSignature.create(EntityId.of("TextOut", "print"), typeParameters = listOf(),
            argumentTypes = listOf(NativeOpaqueType.TEXT_OUT.getType(), NativeStruct.STRING.getType()),
            outputType = NativeStruct.VOID.getType()))

    val listBuilderT = NativeOpaqueType.LIST_BUILDER.getType(typeT)

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
            argumentTypes = listOf(listBuilderT, NativeOpaqueType.LIST.getType(typeT)),
            outputType = NativeStruct.VOID.getType()))

    // ListBuilder.build
    definitions.add(FunctionSignature.create(EntityId.of("ListBuilder", "build"), typeParameters = listOf(t),
            argumentTypes = listOf(listBuilderT),
            outputType = NativeOpaqueType.LIST.getType(typeT)))

    // Var constructor
    val varT = NativeOpaqueType.VAR.getType(typeT)
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
            argumentTypes = listOf(Type.FunctionType.create(true, listOf(), listOf(), NativeOpaqueType.BOOLEAN.getType()), Type.FunctionType.create(true, listOf(), listOf(), NativeStruct.VOID.getType())),
            outputType = NativeStruct.VOID.getType()))

    // TODO: This should go in a separate module for Returnable at some point
    // Returnable.continue
    val returnT = TypeParameter("returnT", null)
    val typeReturnT = Type.ParameterType(returnT)
    val continueT = TypeParameter("continueT", null)
    val typeContinueT = Type.ParameterType(continueT)
    definitions.add(FunctionSignature.create(EntityId.of("Returnable", "continue"), typeParameters = listOf(returnT, continueT),
        argumentTypes = listOf(
            Type.NamedType(NativeUnion.RETURNABLE.resolvedRef, NativeUnion.RETURNABLE.resolvedRef.toUnresolvedRef(), false, listOf(typeReturnT, typeContinueT)),
            Type.FunctionType.create(false, listOf(), listOf(typeContinueT), typeReturnT)
        ),
        outputType = typeReturnT
    ))
    // TODO: This should go in a separate module for Returnable at some point
    // Returnable.map
    val continue1T = TypeParameter("continue1T", null)
    val typeContinue1T = Type.ParameterType(continue1T)
    val continue2T = TypeParameter("continue2T", null)
    val typeContinue2T = Type.ParameterType(continue2T)
    definitions.add(FunctionSignature.create(EntityId.of("Returnable", "map"), typeParameters = listOf(returnT, continue1T, continue2T),
        argumentTypes = listOf(
            Type.NamedType(NativeUnion.RETURNABLE.resolvedRef, NativeUnion.RETURNABLE.resolvedRef.toUnresolvedRef(), false, listOf(typeReturnT, typeContinue1T)),
            Type.FunctionType.create(false, listOf(), listOf(typeContinue1T), typeContinue2T)
        ),
        outputType = Type.NamedType(NativeUnion.RETURNABLE.resolvedRef, NativeUnion.RETURNABLE.resolvedRef.toUnresolvedRef(), false, listOf(typeReturnT, typeContinue2T))
    ))

}

object NativeStruct {
    private val t = TypeParameter("T", null)
    private val typeT = Type.ParameterType(t)

    val NATURAL = Struct(
            EntityId.of("Natural"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("integer", NativeOpaqueType.INTEGER.getType())
            ),
            // requires: value > -1
            TypedBlock(NativeOpaqueType.BOOLEAN.getType(), listOf(ValidatedStatement.Bare(
                NativeOpaqueType.BOOLEAN.getType(),
                TypedExpression.NamedFunctionCall(
                    NativeOpaqueType.BOOLEAN.getType(),
                    AliasType.NotAliased,
                    EntityRef.of("Integer", "greaterThan"),
                    ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "greaterThan")),
                    listOf(TypedExpression.Variable(NativeOpaqueType.INTEGER.getType(), AliasType.PossiblyAliased, "integer"),
                            TypedExpression.Literal(NativeOpaqueType.INTEGER.getType(), AliasType.NotAliased, "-1")),
                    listOf(),
                    listOf()
                )
            )), AliasType.NotAliased),
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
            TypedBlock(NativeOpaqueType.BOOLEAN.getType(), listOf(ValidatedStatement.Bare(
                NativeOpaqueType.BOOLEAN.getType(),
                TypedExpression.NamedFunctionCall(
                    NativeOpaqueType.BOOLEAN.getType(),
                    AliasType.NotAliased,
                    EntityRef.of("Integer", "lessThan"),
                    ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, EntityId.of("Integer", "lessThan")),
                    listOf(
                            TypedExpression.Follow(NativeOpaqueType.INTEGER.getType(),
                                    AliasType.PossiblyAliased,
                                    TypedExpression.Variable(NativeStruct.NATURAL.getType(), AliasType.PossiblyAliased, "natural"),
                                    "integer"),
                            TypedExpression.Literal(NativeOpaqueType.INTEGER.getType(), AliasType.NotAliased, "1114112")
                    ),
                    listOf(),
                    listOf()
                )
            )), AliasType.NotAliased),
            listOf()
    )
    val STRING = Struct(
            EntityId.of("String"),
            CURRENT_NATIVE_MODULE_ID,
            listOf(),
            listOf(
                    Member("codePoints", NativeOpaqueType.LIST.getType(CODE_POINT.getType()))
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

object NativeUnion {
    private val returnT = TypeParameter("ReturnT", null)
    private val continueT = TypeParameter("ContinueT", null)

    //TODO: Move this out of the native module
    private val returnableId = EntityId.of("Returnable")
    val RETURNABLE = Union(
        id = returnableId,
        moduleId = CURRENT_NATIVE_MODULE_ID,
        typeParameters = listOf(returnT, continueT),
        options = listOf(
            Option("Return", Type.ParameterType(returnT)),
            Option("Continue", Type.ParameterType(continueT))
        ),
        annotations = listOf()
    )
}

fun getNativeUnions(): Map<EntityId, Union> {
    val unions = ArrayList<Union>()

    unions.add(NativeUnion.RETURNABLE)

    return toMap(unions)
}

object NativeOpaqueType {
    private val t = TypeParameter("T", null)

    private val integerId = EntityId.of("Integer")
    val INTEGER = OpaqueType(integerId, CURRENT_NATIVE_MODULE_ID, listOf(), false)

    private val booleanId = EntityId.of("Boolean")
    val BOOLEAN = OpaqueType(booleanId, CURRENT_NATIVE_MODULE_ID, listOf(), false)

    private val listId = EntityId.of("List")
    val LIST = OpaqueType(listId, CURRENT_NATIVE_MODULE_ID, listOf(t), false)

    private val maybeId = EntityId.of("Maybe")
    val MAYBE = OpaqueType(maybeId, CURRENT_NATIVE_MODULE_ID, listOf(t), false)

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

    types.add(NativeOpaqueType.INTEGER)
    types.add(NativeOpaqueType.BOOLEAN)
    types.add(NativeOpaqueType.LIST)
    types.add(NativeOpaqueType.MAYBE)
    types.add(NativeOpaqueType.TEXT_OUT)
    types.add(NativeOpaqueType.LIST_BUILDER)
    types.add(NativeOpaqueType.VAR)

    return types.associateBy { it.id }
}
