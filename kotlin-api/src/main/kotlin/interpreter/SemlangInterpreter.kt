package semlang.interpreter

import semlang.api.*
import semlang.api.Function
import java.math.BigInteger
import java.util.HashMap


class SemlangForwardInterpreter(val knownFunctions: Map<FunctionId, Function>) {
    private val nativeFunctions: Map<FunctionId, NativeFunction> = getNativeFunctions()

    fun interpret(functionId: FunctionId, arguments: List<SemObject>): SemObject {
        // Handle "native" functions
        val nativeFunction = nativeFunctions[functionId]
        if (nativeFunction != null) {
            if (arguments.size != nativeFunction.numArgs) {
                throw IllegalArgumentException("Wrong number of arguments for function $functionId")
            }
            // TODO: Validate argument types
            return nativeFunction.apply(arguments)
        }

        // Handle non-native functions
        val variableAssignments: MutableMap<String, SemObject> = HashMap()
        val function: Function = knownFunctions.getOrElse(functionId, fun (): Function {throw IllegalArgumentException("Unrecognized function ID $functionId")})
        if (arguments.size != function.arguments.size) {
            throw IllegalArgumentException("Wrong number of arguments for function $functionId")
        }
        for ((value, argumentDefinition) in arguments.zip(function.arguments)) {
            if (value.getType() != argumentDefinition.type) {
                throw IllegalArgumentException("Type mismatch in function argument: ${value.getType()} vs. ${argumentDefinition.type}")
            }
            variableAssignments.put(argumentDefinition.name, value)
        }
        return evaluateBlock(function.block, variableAssignments)
    }

    private fun evaluateBlock(block: Block, initialAssignments: Map<String, SemObject>): SemObject {
        val assignments: MutableMap<String, SemObject> = HashMap(initialAssignments)
        for ((name, type, expression) in block.assignments) {
            val value = evaluateExpression(expression, assignments)
            if (assignments.containsKey(name)) {
                throw IllegalStateException("Tried to double-assign variable $name")
            }
            assignments.put(name, value)
        }
        return evaluateExpression(block.returnedExpression, assignments)
    }

    private fun evaluateExpression(expression: Expression, assignments: Map<String, SemObject>): SemObject {
        return when (expression) {
            is Expression.Variable -> assignments[expression.name] ?: throw IllegalArgumentException("No variable defined with name $expression.name")
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
            is Expression.Equals -> {
                val left = evaluateExpression(expression.left, assignments)
                val right = evaluateExpression(expression.right, assignments)
                return valueEquals(left, right)
            }
        }
    }

    private fun valueEquals(left: SemObject, right: SemObject): SemObject.Boolean {
        //TODO: I'm probably doing something wrong here...
        return SemObject.Boolean(left.equals(right))
    }
}
