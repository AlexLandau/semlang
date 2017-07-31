package semlang.interpreter

import semlang.api.*
import java.math.BigInteger
import java.util.*
import java.util.stream.Collectors

interface SemlangInterpreter {
    fun interpret(functionId: FunctionId, arguments: List<SemObject>): SemObject
}

class SemlangForwardInterpreter(val context: ValidatedContext): SemlangInterpreter {
    private val nativeFunctions: Map<FunctionId, NativeFunction> = getNativeFunctions()

    override fun interpret(functionId: FunctionId, arguments: List<SemObject>): SemObject {
        // Handle "native" functions
        val nativeFunction = nativeFunctions[functionId]
        if (nativeFunction != null) {
            return nativeFunction.apply(arguments, this::interpretBinding)
        }

        // Handle struct constructors
        val structFunction: Struct? = context.getStruct(functionId)
        if (structFunction != null) {
            return evaluateStructConstructor(structFunction, arguments)
        }

        // Handle adapter constructors
        val adapterFunction: Interface? = context.getInterfaceByAdapterId(functionId)
        if (adapterFunction != null) {
            return evaluateAdapterConstructor(adapterFunction, arguments)
        }

        // Handle instance constructors
        val interfaceFunction: Interface? = context.getInterface(functionId)
        if (interfaceFunction != null) {
            return evaluateInterfaceConstructor(interfaceFunction, arguments)
        }

        // Handle non-native functions
        val function: ValidatedFunction = context.getFunctionImplementation(functionId) ?: throw IllegalArgumentException("Unrecognized function ID $functionId")
        if (arguments.size != function.arguments.size) {
            throw IllegalArgumentException("Wrong number of arguments for function $functionId")
        }
        val variableAssignments: MutableMap<String, SemObject> = HashMap()
        for ((value, argumentDefinition) in arguments.zip(function.arguments)) {
            variableAssignments.put(argumentDefinition.name, value)
        }
        return evaluateBlock(function.block, variableAssignments)
    }

    private fun interpretBinding(functionBinding: SemObject.FunctionBinding, args: List<SemObject>): SemObject {
        val functionId = functionBinding.functionId

        val argsItr = args.iterator()
        val fullArguments = functionBinding.bindings.map { it ?: argsItr.next() }

        return interpret(functionId, fullArguments)
    }

    private fun evaluateStructConstructor(structFunction: Struct, arguments: List<SemObject>): SemObject {
        if (arguments.size != structFunction.members.size) {
            throw IllegalArgumentException("Wrong number of arguments for struct constructor " + structFunction)
        }
        return SemObject.Struct(structFunction, arguments)
    }

    private fun evaluateAdapterConstructor(interfaceDef: Interface, arguments: List<SemObject>): SemObject {
        if (arguments.size != interfaceDef.methods.size) {
            throw IllegalArgumentException("Wrong number of arguments for adapter constructor " + interfaceDef.adapterId)
        }
        return SemObject.Struct(interfaceDef.adapterStruct, arguments)
    }

    private fun evaluateInterfaceConstructor(interfaceDef: Interface, arguments: List<SemObject>): SemObject {
        if (arguments.size != 2) {
            throw IllegalArgumentException("Wrong number of arguments for interface constructor " + interfaceDef.id)
        }
        val dataObject = arguments[0]
        val adapter = arguments[1] as? SemObject.Struct ?: error("Passed a non-adapter object to an instance constructor")
        val fixedBindings = adapter.objects.stream()
                .map { obj -> obj as? SemObject.FunctionBinding ?: error("Non-function binding argument for a method in an adapter") }
                .map { binding -> if (binding.bindings[0] != null) {
                        error("Was expecting a null binding for the first element")
                    } else {
                        val newBindings = ArrayList(binding.bindings)
                        newBindings[0] = dataObject
                        binding.copy(bindings = newBindings)
                    }
                }
                .collect(Collectors.toList())
        return SemObject.Instance(interfaceDef, arguments[0], fixedBindings)
    }

    private fun evaluateBlock(block: TypedBlock, initialAssignments: Map<String, SemObject>): SemObject {
        val assignments: MutableMap<String, SemObject> = HashMap(initialAssignments)
        for ((name, _, expression) in block.assignments) {
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
                val name = expression.name
                if (innerResult is SemObject.Struct) {
                    val index = innerResult.struct.getIndexForName(name)
                    return innerResult.objects[index]
                } else if (innerResult is SemObject.Instance) {
                    val index = innerResult.interfaceDef.getIndexForName(name)
                    val functionBinding = innerResult.methods[index]
                    return functionBinding
                } else if (innerResult is SemObject.UnicodeString) {
                    if (name != "value") {
                        error("The only valid member in a Unicode.String is 'value'")
                    }
                    // TODO: Cache this, or otherwise make it more efficient
                    val codePointsList = innerResult.contents.codePoints().mapToObj { value -> SemObject.Struct(NativeStruct.UNICODE_CODE_POINT, listOf(
                            SemObject.Natural(BigInteger.valueOf(value.toLong()))))}
                            .collect(Collectors.toList())
                    return SemObject.SemList(codePointsList)
                } else {
                    throw IllegalStateException("Trying to use -> on a non-struct, non-interface object")
                }
            }
            is TypedExpression.ExpressionFunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments) }
                val function = evaluateExpression(expression.functionExpression, assignments)

                if (function !is SemObject.FunctionBinding) {
                    throw IllegalArgumentException("Trying to call the result of ${expression.functionExpression} as a function, but it is not a function")
                }
                val argumentsItr = arguments.iterator()
                val inputs = function.bindings.map { it ?: argumentsItr.next() }

                return interpret(function.functionId, inputs)
            }
            is TypedExpression.NamedFunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments) }
                return interpret(expression.functionId, arguments)
            }
            is TypedExpression.Literal -> {
                return evaluateLiteral(expression.type, expression.literal)
            }
            is TypedExpression.NamedFunctionBinding -> {
                val functionId = expression.functionId
                if (context.getFunctionImplementation(functionId) == null && !nativeFunctions.containsKey(functionId)) {
                    error("Function ID not recognized: $functionId")
                }
                val bindings = expression.bindings.map { expr -> if (expr != null) evaluateExpression(expr, assignments) else null }
                return SemObject.FunctionBinding(functionId, bindings)
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                val function = evaluateExpression(expression.functionExpression, assignments)

                if (function !is SemObject.FunctionBinding) {
                    throw IllegalArgumentException("Trying to reference ${expression.functionExpression} as a function for binding, but it is not a function")
                }
                val earlierBindings = function.bindings
                val laterBindings = expression.bindings.map { expr -> if (expr != null) evaluateExpression(expr, assignments) else null }

                // The later bindings replace the underscores (null values) in the earlier bindings.
                val laterBindingsItr = laterBindings.iterator()
                val newBindings = earlierBindings.map { it ?: laterBindingsItr.next() }

                return SemObject.FunctionBinding(function.functionId, newBindings)
            }
        }
    }

    fun evaluateLiteral(type: Type, literal: String): SemObject {
        return evaluateLiteralImpl(type, literal)
    }

    fun areEqual(actualOutput: SemObject, desiredOutput: SemObject): Boolean {
        // This works for now
        return actualOutput.equals(desiredOutput)
    }
}

private fun evaluateLiteralImpl(type: Type, literal: String): SemObject {
    return when (type) {
        Type.NATURAL -> evaluateNaturalLiteral(literal)
        Type.INTEGER -> evaluateIntegerLiteral(literal)
        Type.BOOLEAN -> evaluateBooleanLiteral(literal)
        is Type.List -> throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
        is Type.Try -> evaluateTryLiteral(type, literal)
        is Type.FunctionType -> throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
        is Type.NamedType -> evaluateNamedLiteral(type, literal)
    }
}

fun evaluateNamedLiteral(type: Type.NamedType, literal: String): SemObject {
    if (type.id == NativeStruct.UNICODE_STRING.id) {
        // TODO: Check for errors related to string encodings
        return SemObject.UnicodeString(literal)
    }

    throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
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

/**
 * Note: Currently this can be used by things like @Test, but trying to invoke this directly in a
 * Semlang function will fail.
 */
private fun evaluateTryLiteral(type: Type.Try, literal: String): SemObject {
    if (literal == "failure") {
        return SemObject.Try.Failure
    }
    if (literal.startsWith("success(") && literal.endsWith(")")) {
        val innerType = type.parameter
        val innerLiteral = literal.substring("success(".length, literal.length - ")".length)
        return SemObject.Try.Success(evaluateLiteralImpl(innerType, innerLiteral))
    }
    throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
}
