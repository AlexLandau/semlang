package net.semlang.api

import java.util.ArrayList


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
/**
 * Note: These should usually not be used as keys in a map; use ResolvedEntityRefs from an EntityResolver instead.
 */
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
data class ResolvedEntityRef(val module: ModuleId, val id: EntityId) {
    override fun toString(): String {
        return "${module.group}:${module.module}:${module.version}:$id"
    }
}

// TODO: Are these actually useful?
interface ParameterizableType {
    fun getParameterizedTypes(): List<Type>
}

private fun replaceParameters(parameters: List<Type>, parameterMap: Map<Type, Type>): List<Type> {
    return parameters.map { type ->
        parameterMap.getOrElse(type, fun (): Type {return type})
    }
}

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
    data class NamedType(val ref: EntityRef, val parameters: kotlin.collections.List<Type> = listOf()): Type(), ParameterizableType {
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
            return NamedType(ref,
                    replaceParameters(parameters, parameterMap))
        }

        override fun getParameterizedTypes(): kotlin.collections.List<Type> {
            return parameters
        }

        override fun getTypeString(): String {
            return ref.toString() +
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

// TODO: Maybe rename FunctionSignature?
data class TypeSignature(override val id: EntityId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<Type> = listOf()): HasId

data class Position(val lineNumber: Int, val column: Int, val rawIndex: Int)
data class Range(val start: Position, val end: Position)
data class Location(val documentUri: String, val range: Range)

data class Annotation(val name: String, val value: String?)

// Pre-scoping
sealed class AmbiguousExpression {
    abstract val location: Location
    data class Variable(val name: String, override val location: Location): AmbiguousExpression()
    data class VarOrNamedFunctionBinding(val functionIdOrVariable: EntityRef, val chosenParameters: List<Type>, val bindings: List<AmbiguousExpression?>, override val location: Location): AmbiguousExpression()
    data class ExpressionOrNamedFunctionBinding(val expression: AmbiguousExpression, val chosenParameters: List<Type>, val bindings: List<AmbiguousExpression?>, override val location: Location): AmbiguousExpression()
    data class IfThen(val condition: AmbiguousExpression, val thenBlock: AmbiguousBlock, val elseBlock: AmbiguousBlock, override val location: Location): AmbiguousExpression()
    data class VarOrNamedFunctionCall(val functionIdOrVariable: EntityRef, val arguments: List<AmbiguousExpression>, val chosenParameters: List<Type>, override val location: Location, val varOrNameLocation: Location?): AmbiguousExpression()
    data class ExpressionOrNamedFunctionCall(val expression: AmbiguousExpression, val arguments: List<AmbiguousExpression>, val chosenParameters: List<Type>, override val location: Location, val expressionOrNameLocation: Location): AmbiguousExpression()
    data class Literal(val type: Type, val literal: String, override val location: Location): AmbiguousExpression()
    data class ListLiteral(val contents: List<AmbiguousExpression>, val chosenParameter: Type, override val location: Location): AmbiguousExpression()
    data class Follow(val expression: AmbiguousExpression, val name: String, override val location: Location): AmbiguousExpression()
    data class InlineFunction(val arguments: List<UnvalidatedArgument>, val block: AmbiguousBlock, override val location: Location): AmbiguousExpression()
}

// Post-scoping, pre-type-analysis
sealed class Expression {
    abstract val location: Location?
    data class Variable(val name: String, override val location: Location?): Expression()
    data class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block, override val location: Location?): Expression()
    data class NamedFunctionCall(val functionRef: EntityRef, val arguments: List<Expression>, val chosenParameters: List<Type>, override val location: Location?, val functionRefLocation: Location?): Expression()
    //TODO: Make position of chosenParamters consistent with bindings below
    data class ExpressionFunctionCall(val functionExpression: Expression, val arguments: List<Expression>, val chosenParameters: List<Type>, override val location: Location?): Expression()
    data class Literal(val type: Type, val literal: String, override val location: Location?): Expression()
    data class ListLiteral(val contents: List<Expression>, val chosenParameter: Type, override val location: Location?): Expression()
    data class NamedFunctionBinding(val functionRef: EntityRef, val chosenParameters: List<Type>, val bindings: List<Expression?>, override val location: Location?): Expression()
    data class ExpressionFunctionBinding(val functionExpression: Expression, val chosenParameters: List<Type>, val bindings: List<Expression?>, override val location: Location?): Expression()
    data class Follow(val expression: Expression, val name: String, override val location: Location?): Expression()
    data class InlineFunction(val arguments: List<UnvalidatedArgument>, val block: Block, override val location: Location?): Expression()
}
// Post-type-analysis
sealed class TypedExpression {
    abstract val type: Type
    data class Variable(override val type: Type, val name: String): TypedExpression()
    data class IfThen(override val type: Type, val condition: TypedExpression, val thenBlock: TypedBlock, val elseBlock: TypedBlock): TypedExpression()
    data class NamedFunctionCall(override val type: Type, val functionRef: EntityRef, val arguments: List<TypedExpression>, val chosenParameters: List<Type>): TypedExpression()
    data class ExpressionFunctionCall(override val type: Type, val functionExpression: TypedExpression, val arguments: List<TypedExpression>, val chosenParameters: List<Type>): TypedExpression()
    data class Literal(override val type: Type, val literal: String): TypedExpression()
    data class ListLiteral(override val type: Type, val contents: List<TypedExpression>, val chosenParameter: Type): TypedExpression()
    data class NamedFunctionBinding(override val type: Type, val functionRef: EntityRef, val bindings: List<TypedExpression?>, val chosenParameters: List<Type>) : TypedExpression()
    data class ExpressionFunctionBinding(override val type: Type, val functionExpression: TypedExpression, val bindings: List<TypedExpression?>, val chosenParameters: List<Type>) : TypedExpression()
    data class Follow(override val type: Type, val expression: TypedExpression, val name: String): TypedExpression()
    data class InlineFunction(override val type: Type, val arguments: List<Argument>, val varsToBind: List<String>, val block: TypedBlock): TypedExpression()
}

data class AmbiguousAssignment(val name: String, val type: Type?, val expression: AmbiguousExpression, val nameLocation: Location?)
data class Assignment(val name: String, val type: Type?, val expression: Expression, val nameLocation: Location?)
data class ValidatedAssignment(val name: String, val type: Type, val expression: TypedExpression)
data class UnvalidatedArgument(val name: String, val type: Type, val location: Location?)
data class Argument(val name: String, val type: Type)
data class AmbiguousBlock(val assignments: List<AmbiguousAssignment>, val returnedExpression: AmbiguousExpression, val location: Location?)
data class Block(val assignments: List<Assignment>, val returnedExpression: Expression, val location: Location?)
data class TypedBlock(val type: Type, val assignments: List<ValidatedAssignment>, val returnedExpression: TypedExpression)
data class Function(override val id: EntityId, val typeParameters: List<String>, val arguments: List<UnvalidatedArgument>, val returnType: Type, val block: Block, override val annotations: List<Annotation>, val idLocation: Location?, val returnTypeLocation: Location?) : TopLevelEntity {
    fun getTypeSignature(): TypeSignature {
        return TypeSignature(id,
                arguments.map(UnvalidatedArgument::type),
                returnType,
                typeParameters.map { str -> Type.NamedType.forParameter(str) })
    }
}
data class ValidatedFunction(override val id: EntityId, val typeParameters: List<String>, val arguments: List<Argument>, val returnType: Type, val block: TypedBlock, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getTypeSignature(): TypeSignature {
        return TypeSignature(id,
                arguments.map(Argument::type),
                returnType,
                typeParameters.map { str -> Type.NamedType.forParameter(str) })
    }
}

data class UnvalidatedStruct(override val id: EntityId, val typeParameters: List<String>, val members: List<Member>, val requires: Block?, override val annotations: List<Annotation>, val idLocation: Location?) : TopLevelEntity {
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

data class UnvalidatedInterface(override val id: EntityId, val typeParameters: List<String>, val methods: List<UnvalidatedMethod>, override val annotations: List<Annotation>, val idLocation: Location?) : TopLevelEntity {
    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val adapterStruct: UnvalidatedStruct = UnvalidatedStruct(adapterId, listOf(getUnusedTypeParameterName(typeParameters)) + typeParameters,
            methods.map { method -> Member(method.name, method.functionType) }, null, listOf(), idLocation)

    fun getInstanceConstructorSignature(): TypeSignature {
        val explicitTypeParameters = this.typeParameters
        val allTypeParameters = this.adapterStruct.typeParameters
        val dataTypeParameter = allTypeParameters[0]

        val argumentTypes = ArrayList<Type>()
        val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
        argumentTypes.add(dataStructType)

        val adapterType = Type.NamedType(this.adapterId.asRef(), allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
        argumentTypes.add(adapterType)

        val outputType = Type.NamedType(this.id.asRef(), explicitTypeParameters.map { name -> Type.NamedType.forParameter(name) })

        return TypeSignature(this.id, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    }
    fun getAdapterConstructorSignature(): TypeSignature {
        val adapterTypeParameters = this.adapterStruct.typeParameters
        val dataStructType = Type.NamedType.forParameter(adapterTypeParameters[0])

        val argumentTypes = ArrayList<Type>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = Type.NamedType(this.adapterId.asRef(), adapterTypeParameters.map { name -> Type.NamedType.forParameter(name) })

        return TypeSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    }
}
data class Interface(override val id: EntityId, val typeParameters: List<String>, val methods: List<Method>, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getIndexForName(name: String): Int {
        return methods.indexOfFirst { method -> method.name == name }
    }
    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val adapterStruct: Struct = Struct(adapterId, listOf(getUnusedTypeParameterName(typeParameters)) + typeParameters,
            methods.map { method -> Member(method.name, method.functionType) }, null, listOf())

    fun getInstanceConstructorSignature(): TypeSignature {
        val explicitTypeParameters = this.typeParameters
        val allTypeParameters = this.adapterStruct.typeParameters
        val dataTypeParameter = allTypeParameters[0]

        val argumentTypes = ArrayList<Type>()
        val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
        argumentTypes.add(dataStructType)

        val adapterType = Type.NamedType(this.adapterId.asRef(), allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
        argumentTypes.add(adapterType)

        val outputType = Type.NamedType(this.id.asRef(), explicitTypeParameters.map { name -> Type.NamedType.forParameter(name) })

        return TypeSignature(this.id, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    }
    fun getAdapterConstructorSignature(): TypeSignature {
        val adapterTypeParameters = this.adapterStruct.typeParameters
        val dataStructType = Type.NamedType.forParameter(adapterTypeParameters[0])

        val argumentTypes = ArrayList<Type>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = Type.NamedType(this.adapterId.asRef(), adapterTypeParameters.map { name -> Type.NamedType.forParameter(name) })

        return TypeSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    }
}
data class UnvalidatedMethod(val name: String, val typeParameters: List<String>, val arguments: List<UnvalidatedArgument>, val returnType: Type) {
    val functionType = Type.FunctionType(arguments.map { arg -> arg.type }, returnType)
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

private fun getInterfaceMethodReferenceType(intrinsicStructType: Type.NamedType, method: UnvalidatedMethod): Type {
    val argTypes = ArrayList<Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return Type.FunctionType(argTypes, method.returnType)
}
private fun getInterfaceMethodReferenceType(intrinsicStructType: Type.NamedType, method: Method): Type {
    val argTypes = ArrayList<Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return Type.FunctionType(argTypes, method.returnType)
}

fun getInterfaceIdForAdapterId(adapterId: EntityId): EntityId? {
    if (adapterId.namespacedName.size > 1 && adapterId.namespacedName.last() == "Adapter") {
        return EntityId(adapterId.namespacedName.dropLast(1))
    }
    return null
}
fun getAdapterIdForInterfaceId(interfaceId: EntityId): EntityId {
    return EntityId(interfaceId.namespacedName + "Adapter")
}
fun getInterfaceRefForAdapterRef(adapterRef: EntityRef): EntityRef? {
    val interfaceId = getInterfaceIdForAdapterId(adapterRef.id)
    if (interfaceId == null) {
        return null
    }
    return EntityRef(adapterRef.moduleRef, interfaceId)
}
