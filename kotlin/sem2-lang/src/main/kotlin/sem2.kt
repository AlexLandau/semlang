package net.semlang.sem2.api

data class S2ModuleRef(val group: String?, val module: String, val version: String?) {
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
data class EntityRef(val moduleRef: S2ModuleRef?, val id: EntityId) {
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

sealed class S2Type {
    abstract val location: Location?
    abstract protected fun getTypeString(): String
    abstract fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type
    override fun toString(): String {
        return getTypeString()
    }

    // This contains some types that are inherently invalid, but should be returned by the parser so the error messages
    // can be left to the validator
    object Invalid {
        data class ReferenceInteger(override val location: Location? = null) : S2Type() {
            override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
                return this
            }

            override fun getTypeString(): String {
                return "&Integer"
            }

            override fun toString(): String {
                return getTypeString()
            }
        }

        data class ReferenceBoolean(override val location: Location? = null) : S2Type() {
            override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
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

    data class Integer(override val location: Location? = null) : S2Type() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
            return this
        }

        override fun getTypeString(): String {
            return "Integer"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }
    data class Boolean(override val location: Location? = null) : S2Type() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
            return this
        }

        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class List(val parameter: S2Type, override val location: Location? = null): S2Type() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
            return List(parameter.replacingNamedParameterTypes(parameterReplacementMap), location)
        }

        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Maybe(val parameter: S2Type, override val location: Location? = null): S2Type() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
            return Maybe(parameter.replacingNamedParameterTypes(parameterReplacementMap), location)
        }

        override fun getTypeString(): String {
            return "Maybe<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class FunctionType(val typeParameters: kotlin.collections.List<TypeParameter>, val argTypes: kotlin.collections.List<S2Type>, val outputType: S2Type, override val location: Location? = null): S2Type() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
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

    data class NamedType(val ref: EntityRef, val isReference: kotlin.Boolean, val parameters: kotlin.collections.List<S2Type> = listOf(), override val location: Location? = null): S2Type() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
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

data class S2FunctionSignature(override val id: EntityId, val argumentTypes: List<S2Type>, val outputType: S2Type, val typeParameters: List<TypeParameter> = listOf()): HasId {
    fun getFunctionType(): S2Type.FunctionType {
        return S2Type.FunctionType(typeParameters, argumentTypes, outputType)
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

data class S2Annotation(val name: EntityId, val values: List<S2AnnotationArgument>)
sealed class S2AnnotationArgument {
    data class Literal(val value: String): S2AnnotationArgument()
    data class List(val values: kotlin.collections.List<S2AnnotationArgument>): S2AnnotationArgument()
}

// Pre-scoping
//sealed class AmbiguousExpression {
//    abstract val location: Location
////    data class Variable(val name: String, override val location: Location): AmbiguousExpression()
////    data class DottedSequence(val strings: List<String>, override val location: Location): AmbiguousExpression()
//    // TODO: Renames to remove "ExpressionOrNamed"
//    data class FunctionBinding(val expression: AmbiguousExpression, val bindings: List<AmbiguousExpression?>, val chosenParameters: List<S2Type?>, override val location: Location, val expressionOrNameLocation: Location): AmbiguousExpression()
//    data class IfThen(val condition: AmbiguousExpression, val thenBlock: AmbiguousBlock, val elseBlock: AmbiguousBlock, override val location: Location): AmbiguousExpression()
//    data class FunctionCall(val expression: AmbiguousExpression, val arguments: List<AmbiguousExpression>, val chosenParameters: List<S2Type>, override val location: Location, val expressionOrNameLocation: Location): AmbiguousExpression()
//    data class Literal(val type: S2Type, val literal: String, override val location: Location): AmbiguousExpression()
//    data class ListLiteral(val contents: List<AmbiguousExpression>, val chosenParameter: S2Type, override val location: Location): AmbiguousExpression()
//    data class Follow(val structureExpression: AmbiguousExpression, val name: String, override val location: Location): AmbiguousExpression()
//    data class InlineFunction(val arguments: List<S2Argument>, val returnType: S2Type, val block: AmbiguousBlock, override val location: Location): AmbiguousExpression()
//}

// Post-scoping, pre-type-analysis
sealed class S2Expression {
    abstract val location: Location?
//    data class Variable(val name: String, override val location: Location? = null): S2Expression()
    data class DottedSequence(val strings: List<String>, override val location: Location): S2Expression()
    data class IfThen(val condition: S2Expression, val thenBlock: S2Block, val elseBlock: S2Block, override val location: Location? = null): S2Expression()
    data class FunctionCall(val expression: S2Expression, val arguments: List<S2Expression>, val chosenParameters: List<S2Type>, override val location: Location? = null): S2Expression()
//    data class ExpressionFunctionCall(val functionExpression: S2Expression, val arguments: List<S2Expression>, val chosenParameters: List<S2Type>, override val location: Location? = null): S2Expression()
    data class Literal(val type: S2Type, val literal: String, override val location: Location? = null): S2Expression()
    data class ListLiteral(val contents: List<S2Expression>, val chosenParameter: S2Type, override val location: Location? = null): S2Expression()
    data class FunctionBinding(val expression: S2Expression, val bindings: List<S2Expression?>, val chosenParameters: List<S2Type?>, override val location: Location? = null): S2Expression()
//    data class ExpressionFunctionBinding(val functionExpression: S2Expression, val bindings: List<S2Expression?>, val chosenParameters: List<S2Type?>, override val location: Location? = null): S2Expression()
    data class Follow(val structureExpression: S2Expression, val name: String, override val location: Location? = null): S2Expression()
    data class InlineFunction(val arguments: List<S2Argument>, val returnType: S2Type, val block: S2Block, override val location: Location? = null): S2Expression()
}

// Note: Currently Statements can refer to either assignments (if name is non-null) or "plain" statements with imperative
// effects (otherwise). If we introduce a third statement type, we should probably switch this to be a sealed class.
//data class AmbiguousStatement(val name: String?, val type: S2Type?, val expression: AmbiguousExpression, val nameLocation: Location?)
data class S2Statement(val name: String?, val type: S2Type?, val expression: S2Expression, val nameLocation: Location? = null)
data class S2Argument(val name: String, val type: S2Type, val location: Location? = null)
//data class AmbiguousBlock(val statements: List<AmbiguousStatement>, val returnedExpression: AmbiguousExpression, val location: Location?)
data class S2Block(val statements: List<S2Statement>, val returnedExpression: S2Expression, val location: Location? = null)
data class S2Function(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<S2Argument>, val returnType: S2Type, val block: S2Block, override val annotations: List<S2Annotation>, val idLocation: Location? = null, val returnTypeLocation: Location? = null) : TopLevelEntity {
    fun getType(): S2Type.FunctionType {
        return S2Type.FunctionType(
                typeParameters,
                arguments.map(S2Argument::type),
                returnType
        )
    }

    fun getSignature(): S2FunctionSignature {
        return S2FunctionSignature(id, arguments.map { it.type }, returnType, typeParameters)
    }
}

data class S2Struct(override val id: EntityId, val typeParameters: List<TypeParameter>, val members: List<S2Member>, val requires: S2Block?, override val annotations: List<S2Annotation>, val idLocation: Location? = null) : TopLevelEntity {
    fun getConstructorSignature(): S2FunctionSignature {
        val argumentTypes = members.map(S2Member::type)
        val typeParameters = typeParameters.map { S2Type.NamedType.forParameter(it, idLocation) }
        val outputType = if (requires == null) {
            S2Type.NamedType(id.asRef(), false, typeParameters, idLocation)
        } else {
            S2Type.Maybe(S2Type.NamedType(id.asRef(), false, typeParameters, idLocation), idLocation)
        }
        return S2FunctionSignature(id, argumentTypes, outputType, this.typeParameters)
    }
}

interface HasId {
    val id: EntityId
}
interface TopLevelEntity: HasId {
    val annotations: List<S2Annotation>
}
data class S2Member(val name: String, val type: S2Type)

data class S2Interface(override val id: EntityId, val typeParameters: List<TypeParameter>, val methods: List<S2Method>, override val annotations: List<S2Annotation>, val idLocation: Location? = null) : TopLevelEntity {
    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val dataTypeParameter = TypeParameter(getUnusedTypeParameterName(typeParameters), null)
    val dataType = S2Type.NamedType.forParameter(dataTypeParameter, null)

    val instanceType = S2Type.NamedType(this.id.asRef(), false, typeParameters.map { name -> S2Type.NamedType.forParameter(name, null) }, idLocation)

    fun getInstanceConstructorSignature(): S2FunctionSignature {
        val typeParameters = this.typeParameters
        val argumentTypes = this.methods.map(S2Method::functionType)

        return S2FunctionSignature(this.id, argumentTypes, instanceType, typeParameters)
    }
    fun getAdapterFunctionSignature(): S2FunctionSignature {
        val adapterTypeParameters = listOf(dataTypeParameter) + typeParameters

        val dataStructType = S2Type.NamedType.forParameter(adapterTypeParameters[0], null)
        val argumentTypes = ArrayList<S2Type>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = S2Type.FunctionType(listOf(), listOf(dataType), instanceType)

        return S2FunctionSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters)
    }
}
data class S2Method(val name: String, val typeParameters: List<TypeParameter>, val arguments: List<S2Argument>, val returnType: S2Type) {
    init {
        if (typeParameters.isNotEmpty()) {
            // This case hasn't really been handled correctly/tested yet
            TODO()
        }
    }
    val functionType = S2Type.FunctionType(typeParameters, arguments.map { arg -> arg.type }, returnType, null)
}

data class S2Union(override val id: EntityId, val typeParameters: List<TypeParameter>, val options: List<S2Option>, override val annotations: List<S2Annotation>, val idLocation: Location? = null): TopLevelEntity {
    private fun getType(): S2Type {
        val functionParameters = typeParameters.map { S2Type.NamedType.forParameter(it) }
        return S2Type.NamedType(id.asRef(), false, functionParameters)
    }
    fun getConstructorSignature(option: S2Option): S2FunctionSignature {
        if (!options.contains(option)) {
            error("Invalid option $option")
        }
        val optionId = EntityId(id.namespacedName + option.name)
        val argumentTypes = if (option.type == null) {
            listOf()
        } else {
            listOf(option.type)
        }
        return S2FunctionSignature(optionId, argumentTypes, getType(), typeParameters)
    }

    fun getWhenSignature(): S2FunctionSignature {
        val whenId = EntityId(id.namespacedName + "when")
        val outputParameterName = getUnusedTypeParameterName(typeParameters)
        val outputParameterType = S2Type.NamedType(EntityId.of(outputParameterName).asRef(), false)
        val outputTypeParameter = TypeParameter(outputParameterName, null)
        val whenTypeParameters = typeParameters + outputTypeParameter

        val argumentTypes = listOf(getType()) + options.map { option ->
            val optionArgTypes = if (option.type == null) {
                listOf()
            } else {
                listOf(option.type)
            }
            S2Type.FunctionType(listOf(), optionArgTypes, outputParameterType)
        }

        return S2FunctionSignature(whenId, argumentTypes, outputParameterType, whenTypeParameters)
    }
}
data class S2Option(val name: String, val type: S2Type?, val idLocation: Location? = null)

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

private fun getInterfaceMethodReferenceType(intrinsicStructType: S2Type.NamedType, method: S2Method): S2Type {
    val argTypes = ArrayList<S2Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return S2Type.FunctionType(listOf(), argTypes, method.returnType, null)
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

data class S2Context(val functions: List<S2Function>, val structs: List<S2Struct>, val interfaces: List<S2Interface>, val unions: List<S2Union>)
