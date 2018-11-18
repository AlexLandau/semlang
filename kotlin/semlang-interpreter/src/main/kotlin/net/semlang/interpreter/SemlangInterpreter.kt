package net.semlang.interpreter

import net.semlang.api.*
import java.io.PrintStream
import java.math.BigInteger
import java.util.*
import java.util.stream.Collectors

interface SemlangInterpreter {
    fun interpret(functionId: EntityId, arguments: List<SemObject>): SemObject
}

data class InterpreterOptions(val useLibraryOptimizations: Boolean = true, val runCountOutput: PrintStream? = null, val mockCalls: Map<EntityId, Map<List<SemObject>, SemObject>> = mapOf()) {
    val collectRunCounts = runCountOutput != null
}

class SemlangForwardInterpreter(val mainModule: ValidatedModule, val options: InterpreterOptions): SemlangInterpreter {
    private val nativeFunctions: Map<EntityId, NativeFunction> = getNativeFunctions()
    private val otherOptimizedFunctions: Map<ResolvedEntityRef, NativeFunction> =
            if (options.useLibraryOptimizations)
                getOptimizedFunctions(mainModule)
            else
                mapOf()
    private val otherOptimizedStructConstructors: Map<ResolvedEntityRef, NativeFunction> =
            if (options.useLibraryOptimizations)
                getOptimizedStructConstructors(mainModule)
            else
                mapOf()
    private val otherOptimizedStructLiteralParsers: Map<ResolvedEntityRef, (String) -> SemObject> =
            if (options.useLibraryOptimizations)
                getOptimizedStructLiteralParsers(mainModule)
            else
                mapOf()

    private val runCounts = HashMap<ResolvedEntityRef, Long>()

    override fun interpret(functionId: EntityId, arguments: List<SemObject>): SemObject {
        val result = interpret(ResolvedEntityRef(mainModule.id, functionId), arguments, mainModule)
        if (options.runCountOutput != null) {
            options.runCountOutput.println("Run counts:")
            for (key in runCounts.keys) {
                options.runCountOutput.println("$key: ${runCounts[key]}")
            }
        }
        return result
    }

    /**
     * Note: the "referring module" is the one that tried calling the function. When the function is
     * actually evaluated, we'll need to use the "containing module", i.e. the module where that code
     * was written.
     */
    private fun interpret(functionRef: ResolvedEntityRef, arguments: List<SemObject>, referringModule: ValidatedModule?): SemObject {
        try {
            if (options.collectRunCounts) {
                addToRunCount(functionRef)
            }
            if (referringModule == null) {
                return interpretNative(functionRef, arguments)
            }

            // TODO: Have one of these for native stuff, as well
            // TODO: We already have the reference here but not the FunctionLikeType. Should we store that in language objects as well?
            val entityResolution = referringModule.resolve(functionRef) ?: error("The function $functionRef isn't recognized")

            when (entityResolution.type) {
                FunctionLikeType.NATIVE_FUNCTION -> {
                    val mockSpec = options.mockCalls[entityResolution.entityRef.id]
                    if (mockSpec != null) {
                        return doMockCall(mockSpec, arguments)
                    }
                    val nativeFunction = nativeFunctions[functionRef.id] ?: error("Native function not implemented: $functionRef")
                    return nativeFunction.apply(arguments, this::interpretBinding)
                }
                FunctionLikeType.FUNCTION -> {
                    // TODO: Check for standard library functions
                    val optimizedImpl = otherOptimizedFunctions[entityResolution.entityRef]
                    if (optimizedImpl != null) {
                        return optimizedImpl.apply(arguments, this::interpretBinding)
                    }

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
                    val optimizedImpl = otherOptimizedStructConstructors[entityResolution.entityRef]
                    if (optimizedImpl != null) {
                        return optimizedImpl.apply(arguments, this::interpretBinding)
                    }

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
                FunctionLikeType.UNION_TYPE -> {
                    error("Tried to use a union type as a function: $entityResolution")
                }
                FunctionLikeType.UNION_OPTION_CONSTRUCTOR -> {
                    val unionId = EntityId(entityResolution.entityRef.id.namespacedName.dropLast(1))
                    val unionRef = entityResolution.entityRef.copy(id = unionId)
                    val union = referringModule.getInternalUnion(unionRef)
                    return evaluateUnionOptionConstructor(union.union, entityResolution.entityRef.id, arguments, union.module)
                }
                FunctionLikeType.UNION_WHEN_FUNCTION -> {
                    val unionId = EntityId(entityResolution.entityRef.id.namespacedName.dropLast(1))
                    val unionRef = entityResolution.entityRef.copy(id = unionId)
                    val union = referringModule.getInternalUnion(unionRef)
                    return evaluateWhenFunction(union.union, arguments)
                }
                FunctionLikeType.OPAQUE_TYPE -> {
                    error("Tried to use an opaque type as a function: $entityResolution")
                }
            }
        } catch (e: RuntimeException) {
            throw IllegalStateException("Error while interpreting $functionRef with arguments $arguments", e)
        }
    }

    private fun doMockCall(mockSpec: Map<List<SemObject>, SemObject>, arguments: List<SemObject>): SemObject {
        val thisCase = mockSpec[arguments]
        if (thisCase == null) {
            // TODO: Add function name
            error("No mock call was provided for this function for arguments $arguments")
        }
        return thisCase
    }

    private fun addToRunCount(functionRef: ResolvedEntityRef) {
        val curCount = runCounts[functionRef]
        if (curCount != null) {
            runCounts[functionRef] = curCount + 1
        } else {
            runCounts[functionRef] = 1
        }
    }

    private fun getModule(id: ModuleUniqueId, referringModule: ValidatedModule?): ValidatedModule? {
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

    private fun interpretNative(ref: ResolvedEntityRef, arguments: List<SemObject>): SemObject {
        // TODO: Better approach to figuring out the type of thing here (i.e. a TypeResolver just for native stuff)
        assertModuleRefConsistentWithNative(ref)

        val mockSpec = options.mockCalls[ref.id]
        if (mockSpec != null) {
            return doMockCall(mockSpec, arguments)
        }

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

    private fun assertModuleRefConsistentWithNative(entityRef: ResolvedEntityRef) {
        val moduleId = entityRef.module
        if (!isNativeModule(moduleId)) {
            error("We're trying to evaluate ${entityRef} like a native entity, but its module ref is not consistent with the native package")
        }
    }

    private fun interpretBinding(functionBinding: SemObject.FunctionBinding, args: List<SemObject>): SemObject {
        val target = functionBinding.target
        return when (target) {
            is FunctionBindingTarget.Named -> {
                val containingModule = functionBinding.containingModule

                val argsItr = args.iterator()
                val fullArguments = functionBinding.bindings.map { it ?: argsItr.next() }

                return interpret(target.functionRef, fullArguments, containingModule)
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
            is FunctionBindingTarget.InterfaceAdapter -> {
                val dataObject = args.single()

                val reboundBindings = functionBinding.bindings.map { adapterArgumentOrNull ->
                    val adapterArgument = adapterArgumentOrNull as? SemObject.FunctionBinding ?: error("Arguments to interface adapters should be function bindings")
                    val bindingsWithDataObject = adapterArgument.bindings.replaceFirstNullWith(dataObject)
                    adapterArgument.copy(bindings = bindingsWithDataObject)
                }

                return SemObject.Instance(target.interfac, reboundBindings)
            }
        }
    }

    private fun evaluateStructConstructor(struct: Struct, arguments: List<SemObject>, structModule: ValidatedModule?): SemObject {
        if (arguments.size != struct.members.size) {
            throw IllegalArgumentException("Wrong number of arguments for struct constructor " + struct)
        }

        if (struct.id == NativeStruct.NATURAL.id) {
            val semInteger = arguments[0] as? SemObject.Integer ?: error("Type error when constructing a Natural")
            val value = semInteger.value
            if (value >= BigInteger.ZERO) {
                return SemObject.Maybe.Success(SemObject.Natural(semInteger.value))
            } else {
                return SemObject.Maybe.Failure
            }
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
                return SemObject.Maybe.Success(SemObject.Struct(struct, arguments))
            } else {
                return SemObject.Maybe.Failure
            }
        }
    }

    private fun evaluateAdapterConstructor(interfaceDef: Interface, arguments: List<SemObject>, interfaceModule: ValidatedModule?): SemObject {
        if (arguments.size != interfaceDef.methods.size) {
            throw IllegalArgumentException("Wrong number of arguments for adapter constructor " + interfaceDef.adapterId)
        }

        val target = FunctionBindingTarget.InterfaceAdapter(interfaceDef)
        return SemObject.FunctionBinding(target, interfaceModule, arguments)
    }

    private fun evaluateInterfaceConstructor(interfaceDef: Interface, arguments: List<SemObject>, interfaceModule: ValidatedModule?): SemObject {
        val bindings = arguments.map { it as? SemObject.FunctionBinding ?: error("Every argument to an interface constructor must be a function binding") }
        return SemObject.Instance(interfaceDef, bindings)
    }

    private fun evaluateUnionOptionConstructor(union: Union, functionId: EntityId, arguments: List<SemObject>, module: ValidatedModule?): SemObject {
        val optionIndex = union.getOptionIndexById(functionId) ?: error("Bad combination of union ${union.id} and functionId ${functionId}")
        val option = union.options[optionIndex]
        if (option.type == null) {
            if (arguments.isNotEmpty()) {
                error("Expected no arguments for a union option with no type")
            }
        } else {
            if (arguments.size != 1) {
                error("Expected one argument for a typed union option")
            }
        }
        val objectMaybe = if (arguments.isEmpty()) null else arguments[0]
        return SemObject.Union(union, optionIndex, objectMaybe)
    }

    private fun evaluateWhenFunction(union: Union, arguments: List<SemObject>): SemObject {
        val unionObject = arguments[0] as? SemObject.Union ?: error("Wrong type in first argument of a union's when-function")
        val index = unionObject.optionIndex
        val appropriateFunctionBinding = arguments[index + 1] as? SemObject.FunctionBinding ?: error("Wrong type in function argument of a union's when-function")
        val bindingArgs = if (unionObject.contents == null) {
            listOf()
        } else {
            listOf(unionObject.contents)
        }
        return this.interpretBinding(appropriateFunctionBinding, bindingArgs)
    }

    private fun evaluateBlock(block: TypedBlock, initialAssignments: Map<String, SemObject>, containingModule: ValidatedModule?): SemObject {
        val assignments: MutableMap<String, SemObject> = HashMap(initialAssignments)
        for ((name, _, expression) in block.statements) {
            val value = evaluateExpression(expression, assignments, containingModule)
            if (assignments.containsKey(name)) {
                throw IllegalStateException("Tried to double-assign variable $name")
            }
            if (name != null) {
                assignments.put(name, value)
            }
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
                    return innerResult.methods[index]
                } else if (innerResult is SemObject.Natural) {
                    if (name != "integer") {
                        error("The only valid member in a Natural is 'integer'")
                    }
                    return SemObject.Integer(innerResult.value)
                } else if (innerResult is SemObject.UnicodeString) {
                    if (name != "codePoints") {
                        error("The only valid member in a Unicode.String is 'codePoints'")
                    }
                    // TODO: Cache this, or otherwise make it more efficient
                    val codePointsList = innerResult.contents.codePoints().mapToObj { value ->
                        SemObject.Struct(NativeStruct.UNICODE_CODE_POINT, listOf(
                                SemObject.Natural(BigInteger.valueOf(value.toLong()))))
                    }
                            .collect(Collectors.toList())
                    return SemObject.SemList(codePointsList)
                } else if (innerResult is SemObject.Int64) {
                    if (name != "integer") {
                        error("The only valid member of an Int64 is 'integer'")
                    }
                    return SemObject.Integer(BigInteger.valueOf(innerResult.value))
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
                return interpret(expression.resolvedFunctionRef, arguments, containingModule)
            }
            is TypedExpression.Literal -> {
                return evaluateLiteral(expression.type, expression.literal)
            }
            is TypedExpression.ListLiteral -> {
                val contents = expression.contents.map { itemExpr -> evaluateExpression(itemExpr, assignments, containingModule) }
                return SemObject.SemList(contents)
            }
            is TypedExpression.NamedFunctionBinding -> {
                val functionRef = expression.resolvedFunctionRef
                val bindings = expression.bindings.map { expr -> if (expr != null) evaluateExpression(expr, assignments, containingModule) else null }
                return if (containingModule != null) {
                    val module = getModule(functionRef.module, containingModule)
                    return SemObject.FunctionBinding(FunctionBindingTarget.Named(functionRef), module, bindings)
                } else {
                    // TODO: Use a resolver for natives so this can also handle constructors
                    SemObject.FunctionBinding(FunctionBindingTarget.Named(functionRef), null, bindings)
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
            Type.INTEGER -> evaluateIntegerLiteral(literal)
            Type.BOOLEAN -> evaluateBooleanLiteral(literal)
            is Type.List -> evaluateComplexLiteral(type, literal)
            is Type.Maybe -> evaluateMaybeLiteral(type, literal)
            is Type.FunctionType -> throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
            is Type.NamedType -> evaluateNamedLiteral(type, literal)
            is Type.ParameterType -> throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
            is Type.InternalParameterType -> throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
        }
    }

    private fun evaluateComplexLiteral(type: Type, literal: String): SemObject {
        val parseResult = parseComplexLiteral(literal)
        return when (parseResult) {
            is ComplexLiteralParsingResult.Failure -> {
                throw IllegalArgumentException("Complex literal parsing failed for literal $literal: ${parseResult.errorMessage}")
            }
            is ComplexLiteralParsingResult.Success -> {
                evaluateComplexLiteralNode(type, parseResult.node)
            }
        }
    }

    private fun evaluateComplexLiteralNode(type: Type, node: ComplexLiteralNode): SemObject {
        return when (type) {
            Type.INTEGER -> {
                if (node !is ComplexLiteralNode.Literal) {
                    error("Integers are expected to be simple literals")
                }
                evaluateIntegerLiteral(node.contents)
            }
            Type.BOOLEAN -> {
                if (node !is ComplexLiteralNode.Literal) {
                    error("Booleans are expected to be simple literals")
                }
                evaluateBooleanLiteral(node.contents)
            }
            is Type.List -> {
                if (node !is ComplexLiteralNode.SquareList) {
                    error("Lists are expected to be in square brackets")
                }
                val contents = node.contents.map { evaluateComplexLiteralNode(type.parameter, it) }
                SemObject.SemList(contents)
            }
            is Type.Maybe -> TODO()
            is Type.FunctionType -> TODO()
            is Type.ParameterType -> TODO()
            is Type.InternalParameterType -> TODO()
            is Type.NamedType -> {
                // Special cases for special SemObjects...
                if (isNativeModule(type.ref.module)) {
                    if (type.ref.id == NativeStruct.NATURAL.id) {
                        val literal = (node as? ComplexLiteralNode.Literal ?: error("Type error")).contents
                        return evaluateNaturalLiteral(literal)
                    }
                    if (type.ref.id == NativeStruct.UNICODE_STRING.id) {
                        val literal = (node as? ComplexLiteralNode.Literal ?: error("Type error")).contents
                        return evaluateStringLiteral(literal)
                    }
                }

                val nameResolution = mainModule.resolve(type.ref) ?: error("No resolution found for ${type.ref}")
                if (nameResolution.type == FunctionLikeType.STRUCT_CONSTRUCTOR) {
                    //Handle structs
                    val (struct, structModule) = mainModule.getInternalStruct(type.ref)
                    if (struct.members.size == 1) {
                        // When there's a single member, we use its literal representation directly
                        val member = struct.members.single()
                        val memberValue = evaluateComplexLiteralNode(member.type, node)
                        val requiresBlock = struct.requires
                        if (requiresBlock != null) {
                            val initialAssignments = mapOf(member.name to memberValue)
                            val validationResult = evaluateBlock(requiresBlock, initialAssignments, structModule) as? SemObject.Boolean ?: error("Type error")
                            if (!validationResult.value) {
                                error("Literal value does not satisfy the requires block of type ${struct.id}: \"$node\"")
                            }
                        }
                        return SemObject.Struct(struct, listOf(memberValue))
                    } else {
                        TODO()
                    }
                } else {
                    TODO()
                }
            }
        }
    }

    private fun evaluateNamedLiteral(type: Type.NamedType, literal: String): SemObject {
        if (isNativeModule(type.ref.module) && type.ref.id == NativeStruct.NATURAL.id) {
            return evaluateNaturalLiteral(literal)
        }
        if (isNativeModule(type.ref.module) && type.ref.id == NativeStruct.UNICODE_STRING.id) {
            // TODO: Check for errors related to string encodings
            return evaluateStringLiteral(literal)
        }

        val resolved = this.mainModule.resolve(type.ref) ?: error("Unhandled literal \"$literal\" of type $type")
        if (resolved.type == FunctionLikeType.STRUCT_CONSTRUCTOR) {
            if (options.useLibraryOptimizations) {
                val optimizedImpl = otherOptimizedStructLiteralParsers[resolved.entityRef]
                if (optimizedImpl != null) {
                    return optimizedImpl(literal)
                }
            }


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
    private fun evaluateMaybeLiteral(type: Type.Maybe, literal: String): SemObject {
        if (literal == "failure") {
            return SemObject.Maybe.Failure
        }
        if (literal.startsWith("success(") && literal.endsWith(")")) {
            val innerType = type.parameter
            val innerLiteral = literal.substring("success(".length, literal.length - ")".length)
            return SemObject.Maybe.Success(evaluateLiteralImpl(innerType, innerLiteral))
        }
        throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
    }
}

private fun <E> List<E?>.replaceFirstNullWith(replacement: E): List<E?> {
    val newList = this.toMutableList()
    val firstNullIndex = newList.indexOf(null)
    if (firstNullIndex < 0) {
        error("Expected a null element in $this")
    }
    newList.set(firstNullIndex, replacement)
    return newList
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
