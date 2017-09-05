package net.semlang.api

import java.util.ArrayList
import java.util.regex.Pattern

//data class Package(val strings: List<String>) {
//    companion object {
//        val EMPTY = Package(listOf())
//    }
//
//    override fun toString(): String {
//        return strings.joinToString(".")
//    }
//}
//TODO: Currently this plays double duty as the ID for functions and structs. We may want to make this a more general
// "EntityId" type, or some such. (Other concepts like interfaces and annotations will probably use the same type.)
//data class FunctionId(val thePackage: Package, val functionName: String) {
//    companion object {
//        fun of(name: String): FunctionId {
//            return FunctionId(Package.EMPTY, name)
//        }
//    }
//    fun toPackage(): Package {
//        return Package(thePackage.strings + functionName)
//    }
//
//    override fun toString(): String {
//        if (thePackage.strings.isEmpty()) {
//            return functionName;
//        } else {
//            return thePackage.toString() + "." + functionName
//        }
//    }
//}

private val LEGAL_MODULE_PATTERN = Pattern.compile("[0-9a-zA-Z]+([_.-][0-9a-zA-Z]+)*")

data class ModuleId(val group: String, val module: String, val version: String) {
    init {
        // TODO: Consider if these restrictions can/should be relaxed
        for ((string, stringType) in listOf(group to "group",
                module to "name",
                version to "version"))
            if (!LEGAL_MODULE_PATTERN.matcher(string).matches()) {
                // TODO: Update explanation
                throw IllegalArgumentException("Illegal character in module $stringType '$string'; only letters, numbers, dots, hyphens, and underscores are allowed.")
            }
    }

    fun asRef(): ModuleRef {
        return ModuleRef(group, module, version)
    }
}

data class ModuleRef(val group: String?, val module: String, val version: String?) {
    init {
        if (group == null && version != null) {
            error("Version may not be set unless group is also set")
        }
    }
    override fun toString(): String {
        if (version != null) {
            return "$group:$module:$version"
        } else if (group != null) {
            return "$group:$module"
        } else {
            return module
        }
    }
}
// An EntityId uniquely identifies an entity within a module. An EntityRef refers to an entity that may be in this
// module or another, and may or may not have hints pointing to a particular module.
data class EntityId(val namespacedName: List<String>) {
    init {
        if (namespacedName.isEmpty()) {
            error("Entity IDs must have at least one name component")
        }
    }
    companion object {
        fun of(vararg names: String): EntityId {
            return EntityId(names.toList())
        }
    }

    override fun toString(): String {
        return namespacedName.joinToString(".")
    }

    /**
     * Returns an EntityRef with this identity and no module hints.
     */
    fun asRef(): EntityRef {
        return EntityRef(null, this)
    }
}
// Note: These should usually not be used as keys in a map; use an EntityResolver instead.
data class EntityRef(val moduleRef: ModuleRef?, val id: EntityId) {
    companion object {
        fun of(vararg names: String): EntityRef {
            return EntityRef(null, EntityId.of(*names))
        }
    }

    override fun toString(): String {
        if (moduleRef != null) {
            return moduleRef.toString() + ":" + id.toString()
        } else {
            return id.toString()
        }
    }
}
// TODO: Rename these? Use in more places?
// TODO: I'm not sure yet if we'll be using originalHints anywhere (perhaps in writer)
// Note: Null module represents either a type parameter (TODO: handle a little differently) or the native module
data class ResolvedEntityRef(val module: ModuleId?, val id: EntityId) {
}

// TODO: Are these actually useful?
interface ParameterizableType {
    fun getParameterizedTypes(): List<Type>
}

//interface ParameterizableUncheckedType {
//    fun getParameterizedTypes(): List<UncheckedType>
//}
//
//private fun replaceParametersU(parameters: List<UncheckedType>, parameterMap: Map<UncheckedType, UncheckedType>): List<UncheckedType> {
//    return parameters.map { type ->
//        parameterMap.getOrElse(type, fun (): UncheckedType {return type})
//    }
//}

private fun replaceParameters(parameters: List<Type>, parameterMap: Map<Type, Type>): List<Type> {
    return parameters.map { type ->
        parameterMap.getOrElse(type, fun (): Type {return type})
    }
}

// TODO: Hopefully some of this will be unused cruft...
//sealed class UncheckedType {
//    abstract fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType
//    abstract protected fun getTypeString(): String
//    override fun toString(): String {
//        return getTypeString()
//    }
//
//    object INTEGER : UncheckedType() {
//        override fun getTypeString(): String {
//            return "Integer"
//        }
//
//        override fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType {
//            return this
//        }
//    }
//    object NATURAL : UncheckedType() {
//        override fun getTypeString(): String {
//            return "Natural"
//        }
//
//        override fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType {
//            return this
//        }
//    }
//    object BOOLEAN : UncheckedType() {
//        override fun getTypeString(): String {
//            return "Boolean"
//        }
//
//        override fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType {
//            return this
//        }
//    }
//
//    data class List(val parameter: UncheckedType): UncheckedType() {
//        override fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType {
//            return List(parameter.replacingParameters(parameterMap))
//        }
//
//        override fun getTypeString(): String {
//            return "List<$parameter>"
//        }
//
//        override fun toString(): String {
//            return getTypeString()
//        }
//    }
//
//    data class Try(val parameter: UncheckedType): UncheckedType() {
//        override fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType {
//            return Try(parameter.replacingParameters(parameterMap))
//        }
//
//        override fun getTypeString(): String {
//            return "Try<$parameter>"
//        }
//
//        override fun toString(): String {
//            return getTypeString()
//        }
//    }
//
//    data class FunctionType(val argTypes: kotlin.collections.List<UncheckedType>, val outputType: UncheckedType): UncheckedType() {
//        override fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType {
//            return FunctionType(argTypes.map { type -> type.replacingParameters(parameterMap) },
//                    outputType.replacingParameters(parameterMap))
//        }
//
//        override fun getTypeString(): String {
//            return "(" +
//                    argTypes.joinToString(", ") +
//                    ") -> " +
//                    outputType.toString()
//        }
//
//        override fun toString(): String {
//            return getTypeString()
//        }
//    }
//
//    //TODO: In the validator, validate that it does not share a name with a default type
//    data class NamedType(val id: EntityRef, val parameters: kotlin.collections.List<UncheckedType> = listOf()): UncheckedType(), ParameterizableUncheckedType {
//        companion object {
//            fun forParameter(name: String): NamedType {
//                return NamedType(EntityRef.of(name), listOf())
//            }
//        }
//        override fun replacingParameters(parameterMap: Map<UncheckedType, UncheckedType>): UncheckedType {
//            val replacement = parameterMap[this]
//            if (replacement != null) {
//                // TODO: Should this have replaceParameters applied to it?
//                return replacement
//            }
//            return NamedType(id,
//                    replaceParametersU(parameters, parameterMap))
//        }
//
//        override fun getParameterizedTypes(): kotlin.collections.List<UncheckedType> {
//            return parameters
//        }
//
//        override fun getTypeString(): String {
//            return id.toString() +
//                    if (parameters.isEmpty()) {
//                        ""
//                    } else {
//                        "<" + parameters.joinToString(", ") + ">"
//                    }
//        }
//
//        override fun toString(): String {
//            return super.toString()
//        }
//    }
//}

sealed class Type {
    abstract fun replacingParameters(parameterMap: Map<Type, Type>): Type
    abstract protected fun getTypeString(): String
    override fun toString(): String {
        return getTypeString()
    }

    object INTEGER : Type() {
        override fun getTypeString(): String {
            return "Integer"
        }

        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return this
        }
    }
    object NATURAL : Type() {
        override fun getTypeString(): String {
            return "Natural"
        }

        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return this
        }
    }
    object BOOLEAN : Type() {
        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return this
        }
    }

    data class List(val parameter: Type): Type() {
        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return List(parameter.replacingParameters(parameterMap))
        }

        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Try(val parameter: Type): Type() {
        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return Try(parameter.replacingParameters(parameterMap))
        }

        override fun getTypeString(): String {
            return "Try<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class FunctionType(val argTypes: kotlin.collections.List<Type>, val outputType: Type): Type() {
        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return FunctionType(argTypes.map { type -> type.replacingParameters(parameterMap) },
                    outputType.replacingParameters(parameterMap))
        }

        override fun getTypeString(): String {
            return "(" +
                    argTypes.joinToString(", ") +
                    ") -> " +
                    outputType.toString()
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    //TODO: In the validator, validate that it does not share a name with a default type
    data class NamedType(val id: EntityRef, val parameters: kotlin.collections.List<Type> = listOf()): Type(), ParameterizableType {
        companion object {
            fun forParameter(name: String): NamedType {
                return NamedType(EntityRef(null, EntityId(listOf(name))), listOf())
            }
        }
        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            val replacement = parameterMap[this]
            if (replacement != null) {
                // TODO: Should this have replaceParameters applied to it?
                return replacement
            }
            return NamedType(id,
                    replaceParameters(parameters, parameterMap))
        }

        override fun getParameterizedTypes(): kotlin.collections.List<Type> {
            return parameters
        }

        override fun getTypeString(): String {
            return id.toString() +
                if (parameters.isEmpty()) {
                    ""
                } else {
                    "<" + parameters.joinToString(", ") + ">"
                }
        }

        override fun toString(): String {
            return super.toString()
        }
    }
}

data class Position(val lineNumber: Int, val column: Int, val rawStart: Int, val rawEnd: Int)

data class Annotation(val name: String, val value: String?)

// Pre-scoping
sealed class AmbiguousExpression {
    abstract val position: Position
    data class Variable(val name: String, override val position: Position): AmbiguousExpression()
    data class VarOrNamedFunctionBinding(val functionIdOrVariable: EntityRef, val chosenParameters: List<Type>, val bindings: List<AmbiguousExpression?>, override val position: Position): AmbiguousExpression()
    data class ExpressionOrNamedFunctionBinding(val expression: AmbiguousExpression, val chosenParameters: List<Type>, val bindings: List<AmbiguousExpression?>, override val position: Position): AmbiguousExpression()
    data class IfThen(val condition: AmbiguousExpression, val thenBlock: AmbiguousBlock, val elseBlock: AmbiguousBlock, override val position: Position): AmbiguousExpression()
    data class VarOrNamedFunctionCall(val functionIdOrVariable: EntityRef, val arguments: List<AmbiguousExpression>, val chosenParameters: List<Type>, override val position: Position): AmbiguousExpression()
    data class ExpressionOrNamedFunctionCall(val expression: AmbiguousExpression, val arguments: List<AmbiguousExpression>, val chosenParameters: List<Type>, override val position: Position): AmbiguousExpression()
    data class Literal(val type: Type, val literal: String, override val position: Position): AmbiguousExpression()
    data class Follow(val expression: AmbiguousExpression, val name: String, override val position: Position): AmbiguousExpression()
}

// Post-scoping, pre-type-analysis
sealed class Expression {
    abstract val position: Position?
    data class Variable(val name: String, override val position: Position?): Expression()
    data class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block, override val position: Position?): Expression()
    data class NamedFunctionCall(val functionRef: EntityRef, val arguments: List<Expression>, val chosenParameters: List<Type>, override val position: Position?): Expression()
    //TODO: Make position of chosenParamters consistent with bindings below
    data class ExpressionFunctionCall(val functionExpression: Expression, val arguments: List<Expression>, val chosenParameters: List<Type>, override val position: Position?): Expression()
    data class Literal(val type: Type, val literal: String, override val position: Position?): Expression()
    data class NamedFunctionBinding(val functionRef: EntityRef, val chosenParameters: List<Type>, val bindings: List<Expression?>, override val position: Position?): Expression()
    data class ExpressionFunctionBinding(val functionExpression: Expression, val chosenParameters: List<Type>, val bindings: List<Expression?>, override val position: Position?): Expression()
    data class Follow(val expression: Expression, val name: String, override val position: Position?): Expression()
}
// Post-type-analysis
sealed class TypedExpression {
    abstract val type: Type
    data class Variable(override val type: Type, val name: String): TypedExpression()
    data class IfThen(override val type: Type, val condition: TypedExpression, val thenBlock: TypedBlock, val elseBlock: TypedBlock): TypedExpression()
    data class NamedFunctionCall(override val type: Type, val functionRef: EntityRef, val arguments: List<TypedExpression>, val chosenParameters: List<Type>): TypedExpression()
    data class ExpressionFunctionCall(override val type: Type, val functionExpression: TypedExpression, val arguments: List<TypedExpression>, val chosenParameters: List<Type>): TypedExpression()
    data class Literal(override val type: Type, val literal: String): TypedExpression()
    data class Follow(override val type: Type, val expression: TypedExpression, val name: String): TypedExpression()
    data class NamedFunctionBinding(override val type: Type, val functionRef: EntityRef, val bindings: List<TypedExpression?>, val chosenParameters: List<Type>) : TypedExpression()
    data class ExpressionFunctionBinding(override val type: Type, val functionExpression: TypedExpression, val bindings: List<TypedExpression?>, val chosenParameters: List<Type>) : TypedExpression()
}

data class AmbiguousAssignment(val name: String, val type: Type?, val expression: AmbiguousExpression)
data class Assignment(val name: String, val type: Type?, val expression: Expression)
data class ValidatedAssignment(val name: String, val type: Type, val expression: TypedExpression)
data class Argument(val name: String, val type: Type)
data class AmbiguousBlock(val assignments: List<AmbiguousAssignment>, val returnedExpression: AmbiguousExpression)
data class Block(val assignments: List<Assignment>, val returnedExpression: Expression)
data class TypedBlock(val type: Type, val assignments: List<ValidatedAssignment>, val returnedExpression: TypedExpression)
data class Function(override val id: EntityId, val typeParameters: List<String>, val arguments: List<Argument>, val returnType: Type, val block: Block, override val annotations: List<Annotation>) : TopLevelEntity
data class ValidatedFunction(override val id: EntityId, val typeParameters: List<String>, val arguments: List<Argument>, val returnType: Type, val block: TypedBlock, override val annotations: List<Annotation>) : TopLevelEntity {
    fun toTypeSignature(): TypeSignature {
        return TypeSignature(id,
                arguments.map(Argument::type),
                returnType,
                typeParameters.map { str -> Type.NamedType.forParameter(str) })
    }
}

data class UnvalidatedStruct(override val id: EntityId, val typeParameters: List<String>, val members: List<Member>, val requires: Block?, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getConstructorSignature(): TypeSignature {
        val argumentTypes = members.map(Member::type)
        val typeParameters = typeParameters.map(Type.NamedType.Companion::forParameter)
        val outputType = if (requires == null) {
            Type.NamedType(id.asRef(), typeParameters)
        } else {
            Type.Try(Type.NamedType(id.asRef(), typeParameters))
        }
        return TypeSignature(id, argumentTypes, outputType, typeParameters)
    }
}
data class Struct(override val id: EntityId, val typeParameters: List<String>, val members: List<Member>, val requires: TypedBlock?, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getIndexForName(name: String): Int {
        return members.indexOfFirst { member -> member.name == name }
    }

    // TODO: Deconflict with UnvalidatedStruct version
    fun getConstructorSignature(): TypeSignature {
        val argumentTypes = members.map(Member::type)
        val typeParameters = typeParameters.map(Type.NamedType.Companion::forParameter)
        val outputType = if (requires == null) {
            Type.NamedType(id.asRef(), typeParameters)
        } else {
            Type.Try(Type.NamedType(id.asRef(), typeParameters))
        }
        return TypeSignature(id, argumentTypes, outputType, typeParameters)
    }
}
interface HasId {
    val id: EntityId
}
interface TopLevelEntity: HasId {
    val annotations: List<Annotation>
}
data class Member(val name: String, val type: Type)

data class Interface(override val id: EntityId, val typeParameters: List<String>, val methods: List<Method>, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getIndexForName(name: String): Int {
        return methods.indexOfFirst { method -> method.name == name }
    }
    val adapterId: EntityId = EntityId(id.namespacedName + "Adapter")
    val adapterStruct: Struct = Struct(adapterId, typeParameters, methods.map { method -> Member(method.name, method.functionType) }, null, listOf())

    fun getInstanceConstructorSignature(): TypeSignature {
        val explicitTypeParameters = this.typeParameters
        val dataTypeParameter = getUnusedTypeParameterName(explicitTypeParameters)
        val allTypeParameters = ArrayList(explicitTypeParameters)
        allTypeParameters.add(0, dataTypeParameter) // Data type parameter comes first

        val argumentTypes = ArrayList<Type>()
        val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
        argumentTypes.add(dataStructType)

        val adapterType = Type.NamedType(this.adapterId.asRef(), allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
        argumentTypes.add(adapterType)

        val outputType = Type.NamedType(this.id.asRef(), explicitTypeParameters.map { name -> Type.NamedType.forParameter(name) })

        return TypeSignature(this.id, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    }
    fun getAdapterConstructorSignature(): TypeSignature {
        val explicitTypeParameters = this.typeParameters
        val dataTypeParameter = getUnusedTypeParameterName(explicitTypeParameters)
        val allTypeParameters = ArrayList(explicitTypeParameters)
        allTypeParameters.add(0, dataTypeParameter) // Data type parameter comes first

        val argumentTypes = ArrayList<Type>()
        val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = Type.NamedType(this.adapterId.asRef(), allTypeParameters.map { name -> Type.NamedType.forParameter(name) })

        return TypeSignature(this.adapterId, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    }
}
data class Method(val name: String, val typeParameters: List<String>, val arguments: List<Argument>, val returnType: Type) {
    val functionType = Type.FunctionType(arguments.map { arg -> arg.type }, returnType)
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

private fun getInterfaceMethodReferenceType(intrinsicStructType: Type.NamedType, method: Method): Type {
    val argTypes = ArrayList<Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return Type.FunctionType(argTypes, method.returnType)
}

// TODO: Use this where appropriate, colocate with reverse function
// TODO: EntityId or EntityRef?
fun getInterfaceIdForAdapterId(adapterId: EntityId): EntityId? {
    if (adapterId.namespacedName.size > 1 && adapterId.namespacedName.last() == "Adapter") {
        return EntityId(adapterId.namespacedName.dropLast(1))
    }
    return null
}
fun getInterfaceRefForAdapterRef(adapterRef: EntityRef): EntityRef? {
    val interfaceId = getInterfaceIdForAdapterId(adapterRef.id)
    if (interfaceId == null) {
        return null
    }
    return EntityRef(adapterRef.moduleRef, interfaceId)
}
