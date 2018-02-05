package net.semlang.api

import java.util.*


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

private fun replaceParametersUnvalidated(parameters: List<UnvalidatedType>, parameterMap: Map<UnvalidatedType, UnvalidatedType>): List<UnvalidatedType> {
    return parameters.map { type ->
        parameterMap.getOrElse(type, fun (): UnvalidatedType {return type})
    }
}

//private fun replaceParameters(parameters: List<Type>, parameterMap: Map<out Type, Type>): List<Type> {
//    return parameters.map { type ->
//        parameterMap.getOrElse(type, fun (): Type {return type})
//    }
//}

sealed class UnvalidatedType {
    abstract fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType
    abstract protected fun getTypeString(): String
    override fun toString(): String {
        return getTypeString()
    }

    object INTEGER : UnvalidatedType() {
        override fun getTypeString(): String {
            return "Integer"
        }

        override fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType {
            return this
        }
    }
    object NATURAL : UnvalidatedType() {
        override fun getTypeString(): String {
            return "Natural"
        }

        override fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType {
            return this
        }
    }
    object BOOLEAN : UnvalidatedType() {
        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType {
            return this
        }
    }

    data class List(val parameter: UnvalidatedType): UnvalidatedType() {
        override fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType {
            return List(parameter.replacingParameters(parameterMap))
        }

        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Try(val parameter: UnvalidatedType): UnvalidatedType() {
        override fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType {
            return Try(parameter.replacingParameters(parameterMap))
        }

        override fun getTypeString(): String {
            return "Try<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class FunctionType(val argTypes: kotlin.collections.List<UnvalidatedType>, val outputType: UnvalidatedType): UnvalidatedType() {
        override fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType {
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

    data class NamedType(val ref: EntityRef, val parameters: kotlin.collections.List<UnvalidatedType> = listOf()): UnvalidatedType() {
        companion object {
            fun forParameter(name: String): NamedType {
                return NamedType(EntityRef(null, EntityId(listOf(name))), listOf())
            }
        }
        override fun replacingParameters(parameterMap: Map<UnvalidatedType, UnvalidatedType>): UnvalidatedType {
            val replacement = parameterMap[this]
            if (replacement != null) {
                // TODO: Should this have replaceParameters applied to it?
                return replacement
            }
            return NamedType(ref,
                    replaceParametersUnvalidated(parameters, parameterMap))
        }

        fun getParameterizedTypes(): kotlin.collections.List<UnvalidatedType> {
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

/**
 * Note: The hashCode() and equals() methods for [Type.NamedType] ignore [originalRef], which is provided for a narrow
 * range of reasons -- primarily, converting back to the original unvalidated code. This may cause odd behavior if
 * you put Types in a set or as the key of a map, but it makes equality checking correct for the purposes of checking
 * that types agree with one another.
 */
sealed class Type {
    abstract fun replacingParameters(parameterMap: Map<out Type, Type>): Type
    abstract protected fun getTypeString(): String
    override fun toString(): String {
        return getTypeString()
    }

    object INTEGER : Type() {
        override fun getTypeString(): String {
            return "Integer"
        }

        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
            return this
        }
    }
    object NATURAL : Type() {
        override fun getTypeString(): String {
            return "Natural"
        }

        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
            return this
        }
    }
    object BOOLEAN : Type() {
        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
            return this
        }
    }

    data class List(val parameter: Type): Type() {
        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
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
        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
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
        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
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

    data class ParameterType(val name: String): Type() {
        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
            val replacement = parameterMap[this]
            return if (replacement != null) replacement else this
        }

        override fun getTypeString(): String {
            return name
        }

        override fun toString(): String {
            return name
        }
    }

    /**
     * Note: The hashCode() and equals() methods for this class ignore [originalRef], which is provided for a narrow
     * range of reasons -- primarily, converting back to the original unvalidated code. This may cause odd behavior if
     * you put Types in a set or as the key of a map, but it makes equality checking correct for the purposes of checking
     * that types agree with one another.
     */
    // TODO: Should "ref" be an EntityResolution? Either way, stop passing originalRef to resolvers
    data class NamedType(val ref: ResolvedEntityRef, val originalRef: EntityRef, val parameters: kotlin.collections.List<Type> = listOf()): Type() {
        override fun replacingParameters(parameterMap: Map<out Type, Type>): Type {
            val replacement = parameterMap[this]
            if (replacement != null) {
                // TODO: Should this have replaceParameters applied to it?
                return replacement
            }
            return NamedType(ref,
                    originalRef,
                    parameters.map { parameter -> parameter.replacingParameters(parameterMap) }
            )
        }

        fun getParameterizedTypes(): kotlin.collections.List<Type> {
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
            return getTypeString()
        }

        /**
         * Ignores the value of __.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null) {
                return false
            }
            if (other !is NamedType) {
                return false
            }
            return Objects.equals(ref, other.ref) && Objects.equals(parameters, other.parameters)
        }

        override fun hashCode(): Int {
            return Objects.hash(ref, parameters)
        }
    }
}

// TODO: Maybe rename FunctionSignature?
data class UnvalidatedTypeSignature(override val id: EntityId, val argumentTypes: List<UnvalidatedType>, val outputType: UnvalidatedType, val typeParameters: List<String> = listOf()): HasId {
    fun getFunctionType(): UnvalidatedType.FunctionType {
        return UnvalidatedType.FunctionType(argumentTypes, outputType)
    }
}

data class TypeSignature(override val id: EntityId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<String> = listOf()): HasId

data class Position(val lineNumber: Int, val column: Int, val rawIndex: Int)
data class Range(val start: Position, val end: Position)
data class Location(val documentUri: String, val range: Range)

data class Annotation(val name: String, val values: List<AnnotationArgument>)
sealed class AnnotationArgument {
    data class Literal(val value: String): AnnotationArgument()
    data class List(val values: kotlin.collections.List<AnnotationArgument>): AnnotationArgument()
}

// Pre-scoping
sealed class AmbiguousExpression {
    abstract val location: Location
    data class Variable(val name: String, override val location: Location): AmbiguousExpression()
    data class VarOrNamedFunctionBinding(val functionIdOrVariable: EntityRef, val bindings: List<AmbiguousExpression?>, val chosenParameters: List<UnvalidatedType>, override val location: Location): AmbiguousExpression()
    data class ExpressionOrNamedFunctionBinding(val expression: AmbiguousExpression, val bindings: List<AmbiguousExpression?>, val chosenParameters: List<UnvalidatedType>, override val location: Location): AmbiguousExpression()
    data class IfThen(val condition: AmbiguousExpression, val thenBlock: AmbiguousBlock, val elseBlock: AmbiguousBlock, override val location: Location): AmbiguousExpression()
    data class VarOrNamedFunctionCall(val functionIdOrVariable: EntityRef, val arguments: List<AmbiguousExpression>, val chosenParameters: List<UnvalidatedType>, override val location: Location, val varOrNameLocation: Location?): AmbiguousExpression()
    data class ExpressionOrNamedFunctionCall(val expression: AmbiguousExpression, val arguments: List<AmbiguousExpression>, val chosenParameters: List<UnvalidatedType>, override val location: Location, val expressionOrNameLocation: Location): AmbiguousExpression()
    data class Literal(val type: UnvalidatedType, val literal: String, override val location: Location): AmbiguousExpression()
    data class ListLiteral(val contents: List<AmbiguousExpression>, val chosenParameter: UnvalidatedType, override val location: Location): AmbiguousExpression()
    data class Follow(val structureExpression: AmbiguousExpression, val name: String, override val location: Location): AmbiguousExpression()
    data class InlineFunction(val arguments: List<UnvalidatedArgument>, val block: AmbiguousBlock, override val location: Location): AmbiguousExpression()
}

// Post-scoping, pre-type-analysis
sealed class Expression {
    abstract val location: Location?
    data class Variable(val name: String, override val location: Location?): Expression()
    data class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block, override val location: Location?): Expression()
    data class NamedFunctionCall(val functionRef: EntityRef, val arguments: List<Expression>, val chosenParameters: List<UnvalidatedType>, override val location: Location?, val functionRefLocation: Location?): Expression()
    //TODO: Make position of chosenParamters consistent with bindings below
    data class ExpressionFunctionCall(val functionExpression: Expression, val arguments: List<Expression>, val chosenParameters: List<UnvalidatedType>, override val location: Location?): Expression()
    data class Literal(val type: UnvalidatedType, val literal: String, override val location: Location?): Expression()
    data class ListLiteral(val contents: List<Expression>, val chosenParameter: UnvalidatedType, override val location: Location?): Expression()
    data class NamedFunctionBinding(val functionRef: EntityRef, val bindings: List<Expression?>, val chosenParameters: List<UnvalidatedType>, override val location: Location?): Expression()
    data class ExpressionFunctionBinding(val functionExpression: Expression, val bindings: List<Expression?>, val chosenParameters: List<UnvalidatedType>, override val location: Location?): Expression()
    data class Follow(val structureExpression: Expression, val name: String, override val location: Location?): Expression()
    data class InlineFunction(val arguments: List<UnvalidatedArgument>, val block: Block, override val location: Location?): Expression()
}
// Post-type-analysis
sealed class TypedExpression {
    abstract val type: Type
    data class Variable(override val type: Type, val name: String): TypedExpression()
    data class IfThen(override val type: Type, val condition: TypedExpression, val thenBlock: TypedBlock, val elseBlock: TypedBlock): TypedExpression()
    data class NamedFunctionCall(override val type: Type, val functionRef: EntityRef, val resolvedFunctionRef: ResolvedEntityRef, val arguments: List<TypedExpression>, val chosenParameters: List<Type>): TypedExpression()
    data class ExpressionFunctionCall(override val type: Type, val functionExpression: TypedExpression, val arguments: List<TypedExpression>, val chosenParameters: List<Type>): TypedExpression()
    data class Literal(override val type: Type, val literal: String): TypedExpression()
    data class ListLiteral(override val type: Type, val contents: List<TypedExpression>, val chosenParameter: Type): TypedExpression()
    data class NamedFunctionBinding(override val type: Type, val functionRef: EntityRef, val resolvedFunctionRef: ResolvedEntityRef, val bindings: List<TypedExpression?>, val chosenParameters: List<Type>) : TypedExpression()
    data class ExpressionFunctionBinding(override val type: Type, val functionExpression: TypedExpression, val bindings: List<TypedExpression?>, val chosenParameters: List<Type>) : TypedExpression()
    data class Follow(override val type: Type, val structureExpression: TypedExpression, val name: String): TypedExpression()
    data class InlineFunction(override val type: Type, val arguments: List<Argument>, val boundVars: List<Argument>, val block: TypedBlock): TypedExpression()
}

data class AmbiguousAssignment(val name: String, val type: UnvalidatedType?, val expression: AmbiguousExpression, val nameLocation: Location?)
data class Assignment(val name: String, val type: UnvalidatedType?, val expression: Expression, val nameLocation: Location?)
data class ValidatedAssignment(val name: String, val type: Type, val expression: TypedExpression)
data class UnvalidatedArgument(val name: String, val type: UnvalidatedType, val location: Location?)
data class Argument(val name: String, val type: Type)
data class AmbiguousBlock(val assignments: List<AmbiguousAssignment>, val returnedExpression: AmbiguousExpression, val location: Location?)
data class Block(val assignments: List<Assignment>, val returnedExpression: Expression, val location: Location?)
data class TypedBlock(val type: Type, val assignments: List<ValidatedAssignment>, val returnedExpression: TypedExpression)
data class Function(override val id: EntityId, val typeParameters: List<String>, val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: Block, override val annotations: List<Annotation>, val idLocation: Location?, val returnTypeLocation: Location?) : TopLevelEntity {
    fun getTypeSignature(): UnvalidatedTypeSignature {
        return UnvalidatedTypeSignature(id,
                arguments.map(UnvalidatedArgument::type),
                returnType,
                typeParameters)
    }
}
data class ValidatedFunction(override val id: EntityId, val typeParameters: List<String>, val arguments: List<Argument>, val returnType: Type, val block: TypedBlock, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getTypeSignature(): TypeSignature {
        return TypeSignature(id,
                arguments.map(Argument::type),
                returnType,
                typeParameters)
    }
}

data class UnvalidatedStruct(override val id: EntityId, val typeParameters: List<String>, val members: List<UnvalidatedMember>, val requires: Block?, override val annotations: List<Annotation>, val idLocation: Location?) : TopLevelEntity {
    fun getConstructorSignature(): UnvalidatedTypeSignature {
        val argumentTypes = members.map(UnvalidatedMember::type)
        val typeParameters = typeParameters.map(UnvalidatedType.NamedType.Companion::forParameter)
        val outputType = if (requires == null) {
            UnvalidatedType.NamedType(id.asRef(), typeParameters)
        } else {
            UnvalidatedType.Try(UnvalidatedType.NamedType(id.asRef(), typeParameters))
        }
        return UnvalidatedTypeSignature(id, argumentTypes, outputType, this.typeParameters)
    }
}
data class Struct(override val id: EntityId, val moduleId: ModuleId, val typeParameters: List<String>, val members: List<Member>, val requires: TypedBlock?, override val annotations: List<Annotation>) : TopLevelEntity {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    fun getIndexForName(name: String): Int {
        return members.indexOfFirst { member -> member.name == name }
    }

    fun getType(): Type.NamedType {
        return Type.NamedType(resolvedRef, id.asRef(), typeParameters.map(Type::ParameterType))
    }

    // TODO: Deconflict with UnvalidatedStruct version
    fun getConstructorSignature(): TypeSignature {
        val argumentTypes = members.map(Member::type)
        val typeParameters = typeParameters.map(Type::ParameterType)
        val outputType = if (requires == null) {
            Type.NamedType(resolvedRef, id.asRef(), typeParameters)
        } else {
            Type.Try(Type.NamedType(resolvedRef, id.asRef(), typeParameters))
        }
        return TypeSignature(id, argumentTypes, outputType, this.typeParameters)
    }
}
interface HasId {
    val id: EntityId
}
interface TopLevelEntity: HasId {
    val annotations: List<Annotation>
}
data class UnvalidatedMember(val name: String, val type: UnvalidatedType)
data class Member(val name: String, val type: Type)

data class UnvalidatedInterface(override val id: EntityId, val typeParameters: List<String>, val methods: List<UnvalidatedMethod>, override val annotations: List<Annotation>, val idLocation: Location?) : TopLevelEntity {
    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val dataTypeParameter = getUnusedTypeParameterName(typeParameters)
    val dataType = UnvalidatedType.NamedType.forParameter(dataTypeParameter)
    fun getAdapterStruct(): UnvalidatedStruct {
        val members = methods.map { method ->
            val methodType = method.functionType
            UnvalidatedMember(method.name, UnvalidatedType.FunctionType(listOf(dataType) + methodType.argTypes, methodType.outputType))
        }
        return UnvalidatedStruct(adapterId, listOf(dataTypeParameter) + typeParameters,
                members, null, listOf(), idLocation)
    }

    fun getInstanceConstructorSignature(): UnvalidatedTypeSignature {
        val explicitTypeParameters = this.typeParameters
        val allTypeParameters = this.getAdapterStruct().typeParameters
        val dataTypeParameter = allTypeParameters[0]

        val argumentTypes = ArrayList<UnvalidatedType>()
        val dataStructType = UnvalidatedType.NamedType.forParameter(dataTypeParameter)
        argumentTypes.add(dataStructType)

        val adapterType = UnvalidatedType.NamedType(this.adapterId.asRef(), allTypeParameters.map { name -> UnvalidatedType.NamedType.forParameter(name) })
        argumentTypes.add(adapterType)

        val outputType = UnvalidatedType.NamedType(this.id.asRef(), explicitTypeParameters.map { name -> UnvalidatedType.NamedType.forParameter(name) })

        return UnvalidatedTypeSignature(this.id, argumentTypes, outputType, allTypeParameters)
    }
    fun getAdapterConstructorSignature(): UnvalidatedTypeSignature {
        val adapterTypeParameters = this.getAdapterStruct().typeParameters
        val dataStructType = UnvalidatedType.NamedType.forParameter(adapterTypeParameters[0])

        val argumentTypes = ArrayList<UnvalidatedType>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = UnvalidatedType.NamedType(this.adapterId.asRef(), adapterTypeParameters.map { name -> UnvalidatedType.NamedType.forParameter(name) })

        return UnvalidatedTypeSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters)
    }
}
data class Interface(override val id: EntityId, val moduleId: ModuleId, val typeParameters: List<String>, val methods: List<Method>, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getIndexForName(name: String): Int {
        return methods.indexOfFirst { method -> method.name == name }
    }
    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val dataTypeParameter = getUnusedTypeParameterName(typeParameters)
    val dataType = Type.ParameterType(dataTypeParameter)
    fun getAdapterStruct(): Struct {
        val members = methods.map { method ->
            val methodType = method.functionType
            Member(method.name, Type.FunctionType(listOf(dataType) + methodType.argTypes, methodType.outputType))
        }
        return Struct(adapterId, moduleId, listOf(dataTypeParameter) + typeParameters,
                members, null, listOf())
    }

    fun getInstanceConstructorSignature(): TypeSignature {
        val explicitTypeParameters = this.typeParameters
        val allTypeParameters = this.getAdapterStruct().typeParameters
        val dataTypeParameter = allTypeParameters[0]

        val argumentTypes = ArrayList<Type>()
        val dataStructType = Type.ParameterType(dataTypeParameter)
        argumentTypes.add(dataStructType)

        val adapterType = Type.NamedType(ResolvedEntityRef(moduleId, this.adapterId), this.adapterId.asRef(), allTypeParameters.map { name -> Type.ParameterType(name) })
        argumentTypes.add(adapterType)

        val outputType = Type.NamedType(ResolvedEntityRef(moduleId, this.id), this.id.asRef(), explicitTypeParameters.map { name -> Type.ParameterType(name) })

        return TypeSignature(this.id, argumentTypes, outputType, allTypeParameters)
    }
    fun getAdapterConstructorSignature(): TypeSignature {
        val adapterTypeParameters = this.getAdapterStruct().typeParameters
        val dataStructType = Type.ParameterType(adapterTypeParameters[0])

        val argumentTypes = ArrayList<Type>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = Type.NamedType(ResolvedEntityRef(moduleId, this.adapterId), this.adapterId.asRef(), adapterTypeParameters.map { name -> Type.ParameterType(name) })

        return TypeSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters)
    }
}
data class UnvalidatedMethod(val name: String, val typeParameters: List<String>, val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType) {
    val functionType = UnvalidatedType.FunctionType(arguments.map { arg -> arg.type }, returnType)
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

private fun getInterfaceMethodReferenceType(intrinsicStructType: UnvalidatedType.NamedType, method: UnvalidatedMethod): UnvalidatedType {
    val argTypes = ArrayList<UnvalidatedType>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return UnvalidatedType.FunctionType(argTypes, method.returnType)
}
private fun getInterfaceMethodReferenceType(intrinsicStructType: Type.ParameterType, method: Method): Type {
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
