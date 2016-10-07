package semlang.api

data class Package(val strings: List<String>)
data class FunctionId(val thePackage: Package, val functionName: String)
sealed class Type {
    enum class NativeType {
        INT_S32,
        INT_S64,
        INTEGER,
        NATURAL
    }
}
sealed class Expression {
    class Variable(val name: String): Expression()
    class IfThen(val condition: Expression, val thenBlock: Block, val elseBlock: Block): Expression()
    class FunctionCall(val functionId: FunctionId, val arguments: List<Expression>): Expression()
}
data class Assignment(val name: String, val expression: Expression)
data class Argument(val name: String, val type: Type)
data class Block(val assignments: List<Assignment>, val returnedExpresssion: Expression)
data class Function(val id: FunctionId, val arguments: List<Argument>, val returnType: Type, val block: Block)
