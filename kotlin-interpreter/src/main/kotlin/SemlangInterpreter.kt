package net.semlang.interpreter

import net.semlang.api.*
import java.math.BigInteger
import java.util.*
import java.util.stream.Collectors

interface SemlangInterpreter {
    fun interpret(functionId: EntityId, arguments: List<SemObject>): SemObject
}

class SemlangForwardInterpreter(val mainModule: ValidatedModule): SemlangInterpreter {
    private val nativeFunctions: Map<EntityId, NativeFunction> = getNativeFunctions()

    override fun interpret(functionId: EntityId, arguments: List<SemObject>): SemObject {
        return interpret(EntityRef(null, functionId), arguments, mainModule)
    }

    /**
     * Note: the "referring module" is the one that tried calling the function. When the function is
     * actually evaluated, we'll need to use the "containing module", i.e. the module where that code
     * was written.
     */
    private fun interpret(functionRef: EntityRef, arguments: List<SemObject>, referringModule: ValidatedModule?): SemObject {
        // Handle "native" functions
        if (functionRef.moduleRef == null) {
            val nativeFunction = nativeFunctions[functionRef.id]
            if (nativeFunction != null) {
                return nativeFunction.apply(arguments, this::interpretBinding)
            }
        }

        if (referringModule == null) {
            error("We're in the native module, but $functionRef isn't a recognized native function")
        }

        // Handle struct constructors
        val struct: StructWithModule? = referringModule.getInternalStruct(functionRef) ?: getNativeStruct(functionRef)
        if (struct != null) {
            // TODO: These might just be able to accept the StructWithModule
            return evaluateStructConstructor(struct.struct, arguments, struct.module)
        }

        // Handle adapter constructors
        val adapter: InterfaceWithModule? = referringModule.getInternalInterfaceByAdapterId(functionRef) ?: getNativeInterfaceByAdapterId(functionRef)
        if (adapter != null) {
            return evaluateAdapterConstructor(adapter.interfac, arguments, adapter.module)
        }

        // Handle instance constructors
        val interfac: InterfaceWithModule? = referringModule.getInternalInterface(functionRef) ?: getNativeInterface(functionRef)
        if (interfac != null) {
            return evaluateInterfaceConstructor(interfac.interfac, arguments, interfac.module)
        }

        // Handle non-native functions
        val function: FunctionWithModule = referringModule.getInternalFunction(functionRef) ?: throw IllegalArgumentException("Unrecognized function ID $functionRef")
        if (arguments.size != function.function.arguments.size) {
            throw IllegalArgumentException("Wrong number of arguments for function $functionRef")
        }
        val variableAssignments: MutableMap<String, SemObject> = HashMap()
        for ((value, argumentDefinition) in arguments.zip(function.function.arguments)) {
            variableAssignments.put(argumentDefinition.name, value)
        }
        return evaluateBlock(function.function.block, variableAssignments, function.module)
    }

    private fun getNativeStruct(ref: EntityRef): StructWithModule? {
        if (ref.moduleRef == null) {
            val struct = getNativeStructs()[ref.id]
            if (struct != null) {
                return StructWithModule(struct, null)
            }
        }
        return null
    }

    private fun getNativeInterface(ref: EntityRef): InterfaceWithModule? {
        if (ref.moduleRef == null) {
            val interfac = getNativeInterfaces()[ref.id]
            if (interfac != null) {
                return InterfaceWithModule(interfac, null)
            }
        }
        return null
    }

    private fun getNativeInterfaceByAdapterId(adapterRef: EntityRef): InterfaceWithModule? {
        if (adapterRef.moduleRef == null) {
            val interfaceRef = getInterfaceRefForAdapterRef(adapterRef)
            if (interfaceRef != null) {
                return getNativeInterface(interfaceRef)
            }
        }
        return null
    }

    private fun interpretBinding(functionBinding: SemObject.FunctionBinding, args: List<SemObject>): SemObject {
        // TODO: Ideally this would be a ResolvedEntityRef, which would then give us the module?
        // (I guess ResolvedEntityRef needs to deal with the possibility of the native module)
        val functionRef = functionBinding.getFunctionRef()
        val containingModule = functionBinding.containingModule

        val argsItr = args.iterator()
        val fullArguments = functionBinding.bindings.map { it ?: argsItr.next() }

        return interpret(functionRef, fullArguments, containingModule)
    }

    private fun evaluateStructConstructor(struct: Struct, arguments: List<SemObject>, structModule: ValidatedModule?): SemObject {
        if (arguments.size != struct.members.size) {
            throw IllegalArgumentException("Wrong number of arguments for struct constructor " + struct)
        }
        val requiresBlock = struct.requires
        if (requiresBlock == null) {
            return SemObject.Struct(struct, arguments)
        } else {
            // Check if it meets the "requires" condition
            val variableAssignments = struct.members.map(Member::name).zip(arguments).toMap()
            val success = evaluateBlock(requiresBlock, variableAssignments, structModule) as? SemObject.Boolean ?: error("Non-boolean output of a requires block at runtime")
            if (success.value) {
                return SemObject.Try.Success(SemObject.Struct(struct, arguments))
            } else {
                return SemObject.Try.Failure
            }
        }
    }

    private fun evaluateAdapterConstructor(interfaceDef: Interface, arguments: List<SemObject>, interfaceModule: ValidatedModule?): SemObject {
        if (arguments.size != interfaceDef.methods.size) {
            throw IllegalArgumentException("Wrong number of arguments for adapter constructor " + interfaceDef.adapterId)
        }
        return SemObject.Struct(interfaceDef.adapterStruct, arguments)
    }

    private fun evaluateInterfaceConstructor(interfaceDef: Interface, arguments: List<SemObject>, interfaceModule: ValidatedModule?): SemObject {
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

    private fun evaluateBlock(block: TypedBlock, initialAssignments: Map<String, SemObject>, containingModule: ValidatedModule?): SemObject {
        val assignments: MutableMap<String, SemObject> = HashMap(initialAssignments)
        for ((name, _, expression) in block.assignments) {
            val value = evaluateExpression(expression, assignments, containingModule)
            if (assignments.containsKey(name)) {
                throw IllegalStateException("Tried to double-assign variable $name")
            }
            assignments.put(name, value)
        }
        return evaluateExpression(block.returnedExpression, assignments, containingModule)
    }

    private fun evaluateExpression(expression: TypedExpression, assignments: Map<String, SemObject>, containingModule: ValidatedModule?): SemObject {
        return when (expression) {
            is TypedExpression.Variable -> assignments[expression.name] ?: throw IllegalArgumentException("No variable defined with name ${expression.name}")
            is TypedExpression.IfThen -> {
                val condition = evaluateExpression(expression.condition, assignments, containingModule)
                if (condition is SemObject.Boolean) {
                    return if (condition.value) {
                        evaluateBlock(expression.thenBlock, assignments, containingModule)
                    } else {
                        evaluateBlock(expression.elseBlock, assignments, containingModule)
                    }
                } else {
                    throw IllegalStateException("Condition block in if-then is not a boolean value")
                }
            }
            is TypedExpression.Follow -> {
                val innerResult = evaluateExpression(expression.expression, assignments, containingModule)
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
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments, containingModule) }
                val function = evaluateExpression(expression.functionExpression, assignments, containingModule)

                if (function !is SemObject.FunctionBinding) {
                    throw IllegalArgumentException("Trying to call the result of ${expression.functionExpression} as a function, but it is not a function")
                }
                val argumentsItr = arguments.iterator()
                val inputs = function.bindings.map { it ?: argumentsItr.next() }

                return interpret(function.getFunctionRef(), inputs, function.containingModule)
            }
            is TypedExpression.NamedFunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments, containingModule) }
                return interpret(expression.functionRef, arguments, containingModule)
            }
            is TypedExpression.Literal -> {
                return evaluateLiteral(expression.type, expression.literal)
            }
            is TypedExpression.NamedFunctionBinding -> {
                val functionRef = expression.functionRef
                val functionSignature = containingModule?.getInternalFunctionSignature(functionRef) ?:
                        if (functionRef.moduleRef == null) {
                            getNativeFunctionDefinitions()[functionRef.id]?.let { FunctionSignatureWithModule(it, null) }
                        } else {
                            null
                        }
                if (functionSignature == null) {
                    error("Function ID not recognized: $functionRef")
                }
                val bindings = expression.bindings.map { expr -> if (expr != null) evaluateExpression(expr, assignments, containingModule) else null }
                return SemObject.FunctionBinding(functionRef.id, functionSignature.module, bindings)
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                val function = evaluateExpression(expression.functionExpression, assignments, containingModule)

                if (function !is SemObject.FunctionBinding) {
                    throw IllegalArgumentException("Trying to reference ${expression.functionExpression} as a function for binding, but it is not a function")
                }
                val earlierBindings = function.bindings
                val laterBindings = expression.bindings.map { expr -> if (expr != null) evaluateExpression(expr, assignments, containingModule) else null }

                // The later bindings replace the underscores (null values) in the earlier bindings.
                val laterBindingsItr = laterBindings.iterator()
                val newBindings = earlierBindings.map { it ?: laterBindingsItr.next() }

                return SemObject.FunctionBinding(function.functionId, function.containingModule, newBindings)
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

private fun evaluateNamedLiteral(type: Type.NamedType, literal: String): SemObject {
    if (type.id.moduleRef == null && type.id.id == NativeStruct.UNICODE_STRING.id) {
        // TODO: Check for errors related to string encodings
        return evaluateStringLiteral(literal)
    }

    throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
}

fun evaluateStringLiteral(literal: String): SemObject.UnicodeString {
    val sb = StringBuilder()
    var i = 0
    while (i < literal.length) {
        val c = literal[i]
        if (c == '\\') {
            if (i + 1 >= literal.length) {
                error("Something went wrong with string literal evaluation")
            }
            sb.append(literal[i + 1])
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return SemObject.UnicodeString(sb.toString())
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
