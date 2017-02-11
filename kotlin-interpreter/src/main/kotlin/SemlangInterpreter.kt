package semlang.interpreter

import semlang.api.*
import semlang.api.Function
import java.math.BigInteger
import java.util.HashMap

interface SemlangInterpreter {
    fun interpret(functionId: FunctionId, arguments: List<SemObject>): SemObject
}

class SemlangForwardInterpreter(val context: ValidatedContext): SemlangInterpreter {
    private val nativeFunctions: Map<FunctionId, NativeFunction> = getNativeFunctions()

    override fun interpret(functionId: FunctionId, arguments: List<SemObject>): SemObject {
        // Handle "native" functions
        val nativeFunction = nativeFunctions[functionId]
        if (nativeFunction != null) {
            return nativeFunction.apply(arguments)
        }

        // Handle struct constructors
        val structFunction: Struct? = context.structs[functionId]
        if (structFunction != null) {
            return evaluateStructConstructor(structFunction, arguments)
        }

        // Handle non-native functions
        val function: ValidatedFunction = context.functions.getOrElse(functionId, fun (): ValidatedFunction {throw IllegalArgumentException("Unrecognized function ID $functionId")})
        if (arguments.size != function.arguments.size) {
            throw IllegalArgumentException("Wrong number of arguments for function $functionId")
        }
        val variableAssignments: MutableMap<String, SemObject> = HashMap()
        for ((value, argumentDefinition) in arguments.zip(function.arguments)) {
            variableAssignments.put(argumentDefinition.name, value)
        }
        return evaluateBlock(function.block, variableAssignments)
    }

    private fun evaluateStructConstructor(structFunction: Struct, arguments: List<SemObject>): SemObject {
        if (arguments.size != structFunction.members.size) {
            throw IllegalArgumentException("Wrong number of arguments for struct constructor " + structFunction)
        }
        return SemObject.Struct(structFunction, arguments)
    }

    private fun evaluateBlock(block: TypedBlock, initialAssignments: Map<String, SemObject>): SemObject {
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

    private fun evaluateExpression(expression: TypedExpression, assignments: Map<String, SemObject>): SemObject {
        return when (expression) {
            is TypedExpression.Variable -> assignments[expression.name] ?: throw IllegalArgumentException("No variable defined with name ${expression.name}")
            is TypedExpression.IfThen -> {
                val condition = evaluateExpression(expression.condition, assignments)
                if (condition is SemObject.Boolean) {
                    return if (condition.value) {
                        evaluateBlock(expression.thenBlock, assignments)
                    } else {
                        evaluateBlock(expression.elseBlock, assignments)
                    }
                } else {
                    throw IllegalStateException("Condition block in if-then is not a boolean value")
                }
            }
            is TypedExpression.Follow -> {
                val innerResult = evaluateExpression(expression.expression, assignments)
                val name = expression.id
                if (innerResult is SemObject.Struct) {
                    val index = innerResult.struct.getIndexForName(name)
                    return innerResult.objects[index]
                } else {
                    throw IllegalStateException("Trying to use -> on a non-struct object")
                }
            }
            is TypedExpression.VariableFunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments) }
                val function: SemObject = assignments[expression.variableName] ?: throw IllegalArgumentException("No variable defined with name ${expression.variableName}")
                if (function !is SemObject.FunctionReference) {
                    throw IllegalArgumentException("Trying to call ${expression.variableName} as a function, but it is not a function")
                }
                return interpret(function.functionId, arguments)
            }
            is TypedExpression.NamedFunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments) }
                return interpret(expression.functionId, arguments)
            }
            is TypedExpression.Literal -> {
                val type = expression.type
                if (type == Type.BOOLEAN) {
                    return evaluateBooleanLiteral(expression.literal)
                } else if (type == Type.INTEGER) {
                    return evaluateIntegerLiteral(expression.literal)
                } else if (type == Type.NATURAL) {
                    return evaluateNaturalLiteral(expression.literal)
                } else {
                    throw IllegalArgumentException("Unhandled literal \"${expression.literal}\" of type $type")
                }
            }
            is TypedExpression.FunctionReference -> {
                val functionId = expression.functionId
                if (!context.functions.containsKey(functionId)) {
                    error("Function ID not recognized: $functionId")
                }
                return SemObject.FunctionReference(functionId)
            }
        }
    }
}

private fun evaluateIntegerLiteral(literal: String): SemObject {
    return SemObject.Integer(BigInteger(literal))
}

private fun evaluateNaturalLiteral(literal: String): SemObject {
    val bigint = BigInteger(literal)
    if (bigint < BigInteger.ZERO) {
        throw IllegalArgumentException("Natural numbers can't be negative; literal was: $literal")
    }
    return SemObject.Natural(bigint)
}

private fun evaluateBooleanLiteral(literal: String): SemObject {
    if (literal == "true") {
        return SemObject.Boolean(true)
    } else if (literal == "false") {
        return SemObject.Boolean(false)
    } else {
        throw IllegalArgumentException("Unhandled literal \"$literal\" of type Boolean")
    }
}
