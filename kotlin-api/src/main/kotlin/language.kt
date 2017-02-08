package semlang.api

data class Package(val strings: List<String>) {
    companion object {
        val EMPTY = Package(listOf())
    }
}
//TODO: Currently this plays double duty as the ID for functions and structs. We may want to make this a more general
// "EntityId" type, or some such. (Other concepts like interfaces and annotations will probably use the same type.)
data class FunctionId(val thePackage: Package, val functionName: String)
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

//    class FunctionType(val argTypes: List<Type>, val outputType: Type, val parameters: List<Type>): Type(), ParameterizableType {
//        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
//            return FunctionType(argTypes.map { type -> type.replacingParameters(parameterMap) },
//                    outputType.replacingParameters(parameterMap),
//                    replaceParameters(parameters, parameterMap))
//        }
//
//
//        override fun getParameterizedTypes(): List<Type> {
//            return parameters
//        }
//
//        override fun equals(other: Any?): Boolean {
//            if (this === other) return true
//            if (other?.javaClass != javaClass) return false
//
//            other as FunctionType
//
//            if (argTypes != other.argTypes) return false
//            if (outputType != other.outputType) return false
//            if (parameters != other.parameters) return false
//
//            return true
//        }
//
//        override fun hashCode(): Int {
//            var result = argTypes.hashCode()
//            result = 31 * result + outputType.hashCode()
//            result = 31 * result + parameters.hashCode()
//            return result
//        }
//
//        override fun toString(): String {
//            return "FunctionType(argTypes=$argTypes, outputType=$outputType, parameters=$parameters)"
//        }
//    }

    //TODO: Make this a data class when/if possible
    //TODO: In the validator, validate that it does not share a name with a default type
    class NamedType(val id: FunctionId, val parameters: List<Type>): Type(), ParameterizableType {
        override fun replacingParameters(parameterMap: Map<Type, Type>): Type {
            val replacement = parameterMap.get(this)
            if (replacement != null) {
                // TODO: Should this have replaceParameters applied to it?
                return replacement
            }
            return NamedType(id,
                    replaceParameters(parameters, parameterMap))
        }

        override fun getParameterizedTypes(): List<Type> {
            return parameters
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as NamedType

            if (id != other.id) return false
            if (parameters != other.parameters) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + parameters.hashCode()
            return result
        }

        override fun toString(): String {
            return "NamedType(id=$id, parameters=$parameters)"
        }
    }

}
sealed class Expression {
    class Variable(val name: String): Expression()
    class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block): Expression()
    class FunctionCall(val functionId: FunctionId, val arguments: List<Expression>, val chosenParameters: List<Type>): Expression()
    class Literal(val type: Type, val literal: String): Expression()
    class Follow(val expression: Expression, val id: String): Expression()
}
sealed class TypedExpression() {
    abstract val type: Type
    class Variable(override val type: Type, val name: String): TypedExpression()
    class IfThen(override val type: Type, val condition: TypedExpression, val thenBlock: TypedBlock, val elseBlock: TypedBlock): TypedExpression()
    class FunctionCall(override val type: Type, val functionId: FunctionId, val arguments: List<TypedExpression>): TypedExpression()
    class Literal(override val type: Type, val literal: String): TypedExpression()
    class Follow(override val type: Type, val expression: TypedExpression, val id: String): TypedExpression()
}

data class Assignment(val name: String, val type: Type, val expression: Expression)
data class ValidatedAssignment(val name: String, val type: Type, val expression: TypedExpression)
data class Argument(val name: String, val type: Type)
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
