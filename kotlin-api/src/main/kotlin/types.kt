package semlang.api

data class Package(val strings: List<String>)
data class FunctionId(val thePackage: Package, val functionName: String)
sealed class Type {
    // TODO: Some kind of toString() handling for these
    object INT_S32 : Type()
    object INT_S64 : Type()
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
}
sealed class Expression {
    class Variable(val name: String): Expression()
    class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block): Expression()
    class FunctionCall(val functionId: FunctionId, val arguments: List<Expression>): Expression()
    class Literal(val type: Type, val literal: String): Expression()
}
data class Assignment(val name: String, val type: Type, val expression: Expression)
data class Argument(val name: String, val type: Type)
data class Block(val assignments: List<Assignment>, val returnedExpression: Expression)
data class Function(val id: FunctionId, val arguments: List<Argument>, val returnType: Type, val block: Block)
