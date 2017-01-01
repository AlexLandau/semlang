package semlang.api

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
}
sealed class Expression {
    class Variable(val name: String): Expression()
    class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block): Expression()
    class FunctionCall(val functionId: FunctionId, val arguments: List<Expression>): Expression()
    class Literal(val type: Type, val literal: String): Expression()
    class Follow(val expression: Expression, val id: String): Expression()
}
data class Assignment(val name: String, val type: Type, val expression: Expression)
data class Argument(val name: String, val type: Type)
data class Block(val assignments: List<Assignment>, val returnedExpression: Expression)
data class Function(val id: FunctionId, val arguments: List<Argument>, val returnType: Type, val block: Block)
data class Struct(val id: FunctionId, val members: List<Member>) {
    fun getIndexForName(name: String): Int {
        return members.indexOfFirst { member -> member.name == name }
    }
}

data class Member(val name: String, val type: Type)


//TODO: Put somewhere different?
//TODO: Validate inputs (non-overlapping keys)
data class InterpreterContext(val functions: Map<FunctionId, Function>, val structs: Map<FunctionId, Struct>)
