package net.semlang.sem2.api

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
        for (namePart in namespacedName) {
            if (namePart.isEmpty()) {
                error("Entity IDs may not have empty name components")
            }
            var foundNonUnderscore = false
            for (character in namePart) {
                if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9') {
                    foundNonUnderscore = true
                } else if (character == '_') {
                    // Do nothing
                } else {
                    error("Invalid character '$character' (code: ${character.toInt()}) in entity ID with components $namespacedName")
                }
            }
            if (!foundNonUnderscore) {
                error("Name components must contain non-underscore characters; bad entity ID components: $namespacedName")
            }
        }
    }
    companion object {
        fun of(vararg names: String): EntityId {
            return EntityId(names.toList())
        }

        /**
         * Parses the components of an entity ID expressed as a period-delimited string. In particular, this reverses
         * the [EntityId.toString] operation.
         */
        fun parse(periodDelimitedNames: String): EntityId {
            return EntityId(periodDelimitedNames.split("."))
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

sealed class UnvalidatedType {
    abstract val location: Location?
    abstract protected fun getTypeString(): String
    abstract fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType
    override fun toString(): String {
        return getTypeString()
    }

    // This contains some types that are inherently invalid, but should be returned by the parser so the error messages
    // can be left to the validator
    object Invalid {
        data class ReferenceInteger(override val location: Location? = null) : UnvalidatedType() {
            override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
                return this
            }

            override fun getTypeString(): String {
                return "&Integer"
            }

            override fun toString(): String {
                return getTypeString()
            }
        }

        data class ReferenceBoolean(override val location: Location? = null) : UnvalidatedType() {
            override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
                return this
            }

            override fun getTypeString(): String {
                return "&Boolean"
            }

            override fun toString(): String {
                return getTypeString()
            }
        }
    }

    data class Integer(override val location: Location? = null) : UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
            return this
        }

        override fun getTypeString(): String {
            return "Integer"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }
    data class Boolean(override val location: Location? = null) : UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
            return this
        }

        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class List(val parameter: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
            return List(parameter.replacingNamedParameterTypes(parameterReplacementMap), location)
        }

        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Maybe(val parameter: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
            return Maybe(parameter.replacingNamedParameterTypes(parameterReplacementMap), location)
        }

        override fun getTypeString(): String {
            return "Maybe<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class FunctionType(val typeParameters: kotlin.collections.List<TypeParameter>, val argTypes: kotlin.collections.List<UnvalidatedType>, val outputType: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
            return FunctionType(
                    typeParameters,
                    this.argTypes.map { it.replacingNamedParameterTypes(parameterReplacementMap) },
                    this.outputType.replacingNamedParameterTypes(parameterReplacementMap),
                    location)
        }

        override fun getTypeString(): String {
            val typeParametersString = if (typeParameters.isEmpty()) {
                ""
            } else {
                "<" + typeParameters.joinToString(", ") + ">"
            }
            return typeParametersString +
                    "(" +
                    argTypes.joinToString(", ") +
                    ") -> " +
                    outputType.toString()
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class NamedType(val ref: EntityRef, val isReference: kotlin.Boolean, val parameters: kotlin.collections.List<UnvalidatedType> = listOf(), override val location: Location? = null): UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType {
            if (ref.moduleRef == null && ref.id.namespacedName.size == 1) {
                val replacement = parameterReplacementMap[ref.id.namespacedName[0]]
                if (replacement != null) {
                    return replacement
                }
            }
            return NamedType(
                    ref,
                    isReference,
                    parameters.map { it.replacingNamedParameterTypes(parameterReplacementMap) },
                    location)
        }

        companion object {
            fun forParameter(parameter: TypeParameter, location: Location? = null): NamedType {
                return NamedType(EntityRef(null, EntityId(listOf(parameter.name))), false, listOf(), location)
            }
        }

        override fun getTypeString(): String {
            // TODO: This might be wrong if the ref includes a module...
            return (if (isReference) "&" else "") +
                    ref.toString() +
                    if (parameters.isEmpty()) {
                        ""
                    } else {
                        "<" + parameters.joinToString(", ") + ">"
                    }
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

}

enum class TypeClass {
    Data,
}

data class TypeParameter(val name: String, val typeClass: TypeClass?) {
    override fun toString(): String {
        if (typeClass == null) {
            return name
        } else {
            return "$name: $typeClass"
        }
    }
}

// TODO: Maybe rename TypeSignature -> FunctionSignature?
data class UnvalidatedTypeSignature(override val id: EntityId, val argumentTypes: List<UnvalidatedType>, val outputType: UnvalidatedType, val typeParameters: List<TypeParameter> = listOf()): HasId {
    fun getFunctionType(): UnvalidatedType.FunctionType {
        return UnvalidatedType.FunctionType(typeParameters, argumentTypes, outputType)
    }
}

data class Position(val lineNumber: Int, val column: Int, val rawIndex: Int) {
    override fun toString(): String {
        return "L${lineNumber}:${column}"
    }
}
data class Range(val start: Position, val end: Position) {
    override fun toString(): String {
        if (start.lineNumber == end.lineNumber) {
            return "L${start.lineNumber}:${start.column}-${end.column}"
        } else {
            return "${start}-${end}"
        }
    }
}
data class Location(val documentUri: String, val range: Range)

data class Annotation(val name: EntityId, val values: List<AnnotationArgument>)
sealed class AnnotationArgument {
    data class Literal(val value: String): AnnotationArgument()
    data class List(val values: kotlin.collections.List<AnnotationArgument>): AnnotationArgument()
}

// Pre-scoping
sealed class AmbiguousExpression {
    abstract val location: Location
    data class Variable(val name: String, override val location: Location): AmbiguousExpression()
    data class VarOrNamedFunctionBinding(val functionIdOrVariable: EntityRef, val bindings: List<AmbiguousExpression?>, val chosenParameters: List<UnvalidatedType?>, override val location: Location, val varOrNameLocation: Location): AmbiguousExpression()
    data class ExpressionOrNamedFunctionBinding(val expression: AmbiguousExpression, val bindings: List<AmbiguousExpression?>, val chosenParameters: List<UnvalidatedType?>, override val location: Location, val expressionOrNameLocation: Location): AmbiguousExpression()
    data class IfThen(val condition: AmbiguousExpression, val thenBlock: AmbiguousBlock, val elseBlock: AmbiguousBlock, override val location: Location): AmbiguousExpression()
    data class VarOrNamedFunctionCall(val functionIdOrVariable: EntityRef, val arguments: List<AmbiguousExpression>, val chosenParameters: List<UnvalidatedType>, override val location: Location, val varOrNameLocation: Location): AmbiguousExpression()
    data class ExpressionOrNamedFunctionCall(val expression: AmbiguousExpression, val arguments: List<AmbiguousExpression>, val chosenParameters: List<UnvalidatedType>, override val location: Location, val expressionOrNameLocation: Location): AmbiguousExpression()
    data class Literal(val type: UnvalidatedType, val literal: String, override val location: Location): AmbiguousExpression()
    data class ListLiteral(val contents: List<AmbiguousExpression>, val chosenParameter: UnvalidatedType, override val location: Location): AmbiguousExpression()
    data class Follow(val structureExpression: AmbiguousExpression, val name: String, override val location: Location): AmbiguousExpression()
    data class InlineFunction(val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: AmbiguousBlock, override val location: Location): AmbiguousExpression()
}

// Post-scoping, pre-type-analysis
sealed class Expression {
    abstract val location: Location?
    data class Variable(val name: String, override val location: Location? = null): Expression()
    data class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block, override val location: Location? = null): Expression()
    data class NamedFunctionCall(val functionRef: EntityRef, val arguments: List<Expression>, val chosenParameters: List<UnvalidatedType>, override val location: Location? = null, val functionRefLocation: Location? = null): Expression()
    data class ExpressionFunctionCall(val functionExpression: Expression, val arguments: List<Expression>, val chosenParameters: List<UnvalidatedType>, override val location: Location? = null): Expression()
    data class Literal(val type: UnvalidatedType, val literal: String, override val location: Location? = null): Expression()
    data class ListLiteral(val contents: List<Expression>, val chosenParameter: UnvalidatedType, override val location: Location? = null): Expression()
    data class NamedFunctionBinding(val functionRef: EntityRef, val bindings: List<Expression?>, val chosenParameters: List<UnvalidatedType?>, override val location: Location? = null, val functionRefLocation: Location? = null): Expression()
    data class ExpressionFunctionBinding(val functionExpression: Expression, val bindings: List<Expression?>, val chosenParameters: List<UnvalidatedType?>, override val location: Location? = null): Expression()
    data class Follow(val structureExpression: Expression, val name: String, override val location: Location? = null): Expression()
    data class InlineFunction(val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: Block, override val location: Location? = null): Expression()
}
// Post-type-analysis
enum class AliasType {
    /**
     * Indicates that the expression represents a reference or value without an existing alias, and thus can be assigned
     * to a variable if it's a reference type.
     */
    NotAliased,
    /**
     * Indicates that the expression represents a reference or value that either is guaranteed to have
     * or conditionally may have an existing alias, and thus cannot be assigned to a variable if it's a reference type.
     *
     * Note that this category includes expressions that may be "definitely aliased", but there's no need to distinguish
     * them from expressions that are "possibly aliased" (such as may result from an if-expression where one possible
     * result is aliased and the other is not).
     */
    PossiblyAliased,
    ;
    companion object {
        // I'd really rather this be named "for", but that's not an option
        fun of(possiblyAliased: Boolean): AliasType {
            if (possiblyAliased) {
                return PossiblyAliased
            } else {
                return NotAliased
            }
        }
    }
}

// Note: Currently Statements can refer to either assignments (if name is non-null) or "plain" statements with imperative
// effects (otherwise). If we introduce a third statement type, we should probably switch this to be a sealed class.
data class AmbiguousStatement(val name: String?, val type: UnvalidatedType?, val expression: AmbiguousExpression, val nameLocation: Location?)
data class Statement(val name: String?, val type: UnvalidatedType?, val expression: Expression, val nameLocation: Location? = null)
data class UnvalidatedArgument(val name: String, val type: UnvalidatedType, val location: Location? = null)
data class AmbiguousBlock(val statements: List<AmbiguousStatement>, val returnedExpression: AmbiguousExpression, val location: Location?)
data class Block(val statements: List<Statement>, val returnedExpression: Expression, val location: Location? = null)
data class Function(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: Block, override val annotations: List<Annotation>, val idLocation: Location? = null, val returnTypeLocation: Location? = null) : TopLevelEntity {
    fun getType(): UnvalidatedType.FunctionType {
        return UnvalidatedType.FunctionType(
                typeParameters,
                arguments.map(UnvalidatedArgument::type),
                returnType
        )
    }
}

data class UnvalidatedStruct(override val id: EntityId, val typeParameters: List<TypeParameter>, val members: List<UnvalidatedMember>, val requires: Block?, override val annotations: List<Annotation>, val idLocation: Location? = null) : TopLevelEntity {
    fun getConstructorSignature(): UnvalidatedTypeSignature {
        val argumentTypes = members.map(UnvalidatedMember::type)
        val typeParameters = typeParameters.map { UnvalidatedType.NamedType.forParameter(it, idLocation) }
        val outputType = if (requires == null) {
            UnvalidatedType.NamedType(id.asRef(), false, typeParameters, idLocation)
        } else {
            UnvalidatedType.Maybe(UnvalidatedType.NamedType(id.asRef(), false, typeParameters, idLocation), idLocation)
        }
        return UnvalidatedTypeSignature(id, argumentTypes, outputType, this.typeParameters)
    }
}

interface HasId {
    val id: EntityId
}
interface TopLevelEntity: HasId {
    val annotations: List<Annotation>
}
data class UnvalidatedMember(val name: String, val type: UnvalidatedType)

data class UnvalidatedInterface(override val id: EntityId, val typeParameters: List<TypeParameter>, val methods: List<UnvalidatedMethod>, override val annotations: List<Annotation>, val idLocation: Location? = null) : TopLevelEntity {
    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val dataTypeParameter = TypeParameter(getUnusedTypeParameterName(typeParameters), null)
    val dataType = UnvalidatedType.NamedType.forParameter(dataTypeParameter, null)

    val instanceType = UnvalidatedType.NamedType(this.id.asRef(), false, typeParameters.map { name -> UnvalidatedType.NamedType.forParameter(name, null) }, idLocation)

    fun getInstanceConstructorSignature(): UnvalidatedTypeSignature {
        val typeParameters = this.typeParameters
        val argumentTypes = this.methods.map(UnvalidatedMethod::functionType)

        return UnvalidatedTypeSignature(this.id, argumentTypes, instanceType, typeParameters)
    }
    fun getAdapterFunctionSignature(): UnvalidatedTypeSignature {
        val adapterTypeParameters = listOf(dataTypeParameter) + typeParameters

        val dataStructType = UnvalidatedType.NamedType.forParameter(adapterTypeParameters[0], null)
        val argumentTypes = ArrayList<UnvalidatedType>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = UnvalidatedType.FunctionType(listOf(), listOf(dataType), instanceType)

        return UnvalidatedTypeSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters)
    }
}
data class UnvalidatedMethod(val name: String, val typeParameters: List<TypeParameter>, val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType) {
    init {
        if (typeParameters.isNotEmpty()) {
            // This case hasn't really been handled correctly/tested yet
            TODO()
        }
    }
    val functionType = UnvalidatedType.FunctionType(typeParameters, arguments.map { arg -> arg.type }, returnType, null)
}

data class UnvalidatedUnion(override val id: EntityId, val typeParameters: List<TypeParameter>, val options: List<UnvalidatedOption>, override val annotations: List<Annotation>, val idLocation: Location? = null): TopLevelEntity {
    private fun getType(): UnvalidatedType {
        val functionParameters = typeParameters.map { UnvalidatedType.NamedType.forParameter(it) }
        return UnvalidatedType.NamedType(id.asRef(), false, functionParameters)
    }
    fun getConstructorSignature(option: UnvalidatedOption): UnvalidatedTypeSignature {
        if (!options.contains(option)) {
            error("Invalid option $option")
        }
        val optionId = EntityId(id.namespacedName + option.name)
        val argumentTypes = if (option.type == null) {
            listOf()
        } else {
            listOf(option.type)
        }
        return UnvalidatedTypeSignature(optionId, argumentTypes, getType(), typeParameters)
    }

    fun getWhenSignature(): UnvalidatedTypeSignature {
        val whenId = EntityId(id.namespacedName + "when")
        val outputParameterName = getUnusedTypeParameterName(typeParameters)
        val outputParameterType = UnvalidatedType.NamedType(EntityId.of(outputParameterName).asRef(), false)
        val outputTypeParameter = TypeParameter(outputParameterName, null)
        val whenTypeParameters = typeParameters + outputTypeParameter

        val argumentTypes = listOf(getType()) + options.map { option ->
            val optionArgTypes = if (option.type == null) {
                listOf()
            } else {
                listOf(option.type)
            }
            UnvalidatedType.FunctionType(listOf(), optionArgTypes, outputParameterType)
        }

        return UnvalidatedTypeSignature(whenId, argumentTypes, outputParameterType, whenTypeParameters)
    }
}
data class UnvalidatedOption(val name: String, val type: UnvalidatedType?, val idLocation: Location? = null)

private fun getUnusedTypeParameterName(explicitTypeParameters: List<TypeParameter>): String {
    val typeParameterNames = explicitTypeParameters.map(TypeParameter::name)
    if (!typeParameterNames.contains("A")) {
        return "A"
    }
    var index = 2
    while (true) {
        val name = "A" + index
        if (!typeParameterNames.contains(name)) {
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

    return UnvalidatedType.FunctionType(listOf(), argTypes, method.returnType, null)
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
