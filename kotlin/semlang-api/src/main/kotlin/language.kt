package net.semlang.api

import java.util.Objects
import net.semlang.api.parser.Location

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
data class ResolvedEntityRef(val module: ModuleUniqueId, val id: EntityId) {
    override fun toString(): String {
        return "${module.name}:\"${ModuleUniqueId.UNIQUE_VERSION_SCHEME_PREFIX}${module.fake0Version}\":$id"
    }

    fun toUnresolvedRef(): EntityRef {
        val moduleRef = ModuleRef(module.name.group, module.name.module, ModuleUniqueId.UNIQUE_VERSION_SCHEME_PREFIX + module.fake0Version)
        return EntityRef(moduleRef, id)
    }
}

// TODO: It would be really nice to have a TypeId or something that didn't have a location, vs. UnvalidatedType
sealed class UnvalidatedType {
    abstract val location: Location?
    abstract protected fun getTypeString(): String
    abstract fun isReference(): Boolean
    abstract fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType
    abstract fun equalsIgnoringLocation(other: UnvalidatedType?): Boolean
    override fun toString(): String {
        return getTypeString()
    }

    data class FunctionType(private val isReference: Boolean, val typeParameters: List<TypeParameter>, val argTypes: List<UnvalidatedType>, val outputType: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun isReference(): Boolean {
            return isReference
        }

        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): FunctionType {
            return FunctionType(
                    isReference,
                    typeParameters.filter { !parameterReplacementMap.containsKey(it.name) },
                    this.argTypes.map { it.replacingNamedParameterTypes(parameterReplacementMap) },
                    this.outputType.replacingNamedParameterTypes(parameterReplacementMap),
                    location)
        }

        override fun equalsIgnoringLocation(other: UnvalidatedType?): Boolean {
            return other is FunctionType &&
                    isReference == other.isReference &&
                    typeParameters == other.typeParameters &&
                    argTypes.size == other.argTypes.size &&
                    argTypes.zip(other.argTypes).all { it.first.equalsIgnoringLocation(it.second) } &&
                    outputType.equalsIgnoringLocation(other.outputType)
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

        fun getNumArguments(): Int {
            return argTypes.size
        }
    }

    data class NamedType(val ref: EntityRef, private val isReference: Boolean, val parameters: List<UnvalidatedType> = listOf(), override val location: Location? = null): UnvalidatedType() {
        override fun isReference(): Boolean {
            return isReference
        }

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

        override fun equalsIgnoringLocation(other: UnvalidatedType?): Boolean {
            return other is NamedType &&
                    ref == other.ref &&
                    isReference == other.isReference &&
                    parameters.size == other.parameters.size &&
                    parameters.zip(other.parameters).all { it.first.equalsIgnoringLocation(it.second) }
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

/**
 * Note: The hashCode() and equals() methods for [Type.NamedType] ignore [Type.NamedType.originalRef], which is provided for a narrow
 * range of reasons -- primarily, converting back to the original unvalidated code. This may cause odd behavior if
 * you put Types in a set or as the key of a map, but it makes equality checking correct for the purposes of checking
 * that types agree with one another.
 */
sealed class Type {
    protected abstract fun replacingInternalParametersInternal(chosenParameters: List<Type?>): Type
    abstract fun getTypeString(): String
    abstract fun isReference(): Boolean
    override fun toString(): String {
        return getTypeString()
    }

    abstract fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type

    /**
     * Some types are not bindable -- that is, if they are arguments of a function type, they cannot immediately accept
     * an argument in a function binding. This happens when they contain references to internal parameter types external
     * to the type itself.
     */
    fun isBindable(): Boolean {
        return isBindableInternal(0)
    }
    abstract protected fun isBindableInternal(numAllowedIndices: Int): Boolean

    sealed class FunctionType: Type() {
        abstract fun getNumArguments(): Int
        abstract fun rebindTypeParameters(boundTypeParameters: List<Type?>): FunctionType
        abstract fun rebindArguments(bindingTypes: List<Type?>): FunctionType
        /**
         * This returns a list of argument types in which any argument type that is not currently bindable (because its type
         * contains an internal parameter type that is not bound) is replaced with null.
         */
        abstract fun getBindableArgumentTypes(): List<Type?>

        // This is usually used for things like printing the types for the user
        abstract fun getDefaultGrounding(): Ground

        abstract fun groundWithTypeParameters(chosenTypeParameters: List<Type>): Ground
        abstract val typeParameters: List<TypeParameter>

        companion object {
            fun create(isReference: Boolean, typeParameters: List<TypeParameter>, argTypes: List<Type>, outputType: Type): FunctionType {
                if (typeParameters.isEmpty()) {
                    return Ground(isReference, argTypes, outputType)
                } else {
                    return Parameterized(isReference, typeParameters, argTypes, outputType)
                }
            }
        }

        data class Ground(private val isReference: Boolean, val argTypes: List<Type>, val outputType: Type) : FunctionType() {
            override val typeParameters = listOf<TypeParameter>()

            override fun groundWithTypeParameters(chosenTypeParameters: List<Type>): Ground {
                if (chosenTypeParameters.isNotEmpty()) {
                    error("Tried to rebind a ground function type $this with parameters $chosenTypeParameters")
                }
                return this
            }

            override fun getDefaultGrounding(): Ground {
                return this
            }

            override fun isBindableInternal(numAllowedIndices: Int): Boolean {
                return argTypes.all { it.isBindableInternal(numAllowedIndices) } && outputType.isBindableInternal(numAllowedIndices)
            }

            override fun getBindableArgumentTypes(): List<Type?> {
                return argTypes
            }

            override fun rebindTypeParameters(boundTypeParameters: List<Type?>): Ground {
                if (boundTypeParameters.isNotEmpty()) {
                    error("Tried to rebind a ground function type $this with parameters $boundTypeParameters")
                }
                return this
            }

            /**
             * Returns the type that would result if a binding were performed with the given types for passed-in bindings.
             */
            override fun rebindArguments(bindingTypes: List<Type?>): Ground {
                if (bindingTypes.size != argTypes.size) {
                    error("Wrong number of binding types")
                }
                val willBeReference = this.isReference || bindingTypes.any { it != null && it.isReference() }
                return Ground(
                        willBeReference,
                        argTypes.zip(bindingTypes).filter { it.second == null }.map { it.first },
                        outputType)
            }

            override fun getNumArguments(): Int {
                return argTypes.size
            }

            override fun isReference(): Boolean {
                return this.isReference
            }

            override fun replacingInternalParametersInternal(chosenParameters: List<Type?>): Ground {
                val argTypes = argTypes.map { it.replacingInternalParametersInternal(chosenParameters) }
                val outputType = outputType.replacingInternalParametersInternal(chosenParameters)
                return Ground(isReference, argTypes, outputType)
            }

            override fun getTypeString(): String {
                val referenceString = if (isReference) "&" else ""
                return referenceString +
                        "(" +
                        argTypes.joinToString(", ") +
                        ") -> " +
                        outputType.toString()
            }

            override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
                return Ground(
                        isReference,
                        argTypes.map { it.replacingExternalParameters(parametersMap) },
                        outputType.replacingExternalParameters(parametersMap)
                )
            }
        }

        //TODO: Figure out if we can return argTypes and outputType to private
        class Parameterized(private val isReference: Boolean, override val typeParameters: List<TypeParameter>, val argTypes: List<Type>, val outputType: Type) : FunctionType() {

            override fun groundWithTypeParameters(chosenTypeParameters: List<Type>): Ground {
                if (chosenTypeParameters.size != typeParameters.size) {
                    error("Wrong number of type parameters")
                }
                val newArgTypes = getArgTypes(chosenTypeParameters)
                val newOutputType = getOutputType(chosenTypeParameters)
                return Ground(isReference, newArgTypes, newOutputType)
            }

            override fun getDefaultGrounding(): Ground {
                val substitution = this.getDefaultTypeParameterNameSubstitution()
                return rebindTypeParameters(substitution) as Ground
            }

            override fun isBindableInternal(incomingNumAllowedIndices: Int): Boolean {
                val newNumAllowedIndices = incomingNumAllowedIndices + typeParameters.size
                return argTypes.all { it.isBindableInternal(newNumAllowedIndices) } && outputType.isBindableInternal(newNumAllowedIndices)
            }

            override fun getBindableArgumentTypes(): List<Type?> {
                return argTypes.map {
                    if (it.isBindable()) {
                        it
                    } else {
                        null
                    }
                }
            }

            override fun rebindTypeParameters(boundTypeParameters: List<Type?>): FunctionType {
                if (boundTypeParameters.size != typeParameters.size) {
                    error("Wrong number of type parameters")
                }

                val newArgTypes = getArgTypes(boundTypeParameters)
                val newOutputType = getOutputType(boundTypeParameters)

                return create(
                        this.isReference,
                        typeParameters.zip(boundTypeParameters).filter { it.second == null }.map { it.first },
                        newArgTypes,
                        newOutputType)
            }

            /**
             * Returns the type that would result if a binding were performed with the given types for passed-in bindings.
             */
            override fun rebindArguments(bindingTypes: List<Type?>): FunctionType {
                if (bindingTypes.size != argTypes.size) {
                    error("Wrong number of binding types")
                }

                for ((bindingType, argType) in bindingTypes.zip(argTypes)) {
                    if (bindingType != null && !argType.isBindable()) {
                        error("Passed in a binding type for a non-bindable argument type $argType; type is $this, bindingTypes are $bindingTypes")
                    }
                }
                val willBeReference = this.isReference || bindingTypes.any { it != null && it.isReference() }

                return create(
                        willBeReference,
                        typeParameters,
                        argTypes.zip(bindingTypes).filter { it.second == null }.map { it.first },
                        outputType)
            }

            init {
                if (typeParameters.isEmpty()) {
                    error("Should be making a FunctionType.Ground instead; argTypes: $argTypes, outputType: $outputType")
                }
            }

            override fun getNumArguments(): Int {
                return argTypes.size
            }

            override fun isReference(): Boolean {
                return this.isReference
            }

            private fun getDefaultTypeParameterNameSubstitution(): List<Type> {
                return typeParameters.map(Type::ParameterType)
            }

            private fun getArgTypes(chosenParameters: List<Type?>): List<Type> {
                if (chosenParameters.size != typeParameters.size) {
                    error("Incorrect size of chosen parameters")
                }

                val replaced = replacingInternalParameters(chosenParameters)
                return when (replaced) {
                    is Ground -> replaced.argTypes
                    is Parameterized -> replaced.argTypes
                }
            }

            private fun getOutputType(chosenParameters: List<Type?>): Type {
                if (chosenParameters.size != typeParameters.size) {
                    error("Incorrect size of chosen parameters")
                }

                val replaced = replacingInternalParameters(chosenParameters)
                return when (replaced) {
                    is Ground -> replaced.outputType
                    is Parameterized -> replaced.outputType
                }
            }

            override fun hashCode(): Int {
                return Objects.hash(
                        isReference,
                        typeParameters.map { it.typeClass },
                        argTypes,
                        outputType
                )
            }

            override fun equals(other: Any?): Boolean {
                if (other !is Parameterized) {
                    return false
                }
                return isReference == other.isReference
                        && typeParameters.map { it.typeClass } == other.typeParameters.map { it.typeClass }
                        && argTypes == other.argTypes
                        && outputType == other.outputType
            }

            private fun replacingInternalParameters(chosenParameters: List<Type?>): FunctionType {
                // The chosenParameters should be correct at this point

                return create(
                        isReference,
                        // Keep type parameters that aren't getting defined
                        typeParameters.filterIndexed { index, typeParameter ->
                            chosenParameters[index] == null
                        },
                        argTypes.map { type -> type.replacingInternalParametersInternal(chosenParameters) },
                        outputType.replacingInternalParametersInternal(chosenParameters)
                )
            }

            override fun replacingInternalParametersInternal(chosenParameters: List<Type?>): FunctionType {
                val adjustedChosenParameters = typeParameters.map { null } + chosenParameters

                return replacingInternalParameters(adjustedChosenParameters)
            }

            override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
                return Parameterized(
                        isReference,
                        typeParameters,
                        argTypes.map { it.replacingExternalParameters(parametersMap) },
                        outputType.replacingExternalParameters(parametersMap)
                )
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
    }

    /**
     * This class represents a type parameter that is defined elsewhere in either this type or a type containing this
     * one. In the latter case, this should not be exposed to clients directly; instead, the type defining the type
     * parameter should require clients to pass in type parameters in order to instantiate them.
     *
     * This deals with the fact that we don't want the name of the type parameter to be meaningful; in particular, if
     * we have a function parameterized in terms of T in its definition, we want to be able to call it and ignore the
     * name "T", which we might be using locally, without having to worry about name shadowing.
     *
     * TODO: Document index ordering.
     */
    data class InternalParameterType(val index: Int): Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return index < numAllowedIndices
        }

        override fun isReference(): Boolean {
            return false
        }

        override fun replacingInternalParametersInternal(chosenParameters: List<Type?>): Type {
            val replacement = chosenParameters[index]
            if (replacement != null) {
                return replacement
            } else {
                // New index: number of null values preceding this index in the chosenParameters array
                val newIndex = chosenParameters.subList(0, index).count { it == null }
                return InternalParameterType(newIndex)
            }
        }

        override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
            return this
        }

        override fun getTypeString(): String {
            error("This internal parameter type should be replaced in the type before being converted to a string")
        }
    }

    /**
     * This represents type parameter types where the type parameter is defined in places other than within the same
     * type definition. For example, a struct with a type parameter T may have a member of type T, or a function with
     * type parameter T may have a variable in its code with type T. These would use ParameterType as opposed to
     * [InternalParameterType].
     */
    data class ParameterType(val parameter: TypeParameter): Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return true
        }

        override fun isReference(): Boolean {
            return false
        }

        override fun replacingInternalParametersInternal(chosenParameters: List<Type?>): Type {
            return this
        }

        override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
            val replacement = parametersMap[this]
            return if (replacement != null) {
                replacement
            } else {
                this
            }
        }

        override fun getTypeString(): String {
            return parameter.name
        }

        override fun toString(): String {
            return parameter.name
        }
    }

    /**
     * Note: The hashCode() and equals() methods for this class ignore [originalRef], which is provided for a narrow
     * range of reasons -- primarily, converting back to the original unvalidated code. This may cause odd behavior if
     * you put Types in a set or as the key of a map, but it makes equality checking correct for the purposes of checking
     * that types agree with one another.
     */
    // TODO: Should "ref" be an EntityResolution? Either way, stop passing originalRef to resolvers
    data class NamedType(val ref: ResolvedEntityRef, val originalRef: EntityRef, private val isReference: Boolean, val parameters: List<Type> = listOf()): Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return parameters.all { it.isBindableInternal(numAllowedIndices) }
        }

        override fun isReference(): Boolean {
            return isReference
        }

        override fun replacingInternalParametersInternal(chosenParameters: List<Type?>): Type {
            return this.copy(parameters = parameters.map { it.replacingInternalParametersInternal(chosenParameters) })
        }

        override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
            return this.copy(parameters = parameters.map { it.replacingExternalParameters(parametersMap) })
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

        /**
         * Ignores the value of [originalRef].
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
            return Objects.equals(ref, other.ref) && isReference == other.isReference && Objects.equals(parameters, other.parameters)
        }

        /**
         * Ignores the value of [originalRef].
         */
        override fun hashCode(): Int {
            return Objects.hash(ref, isReference, parameters)
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

data class UnvalidatedFunctionSignature(override val id: EntityId, val argumentTypes: List<UnvalidatedType>, val outputType: UnvalidatedType, val typeParameters: List<TypeParameter> = listOf()): HasId {
    fun getFunctionType(): UnvalidatedType.FunctionType {
        return UnvalidatedType.FunctionType(false, typeParameters, argumentTypes, outputType)
    }
}
data class FunctionSignature private constructor(override val id: EntityId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<TypeParameter> = listOf()): HasId {
    companion object {
        /**
         * Note: This converts instances of the given type parameters into internal parameters in the argument and output types.
         */
        fun create(id: EntityId, argumentTypes: List<Type>, outputType: Type, typeParameters: List<TypeParameter> = listOf()): FunctionSignature {
            val newParameterIndices = HashMap<String, Int>()
            typeParameters.mapIndexed { index, typeParameter ->
                newParameterIndices.put(typeParameter.name, index)
            }
            val newArgTypes = argumentTypes.map { it.internalizeParameters(newParameterIndices, 0) }
            val newOutputType = outputType.internalizeParameters(newParameterIndices, 0)
            return FunctionSignature(id, newArgTypes, newOutputType, typeParameters)
        }
    }
    fun getFunctionType(): Type.FunctionType {
        return Type.FunctionType.create(false, typeParameters, argumentTypes, outputType)
    }
}

data class Annotation(val name: EntityId, val values: List<AnnotationArgument>)
sealed class AnnotationArgument {
    data class Literal(val value: String): AnnotationArgument()
    data class List(val values: kotlin.collections.List<AnnotationArgument>): AnnotationArgument()
}

// Pre-type-analysis
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
sealed class TypedExpression {
    abstract val type: Type
    // TODO: Consider auto-setting some alias types; i.e. always aliased for Variable, never aliased for Literal/ListLiteral/maybe FunctionCalls
    // (Non-obvious correction: Follows wouldn't be aliased if their structure expressions are unaliased, e.g. foo()->bar)
    abstract val aliasType: AliasType
    data class Variable(override val type: Type, override val aliasType: AliasType, val name: String): TypedExpression()
    data class IfThen(override val type: Type, override val aliasType: AliasType, val condition: TypedExpression, val thenBlock: TypedBlock, val elseBlock: TypedBlock): TypedExpression()
    data class NamedFunctionCall(override val type: Type, override val aliasType: AliasType, val functionRef: EntityRef, val resolvedFunctionRef: ResolvedEntityRef, val arguments: List<TypedExpression>, val chosenParameters: List<Type>, val originalChosenParameters: List<Type>): TypedExpression()
    data class ExpressionFunctionCall(override val type: Type, override val aliasType: AliasType, val functionExpression: TypedExpression, val arguments: List<TypedExpression>, val chosenParameters: List<Type>, val originalChosenParameters: List<Type>): TypedExpression()
    data class Literal(override val type: Type, override val aliasType: AliasType, val literal: String): TypedExpression()
    data class ListLiteral(override val type: Type, override val aliasType: AliasType, val contents: List<TypedExpression>, val chosenParameter: Type): TypedExpression()
    data class NamedFunctionBinding(override val type: Type.FunctionType, override val aliasType: AliasType, val functionRef: EntityRef, val resolvedFunctionRef: ResolvedEntityRef, val bindings: List<TypedExpression?>, val chosenParameters: List<Type?>, val originalChosenParameters: List<Type?>) : TypedExpression()
    data class ExpressionFunctionBinding(override val type: Type.FunctionType, override val aliasType: AliasType, val functionExpression: TypedExpression, val bindings: List<TypedExpression?>, val chosenParameters: List<Type?>, val originalChosenParameters: List<Type?>) : TypedExpression()
    data class Follow(override val type: Type, override val aliasType: AliasType, val structureExpression: TypedExpression, val name: String): TypedExpression()
    data class InlineFunction(override val type: Type, override val aliasType: AliasType, val arguments: List<Argument>, val boundVars: List<Argument>, val returnType: Type, val block: TypedBlock): TypedExpression()
}

// Note: Currently Statements can refer to either assignments (if name is non-null) or "plain" statements with imperative
// effects (otherwise). If we introduce a third statement type, we should probably switch this to be a sealed class.
sealed class Statement {
    abstract val location: Location?
    data class Assignment(val name: String, val type: UnvalidatedType?, val expression: Expression, override val location: Location? = null, val nameLocation: Location? = null): Statement()
    data class Bare(val expression: Expression): Statement() {
        override val location: Location? get() = expression.location
    }
}
sealed class ValidatedStatement {
    data class Assignment(val name: String, val type: Type, val expression: TypedExpression): ValidatedStatement()
    // TODO: Is the type here necessary?
    data class Bare(val type: Type, val expression: TypedExpression): ValidatedStatement()
}
data class UnvalidatedArgument(val name: String, val type: UnvalidatedType, val location: Location? = null)
data class Argument(val name: String, val type: Type)
data class Block(val statements: List<Statement>, val location: Location? = null)
// TODO: Rename to standardize
// TODO: Probably do something different about the lastStatementAliasType
data class TypedBlock(val type: Type, val statements: List<ValidatedStatement>, val lastStatementAliasType: AliasType) {
    init {
        if (statements.isEmpty()) {
            error("The list of statements in a TypedBlock should not be empty; this should have already failed validation")
        }
    }
}
data class Function(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: Block, override val annotations: List<Annotation>, val idLocation: Location? = null, val returnTypeLocation: Location? = null) : TopLevelEntity {
    fun getType(): UnvalidatedType.FunctionType {
        return UnvalidatedType.FunctionType(
                false,
                typeParameters,
                arguments.map(UnvalidatedArgument::type),
                returnType
        )
    }
}
data class ValidatedFunction(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<Argument>, val returnType: Type, val block: TypedBlock, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getTypeSignature(): FunctionSignature {
        return FunctionSignature.create(id,
                arguments.map(Argument::type),
                returnType,
                typeParameters)
    }
}

data class UnvalidatedStruct(override val id: EntityId, val typeParameters: List<TypeParameter>, val members: List<UnvalidatedMember>, val requires: Block?, override val annotations: List<Annotation>, val idLocation: Location? = null) : TopLevelEntity {
    fun getConstructorSignature(): UnvalidatedFunctionSignature {
        val argumentTypes = members.map(UnvalidatedMember::type)
        val typeParameters = typeParameters.map { UnvalidatedType.NamedType.forParameter(it, idLocation) }
        val outputType = if (requires == null) {
            UnvalidatedType.NamedType(id.asRef(), false, typeParameters, idLocation)
        } else {
            UnvalidatedType.NamedType(NativeOpaqueType.MAYBE.resolvedRef.toUnresolvedRef(), false, listOf(
                UnvalidatedType.NamedType(id.asRef(), false, typeParameters, idLocation)
            ), idLocation)
        }
        return UnvalidatedFunctionSignature(id, argumentTypes, outputType, this.typeParameters)
    }
}
data class Struct(override val id: EntityId, val moduleId: ModuleUniqueId, val typeParameters: List<TypeParameter>, val members: List<Member>, val requires: TypedBlock?, override val annotations: List<Annotation>) : TopLevelEntity {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    fun getIndexForName(name: String): Int {
        return members.indexOfFirst { member -> member.name == name }
    }

    fun getType(vararg chosenParameters: Type = arrayOf()): Type.NamedType {
        if (chosenParameters.size != typeParameters.size) {
            error("Incorrect number of type parameters")
        }
        return Type.NamedType(resolvedRef, id.asRef(), false, chosenParameters.toList())
    }

    // TODO: Deconflict with UnvalidatedStruct version
    fun getConstructorSignature(): FunctionSignature {
        val argumentTypes = members.map(Member::type)
        val typeParameters = typeParameters.map(Type::ParameterType)
        val outputType = if (requires == null) {
            Type.NamedType(resolvedRef, id.asRef(), false, typeParameters)
        } else {
            NativeOpaqueType.MAYBE.getType(Type.NamedType(resolvedRef, id.asRef(), false, typeParameters))
        }
        return FunctionSignature.create(id, argumentTypes, outputType, this.typeParameters)
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

data class UnvalidatedUnion(override val id: EntityId, val typeParameters: List<TypeParameter>, val options: List<UnvalidatedOption>, override val annotations: List<Annotation>, val idLocation: Location? = null): TopLevelEntity {
    private fun getType(): UnvalidatedType {
        val functionParameters = typeParameters.map { UnvalidatedType.NamedType.forParameter(it) }
        return UnvalidatedType.NamedType(id.asRef(), false, functionParameters)
    }
    fun getConstructorSignature(option: UnvalidatedOption): UnvalidatedFunctionSignature {
        if (!options.contains(option)) {
            error("Invalid option $option")
        }
        val optionId = EntityId(id.namespacedName + option.name)
        val argumentTypes = if (option.type == null) {
            listOf()
        } else {
            listOf(option.type)
        }
        return UnvalidatedFunctionSignature(optionId, argumentTypes, getType(), typeParameters)
    }

    fun getWhenSignature(): UnvalidatedFunctionSignature {
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
            UnvalidatedType.FunctionType(false, listOf(), optionArgTypes, outputParameterType)
        }

        return UnvalidatedFunctionSignature(whenId, argumentTypes, outputParameterType, whenTypeParameters)
    }
}
data class Union(override val id: EntityId, val moduleId: ModuleUniqueId, val typeParameters: List<TypeParameter>, val options: List<Option>, override val annotations: List<Annotation>): TopLevelEntity {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    val whenId = EntityId(id.namespacedName + "when")
    private val optionIndexLookup: Map<EntityId, Int> = {
        val map = HashMap<EntityId, Int>()
        options.forEachIndexed { index, option ->
            val optionId = EntityId(id.namespacedName + option.name)
            map.put(optionId, index)
        }
        map
    }()
    fun getOptionIndexById(functionId: EntityId): Int? {
        return optionIndexLookup[functionId]
    }

    fun getType(): Type {
        return Type.NamedType(resolvedRef, this.id.asRef(), false, typeParameters.map { name -> Type.ParameterType(name) })
    }

    fun getOptionConstructorSignatureForId(optionId: EntityId): FunctionSignature {
        val optionIndex = optionIndexLookup[optionId] ?: error("The union ${id} has no option with ID $optionId")
        val option = options[optionIndex]
        val optionType = option.type
        if (optionType != null) {
            return FunctionSignature.create(optionId, listOf(optionType), this.getType(), typeParameters)
        } else {
            return FunctionSignature.create(optionId, listOf(), this.getType(), typeParameters)
        }
    }

}
data class UnvalidatedOption(val name: String, val type: UnvalidatedType?, val idLocation: Location? = null)
data class Option(val name: String, val type: Type?)

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

data class OpaqueType(val id: EntityId, val moduleId: ModuleUniqueId, val typeParameters: List<TypeParameter>, val isReference: Boolean) {
    val resolvedRef = ResolvedEntityRef(moduleId, id)

    fun getType(vararg chosenParameters: Type = arrayOf()): Type.NamedType {
        if (chosenParameters.size != typeParameters.size) {
            error("Passed in the wrong number of type parameters to type $this; passed in $chosenParameters")
        }
        return Type.NamedType(resolvedRef, id.asRef(), isReference, chosenParameters.toList())
    }
}

private fun Type.internalizeParameters(newParameterIndices: HashMap<String, Int>, indexOffset: Int): Type {
    return when (this) {
        is Type.FunctionType.Ground -> {
            val newArgTypes = argTypes.map { it.internalizeParameters(newParameterIndices, indexOffset) }
            val newOutputType = outputType.internalizeParameters(newParameterIndices, indexOffset)
            Type.FunctionType.Ground(this.isReference(), newArgTypes, newOutputType)
        }
        is Type.FunctionType.Parameterized -> {
            val newIndexOffset = indexOffset + this.typeParameters.size
            val newArgTypes = argTypes.map { it.internalizeParameters(newParameterIndices, newIndexOffset) }
            val newOutputType = outputType.internalizeParameters(newParameterIndices, newIndexOffset)
            Type.FunctionType.Parameterized(this.isReference(), typeParameters, newArgTypes, newOutputType)
        }
        is Type.InternalParameterType -> this
        is Type.ParameterType -> {
            val index = newParameterIndices[this.parameter.name]?.plus(indexOffset)
            if (index != null) {
                Type.InternalParameterType(index)
            } else {
                this
            }
        }
        is Type.NamedType -> {
            val newParameters = parameters.map { it.internalizeParameters(newParameterIndices, indexOffset) }
            return this.copy(parameters = newParameters)
        }
    }
}
