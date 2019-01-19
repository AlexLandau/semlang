package net.semlang.api

import java.util.Objects

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
        return "${module.name}:${module.fake0Version}:$id"
    }
}

sealed class UnvalidatedType {
    abstract val location: Location?
    abstract protected fun getTypeString(): String
    abstract fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType
    abstract fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean
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

            override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
                return other is ReferenceInteger
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

            override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
                return other is ReferenceBoolean
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
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType.Integer {
            return this
        }

        override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
            return other is Integer
        }

        override fun getTypeString(): String {
            return "Integer"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }
    data class Boolean(override val location: Location? = null) : UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType.Boolean {
            return this
        }

        override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
            return other is Boolean
        }

        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class List(val parameter: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType.List {
            return List(parameter.replacingNamedParameterTypes(parameterReplacementMap), location)
        }

        override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
            return other is List && parameter.equalsIgnoringLocation(other.parameter)
        }

        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Maybe(val parameter: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType.Maybe {
            return Maybe(parameter.replacingNamedParameterTypes(parameterReplacementMap), location)
        }

        override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
            return other is Maybe && parameter.equalsIgnoringLocation(other.parameter)
        }

        override fun getTypeString(): String {
            return "Maybe<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class FunctionType(val typeParameters: kotlin.collections.List<TypeParameter>, val argTypes: kotlin.collections.List<UnvalidatedType>, val outputType: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun replacingNamedParameterTypes(parameterReplacementMap: Map<String, UnvalidatedType>): UnvalidatedType.FunctionType {
            return FunctionType(
                    typeParameters.filter { !parameterReplacementMap.containsKey(it.name) },
                    this.argTypes.map { it.replacingNamedParameterTypes(parameterReplacementMap) },
                    this.outputType.replacingNamedParameterTypes(parameterReplacementMap),
                    location)
        }

        override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
            return other is FunctionType &&
                    typeParameters == other.typeParameters &&
                    argTypes.size == other.argTypes.size &&
                    argTypes.zip(other.argTypes).all { it.first.equalsIgnoringLocation(it.second) } &&
                    outputType.equalsIgnoringLocation(other.outputType)
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

        fun getNumArguments(): Int {
            return argTypes.size
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

        override fun equalsIgnoringLocation(other: UnvalidatedType): kotlin.Boolean {
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
    protected abstract fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type
    protected abstract fun getTypeString(): String
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

    // TODO: Recase these to Integer and Boolean when convenient
    object INTEGER : Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return true
        }

        override fun isReference(): Boolean {
            return false
        }

        override fun getTypeString(): String {
            return "Integer"
        }

        override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type {
            return this
        }

        override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
            return this
        }
    }
    object BOOLEAN : Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return true
        }

        override fun isReference(): Boolean {
            return false
        }

        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type {
            return this
        }

        override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
            return this
        }
    }

    data class List(val parameter: Type): Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return parameter.isBindableInternal(numAllowedIndices)
        }

        override fun isReference(): Boolean {
            return false
        }

        override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type {
            return List(parameter.replacingInternalParametersInternal(chosenParameters))
        }

        override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
            return List(parameter.replacingExternalParameters(parametersMap))
        }

        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Maybe(val parameter: Type): Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return parameter.isBindableInternal(numAllowedIndices)
        }

        override fun isReference(): Boolean {
            return false
        }

        override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type {
            return Maybe(parameter.replacingInternalParametersInternal(chosenParameters))
        }

        override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
            return Maybe(parameter.replacingExternalParameters(parametersMap))
        }

        override fun getTypeString(): String {
            return "Maybe<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    sealed class FunctionType: Type() {
        abstract fun getNumArguments(): Int
        abstract fun rebindTypeParameters(boundTypeParameters: kotlin.collections.List<Type?>): FunctionType
        abstract fun rebindArguments(bindingTypes: kotlin.collections.List<Type?>): FunctionType
        /**
         * This returns a list of argument types in which any argument type that is not currently bindable (because its type
         * contains an internal parameter type that is not bound) is replaced with null.
         */
        abstract fun getBindableArgumentTypes(): kotlin.collections.List<Type?>

        // This is usually used for things like printing the types for the user
        abstract fun getDefaultGrounding(): FunctionType.Ground

        abstract fun groundWithTypeParameters(chosenTypeParameters: kotlin.collections.List<Type>): FunctionType.Ground
        abstract val typeParameters: kotlin.collections.List<TypeParameter>

        companion object {
            fun create(typeParameters: kotlin.collections.List<TypeParameter>, argTypes: kotlin.collections.List<Type>, outputType: Type): FunctionType {
                if (typeParameters.isEmpty()) {
                    return FunctionType.Ground(argTypes, outputType)
                } else {
                    return FunctionType.Parameterized(typeParameters, argTypes, outputType)
                }
            }
        }

        data class Ground(val argTypes: kotlin.collections.List<Type>, val outputType: Type) : FunctionType() {
            override val typeParameters = listOf<TypeParameter>()

            override fun groundWithTypeParameters(chosenTypeParameters: kotlin.collections.List<Type>): Ground {
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

            override fun getBindableArgumentTypes(): kotlin.collections.List<Type?> {
                return argTypes
            }

            override fun rebindTypeParameters(boundTypeParameters: kotlin.collections.List<Type?>): FunctionType.Ground {
                if (boundTypeParameters.isNotEmpty()) {
                    error("Tried to rebind a ground function type $this with parameters $boundTypeParameters")
                }
                return this
            }

            override fun rebindArguments(bindingTypes: kotlin.collections.List<Type?>): FunctionType.Ground {
                if (bindingTypes.size != argTypes.size) {
                    error("Wrong number of binding types")
                }
                return Type.FunctionType.Ground(
                        argTypes.zip(bindingTypes).filter { it.second == null }.map { it.first },
                        outputType)
            }

            override fun getNumArguments(): Int {
                return argTypes.size
            }

            override fun isReference(): Boolean {
                return false
            }

            override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type.FunctionType.Ground {
                val argTypes = argTypes.map { it.replacingInternalParametersInternal(chosenParameters) }
                val outputType = outputType.replacingInternalParametersInternal(chosenParameters)
                return Type.FunctionType.Ground(argTypes, outputType)
            }

            override fun getTypeString(): String {
                return "(" +
                        argTypes.joinToString(", ") +
                        ") -> " +
                        outputType.toString()
            }

            override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
                return FunctionType.Ground(
                        argTypes.map { it.replacingExternalParameters(parametersMap) },
                        outputType.replacingExternalParameters(parametersMap)
                )
            }
        }

        //TODO: Figure out if we can return argTypes and outputType to private
        class Parameterized(override val typeParameters: kotlin.collections.List<TypeParameter>, val argTypes: kotlin.collections.List<Type>, val outputType: Type) : FunctionType() {

            override fun groundWithTypeParameters(chosenTypeParameters: kotlin.collections.List<Type>): Ground {
                if (chosenTypeParameters.size != typeParameters.size) {
                    error("Wrong number of type parameters")
                }
                val newArgTypes = getArgTypes(chosenTypeParameters)
                val newOutputType = getOutputType(chosenTypeParameters)
                return Type.FunctionType.Ground(newArgTypes, newOutputType)
            }

            override fun getDefaultGrounding(): Ground {
                val substitution = this.getDefaultTypeParameterNameSubstitution()
                return rebindTypeParameters(substitution) as Ground
            }

            override fun isBindableInternal(incomingNumAllowedIndices: Int): Boolean {
                val newNumAllowedIndices = incomingNumAllowedIndices + typeParameters.size
                return argTypes.all { it.isBindableInternal(newNumAllowedIndices) } && outputType.isBindableInternal(newNumAllowedIndices)
            }

            override fun getBindableArgumentTypes(): kotlin.collections.List<Type?> {
                return argTypes.map {
                    if (it.isBindable()) {
                        it
                    } else {
                        null
                    }
                }
            }

            override fun rebindTypeParameters(boundTypeParameters: kotlin.collections.List<Type?>): FunctionType {
                if (boundTypeParameters.size != typeParameters.size) {
                    error("Wrong number of type parameters")
                }

                val newArgTypes = getArgTypes(boundTypeParameters)
                val newOutputType = getOutputType(boundTypeParameters)

                return Type.FunctionType.create(
                    typeParameters.zip(boundTypeParameters).filter { it.second == null }.map { it.first },
                    newArgTypes,
                    newOutputType)
            }

            override fun rebindArguments(bindingTypes: kotlin.collections.List<Type?>): FunctionType {
                if (bindingTypes.size != argTypes.size) {
                    error("Wrong number of binding types")
                }

                for ((bindingType, argType) in bindingTypes.zip(argTypes)) {
                    if (bindingType != null && !argType.isBindable()) {
                        error("Passed in a binding type for a non-bindable argument type $argType; type is $this, bindingTypes are $bindingTypes")
                    }
                }

                return Type.FunctionType.create(
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
                return false
            }

            private fun getDefaultTypeParameterNameSubstitution(): kotlin.collections.List<Type> {
                return typeParameters.map(Type::ParameterType)
            }

            private fun getArgTypes(chosenParameters: kotlin.collections.List<Type?>): kotlin.collections.List<Type> {
                if (chosenParameters.size != typeParameters.size) {
                    error("Incorrect size of chosen parameters")
                }

                val replaced = replacingInternalParameters(chosenParameters)
                return when (replaced) {
                    is Type.FunctionType.Ground -> replaced.argTypes
                    is Type.FunctionType.Parameterized -> replaced.argTypes
                }
            }

            private fun getOutputType(chosenParameters: kotlin.collections.List<Type?>): Type {
                if (chosenParameters.size != typeParameters.size) {
                    error("Incorrect size of chosen parameters")
                }

                val replaced = replacingInternalParameters(chosenParameters)
                return when (replaced) {
                    is Type.FunctionType.Ground -> replaced.outputType
                    is Type.FunctionType.Parameterized -> replaced.outputType
                }
            }

            override fun hashCode(): Int {
                return Objects.hash(
                        typeParameters.map { it.typeClass },
                        argTypes,
                        outputType
                )
            }

            override fun equals(other: Any?): Boolean {
                if (other !is Type.FunctionType.Parameterized) {
                    return false
                }
                return typeParameters.map { it.typeClass } == other.typeParameters.map { it.typeClass }
                        && argTypes == other.argTypes
                        && outputType == other.outputType
            }

            private fun replacingInternalParameters(chosenParameters: kotlin.collections.List<Type?>): FunctionType {
                // The chosenParameters should be correct at this point

                return FunctionType.create(
                        // Keep type parameters that aren't getting defined
                        typeParameters.filterIndexed { index, typeParameter ->
                            chosenParameters[index] == null
                        },
                        argTypes.map { type -> type.replacingInternalParametersInternal(chosenParameters) },
                        outputType.replacingInternalParametersInternal(chosenParameters)
                )
            }

            override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): FunctionType {
                val adjustedChosenParameters = typeParameters.map { null } + chosenParameters

                return replacingInternalParameters(adjustedChosenParameters)
            }

            override fun replacingExternalParameters(parametersMap: Map<ParameterType, Type>): Type {
                return FunctionType.Parameterized(
                        typeParameters,
                        argTypes.map { it.replacingExternalParameters(parametersMap) },
                        outputType.replacingExternalParameters(parametersMap)
                )
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

        override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type {
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

        override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type {
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
    data class NamedType(val ref: ResolvedEntityRef, val originalRef: EntityRef, private val isReference: Boolean, val parameters: kotlin.collections.List<Type> = listOf()): Type() {
        override fun isBindableInternal(numAllowedIndices: Int): Boolean {
            return parameters.all { it.isBindableInternal(numAllowedIndices) }
        }

        override fun isReference(): Boolean {
            return isReference
        }

        override fun replacingInternalParametersInternal(chosenParameters: kotlin.collections.List<Type?>): Type {
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
        return UnvalidatedType.FunctionType(typeParameters, argumentTypes, outputType)
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
        return Type.FunctionType.create(typeParameters, argumentTypes, outputType)
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
    data class NamedFunctionBinding(override val type: Type, override val aliasType: AliasType, val functionRef: EntityRef, val resolvedFunctionRef: ResolvedEntityRef, val bindings: List<TypedExpression?>, val chosenParameters: List<Type?>, val originalChosenParameters: List<Type?>) : TypedExpression()
    data class ExpressionFunctionBinding(override val type: Type, override val aliasType: AliasType, val functionExpression: TypedExpression, val bindings: List<TypedExpression?>, val chosenParameters: List<Type?>, val originalChosenParameters: List<Type?>) : TypedExpression()
    data class Follow(override val type: Type, override val aliasType: AliasType, val structureExpression: TypedExpression, val name: String): TypedExpression()
    data class InlineFunction(override val type: Type, override val aliasType: AliasType, val arguments: List<Argument>, val boundVars: List<Argument>, val returnType: Type, val block: TypedBlock): TypedExpression()
}

// Note: Currently Statements can refer to either assignments (if name is non-null) or "plain" statements with imperative
// effects (otherwise). If we introduce a third statement type, we should probably switch this to be a sealed class.
data class Statement(val name: String?, val type: UnvalidatedType?, val expression: Expression, val nameLocation: Location? = null)
data class ValidatedStatement(val name: String?, val type: Type, val expression: TypedExpression)
data class UnvalidatedArgument(val name: String, val type: UnvalidatedType, val location: Location? = null)
data class Argument(val name: String, val type: Type)
data class Block(val statements: List<Statement>, val returnedExpression: Expression, val location: Location? = null)
data class TypedBlock(val type: Type, val statements: List<ValidatedStatement>, val returnedExpression: TypedExpression)
data class Function(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: Block, override val annotations: List<Annotation>, val idLocation: Location? = null, val returnTypeLocation: Location? = null) : TopLevelEntity {
    fun getType(): UnvalidatedType.FunctionType {
        return UnvalidatedType.FunctionType(
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
            UnvalidatedType.Maybe(UnvalidatedType.NamedType(id.asRef(), false, typeParameters, idLocation), idLocation)
        }
        return UnvalidatedFunctionSignature(id, argumentTypes, outputType, this.typeParameters)
    }
}
data class Struct(override val id: EntityId, val moduleId: ModuleUniqueId, val typeParameters: List<TypeParameter>, val members: List<Member>, val requires: TypedBlock?, override val annotations: List<Annotation>) : TopLevelEntity {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    fun getIndexForName(name: String): Int {
        return members.indexOfFirst { member -> member.name == name }
    }

    fun getType(chosenParameters: List<Type> = listOf()): Type.NamedType {
        if (chosenParameters.size != typeParameters.size) {
            error("Incorrect number of type parameters")
        }
        return Type.NamedType(resolvedRef, id.asRef(), false, chosenParameters)
    }

    // TODO: Deconflict with UnvalidatedStruct version
    fun getConstructorSignature(): FunctionSignature {
        val argumentTypes = members.map(Member::type)
        val typeParameters = typeParameters.map(Type::ParameterType)
        val outputType = if (requires == null) {
            Type.NamedType(resolvedRef, id.asRef(), false, typeParameters)
        } else {
            Type.Maybe(Type.NamedType(resolvedRef, id.asRef(), false, typeParameters))
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

data class UnvalidatedInterface(override val id: EntityId, val typeParameters: List<TypeParameter>, val methods: List<UnvalidatedMethod>, override val annotations: List<Annotation>, val idLocation: Location? = null) : TopLevelEntity {
    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val dataTypeParameter = TypeParameter(getUnusedTypeParameterName(typeParameters), null)
    val dataType = UnvalidatedType.NamedType.forParameter(dataTypeParameter, null)

    val instanceType = UnvalidatedType.NamedType(this.id.asRef(), false, typeParameters.map { name -> UnvalidatedType.NamedType.forParameter(name, null) }, idLocation)

    fun getInstanceConstructorSignature(): UnvalidatedFunctionSignature {
        val typeParameters = this.typeParameters
        val argumentTypes = this.methods.map(UnvalidatedMethod::functionType)

        return UnvalidatedFunctionSignature(this.id, argumentTypes, instanceType, typeParameters)
    }
    fun getAdapterFunctionSignature(): UnvalidatedFunctionSignature {
        val adapterTypeParameters = listOf(dataTypeParameter) + typeParameters

        val dataStructType = UnvalidatedType.NamedType.forParameter(adapterTypeParameters[0], null)
        val argumentTypes = ArrayList<UnvalidatedType>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = UnvalidatedType.FunctionType(listOf(), listOf(dataType), instanceType)

        return UnvalidatedFunctionSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters)
    }
}
data class Interface(override val id: EntityId, val moduleId: ModuleUniqueId, val typeParameters: List<TypeParameter>, val methods: List<Method>, override val annotations: List<Annotation>) : TopLevelEntity {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    fun getIndexForName(name: String): Int {
        return methods.indexOfFirst { method -> method.name == name }
    }


    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val dataTypeParameter = TypeParameter(getUnusedTypeParameterName(typeParameters), null)
    val dataType = Type.ParameterType(dataTypeParameter)
    val instanceType = Type.NamedType(resolvedRef, this.id.asRef(), false, typeParameters.map { name -> Type.ParameterType(name) })
    val adapterType = Type.FunctionType.create(listOf(), listOf(dataType), instanceType)
    private fun getType(): Type.NamedType {
        return instanceType
    }

    fun getInstanceConstructorSignature(): FunctionSignature {
        val typeParameters = this.typeParameters
        val argumentTypes = this.methods.map(Method::functionType)

        return FunctionSignature.create(this.id, argumentTypes, instanceType, typeParameters)
    }
    fun getAdapterFunctionSignature(): FunctionSignature {
        val adapterTypeParameters = listOf(dataTypeParameter) + typeParameters

        val dataStructType = Type.InternalParameterType(0)
        val argumentTypes = ArrayList<Type>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = adapterType

        return FunctionSignature.create(this.adapterId, argumentTypes, outputType, adapterTypeParameters)
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
data class Method(val name: String, val typeParameters: List<TypeParameter>, val arguments: List<Argument>, val returnType: Type) {
    init {
        if (typeParameters.isNotEmpty()) {
            // This case hasn't really been handled correctly/tested yet
            TODO()
        }
    }
    val functionType = Type.FunctionType.create(typeParameters, arguments.map { arg -> arg.type }, returnType)
}

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
            UnvalidatedType.FunctionType(listOf(), optionArgTypes, outputParameterType)
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

private fun getInterfaceMethodReferenceType(intrinsicStructType: UnvalidatedType.NamedType, method: UnvalidatedMethod): UnvalidatedType {
    val argTypes = ArrayList<UnvalidatedType>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return UnvalidatedType.FunctionType(listOf(), argTypes, method.returnType, null)
}
private fun getInterfaceMethodReferenceType(intrinsicStructType: Type, method: Method): Type {
    val argTypes = ArrayList<Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return Type.FunctionType.create(listOf(), argTypes, method.returnType)
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

data class OpaqueType(val id: EntityId, val moduleId: ModuleUniqueId, val typeParameters: List<TypeParameter>, val isReference: Boolean) {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    fun getType(chosenParameters: List<Type> = listOf()): Type.NamedType {
        if (chosenParameters.size != typeParameters.size) {
            error("Passed in the wrong number of type parameters to type $this; passed in $chosenParameters")
        }
        return Type.NamedType(resolvedRef, id.asRef(), isReference, chosenParameters)
    }
}

private fun Type.internalizeParameters(newParameterIndices: HashMap<String, Int>, indexOffset: Int): Type {
    return when (this) {
        Type.INTEGER -> this
        Type.BOOLEAN -> this
        is Type.List -> {
            Type.List(parameter.internalizeParameters(newParameterIndices, indexOffset))
        }
        is Type.Maybe -> {
            Type.Maybe(parameter.internalizeParameters(newParameterIndices, indexOffset))
        }
        is Type.FunctionType.Ground -> {
            val newArgTypes = argTypes.map { it.internalizeParameters(newParameterIndices, indexOffset) }
            val newOutputType = outputType.internalizeParameters(newParameterIndices, indexOffset)
            Type.FunctionType.Ground(newArgTypes, newOutputType)
        }
        is Type.FunctionType.Parameterized -> {
            val newIndexOffset = indexOffset + this.typeParameters.size
            val newArgTypes = argTypes.map { it.internalizeParameters(newParameterIndices, newIndexOffset) }
            val newOutputType = outputType.internalizeParameters(newParameterIndices, newIndexOffset)
            Type.FunctionType.Parameterized(typeParameters, newArgTypes, newOutputType)
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
