package semlang.api

// TODO: Rename this file to "language.kt"; will want a separate file for type arithmetic
data class Package(val strings: List<String>)
data class FunctionId(val thePackage: Package, val functionName: String)
sealed class Type {
    object INTEGER : Type() {
        override fun toString(): String {
            return "Integer"
        }
    }
    object NATURAL : Type() {
        override fun toString(): String {
            return "Natural"
        }
    }
    object BOOLEAN : Type() {
        override fun toString(): String {
            return "Boolean"
        }
    }
    //TODO: Make this a data class when possible
    //TODO: When this is constructed, validate that it does not share a name with a default type
    class NamedType(val id: FunctionId): Type() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as NamedType

            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            return id.hashCode()
        }

        override fun toString(): String {
            return "NamedType(id=$id)"
        }
    }
    class ParameterizedType(val simpleType: Type, val parameterizedTypes: List<Type>): Type() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as ParameterizedType

            if (simpleType != other.simpleType) return false
            if (parameterizedTypes != other.parameterizedTypes) return false

            return true
        }

        override fun hashCode(): Int {
            var result = simpleType.hashCode()
            result = 31 * result + parameterizedTypes.hashCode()
            return result
        }

        override fun toString(): String {
            return "ParameterizedType($simpleType<$parameterizedTypes>)"
        }
    }
}
sealed class Expression {
    class Variable(val name: String): Expression()
    class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block): Expression()
    class FunctionCall(val functionId: FunctionId, val arguments: List<Expression>): Expression()
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
data class Function(override val id: FunctionId, val arguments: List<Argument>, val returnType: Type, val block: Block) : HasFunctionId
data class ValidatedFunction(val id: FunctionId, val arguments: List<Argument>, val returnType: Type, val block: TypedBlock)
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
