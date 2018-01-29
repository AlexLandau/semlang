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
    addNaturalFunctions(definitions)
    addListFunctions(definitions)
    addTryFunctions(definitions)
    addSequenceFunctions(definitions)
    addStringFunctions(definitions)

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

    // Boolean.any
    definitions.add(TypeSignature(EntityId.of("Boolean", "any"), listOf(Type.List(Type.BOOLEAN)), Type.BOOLEAN))
    // Boolean.all
    definitions.add(TypeSignature(EntityId.of("Boolean", "all"), listOf(Type.List(Type.BOOLEAN)), Type.BOOLEAN))
}

private fun addIntegerFunctions(definitions: ArrayList<TypeSignature>) {

    // Integer.times
    definitions.add(TypeSignature(EntityId.of("Integer", "times"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.plus
    definitions.add(TypeSignature(EntityId.of("Integer", "plus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.minus
    definitions.add(TypeSignature(EntityId.of("Integer", "minus"), listOf(Type.INTEGER, Type.INTEGER), Type.INTEGER))

    // Integer.equals
    definitions.add(TypeSignature(EntityId.of("Integer", "equals"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.lessThan
    definitions.add(TypeSignature(EntityId.of("Integer", "lessThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))
    // Integer.greaterThan
    definitions.add(TypeSignature(EntityId.of("Integer", "greaterThan"), listOf(Type.INTEGER, Type.INTEGER), Type.BOOLEAN))

    // Integer.sum
    // TODO: Should be in library instead of here, once we can use the library more easily
    definitions.add(TypeSignature(EntityId.of("Integer", "sum"), listOf(Type.List(Type.INTEGER)), Type.INTEGER))

    // Integer.fromNatural
    definitions.add(TypeSignature(EntityId.of("Integer", "fromNatural"), listOf(Type.NATURAL), Type.INTEGER))
}

private fun addNaturalFunctions(definitions: ArrayList<TypeSignature>) {

    // Natural.times
    definitions.add(TypeSignature(EntityId.of("Natural", "times"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.plus
    definitions.add(TypeSignature(EntityId.of("Natural", "plus"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.divide
    definitions.add(TypeSignature(EntityId.of("Natural", "divide"), listOf(Type.NATURAL, Type.NATURAL), Type.Try(Type.NATURAL)))

    // Natural.remainder
    definitions.add(TypeSignature(EntityId.of("Natural", "remainder"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.toPower
    definitions.add(TypeSignature(EntityId.of("Natural", "toPower"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.equals
    definitions.add(TypeSignature(EntityId.of("Natural", "equals"), listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))
    // Natural.lessThan
    definitions.add(TypeSignature(EntityId.of("Natural", "lessThan"), listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))
    // Natural.greaterThan
    definitions.add(TypeSignature(EntityId.of("Natural", "greaterThan"), listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))
    // Natural.lessThanOrEqualTo
    definitions.add(TypeSignature(EntityId.of("Natural", "lessThanOrEqualTo"), listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))
    // Natural.greaterThanOrEqualTo
    definitions.add(TypeSignature(EntityId.of("Natural", "greaterThanOrEqualTo"), listOf(Type.NATURAL, Type.NATURAL), Type.BOOLEAN))

    // Natural.bitwiseAnd
    definitions.add(TypeSignature(EntityId.of("Natural", "bitwiseAnd"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))
    // Natural.toBits
    definitions.add(TypeSignature(EntityId.of("Natural", "toBits"), listOf(Type.NATURAL), Type.NamedType(NativeStruct.BITS_BIG_ENDIAN.id.asRef())))
    // Natural.toNBits
    definitions.add(TypeSignature(EntityId.of("Natural", "toNBits"), listOf(Type.NATURAL, Type.NATURAL), Type.Try(Type.NamedType(NativeStruct.BITS_BIG_ENDIAN.id.asRef()))))
    // Natural.fromBits
    definitions.add(TypeSignature(EntityId.of("Natural", "fromBits"), listOf(Type.NamedType(NativeStruct.BITS_BIG_ENDIAN.id.asRef())), Type.NATURAL))

    // Natural.max
    definitions.add(TypeSignature(EntityId.of("Natural", "max"), listOf(Type.List(Type.NATURAL)), Type.Try(Type.NATURAL)))
    // Natural.lesser
    definitions.add(TypeSignature(EntityId.of("Natural", "lesser"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.absoluteDifference
    definitions.add(TypeSignature(EntityId.of("Natural", "absoluteDifference"), listOf(Type.NATURAL, Type.NATURAL), Type.NATURAL))

    // Natural.rangeInclusive
    definitions.add(TypeSignature(EntityId.of("Natural", "rangeInclusive"), listOf(Type.NATURAL, Type.NATURAL), Type.List(Type.NATURAL)))
}

private fun addListFunctions(definitions: ArrayList<TypeSignature>) {
    val paramT = Type.NamedType.forParameter("T")
    val paramU = Type.NamedType.forParameter("U")

    // List.append
    definitions.add(TypeSignature(EntityId.of("List", "append"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), paramT),
            outputType = Type.List(paramT)))

    // List.appendFront
    definitions.add(TypeSignature(EntityId.of("List", "appendFront"), typeParameters = listOf(paramT),
            argumentTypes = listOf(paramT, Type.List(paramT)),
            outputType = Type.List(paramT)))

    // List.concatenate
    definitions.add(TypeSignature(EntityId.of("List", "concatenate"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.List(paramT)),
            outputType = Type.List(paramT)))

    // List.drop
    definitions.add(TypeSignature(EntityId.of("List", "drop"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.NATURAL),
            outputType = Type.List(paramT)))

    // TODO: Semantics of this are arguably different from last()... but I kind of like it that way
    // List.lastN
    definitions.add(TypeSignature(EntityId.of("List", "lastN"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.NATURAL),
            outputType = Type.List(paramT)))

    // List.map
    definitions.add(TypeSignature(EntityId.of("List", "map"), typeParameters = listOf(paramT, paramU),
            argumentTypes = listOf(Type.List(paramT), Type.FunctionType(listOf(paramT), paramU)),
            outputType = Type.List(paramU)))

    // List.filter
    definitions.add(TypeSignature(EntityId.of("List", "filter"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.FunctionType(listOf(paramT), Type.BOOLEAN)),
            outputType = Type.List(paramT)))

    // List.reduce
    definitions.add(TypeSignature(EntityId.of("List", "reduce"), typeParameters = listOf(paramT, paramU),
            argumentTypes = listOf(Type.List(paramT), paramU, Type.FunctionType(listOf(paramU, paramT), paramU)),
            outputType = paramU))

    // List.size
    definitions.add(TypeSignature(EntityId.of("List", "size"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT)),
            outputType = Type.NATURAL))

    // List.get
    definitions.add(TypeSignature(EntityId.of("List", "get"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT), Type.NATURAL),
            outputType = Type.Try(paramT)))

    // List.first TODO: To library
    definitions.add(TypeSignature(EntityId.of("List", "first"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT)),
            outputType = Type.Try(paramT)))

    // List.last TODO: To library
    definitions.add(TypeSignature(EntityId.of("List", "last"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.List(paramT)),
            outputType = Type.Try(paramT)))
}

private fun addTryFunctions(definitions: ArrayList<TypeSignature>) {
    val paramT = Type.NamedType.forParameter("T")
    val paramU = Type.NamedType.forParameter("U")

    // Try.success
    definitions.add(TypeSignature(EntityId.of("Try", "success"), typeParameters = listOf(paramT),
            argumentTypes = listOf(paramT),
            outputType = Type.Try(paramT)))

    // Try.failure
    definitions.add(TypeSignature(EntityId.of("Try", "failure"), typeParameters = listOf(paramT),
            argumentTypes = listOf(),
            outputType = Type.Try(paramT)))

    // Try.assume
    definitions.add(TypeSignature(EntityId.of("Try", "assume"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.Try(paramT)),
            outputType = paramT))


    // Try.isSuccess
    definitions.add(TypeSignature(EntityId.of("Try", "isSuccess"), typeParameters = listOf(paramT),
            argumentTypes = listOf(Type.Try(paramT)),
            outputType = Type.BOOLEAN))

    // Try.map
    definitions.add(TypeSignature(EntityId.of("Try", "map"), typeParameters = listOf(paramT, paramU),
            argumentTypes = listOf(Type.Try(paramT), Type.FunctionType(listOf(paramT), paramU)),
            outputType = Type.Try(paramU)))
}

private fun addSequenceFunctions(definitions: ArrayList<TypeSignature>) {
    val paramT = Type.NamedType.forParameter("T")

    val sequenceT = Type.NamedType(EntityRef.of("Sequence"), listOf(paramT))

    // Sequence.create
    // TODO: This should be library code in semlang in most cases, not native
    definitions.add(TypeSignature(EntityId.of("Sequence", "create"), typeParameters = listOf(paramT),
            argumentTypes = listOf(paramT, Type.FunctionType(listOf(paramT), paramT)),
            outputType = sequenceT))

    // TODO: Consider adding BasicSequence functions here? Or unnecessary?
}

private fun addStringFunctions(definitions: ArrayList<TypeSignature>) {
    val stringType = Type.NamedType(EntityRef.of("Unicode", "String"))

    // Unicode.String.length
    // TODO: Limit output to 32-bit type
    definitions.add(TypeSignature(EntityId.of("Unicode", "String", "length"),
            argumentTypes = listOf(stringType),
            outputType = Type.NATURAL))
}

object NativeStruct {
    private val typeT = Type.NamedType.forParameter("T")
    private val typeU = Type.NamedType.forParameter("U")
    val BASIC_SEQUENCE = Struct(
            EntityId.of("BasicSequence"),
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
            listOf(),
            listOf(
                    Member("natural", Type.NATURAL)
            ),
            // requires: value < 1114112
            TypedBlock(Type.BOOLEAN, listOf(), TypedExpression.NamedFunctionCall(
                    Type.BOOLEAN,
                    EntityRef.of("Natural", "lessThan"),
                    listOf(TypedExpression.Variable(Type.NATURAL, "natural"),
                            TypedExpression.Literal(Type.NATURAL, "1114112")),
                    listOf()
            )),
            listOf()
    )
    val UNICODE_STRING = Struct(
            EntityId.of("Unicode", "String"),
            listOf(),
            listOf(
                    Member("codePoints", Type.List(Type.NamedType(UNICODE_CODE_POINT.id.asRef())))
            ),
            null,
            listOf()
    )
    val BIT = Struct(
            EntityId.of("Bit"),
            listOf(),
            listOf(
                    Member("natural", Type.NATURAL)
            ),
            // requires: value = 0 or value = 1
            TypedBlock(Type.BOOLEAN, listOf(), TypedExpression.NamedFunctionCall(
                    Type.BOOLEAN,
                    EntityRef.of("Boolean", "or"),
                    listOf(TypedExpression.NamedFunctionCall(
                            Type.BOOLEAN,
                            EntityRef.of("Natural", "equals"),
                            listOf(TypedExpression.Variable(Type.NATURAL, "natural"),
                                    TypedExpression.Literal(Type.NATURAL, "0")),
                            listOf()
                        ), TypedExpression.NamedFunctionCall(
                            Type.BOOLEAN,
                            EntityRef.of("Natural", "equals"),
                            listOf(TypedExpression.Variable(Type.NATURAL, "natural"),
                                    TypedExpression.Literal(Type.NATURAL, "1")),
                            listOf()
                        )
                    ),
                    listOf()
            )),
            listOf()
    )
    val BITS_BIG_ENDIAN = Struct(
            EntityId.of("BitsBigEndian"),
            listOf(),
            listOf(
                    Member("bits", Type.List(Type.NamedType(BIT.id.asRef())))
            ),
            null,
            listOf()
    )
}

fun getNativeStructs(): Map<EntityId, Struct> {
    val structs = ArrayList<Struct>()

    structs.add(NativeStruct.BASIC_SEQUENCE)
    structs.add(NativeStruct.UNICODE_CODE_POINT)
    structs.add(NativeStruct.UNICODE_STRING)
    structs.add(NativeStruct.BIT)
    structs.add(NativeStruct.BITS_BIG_ENDIAN)

    return toMap(structs)
}

object NativeInterface {
    private val typeT = Type.NamedType.forParameter("T")
    val SEQUENCE = Interface(
            EntityId.of("Sequence"),
            listOf("T"),
            listOf(
                    Method("get", listOf(), listOf(Argument("index", Type.NATURAL)), typeT),
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
