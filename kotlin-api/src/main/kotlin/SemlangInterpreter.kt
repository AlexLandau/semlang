package semlang.interpreter

import semlang.api.*
import semlang.api.Function
import java.math.BigInteger
import java.util.HashMap

// These are Semlang objects that are stored and handled by the interpreter.
// These should know their type.
sealed class SemObject {
    // TODO: Someday, these should become data classes...
    // See https://youtrack.jetbrains.com/issue/KT-10330 (waiting on Kotlin 1.1)
    class Integer(val value: BigInteger) : SemObject() {
        override fun equals(other: Any?): kotlin.Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as Integer

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun toString(): String {
            return "Integer(value=$value)"
        }
    }
    class Boolean(val value: kotlin.Boolean) : SemObject() {
        override fun equals(other: Any?): kotlin.Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as Boolean

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun toString(): String {
            return "Boolean(value=$value)"
        }
    }
}

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>) -> SemObject, val numArgs: Int) {
}

class SemlangForwardInterpreter(val knownFunctions: Map<FunctionId, Function>) {
    private val nativeFunctions: Map<FunctionId, NativeFunction> = getNativeFunctions()

    private fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
        val map = HashMap<FunctionId, NativeFunction>()

        val integerPackage = Package(listOf("integer"))

        // integer.times
        val integerTimesId = FunctionId(integerPackage, "times")
        map.put(integerTimesId, NativeFunction(integerTimesId, { args: List<SemObject> ->
            val left = args[0]
            val right = args[1]
            if (left is SemObject.Integer && right is SemObject.Integer) {
                SemObject.Integer(left.value * right.value)
            } else {
                throw IllegalArgumentException()
            }
        }, 2))

        // integer.plus
        val integerPlusId = FunctionId(integerPackage, "plus")
        map.put(integerPlusId, NativeFunction(integerPlusId, { args: List<SemObject> ->
            val left = args[0]
            val right = args[1]
            if (left is SemObject.Integer && right is SemObject.Integer) {
                SemObject.Integer(left.value + right.value)
            } else {
                throw IllegalArgumentException()
            }
        }, 2))

        return map
    }

    fun interpret(functionId: FunctionId, arguments: List<SemObject>): SemObject {
        // TODO: Validate argument types
        // TODO: Handle "native" functions
        val nativeFunction = nativeFunctions[functionId]
        if (nativeFunction != null) {
            val function: NativeFunction = nativeFunction
            if (arguments.size != function.numArgs) {
                throw IllegalArgumentException("Wrong number of arguments for function $functionId")
            }
//            for ((value, argumentDefinition) in arguments.zip(function.arguments)) {
//                variableAssignments.put(argumentDefinition.name, value)
//            }
//            return function//(function.block, variableAssignments)
            return function.apply(arguments)
        }
        val variableAssignments: MutableMap<String, SemObject> = HashMap()
        val function: Function = knownFunctions.getOrElse(functionId, fun (): Function {throw IllegalArgumentException("Unrecognized function ID $functionId")})
        if (arguments.size != function.arguments.size) {
            throw IllegalArgumentException("Wrong number of arguments for function $functionId")
        }
        for ((value, argumentDefinition) in arguments.zip(function.arguments)) {
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
