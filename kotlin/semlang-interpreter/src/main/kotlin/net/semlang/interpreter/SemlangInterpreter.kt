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
        return interpret(EntityRef(mainModule.id.asRef(), functionId), arguments, mainModule)
    }

    /**
     * Note: the "referring module" is the one that tried calling the function. When the function is
     * actually evaluated, we'll need to use the "containing module", i.e. the module where that code
     * was written.
     */
    private fun interpret(functionRef: EntityRef, arguments: List<SemObject>, referringModule: ValidatedModule?): SemObject {
        try {
            if (referringModule == null) {
                return interpretNative(functionRef, arguments)
            }

            // TODO: Have one of these for native stuff, as well
            val entityResolution = referringModule.resolve(functionRef) ?: error("The function $functionRef isn't recognized")

            when (entityResolution.type) {
                FunctionLikeType.NATIVE_FUNCTION -> {
                    val nativeFunction = nativeFunctions[functionRef.id] ?: error("Native function not implemented: $functionRef")
                    return nativeFunction.apply(arguments, this::interpretBinding)
                }
                FunctionLikeType.FUNCTION -> {
                    val function: FunctionWithModule = referringModule.getInternalFunction(entityResolution.entityRef)
                    if (arguments.size != function.function.arguments.size) {
                        throw IllegalArgumentException("Wrong number of arguments for function $functionRef")
                    }
                    val variableAssignments: MutableMap<String, SemObject> = HashMap()
                    for ((value, argumentDefinition) in arguments.zip(function.function.arguments)) {
                        variableAssignments.put(argumentDefinition.name, value)
                    }
                    return evaluateBlock(function.function.block, variableAssignments, function.module)
                }
                FunctionLikeType.STRUCT_CONSTRUCTOR -> {
                    val struct: StructWithModule = referringModule.getInternalStruct(entityResolution.entityRef)
                    return evaluateStructConstructor(struct.struct, arguments, struct.module)
                }
                FunctionLikeType.INSTANCE_CONSTRUCTOR -> {
                    val interfac = referringModule.getInternalInterface(entityResolution.entityRef)
                    return evaluateInterfaceConstructor(interfac.interfac, arguments, interfac.module)
                }
                FunctionLikeType.ADAPTER_CONSTRUCTOR -> {
                    val interfac = referringModule.getInternalInterfaceByAdapterId(entityResolution.entityRef)
                    return evaluateAdapterConstructor(interfac.interfac, arguments, interfac.module)
                }
            }
        } catch (e: RuntimeException) {
            throw IllegalStateException("Error while interpreting $functionRef with arguments $arguments", e)
        }
    }

    private fun getModule(id: ModuleId, referringModule: ValidatedModule?): ValidatedModule? {
        if (referringModule != null) {
            if (id == referringModule.id) {
                return referringModule
            }
            val upstreamModule = referringModule.upstreamModules[id]
            if (upstreamModule != null) {
                return upstreamModule
            }
        }
        if (isNativeModule(id)) {
            return null
        }
        error("I don't know what to do with the module $id from the referring module $referringModule")
    }

    private fun interpretNative(ref: EntityRef, arguments: List<SemObject>): SemObject {
        // TODO: Better approach to figuring out the type of thing here (i.e. a TypeResolver just for native stuff)
        assertModuleRefConsistentWithNative(ref)

        val nativeFunction = nativeFunctions[ref.id]
        if (nativeFunction != null) {
            return nativeFunction.apply(arguments, this::interpretBinding)
        }

        val struct = getNativeStructs()[ref.id]
        if (struct != null) {
            return evaluateStructConstructor(struct, arguments, null)
        }

        // Handle instance constructors
        val interfac = getNativeInterfaces()[ref.id]
        if (interfac != null) {
            return evaluateInterfaceConstructor(interfac, arguments, null)
        }

        // Handle adapter constructors
        val adapter = getNativeInterfaces()[getInterfaceIdForAdapterId(ref.id)]
        if (adapter != null) {
            return evaluateAdapterConstructor(adapter, arguments, null)
        }
        error("Unrecognized entity $ref")
    }

    private fun assertModuleRefConsistentWithNative(entityRef: EntityRef) {
        val moduleRef = entityRef.moduleRef
        if (moduleRef != null) {
            if (moduleRef.module != NATIVE_MODULE_NAME
                    || (moduleRef.group != null && moduleRef.group != NATIVE_MODULE_GROUP)) {
                error("We're trying to evaluate ${entityRef} like a native entity, but its module ref is not consistent with the native package")
            }
        }
    }

    private fun interpretBinding(functionBinding: SemObject.FunctionBinding, args: List<SemObject>): SemObject {
        // TODO: Ideally this would be a ResolvedEntityRef, which would then give us the module?
        // (I guess ResolvedEntityRef needs to deal with the possibility of the native module)
        val target = functionBinding.target
        return when (target) {
            is FunctionBindingTarget.Named -> {
                val functionRef = EntityRef(functionBinding.containingModule?.id?.asRef(), target.functionId)
                val containingModule = functionBinding.containingModule

                val argsItr = args.iterator()
                val fullArguments = functionBinding.bindings.map { it ?: argsItr.next() }

                return interpret(functionRef, fullArguments, containingModule)
            }
            is FunctionBindingTarget.Inline -> {
                val argsItr = args.iterator()
                val fullArguments = functionBinding.bindings.map { it ?: argsItr.next() }

                val numExplicitArguments = target.functionDef.arguments.size
                val explicitArguments = fullArguments.take(numExplicitArguments)
                val implicitArguments = fullArguments.drop(numExplicitArguments)

                val variableAssignments = HashMap<String, SemObject>()
                target.functionDef.arguments.zip(explicitArguments).forEach { (argDef, argObj) ->
                    variableAssignments.put(argDef.name, argObj)
                }
                target.functionDef.boundVars.zip(implicitArguments).forEach { (argument, obj) ->
                    variableAssignments.put(argument.name, obj)
                }

                return evaluateBlock(target.functionDef.block, variableAssignments, functionBinding.containingModule)
            }
        }
    }

    private fun evaluateStructConstructor(struct: Struct, arguments: List<SemObject>, structModule: ValidatedModule?): SemObject {
        if (arguments.size != struct.members.size) {
            throw IllegalArgumentException("Wrong number of arguments for struct constructor " + struct)
        }

        if (struct.id == NativeStruct.UNICODE_STRING.id) {
            val semList = arguments[0] as? SemObject.SemList ?: error("Type error when constructing a Unicode.String")
            val codePoints: List<Int> = semList.contents.map { semObject ->
                val codePointStruct = semObject as? SemObject.Struct ?: error("Type error when constructing a Unicode.String")
                if (codePointStruct.struct.id != NativeStruct.UNICODE_CODE_POINT.id) {
                    error("Invalid struct when constructing a Unicode.String")
                }
                val semNatural = codePointStruct.objects[0] as? SemObject.Natural ?: error("Type error when constructing a Unicode.String")
                semNatural.value.intValueExact()
            }
            val asString = String(codePoints.toIntArray(), 0, codePoints.size)

            return SemObject.UnicodeString(asString)
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
        return SemObject.Struct(interfaceDef.getAdapterStruct(), arguments)
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
                val innerResult = evaluateExpression(expression.structureExpression, assignments, containingModule)
                val name = expression.name
                if (innerResult is SemObject.Struct) {
                    val index = innerResult.struct.getIndexForName(name)
                    return innerResult.objects[index]
                } else if (innerResult is SemObject.Instance) {
                    val index = innerResult.interfaceDef.getIndexForName(name)
                    val functionBinding = innerResult.methods[index]
                    return functionBinding
                } else if (innerResult is SemObject.UnicodeString) {
                    if (name != "codePoints") {
                        error("The only valid member in a Unicode.String is 'codePoints'")
                    }
                    // TODO: Cache this, or otherwise make it more efficient
                    val codePointsList = innerResult.contents.codePoints().mapToObj { value -> SemObject.Struct(NativeStruct.UNICODE_CODE_POINT, listOf(
                            SemObject.Natural(BigInteger.valueOf(value.toLong()))))}
                            .collect(Collectors.toList())
                    return SemObject.SemList(codePointsList)
                } else {
                    throw IllegalStateException("Trying to use -> on a non-struct, non-interface object $innerResult")
                }
            }
            is TypedExpression.ExpressionFunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments, containingModule) }
                val function = evaluateExpression(expression.functionExpression, assignments, containingModule)

                if (function !is SemObject.FunctionBinding) {
                    throw IllegalArgumentException("Trying to call the result of ${expression.functionExpression} as a function, but it is not a function")
                }

                return interpretBinding(function, arguments)
            }
            is TypedExpression.NamedFunctionCall -> {
                val arguments = expression.arguments.map { argExpr -> evaluateExpression(argExpr, assignments, containingModule) }
                return interpret(expression.functionRef, arguments, containingModule)
            }
            is TypedExpression.Literal -> {
                return evaluateLiteral(expression.type, expression.literal)
            }
            is TypedExpression.ListLiteral -> {
                val contents = expression.contents.map { itemExpr -> evaluateExpression(itemExpr, assignments, containingModule) }
                return SemObject.SemList(contents)
            }
            is TypedExpression.NamedFunctionBinding -> {
                val functionRef = expression.functionRef
                val bindings = expression.bindings.map { expr -> if (expr != null) evaluateExpression(expr, assignments, containingModule) else null }
                return if (containingModule != null) {
                    val resolved = containingModule.resolve(functionRef) ?: error("Invalid function reference: $functionRef")
                    val module = getModule(resolved.entityRef.module, containingModule)
                    return SemObject.FunctionBinding(FunctionBindingTarget.Named(functionRef.id), module, bindings)
                } else {
                    // TODO: Use a resolver for natives so this can also handle constructors
                    EntityResolution(ResolvedEntityRef(CURRENT_NATIVE_MODULE_ID, functionRef.id), FunctionLikeType.NATIVE_FUNCTION)
                    SemObject.FunctionBinding(FunctionBindingTarget.Named(functionRef.id), null, bindings)
                }
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

                return SemObject.FunctionBinding(function.target, function.containingModule, newBindings)
            }
            is TypedExpression.InlineFunction -> {
                val explicitBindings = ArrayList<SemObject?>()
                for (argument in expression.arguments) {
                    explicitBindings.add(null)
                }
                val implicitBindings = expression.boundVars.map { (varName, varType) ->
                    val varValue = assignments[varName] ?: error("No value for variable-to-bind $varName is available")
                    varValue
                }

                val bindings = explicitBindings + implicitBindings
                SemObject.FunctionBinding(FunctionBindingTarget.Inline(expression), containingModule, bindings)
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
        if (type.ref.moduleRef == null && type.ref.id == NativeStruct.UNICODE_STRING.id) {
            // TODO: Check for errors related to string encodings
            return evaluateStringLiteral(literal)
        }

        val resolved = this.mainModule.resolve(type.ref) ?: error("Unhandled literal \"$literal\" of type $type")
        if (resolved.type == FunctionLikeType.STRUCT_CONSTRUCTOR) {
            val struct = this.mainModule.getInternalStruct(resolved.entityRef)

            if (struct.struct.members.size != 1) {
                error("Unhandled literal \"$literal\" of type $type")
            }
            val member = struct.struct.members.single()
            val memberValue = evaluateLiteralImpl(member.type, literal)
            val requiresBlock = struct.struct.requires
            if (requiresBlock != null) {
                val initialAssignments = mapOf(member.name to memberValue)
                val validationResult = evaluateBlock(requiresBlock, initialAssignments, struct.module) as? SemObject.Boolean ?: error("Type error")
                if (!validationResult.value) {
                    error("Literal value does not satisfy the requires block of type $resolved: \"$literal\"")
                }
            }
            return SemObject.Struct(struct.struct, listOf(memberValue))
        }

        throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type; resolved as $resolved")
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
}

fun evaluateStringLiteral(literal: String): SemObject.UnicodeString {
    return SemObject.UnicodeString(literal)
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
