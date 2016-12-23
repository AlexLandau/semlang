package semlang.interpreter

import semlang.api.*
import semlang.api.Function
import java.util.HashMap

class SemlangForwardInterpreter(val knownFunctions: Map<FunctionId, Function>) {
    fun interpret(functionId: FunctionId, arguments: List<Any>): Any {
        val variableAssignments: MutableMap<String, Any> = HashMap()
        // TODO: Handle "native" functions
        val function: Function = knownFunctions.getOrElse(functionId, fun (): Function {throw IllegalArgumentException("Unrecognized function ID $functionId")})
        if (arguments.size != function.arguments.size) {
            throw IllegalArgumentException("Wrong number of arguments for function $functionId")
        }
        for ((value, argumentDefinition) in arguments.zip(function.arguments)) {
            variableAssignments.put(argumentDefinition.name, value)
        }
        return evaluateBlock(function.block, variableAssignments)
    }

    private fun evaluateBlock(block: Block, initialAssignments: Map<String, Any>): Any {
//        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        val assignments: MutableMap<String, Any> = HashMap(initialAssignments)
        for ((name, expression) in block.assignments) {
            val value = evaluateExpression(expression, assignments)
            if (assignments.containsKey(name)) {
                throw IllegalStateException("Tried to double-assign variable $name")
            }
            assignments.put(name, value)
        }
        return evaluateExpression(block.returnedExpression, assignments)
    }

    private fun evaluateExpression(expression: Expression, assignments: Map<String, Any>): Any {
        return when (expression) {
            is Expression.Variable -> assignments.getOrElse(expression.name, fun () {throw IllegalArgumentException("No variable defined with name $expression.name")})
            is Expression.IfThen -> {
                val condition: Any = evaluateExpression(expression.condition, assignments)
                if (condition is Boolean) {
                    return if (condition) {
                        evaluateBlock(expression.thenBlock, assignments)
                    } else {
                        evaluateBlock(expression.elseBlock, assignments)
                    }
                } else {
                    throw IllegalStateException("Condition block in if-then is not a boolean value")
                }
            }
            is Expression.FunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments) }
                return interpret(expression.functionId, arguments)
            }
        }
    }
}
