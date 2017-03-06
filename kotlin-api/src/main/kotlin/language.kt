package semlang.api

data class Package(val strings: List<String>) {
    companion object {
        val EMPTY = Package(listOf())
    }
}
//TODO: Currently this plays double duty as the ID for functions and structs. We may want to make this a more general
// "EntityId" type, or some such. (Other concepts like interfaces and annotations will probably use the same type.)
data class FunctionId(val thePackage: Package, val functionName: String) {
    companion object {
        fun of(name: String): FunctionId {
            return FunctionId(Package.EMPTY, name)
        }
    }
}
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

    object INTEGER : Type() {
        override fun toString(): String {
            return "Integer"
        }

        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return this
        }
    }
    object NATURAL : Type() {
        override fun toString(): String {
            return "Natural"
        }

        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return this
        }
    }
    object BOOLEAN : Type() {
        override fun toString(): String {
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
    }

    data class Try(val parameter: Type): Type() {
        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return Try(parameter.replacingParameters(parameterMap))
        }
    }

    data class FunctionType(val argTypes: kotlin.collections.List<Type>, val outputType: Type): Type() {
        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            return FunctionType(argTypes.map { type -> type.replacingParameters(parameterMap) },
                    outputType.replacingParameters(parameterMap))
        }
    }

    //TODO: In the validator, validate that it does not share a name with a default type
    data class NamedType(val id: FunctionId, val parameters: kotlin.collections.List<Type>): Type(), ParameterizableType {
        companion object {
            fun forParameter(name: String): NamedType {
                return NamedType(FunctionId.of(name), listOf())
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
    }

}
// Pre-scoping
sealed class AmbiguousExpression {
    data class Variable(val name: String): AmbiguousExpression()
    data class FunctionBinding(val functionIdOrVariable: FunctionId, val chosenParameters: List<Type>, val bindings: List<AmbiguousExpression?>): AmbiguousExpression()
    data class IfThen(val condition: AmbiguousExpression, val thenBlock: AmbiguousBlock, val elseBlock: AmbiguousBlock): AmbiguousExpression()
    data class FunctionCall(val functionIdOrVariable: FunctionId, val arguments: List<AmbiguousExpression>, val chosenParameters: List<Type>): AmbiguousExpression()
    data class Literal(val type: Type, val literal: String): AmbiguousExpression()
    data class Follow(val expression: AmbiguousExpression, val id: String): AmbiguousExpression()
}

// Post-scoping, pre-type-analysis
sealed class Expression {
    data class Variable(val name: String): Expression()
    data class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block): Expression()
    data class VariableFunctionCall(val variableName: String, val arguments: List<Expression>): Expression()
    data class NamedFunctionCall(val functionId: FunctionId, val arguments: List<Expression>, val chosenParameters: List<Type>): Expression()
    data class Literal(val type: Type, val literal: String): Expression()
    data class VariableFunctionBinding(val variableName: String, val bindings: List<Expression?>): Expression()
    data class NamedFunctionBinding(val functionId: FunctionId, val chosenParameters: List<Type>, val bindings: List<Expression?>): Expression()
    data class Follow(val expression: Expression, val id: String): Expression()
}
// Post-type-analysis
sealed class TypedExpression {
    abstract val type: Type
    data class Variable(override val type: Type, val name: String): TypedExpression()
    data class IfThen(override val type: Type, val condition: TypedExpression, val thenBlock: TypedBlock, val elseBlock: TypedBlock): TypedExpression()
    data class VariableFunctionCall(override val type: Type, val variableName: String, val arguments: List<TypedExpression>): TypedExpression()
    data class NamedFunctionCall(override val type: Type, val functionId: FunctionId, val arguments: List<TypedExpression>): TypedExpression()
    data class Literal(override val type: Type, val literal: String): TypedExpression()
    data class Follow(override val type: Type, val expression: TypedExpression, val id: String): TypedExpression()
    data class VariableFunctionBinding(override val type: Type, val variableName: String, val bindings: List<TypedExpression?>) : TypedExpression()
    data class NamedFunctionBinding(override val type: Type, val functionId: FunctionId, val chosenParameters: List<Type>, val bindings: List<TypedExpression?>) : TypedExpression()
}

data class AmbiguousAssignment(val name: String, val type: Type, val expression: AmbiguousExpression)
data class Assignment(val name: String, val type: Type, val expression: Expression)
data class ValidatedAssignment(val name: String, val type: Type, val expression: TypedExpression)
data class Argument(val name: String, val type: Type)
data class AmbiguousBlock(val assignments: List<AmbiguousAssignment>, val returnedExpression: AmbiguousExpression)
data class Block(val assignments: List<Assignment>, val returnedExpression: Expression)
data class TypedBlock(val type: Type, val assignments: List<ValidatedAssignment>, val returnedExpression: TypedExpression)
data class Function(override val id: FunctionId, val typeParameters: List<String>, val arguments: List<Argument>, val returnType: Type, val block: Block) : HasFunctionId
data class ValidatedFunction(val id: FunctionId, val typeParameters: List<String>, val arguments: List<Argument>, val returnType: Type, val block: TypedBlock)
data class Struct(override val id: FunctionId, val typeParameters: List<String>, val members: List<Member>) : HasFunctionId {
    fun getIndexForName(name: String): Int {
        return members.indexOfFirst { member -> member.name == name }
    }
}
interface HasFunctionId {
    val id: FunctionId
}
data class Member(val name: String, val type: Type)


//TODO: Put somewhere different?
//TODO: Validate inputs (non-overlapping keys)
data class InterpreterContext(val functions: Map<FunctionId, Function>, val structs: Map<FunctionId, Struct>)
data class ValidatedContext(val functions: Map<FunctionId, ValidatedFunction>, val structs: Map<FunctionId, Struct>)