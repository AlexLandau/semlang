package net.semlang.parser

import net.semlang.api.*
import net.semlang.api.Function
import java.util.*

// TODO: Probably need several overloads of this, and Positions stored in more places
private fun fail(expression: Expression, text: String): Nothing {
    val position = expression.position
    val fullMessage = if (position != null) {
        "Validation error, ${position.lineNumber}:${position.column}: " + text
    } else {
        "Validation error: " + text
    }
    error(fullMessage)
}

// TODO: Consider removing in favor of versions taking positions
private fun fail(e: Exception, text: String): Nothing {
    throw IllegalStateException(text, e)
}

// TODO: Remove in favor of versions taking positions
private fun fail(text: String): Nothing {
    val fullMessage = "Validation error, position not recorded: " + text
    error(fullMessage)
}

data class GroundedTypeSignature(val id: EntityId, val argumentTypes: List<Type>, val outputType: Type)

fun validateModule(context: RawContext, moduleId: ModuleId, upstreamModules: List<ValidatedModule>): ValidatedModule {
    val typeInfo = collectTypeInfo(context, moduleId, upstreamModules)

    val ownFunctions = validateFunctions(context.functions, typeInfo)
    val ownStructs = validateStructs(context.structs, typeInfo)
    val ownInterfaces = validateInterfaces(context.interfaces, typeInfo)
    return ValidatedModule.create(moduleId, ownFunctions, ownStructs, ownInterfaces, upstreamModules)
}

private fun collectTypeInfo(context: RawContext, moduleId: ModuleId, upstreamModules: List<ValidatedModule>): AllTypeInfo {
    val resolver = TypeResolver.create(moduleId,
            context.functions.map(Function::id),
            context.structs.map(UnvalidatedStruct::id),
            context.interfaces.map(Interface::id),
            upstreamModules)
    val functionTypeSignatures = getFunctionTypeSignatures(context, upstreamModules)
    val allFunctionTypeSignatures = getAllFunctionTypeSignatures(functionTypeSignatures, moduleId, upstreamModules)
    val allStructs = getAllStructsInfo(context.structs, moduleId, upstreamModules)
    val allInterfaces = getAllInterfacesInfo(context.interfaces, moduleId, upstreamModules)

    return AllTypeInfo(resolver, allFunctionTypeSignatures, allStructs, allInterfaces)
}

private fun validateFunctions(functions: List<Function>, typeInfo: AllTypeInfo): Map<EntityId, ValidatedFunction> {
    val validatedFunctions = HashMap<EntityId, ValidatedFunction>()
    functions.forEach { function ->
        if (validatedFunctions.containsKey(function.id)) {
            error("Duplicate function ID ${function.id}")
        }
        val validatedFunction = validateFunction(function, typeInfo)
        validatedFunctions.put(function.id, validatedFunction)
    }
    return validatedFunctions
}

private fun validateFunction(function: Function, typeInfo: AllTypeInfo): ValidatedFunction {
    //TODO: Validate that no two arguments have the same name
    //TODO: Validate that type parameters don't share a name with something important
    val variableTypes = getArgumentVariableTypes(function.arguments)
    val block = validateBlock(function.block, variableTypes, typeInfo, function.id)
    if (function.returnType != block.type) {
        fail("Function ${function.id}'s stated return type ${function.returnType} does " +
                "not match the block's actual return type ${block.type}")
    } else {
        return ValidatedFunction(function.id, function.typeParameters, function.arguments, function.returnType, block, function.annotations)
    }
}

private fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
    return arguments.associate { argument -> Pair(argument.name, argument.type) }
}


private data class StructTypeInfo(val typeParameters: List<String>, val members: Map<String, Member>, val usesRequires: Boolean)
private data class InterfaceTypeInfo(val typeParameters: List<String>, val methods: Map<String, Method>)

private data class AllTypeInfo(val resolver: TypeResolver, val allFunctionTypeSignatures: Map<ResolvedEntityRef, TypeSignature>, val structs: Map<ResolvedEntityRef, StructTypeInfo>, val interfaces: Map<ResolvedEntityRef, InterfaceTypeInfo>)

private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedBlock {
    val variableTypes = HashMap(externalVariableTypes)
    val validatedAssignments = ArrayList<ValidatedAssignment>()
    block.assignments.forEach { assignment ->
        if (variableTypes.containsKey(assignment.name)) {
            fail("In function $containingFunctionId, there is a reassignment of the already-assigned variable ${assignment.name}")
        }
        if (isInvalidVariableName(assignment.name, typeInfo)) {
            fail("In function $containingFunctionId, there is an invalid variable name ${assignment.name}")
        }

        val validatedExpression = validateExpression(assignment.expression, variableTypes, typeInfo, containingFunctionId)
        if (assignment.type != null && (validatedExpression.type != assignment.type)) {
            fail("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type ${assignment.type}, " +
                    "but the expression given has actual type ${validatedExpression.type}")
        }

        validatedAssignments.add(ValidatedAssignment(assignment.name, validatedExpression.type, validatedExpression))
        variableTypes.put(assignment.name, validatedExpression.type)
    }
    val returnedExpression = validateExpression(block.returnedExpression, variableTypes, typeInfo, containingFunctionId)
    return TypedBlock(returnedExpression.type, validatedAssignments, returnedExpression)
}

//TODO: Construct this more sensibly from more centralized lists
private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Natural", "Boolean", "function", "let", "return", "if", "else", "struct", "requires")

private fun isInvalidVariableName(name: String, typeInfo: AllTypeInfo): Boolean {
    val nameAsEntityRef = EntityId.of(name).asRef()
    return typeInfo.resolver.resolveFunction(nameAsEntityRef) != null
        || typeInfo.resolver.resolveStruct(nameAsEntityRef) != null
        || typeInfo.resolver.resolveInterface(nameAsEntityRef) != null
        || INVALID_VARIABLE_NAMES.contains(name)
}

private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
    return when (expression) {
        is Expression.Variable -> validateVariableExpression(expression, variableTypes, containingFunctionId)
        is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, typeInfo, containingFunctionId)
        is Expression.Follow -> validateFollowExpression(expression, variableTypes, typeInfo, containingFunctionId)
        is Expression.NamedFunctionCall -> validateNamedFunctionCallExpression(expression, variableTypes, typeInfo, containingFunctionId)
        is Expression.ExpressionFunctionCall -> validateExpressionFunctionCallExpression(expression, variableTypes, typeInfo, containingFunctionId)

        is Expression.Literal -> validateLiteralExpression(expression)
        is Expression.NamedFunctionBinding -> validateNamedFunctionBinding(expression, variableTypes, typeInfo, containingFunctionId)
        is Expression.ExpressionFunctionBinding -> validateExpressionFunctionBinding(expression, variableTypes, typeInfo, containingFunctionId)
    }
}

private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
    val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, containingFunctionId)

    val functionType = functionExpression.type as? Type.FunctionType ?: fail("The function $containingFunctionId tries to call $functionExpression like a function, but it has a non-function type ${functionExpression.type}")

    val preBindingArgumentTypes = functionType.argTypes

    if (preBindingArgumentTypes.size != expression.bindings.size) {
        fail("In function $containingFunctionId, tried to bind $functionExpression with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments")
    }

    val bindings = expression.bindings.map { binding ->
        if (binding == null) {
            null
        } else {
            validateExpression(binding, variableTypes, typeInfo, containingFunctionId)
        }
    }

    val postBindingArgumentTypes = ArrayList<Type>()
    for (entry in preBindingArgumentTypes.zip(bindings)) {
        val type = entry.first
        val binding = entry.second
        if (binding == null) {
            postBindingArgumentTypes.add(type)
        } else {
            if (binding.type != type) {
                fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
            }
        }
    }
    val postBindingType = Type.FunctionType(
            postBindingArgumentTypes,
            functionType.outputType)

    return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings, expression.chosenParameters)
}

private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
    val functionRef = expression.functionRef

    // ...
    val resolvedRef = typeInfo.resolver.resolveFunction(functionRef) ?: fail("In function $containingFunctionId, could not find a declaration of a function with ID $functionRef")
    val signature = typeInfo.allFunctionTypeSignatures.get(resolvedRef) ?: getNativeFunctionDefinitions()[resolvedRef.id]
    if (signature == null) {
        fail("In function $containingFunctionId, resolved a function with ID $functionRef but could not find the signature")
    }
    val chosenParameters = expression.chosenParameters
    if (chosenParameters.size != signature.typeParameters.size) {
        fail("In function $containingFunctionId, referenced a function $functionRef with type parameters ${signature.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
    }
    val typeParameters = signature.typeParameters

    val parameterMap = makeParameterMap(typeParameters, chosenParameters, resolvedRef.id)
    val preBindingArgumentTypes = signature.argumentTypes.map { type -> type.replacingParameters(parameterMap) }

    if (preBindingArgumentTypes.size != expression.bindings.size) {
        fail("In function $containingFunctionId, tried to bind function $functionRef with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments")
    }

    val bindings = expression.bindings.map { binding ->
        if (binding == null) {
            null
        } else {
            validateExpression(binding, variableTypes, typeInfo, containingFunctionId)
        }
    }

    val postBindingArgumentTypes = ArrayList<Type>()
    for (entry in preBindingArgumentTypes.zip(bindings)) {
        val type = entry.first
        val binding = entry.second
        if (binding == null) {
            postBindingArgumentTypes.add(type)
        } else {
            if (binding.type != type) {
                fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
            }
        }
    }
    val postBindingType = Type.FunctionType(
            postBindingArgumentTypes,
            signature.outputType.replacingParameters(parameterMap))

    return TypedExpression.NamedFunctionBinding(postBindingType, functionRef, bindings, chosenParameters)
}

private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
    val innerExpression = validateExpression(expression.expression, variableTypes, typeInfo, containingFunctionId)

    val parentNamedType = innerExpression.type as? Type.NamedType ?: fail("In function $containingFunctionId, we try to dereference an expression $innerExpression of non-struct, non-interface type ${innerExpression.type}")

    val resolvedParentStructType = typeInfo.resolver.resolveStruct(parentNamedType.id)
    if (resolvedParentStructType != null) {
        val structInfo = typeInfo.structs[resolvedParentStructType] ?: getNativeStructInfo(resolvedParentStructType.id)
        if (structInfo != null) {
            val member = structInfo.members[expression.name]
            if (member == null) {
                fail("In function $containingFunctionId, we try to dereference a non-existent member '${expression.name}' of the struct type $parentNamedType")
            }

            // Type parameters come from the struct definition itself
            // Chosen types come from the struct type known for the variable
            val typeParameters = structInfo.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
            val chosenTypes = parentNamedType.getParameterizedTypes()
            val type = parameterizeType(member.type, typeParameters, chosenTypes, resolvedParentStructType.id)
            //TODO: Ground this if needed

            return TypedExpression.Follow(type, innerExpression, expression.name)
        }
    }

    val resolvedParentInterfaceType = typeInfo.resolver.resolveInterface(parentNamedType.id)
    if (resolvedParentInterfaceType != null) {
        val interfac = typeInfo.interfaces[resolvedParentInterfaceType] ?: getNativeInterfaceInfo(resolvedParentInterfaceType.id)
        if (interfac != null) {
            val interfaceType = parentNamedType
            val method = interfac.methods[expression.name]
            if (method == null) {
                fail("In function $containingFunctionId, we try to reference a non-existent method '${expression.name}' of the interface type $interfaceType")
            }

            val typeParameters = interfac.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
            val chosenTypes = interfaceType.getParameterizedTypes()
            val type = parameterizeType(method.functionType, typeParameters, chosenTypes, resolvedParentInterfaceType.id)

            return TypedExpression.Follow(type, innerExpression, expression.name)
        }
    }

    fail("In function $containingFunctionId, we try to dereference an expression $innerExpression of a type $parentNamedType that is not recognized as a struct or interface")
}

private fun getNativeStructInfo(id: EntityId): StructTypeInfo? {
    val struct = getNativeStructs()[id]
    if (struct == null) {
        return null
    }
    return StructTypeInfo(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null)
}

private fun getNativeInterfaceInfo(id: EntityId): InterfaceTypeInfo? {
    val interfac = getNativeInterfaces()[id]
    if (interfac == null) {
        return null
    }
    return InterfaceTypeInfo(interfac.typeParameters, interfac.methods.associateBy(Method::name))
}

private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
    val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, containingFunctionId)

    val functionType = functionExpression.type as? Type.FunctionType ?: fail("The function $containingFunctionId tries to call $functionExpression like a function, but it has a non-function type ${functionExpression.type}")

    val arguments = ArrayList<TypedExpression>()
    expression.arguments.forEach { untypedArgument ->
        val argument = validateExpression(untypedArgument, variableTypes, typeInfo, containingFunctionId)
        arguments.add(argument)
    }
    val argumentTypes = arguments.map(TypedExpression::type)

    if (argumentTypes != functionType.argTypes) {
        fail("The function $containingFunctionId tries to call the result of $functionExpression with argument types $argumentTypes, but the function expects argument types ${functionType.argTypes}")
    }

    return TypedExpression.ExpressionFunctionCall(functionType.outputType, functionExpression, arguments, expression.chosenParameters)
}

private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
    val functionRef = expression.functionRef
    val functionResolvedRef = typeInfo.resolver.resolveFunction(functionRef)

    val arguments = ArrayList<TypedExpression>()
    expression.arguments.forEach { untypedArgument ->
        val argument = validateExpression(untypedArgument, variableTypes, typeInfo, containingFunctionId)
        arguments.add(argument)
    }
    val argumentTypes = arguments.map(TypedExpression::type)

    val signature = typeInfo.allFunctionTypeSignatures[functionResolvedRef] ?: if (functionRef.moduleRef == null) {
        getNativeFunctionDefinitions()[functionRef.id]
    } else {
        null
    }
    if (signature == null) {
        fail("The function $containingFunctionId references a function $functionRef that was not found")
    }
    //TODO: Maybe compare argument size before grounding?

    //Ground the signature
    val groundSignature = ground(signature, expression.chosenParameters, functionRef.id)
    if (argumentTypes != groundSignature.argumentTypes) {
        fail("The function $containingFunctionId tries to call $functionRef with argument types $argumentTypes, but the function expects argument types ${groundSignature.argumentTypes}")
    }

    return TypedExpression.NamedFunctionCall(groundSignature.outputType, functionRef, arguments, expression.chosenParameters)
}

private fun ground(signature: TypeSignature, chosenTypes: List<Type>, functionId: EntityId): GroundedTypeSignature {
    val groundedArgumentTypes = signature.argumentTypes.map { t -> parameterizeType(t, signature.typeParameters, chosenTypes, functionId) }
    val groundedOutputType = parameterizeType(signature.outputType, signature.typeParameters, chosenTypes, functionId)
    return GroundedTypeSignature(signature.id, groundedArgumentTypes, groundedOutputType)
}

// TODO: We're disagreeing in multiple places on List<Type> vs. List<String>, should fix that at some point
private fun makeParameterMap(parameters: List<Type>, chosenTypes: List<Type>, functionId: EntityId): Map<Type, Type> {
    if (parameters.size != chosenTypes.size) {
        fail("Disagreement in type parameter list lengths for function $functionId; ${parameters.size} required, but ${chosenTypes.size} provided")
    }
    val map: MutableMap<Type, Type> = HashMap()

    parameters.zip(chosenTypes).forEach { pair ->
        val (parameter, chosenType) = pair
        val existingValue = map.get(parameter)
        if (existingValue != null) {
            // I think this is correct behavior...
            fail("Not anticipating seeing the same parameter type twice")
        }
        map.put(parameter, chosenType)
    }

    return map
}

private fun parameterizeType(typeWithWrongParameters: Type, typeParameters: List<Type>, chosenTypes: List<Type>, functionId: EntityId): Type {
    val parameterMap = makeParameterMap(typeParameters, chosenTypes, functionId)
    return typeWithWrongParameters.replacingParameters(parameterMap)
}

private fun validateLiteralExpression(expression: Expression.Literal): TypedExpression {
    val nativeType = getTypeValidatorFor(expression.type)
    val isValid = nativeType.validate(expression.literal)
    if (!isValid) {
        fail("Invalid literal value '${expression.literal}' for type '${expression.type}'")
    }
    return TypedExpression.Literal(expression.type, expression.literal)
}

private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
    val condition = validateExpression(expression.condition, variableTypes, typeInfo, containingFunctionId)

    if (condition.type != Type.BOOLEAN) {
        fail("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
    }

    val elseBlock = validateBlock(expression.elseBlock, variableTypes, typeInfo, containingFunctionId)
    val thenBlock = validateBlock(expression.thenBlock, variableTypes, typeInfo, containingFunctionId)

    val type = try {
        typeUnion(thenBlock.type, elseBlock.type)
    } catch (e: RuntimeException) {
        fail(e, "In function $containingFunctionId, could not reconcile 'then' and 'else' block types")
    }

    return TypedExpression.IfThen(type, condition, thenBlock, elseBlock)
}

private fun typeUnion(type1: Type, type2: Type): Type {
    // TODO: Handle actual type unions, inheritance as these things get added
    // TODO: Then move this stuff to the API level
    if (type1 == type2) {
        return type1
    }
    fail("Types $type1 and $type2 cannot be unioned")
}

private fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, containingFunctionId: EntityId): TypedExpression {
    val type = variableTypes[expression.name]
    if (type != null) {
        return TypedExpression.Variable(type, expression.name)
    } else {
        fail("In function $containingFunctionId, reference to unknown variable ${expression.name}")
    }
}

private fun getFunctionTypeSignatures(context: RawContext, upstreamModules: List<ValidatedModule>): Map<EntityId, TypeSignature> {
    val signatures = HashMap<EntityId, TypeSignature>()

    context.structs.forEach { struct ->
        val id = struct.id
        if (signatures.containsKey(id)) {
            fail("Struct name $id has an overlap with a native function")
        }
        signatures.put(id, struct.getConstructorSignature())
    }

    context.interfaces.forEach { interfac ->
        val id = interfac.id
        if (signatures.containsKey(id)) {
            fail("Interface name $id has an overlap with a native function or struct")
        }
        signatures.put(interfac.adapterId, interfac.getAdapterConstructorSignature())
        signatures.put(id, interfac.getInstanceConstructorSignature())
    }
    context.functions.forEach { function ->
        val id = function.id
        if (signatures.containsKey(id)) {
            fail("Function name $id overlaps with a struct's or interface's constructor name, or with a native function")
        }
        signatures.put(id, getFunctionSignature(function))
    }
    return signatures
}

private fun validateStructs(structs: List<UnvalidatedStruct>, typeInfo: AllTypeInfo): Map<EntityId, Struct> {
    val validatedStructs = HashMap<EntityId, Struct>()
    for (struct in structs) {
        validatedStructs.put(struct.id, validateStruct(struct, typeInfo))
    }
    return validatedStructs
}

private fun validateStruct(struct: UnvalidatedStruct, typeInfo: AllTypeInfo): Struct {
    validateMemberNames(struct)
    val memberTypes = struct.members.associate { member -> member.name to member.type }

//    val fakeContainingFunctionId = EntityId(struct.id.toPackage(), "requires")
    val fakeContainingFunctionId = EntityId(struct.id.namespacedName + "requires")
    val requires = struct.requires?.let { validateBlock(it, memberTypes, typeInfo, fakeContainingFunctionId) }
    if (requires != null && requires.type != Type.BOOLEAN) {
        error("Struct ${struct.id} has a requires block with inferred type ${requires.type}, but the type should be Boolean")
    }

    return Struct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations)
}

private fun validateMemberNames(struct: UnvalidatedStruct) {
    val allNames = HashSet<String>()
    for (member in struct.members) {
        if (allNames.contains(member.name)) {
            error("Struct ${struct.id} has multiple members named ${member.name}")
        }
        allNames.add(member.name)
    }
}

private fun validateInterfaces(interfaces: List<Interface>, typeInfo: AllTypeInfo): Map<EntityId, Interface> {
    // TODO: Do some actual validation of interfaces
    return interfaces.associateBy(Interface::id)
}

// TODO: This should be a method on the Function
private fun getFunctionSignature(function: Function): TypeSignature {
    val argumentTypes = function.arguments.map(Argument::type)
    val typeParameters = function.typeParameters.map { id -> Type.NamedType.forParameter(id) }
    return TypeSignature(function.id, argumentTypes, function.returnType, typeParameters)
}

private fun getAllFunctionTypeSignatures(ownFunctionTypeSignatures: Map<EntityId, TypeSignature>,
                                         ownModuleId: ModuleId,
                                         upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, TypeSignature> {
//    return getAllEntities(ownFunctionTypeSignatures, ValidatedModule::getAllInternalFunctionSignatures, upstreamModules)
    val results = HashMap<ResolvedEntityRef, TypeSignature>()

    upstreamModules.forEach { module ->
        results.putAll(module.getAllInternalFunctionSignatures().mapKeys { (id, _) -> ResolvedEntityRef(module.id, id) })
    }
    results.putAll(ownFunctionTypeSignatures.mapKeys { (id, _) -> ResolvedEntityRef(ownModuleId, id) })

    return results
}

private fun getAllStructsInfo(ownStructs: List<UnvalidatedStruct>, ownModuleId: ModuleId, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, StructTypeInfo> {
    val allStructsInfo = HashMap<ResolvedEntityRef, StructTypeInfo>()
    upstreamModules.forEach { module ->
        module.getAllInternalStructs().forEach { (_, struct) ->
            allStructsInfo.put(ResolvedEntityRef(module.id, struct.id),
                    StructTypeInfo(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null))
        }
    }
    ownStructs.forEach { struct ->
        allStructsInfo.put(ResolvedEntityRef(ownModuleId, struct.id),
                StructTypeInfo(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null))
    }
    return allStructsInfo
}

private fun getAllInterfacesInfo(ownInterfaces: List<Interface>, ownModuleId: ModuleId, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, InterfaceTypeInfo> {
    val allInterfaces = HashMap<ResolvedEntityRef, Interface>()

    upstreamModules.forEach { module ->
        allInterfaces.putAll(module.getAllInternalInterfaces().mapKeys { (id, _) -> ResolvedEntityRef(module.id, id) })
    }
    allInterfaces.putAll(ownInterfaces.associateBy({ interfac -> ResolvedEntityRef(ownModuleId, interfac.id)}))

    return allInterfaces.mapValues { (_, interfac) ->
        InterfaceTypeInfo(interfac.typeParameters, interfac.methods.associateBy(Method::name))
    }
}
