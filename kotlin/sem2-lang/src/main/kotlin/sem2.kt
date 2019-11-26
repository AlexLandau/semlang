package net.semlang.sem2.api

import net.semlang.api.parser.Location

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

    data class FunctionType(val isReference: kotlin.Boolean, val typeParameters: kotlin.collections.List<TypeParameter>, val argTypes: kotlin.collections.List<S2Type>, val outputType: S2Type, override val location: Location? = null): S2Type() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, S2Type>): S2Type {
            return FunctionType(
                    isReference,
                    typeParameters,
                    this.argTypes.map { it.replacingNamedParameterTypes(parameterReplacementMap) },
                    this.outputType.replacingNamedParameterTypes(parameterReplacementMap),
                    location)
        }

        override fun getTypeString(): String {
            val referenceString = if (isReference) "&" else ""
            val typeParametersString = if (typeParameters.isEmpty()) {
                ""
            } else {
                "<" + typeParameters.joinToString(", ") + ">"
            }
            return referenceString +
                    typeParametersString +
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
        return S2Type.FunctionType(false, typeParameters, argumentTypes, outputType)
    }
}

data class S2Annotation(val name: EntityId, val values: List<S2AnnotationArgument>)
sealed class S2AnnotationArgument {
    data class Literal(val value: String): S2AnnotationArgument()
    data class List(val values: kotlin.collections.List<S2AnnotationArgument>): S2AnnotationArgument()
}

sealed class S2Expression {
    abstract val location: Location?
    data class RawId(val name: String, override val location: Location? = null): S2Expression()
    data class DotAccess(val subexpression: S2Expression, val name: String, override val location: Location? = null, val nameLocation: Location? = null): S2Expression()
    data class IfThen(val condition: S2Expression, val thenBlock: S2Block, val elseBlock: S2Block, override val location: Location? = null): S2Expression()
    data class FunctionCall(val expression: S2Expression, val arguments: List<S2Expression>, val chosenParameters: List<S2Type>, override val location: Location? = null): S2Expression()
    data class Literal(val type: S2Type, val literal: String, override val location: Location? = null): S2Expression()
    data class ListLiteral(val contents: List<S2Expression>, val chosenParameter: S2Type, override val location: Location? = null): S2Expression()
    data class FunctionBinding(val expression: S2Expression, val bindings: List<S2Expression?>, val chosenParameters: List<S2Type?>, override val location: Location? = null): S2Expression()
    data class Follow(val structureExpression: S2Expression, val name: String, override val location: Location? = null): S2Expression()
    data class InlineFunction(val arguments: List<S2Argument>, val returnType: S2Type?, val block: S2Block, override val location: Location? = null): S2Expression()
    data class PlusOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class MinusOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class TimesOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class EqualsOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class NotEqualsOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class LessThanOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class GreaterThanOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class DotAssignOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class GetOp(val subject: S2Expression, val arguments: List<S2Expression>, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class AndOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
    data class OrOp(val left: S2Expression, val right: S2Expression, override val location: Location? = null, val operatorLocation: Location?): S2Expression()
}

sealed class S2Statement {
    data class Normal(val name: String?, val type: S2Type?, val expression: S2Expression, val nameLocation: Location? = null): S2Statement()
    data class WhileLoop(val conditionExpression: S2Expression, val actionBlock: S2Block, val location: Location? = null): S2Statement()
}

data class S2Argument(val name: String, val type: S2Type, val location: Location? = null)
data class S2Block(val statements: List<S2Statement>, val returnedExpression: S2Expression, val location: Location? = null)
data class S2Function(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<S2Argument>, val returnType: S2Type, val block: S2Block, override val annotations: List<S2Annotation>, val idLocation: Location? = null, val returnTypeLocation: Location? = null) : TopLevelEntity {
    fun getType(): S2Type.FunctionType {
        return S2Type.FunctionType(
                false,
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
            S2Type.FunctionType(false, listOf(), optionArgTypes, outputParameterType)
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

data class S2Context(val functions: List<S2Function>, val structs: List<S2Struct>, val unions: List<S2Union>)
