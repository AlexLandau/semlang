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
data class ResolvedEntityRef(val module: ModuleId, val id: EntityId) {
    override fun toString(): String {
        return "${module.group}:${module.module}:${module.version}:$id"
    }
}

sealed class UnvalidatedType {
    abstract val location: Location?
    abstract protected fun getTypeString(): String
    override fun toString(): String {
        return getTypeString()
    }

    // This contains some types that are inherently invalid, but should be returned by the parser so the error messages
    // can be left to the validator
    object Invalid {
        data class ThreadedInteger(override val location: Location? = null) : UnvalidatedType() {
            override fun getTypeString(): String {
                return "~Integer"
            }

            override fun toString(): String {
                return getTypeString()
            }
        }

        data class ThreadedBoolean(override val location: Location? = null) : UnvalidatedType() {
            override fun getTypeString(): String {
                return "~Boolean"
            }

            override fun toString(): String {
                return getTypeString()
            }
        }
    }

    data class Integer(override val location: Location? = null) : UnvalidatedType() {
        override fun getTypeString(): String {
            return "Integer"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }
    data class Boolean(override val location: Location? = null) : UnvalidatedType() {
        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class List(val parameter: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Maybe(val parameter: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
        override fun getTypeString(): String {
            return "Maybe<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    // TODO: Modify this so equals() and hashCode() ignore differences in type parameter names
    // TODO: The visible/invisible type parameter issue might be able to be solved by the same solution as the equals/hashCode
    // solution, if I come up with something clever and robust...
    // ...................................................................
    // One option is to include a list of names for the type parameters (along with __), but only actually refer to these
    // types not by name, but by an index approach: starting with 0, 1 for the "closer" declarations (in the component types)
    // and with later numbers for the "wrapper" types declared further out. This gets more complicated when considering
    // that there are also type parameters in structs and interfaces that have to be considered separately. (Uh, should
    // there also be type parameters in unions?) In particular, members of structs that have function types have two sets
    // of type parameters to include in their types, so those indices need to have their relative orders defined.
    data class FunctionType(val typeParameters: kotlin.collections.List<TypeParameter>, val argTypes: kotlin.collections.List<UnvalidatedType>, val outputType: UnvalidatedType, override val location: Location? = null): UnvalidatedType() {
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

    data class NamedType(val ref: EntityRef, val isThreaded: kotlin.Boolean, val parameters: kotlin.collections.List<UnvalidatedType> = listOf(), override val location: Location? = null): UnvalidatedType() {
        companion object {
            fun forParameter(parameter: TypeParameter, location: Location? = null): NamedType {
                return NamedType(EntityRef(null, EntityId(listOf(parameter.name))), false, listOf(), location)
            }
        }

        override fun getTypeString(): String {
            // TODO: This might be wrong if the ref includes a module...
            return (if (isThreaded) "~" else "") +
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
    abstract fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type
    abstract protected fun getTypeString(): String
    abstract fun isThreaded(): Boolean
    override fun toString(): String {
        return getTypeString()
    }

    object INTEGER : Type() {
        override fun isThreaded(): Boolean {
            return false
        }

        override fun getTypeString(): String {
            return "Integer"
        }

        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type {
            return this
        }
    }
    object BOOLEAN : Type() {
        override fun isThreaded(): Boolean {
            return false
        }

        override fun getTypeString(): String {
            return "Boolean"
        }

        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type {
            return this
        }
    }

    data class List(val parameter: Type): Type() {
        override fun isThreaded(): Boolean {
            return false
        }

        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type {
            return List(parameter.replacingParameters(chosenParameters))
        }

        override fun getTypeString(): String {
            return "List<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    data class Maybe(val parameter: Type): Type() {
        override fun isThreaded(): Boolean {
            return false
        }

        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type {
            return Maybe(parameter.replacingParameters(chosenParameters))
        }

        override fun getTypeString(): String {
            return "Maybe<$parameter>"
        }

        override fun toString(): String {
            return getTypeString()
        }
    }

    class FunctionType(val typeParameters: kotlin.collections.List<TypeParameter>, private val argTypes: kotlin.collections.List<Type>, private val outputType: Type): Type() {
        override fun isThreaded(): Boolean {
            return false
        }

        fun getDefaultTypeParameterNameSubstitution(): kotlin.collections.List<Type> {
            return typeParameters.map(Type::ParameterType)
        }

        fun getArgTypes(chosenParameters: kotlin.collections.List<Type>): kotlin.collections.List<Type> {
            if (chosenParameters.size != typeParameters.size) {
                error("Incorrect size of chosen parameters")
            }
//            val parametersMap = typeParameters.map{ Type.ParameterType(it) }.zip(chosenParameters).toMap()
            // TODO: Validate that none of these are internal parameter types?
            return replacingParameters(chosenParameters).argTypes
        }

        fun getOutputType(chosenParameters: kotlin.collections.List<Type>): Type {
            if (chosenParameters.size != typeParameters.size) {
                error("Incorrect size of chosen parameters")
            }
//            val parametersMap = typeParameters.map{ Type.ParameterType(it) }.zip(chosenParameters).toMap()
            // TODO: Validate that this is not an internal parameter type?
            return replacingParameters(chosenParameters).outputType
        }

        override fun hashCode(): Int {
            return Objects.hash(
                    typeParameters.map { it.typeClass },
                    argTypes,
                    outputType
            )
        }
        override fun equals(other: Any?): Boolean {
            if (other !is Type.FunctionType) {
                return false
            }
            return typeParameters.map { it.typeClass } == other.typeParameters.map { it.typeClass }
            && argTypes == other.argTypes
            && outputType == other.outputType
        }
        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): FunctionType {
            // TODO: This is not quite right, because this could be nested in another function type providing additional parameters...
//            if (chosenParameters.size != typeParameters.size) {
//                error("Incorrect size of chosen parameters")
//            }

//            val replacedNames = parameterMap.keys.map { it.getTypeString() }
//            return FunctionType(
//                    typeParameters.filterNot { replacedNames.contains(it.name) },
//                    argTypes.map { type -> type.replacingParameters(parameterMap) },
//                    outputType.replacingParameters(parameterMap)
//            )
            // Note that we may need to add to the chosenParameters
            TODO()
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
        override fun isThreaded(): Boolean {
            return false
        }

        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type {
//            val replacement = parameterMap[this]
//            return if (replacement != null) replacement else this
            val replacement = chosenParameters[index]
            return replacement
        }

        override fun getTypeString(): String {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }
    }

    /**
     * This represents type parameter types where the type parameter is defined in places other than within the same
     * type definition. For example, a struct with a type parameter T may have a member of type T, or a function with
     * type parameter T may have a variable in its code with type T. These would use ParameterType as opposed to
     * [InternalParameterType].
     */
    data class ParameterType(val parameter: TypeParameter): Type() {
        override fun isThreaded(): Boolean {
            return false
        }

        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type {
//            val replacement = parameterMap[this]
//            return if (replacement != null) replacement else this
            return this
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
    data class NamedType(val ref: ResolvedEntityRef, val originalRef: EntityRef, val threaded: Boolean, val parameters: kotlin.collections.List<Type> = listOf()): Type() {
        override fun isThreaded(): Boolean {
            return threaded
        }

        override fun replacingParameters(chosenParameters: kotlin.collections.List<Type>): Type {
//            val replacement = parameterMap[this]
//            if (replacement != null) {
//                // TODO: Should this have replaceParameters applied to it?
//                return replacement
//            }
            return NamedType(ref,
                    originalRef,
                    threaded,
                    parameters.map { parameter -> parameter.replacingParameters(chosenParameters) }
            )
        }

        fun getParameterizedTypes(): kotlin.collections.List<Type> {
            return parameters
        }

        override fun getTypeString(): String {
            // TODO: This might be wrong if the ref includes a module...
            return (if (threaded) "~" else "") +
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
            return Objects.equals(ref, other.ref) && threaded == other.threaded && Objects.equals(parameters, other.parameters)
        }

        /**
         * Ignores the value of [originalRef].
         */
        override fun hashCode(): Int {
            return Objects.hash(ref, threaded, parameters)
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
data class TypeSignature(override val id: EntityId, val argumentTypes: List<Type>, val outputType: Type, val typeParameters: List<TypeParameter> = listOf()): HasId

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
    data class VarOrNamedFunctionBinding(val functionIdOrVariable: EntityRef, val bindings: List<AmbiguousExpression?>, val chosenParameters: List<UnvalidatedType?>, override val location: Location): AmbiguousExpression()
    data class ExpressionOrNamedFunctionBinding(val expression: AmbiguousExpression, val bindings: List<AmbiguousExpression?>, val chosenParameters: List<UnvalidatedType?>, override val location: Location): AmbiguousExpression()
    data class IfThen(val condition: AmbiguousExpression, val thenBlock: AmbiguousBlock, val elseBlock: AmbiguousBlock, override val location: Location): AmbiguousExpression()
    data class VarOrNamedFunctionCall(val functionIdOrVariable: EntityRef, val arguments: List<AmbiguousExpression>, val chosenParameters: List<UnvalidatedType>, override val location: Location, val varOrNameLocation: Location?): AmbiguousExpression()
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
    data class NamedFunctionBinding(val functionRef: EntityRef, val bindings: List<Expression?>, val chosenParameters: List<UnvalidatedType?>, override val location: Location? = null): Expression()
    data class ExpressionFunctionBinding(val functionExpression: Expression, val bindings: List<Expression?>, override val location: Location? = null): Expression()
    data class Follow(val structureExpression: Expression, val name: String, override val location: Location? = null): Expression()
    data class InlineFunction(val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: Block, override val location: Location? = null): Expression()
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
    data class NamedFunctionBinding(override val type: Type, val functionRef: EntityRef, val resolvedFunctionRef: ResolvedEntityRef, val bindings: List<TypedExpression?>, val chosenParameters: List<Type?>) : TypedExpression()
    data class ExpressionFunctionBinding(override val type: Type, val functionExpression: TypedExpression, val bindings: List<TypedExpression?>) : TypedExpression()
    data class Follow(override val type: Type, val structureExpression: TypedExpression, val name: String): TypedExpression()
    data class InlineFunction(override val type: Type, val arguments: List<Argument>, val boundVars: List<Argument>, val returnType: Type, val block: TypedBlock): TypedExpression()
}

data class AmbiguousAssignment(val name: String, val type: UnvalidatedType?, val expression: AmbiguousExpression, val nameLocation: Location?)
data class Assignment(val name: String, val type: UnvalidatedType?, val expression: Expression, val nameLocation: Location? = null)
data class ValidatedAssignment(val name: String, val type: Type, val expression: TypedExpression)
data class UnvalidatedArgument(val name: String, val type: UnvalidatedType, val location: Location? = null)
data class Argument(val name: String, val type: Type)
data class AmbiguousBlock(val assignments: List<AmbiguousAssignment>, val returnedExpression: AmbiguousExpression, val location: Location?)
data class Block(val assignments: List<Assignment>, val returnedExpression: Expression, val location: Location? = null)
data class TypedBlock(val type: Type, val assignments: List<ValidatedAssignment>, val returnedExpression: TypedExpression)
data class Function(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<UnvalidatedArgument>, val returnType: UnvalidatedType, val block: Block, override val annotations: List<Annotation>, val idLocation: Location? = null, val returnTypeLocation: Location? = null) : TopLevelEntity {
    fun getTypeSignature(): UnvalidatedTypeSignature {
        return UnvalidatedTypeSignature(id,
                arguments.map(UnvalidatedArgument::type),
                returnType,
                typeParameters)
    }
}
data class ValidatedFunction(override val id: EntityId, val typeParameters: List<TypeParameter>, val arguments: List<Argument>, val returnType: Type, val block: TypedBlock, override val annotations: List<Annotation>) : TopLevelEntity {
    fun getTypeSignature(): TypeSignature {
        return TypeSignature(id,
                arguments.map(Argument::type),
                returnType,
                typeParameters)
    }
}

data class UnvalidatedStruct(override val id: EntityId, val markedAsThreaded: Boolean, val typeParameters: List<TypeParameter>, val members: List<UnvalidatedMember>, val requires: Block?, override val annotations: List<Annotation>, val idLocation: Location? = null) : TopLevelEntity {
    fun getConstructorSignature(): UnvalidatedTypeSignature {
        val argumentTypes = members.map(UnvalidatedMember::type)
        val typeParameters = typeParameters.map { UnvalidatedType.NamedType.forParameter(it, idLocation) }
        val outputType = if (requires == null) {
            UnvalidatedType.NamedType(id.asRef(), markedAsThreaded, typeParameters, idLocation)
        } else {
            UnvalidatedType.Maybe(UnvalidatedType.NamedType(id.asRef(), markedAsThreaded, typeParameters, idLocation), idLocation)
        }
        return UnvalidatedTypeSignature(id, argumentTypes, outputType, this.typeParameters)
    }
}
data class Struct(override val id: EntityId, val isThreaded: Boolean, val moduleId: ModuleId, val typeParameters: List<TypeParameter>, val members: List<Member>, val requires: TypedBlock?, override val annotations: List<Annotation>) : TopLevelEntity {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    fun getIndexForName(name: String): Int {
        return members.indexOfFirst { member -> member.name == name }
    }

    fun getType(): Type.NamedType {
        return Type.NamedType(resolvedRef, id.asRef(), isThreaded, typeParameters.map(Type::ParameterType))
    }

    // TODO: Deconflict with UnvalidatedStruct version
    fun getConstructorSignature(): TypeSignature {
        val argumentTypes = members.map(Member::type)
        val typeParameters = typeParameters.map(Type::ParameterType)
        val outputType = if (requires == null) {
            Type.NamedType(resolvedRef, id.asRef(), isThreaded, typeParameters)
        } else {
            Type.Maybe(Type.NamedType(resolvedRef, id.asRef(), isThreaded, typeParameters))
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
data class Interface(override val id: EntityId, val moduleId: ModuleId, val typeParameters: List<TypeParameter>, val methods: List<Method>, override val annotations: List<Annotation>) : TopLevelEntity {
    val resolvedRef = ResolvedEntityRef(moduleId, id)
    fun getIndexForName(name: String): Int {
        return methods.indexOfFirst { method -> method.name == name }
    }


    val adapterId: EntityId = getAdapterIdForInterfaceId(id)
    val dataTypeParameter = TypeParameter(getUnusedTypeParameterName(typeParameters), null)
    val dataType = Type.ParameterType(dataTypeParameter)
    val instanceType = Type.NamedType(resolvedRef, this.id.asRef(), false, typeParameters.map { name -> Type.ParameterType(name) })
    val adapterType = Type.FunctionType(listOf(), listOf(dataType), instanceType)
    fun getType(): Type.NamedType {
        return instanceType
    }

    fun getInstanceConstructorSignature(): TypeSignature {
        val typeParameters = this.typeParameters
        val argumentTypes = this.methods.map(Method::functionType)

        return TypeSignature(this.id, argumentTypes, instanceType, typeParameters)
    }
    fun getAdapterFunctionSignature(): TypeSignature {
        val adapterTypeParameters = listOf(dataTypeParameter) + typeParameters

        val dataStructType = Type.ParameterType(adapterTypeParameters[0])
        val argumentTypes = ArrayList<Type>()
        this.methods.forEach { method ->
            argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
        }

        val outputType = adapterType

        return TypeSignature(this.adapterId, argumentTypes, outputType, adapterTypeParameters)
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
    val functionType = Type.FunctionType(typeParameters, arguments.map { arg -> arg.type }, returnType)
}

data class UnvalidatedUnion(override val id: EntityId, val typeParameters: List<TypeParameter>, val options: List<UnvalidatedOption>, override val annotations: List<Annotation>, val idLocation: Location? = null): TopLevelEntity {
    fun getType(): UnvalidatedType {
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
data class Union(override val id: EntityId, val moduleId: ModuleId, val typeParameters: List<TypeParameter>, val options: List<Option>, override val annotations: List<Annotation>): TopLevelEntity {
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

    fun getOptionConstructorSignatureForId(optionId: EntityId): TypeSignature {
        val optionIndex = optionIndexLookup[optionId] ?: error("The union ${id} has no option with ID $optionId")
        val option = options[optionIndex]
        val optionType = option.type
        if (optionType != null) {
            return TypeSignature(optionId, listOf(optionType), this.getType(), typeParameters)
        } else {
            return TypeSignature(optionId, listOf(), this.getType(), typeParameters)
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
private fun getInterfaceMethodReferenceType(intrinsicStructType: Type.ParameterType, method: Method): Type {
    val argTypes = ArrayList<Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return Type.FunctionType(listOf(), argTypes, method.returnType)
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
