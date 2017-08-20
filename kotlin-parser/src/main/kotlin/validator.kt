package semlang.parser

import semlang.api.*
import semlang.api.Function
import semlang.interpreter.getTypeValidatorFor
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

data class GroundedTypeSignature(val id: FunctionId, val argumentTypes: List<Type>, val outputType: Type)

fun validateContext(context: RawContext, upstreamContexts: List<ValidatedContext>): ValidatedContext {
    val typeInfo = collectTypeInfo(context, upstreamContexts)

    val ownFunctions = validateFunctions(context.functions, typeInfo)
    val ownStructs = validateStructs(context.structs, typeInfo)
    val ownInterfaces = validateInterfaces(context.interfaces, typeInfo)
    return ValidatedContext.create(ownFunctions, ownStructs, ownInterfaces, upstreamContexts)
}

private fun collectTypeInfo(context: RawContext, upstreamContexts: List<ValidatedContext>): AllTypeInfo {
    val functionTypeSignatures = getFunctionTypeSignatures(context, upstreamContexts)
    val allFunctionTypeSignatures = getAllFunctionTypeSignatures(functionTypeSignatures, upstreamContexts)
    val allStructs = getAllStructsInfo(context.structs, upstreamContexts)
    val allInterfaces = getAllInterfacesInfo(context.interfaces, upstreamContexts)

    return AllTypeInfo(allFunctionTypeSignatures, allStructs, allInterfaces)
}

private fun validateFunctions(functions: List<Function>, typeInfo: AllTypeInfo): Map<FunctionId, ValidatedFunction> {
    val validatedFunctions = HashMap<FunctionId, ValidatedFunction>()
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

private data class AllTypeInfo(val allFunctionTypeSignatures: Map<FunctionId, TypeSignature>, val structs: Map<FunctionId, StructTypeInfo>, val interfaces: Map<FunctionId, InterfaceTypeInfo>)

private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedBlock {
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
    val nameAsFunctionId = FunctionId.of(name)
    return typeInfo.allFunctionTypeSignatures.containsKey(nameAsFunctionId) || typeInfo.structs.containsKey(nameAsFunctionId) || INVALID_VARIABLE_NAMES.contains(name)
}

private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedExpression {
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

private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedExpression {
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

private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedExpression {
    val functionId = expression.functionId
    val signature = typeInfo.allFunctionTypeSignatures.get(functionId)
    if (signature == null) {
        fail("In function $containingFunctionId, could not find a declaration of a function with ID $functionId")
    }
    val chosenParameters = expression.chosenParameters
    if (chosenParameters.size != signature.typeParameters.size) {
        fail("In function $containingFunctionId, referenced a function $functionId with type parameters ${signature.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
    }
    val typeParameters = signature.typeParameters

    val parameterMap = makeParameterMap(typeParameters, chosenParameters, functionId)
    val preBindingArgumentTypes = signature.argumentTypes.map { type -> type.replacingParameters(parameterMap) }

    if (preBindingArgumentTypes.size != expression.bindings.size) {
        fail("In function $containingFunctionId, tried to bind function $functionId with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments")
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

    return TypedExpression.NamedFunctionBinding(postBindingType, functionId, bindings, chosenParameters)
}

private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedExpression {
    val innerExpression = validateExpression(expression.expression, variableTypes, typeInfo, containingFunctionId)

    val parentNamedType = innerExpression.type as? Type.NamedType ?: fail("In function $containingFunctionId, we try to dereference an expression $innerExpression of non-struct, non-interface type ${innerExpression.type}")

    val structInfo = typeInfo.structs[parentNamedType.id]
    if (structInfo != null) {
        val member = structInfo.members[expression.name]
        if (member == null) {
            fail("In function $containingFunctionId, we try to dereference a non-existent member '${expression.name}' of the struct type $parentNamedType")
        }

        // Type parameters come from the struct definition itself
        // Chosen types come from the struct type known for the variable
        val typeParameters = structInfo.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
        val chosenTypes = parentNamedType.getParameterizedTypes()
        val type = parameterizeType(member.type, typeParameters, chosenTypes, parentNamedType.id)
        //TODO: Ground this if needed

        return TypedExpression.Follow(type, innerExpression, expression.name)
    }

    val interfac = typeInfo.interfaces[parentNamedType.id]
    if (interfac != null) {
        val interfaceType = parentNamedType
        val method = interfac.methods[expression.name]
        if (method == null) {
            fail("In function $containingFunctionId, we try to reference a non-existent method '${expression.name}' of the interface type $interfaceType")
        }

        val typeParameters = interfac.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
        val chosenTypes = interfaceType.getParameterizedTypes()
        val type = parameterizeType(method.functionType, typeParameters, chosenTypes, parentNamedType.id)

        return TypedExpression.Follow(type, innerExpression, expression.name)
    }

    fail("In function $containingFunctionId, we try to dereference an expression $innerExpression of a type $parentNamedType that is not recognized as a struct or interface")
}

private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedExpression {
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

private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedExpression {
    val functionId = expression.functionId

    val arguments = ArrayList<TypedExpression>()
    expression.arguments.forEach { untypedArgument ->
        val argument = validateExpression(untypedArgument, variableTypes, typeInfo, containingFunctionId)
        arguments.add(argument)
    }
    val argumentTypes = arguments.map(TypedExpression::type)

    val signature = typeInfo.allFunctionTypeSignatures[functionId]
    if (signature == null) {
        fail("The function $containingFunctionId references a function $functionId that was not found")
    }
    //TODO: Maybe compare argument size before grounding?

    //Ground the signature
    val groundSignature = ground(signature, expression.chosenParameters, functionId)
    if (argumentTypes != groundSignature.argumentTypes) {
        fail("The function $containingFunctionId tries to call $functionId with argument types $argumentTypes, but the function expects argument types ${groundSignature.argumentTypes}")
    }

    return TypedExpression.NamedFunctionCall(groundSignature.outputType, functionId, arguments, expression.chosenParameters)
}

private fun ground(signature: TypeSignature, chosenTypes: List<Type>, functionId: FunctionId): GroundedTypeSignature {
    val groundedArgumentTypes = signature.argumentTypes.map { t -> parameterizeType(t, signature.typeParameters, chosenTypes, functionId) }
    val groundedOutputType = parameterizeType(signature.outputType, signature.typeParameters, chosenTypes, functionId)
    return GroundedTypeSignature(signature.id, groundedArgumentTypes, groundedOutputType)
}

// TODO: We're disagreeing in multiple places on List<Type> vs. List<String>, should fix that at some point
private fun makeParameterMap(parameters: List<Type>, chosenTypes: List<Type>, functionId: FunctionId): Map<Type, Type> {
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

private fun parameterizeType(typeWithWrongParameters: Type, typeParameters: List<Type>, chosenTypes: List<Type>, functionId: FunctionId): Type {
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

private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: FunctionId): TypedExpression {
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

private fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, containingFunctionId: FunctionId): TypedExpression {
    val type = variableTypes[expression.name]
    if (type != null) {
        return TypedExpression.Variable(type, expression.name)
    } else {
        fail("In function $containingFunctionId, reference to unknown variable ${expression.name}")
    }
}

private fun getFunctionTypeSignatures(context: RawContext, upstreamContexts: List<ValidatedContext>): Map<FunctionId, TypeSignature> {
    val signatures = HashMap<FunctionId, TypeSignature>()

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

private fun validateStructs(structs: List<UnvalidatedStruct>, typeInfo: AllTypeInfo): Map<FunctionId, Struct> {
    val validatedStructs = HashMap<FunctionId, Struct>()
    for (struct in structs) {
        validatedStructs.put(struct.id, validateStruct(struct, typeInfo))
    }
    return validatedStructs
}

private fun validateStruct(struct: UnvalidatedStruct, typeInfo: AllTypeInfo): Struct {
    validateMemberNames(struct)
    val memberTypes = struct.members.associate { member -> member.name to member.type }

    val fakeContainingFunctionId = FunctionId(struct.id.toPackage(), "requires")
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

private fun validateInterfaces(interfaces: List<Interface>, typeInfo: AllTypeInfo): Map<FunctionId, Interface> {
    // TODO: Do some actual validation of interfaces
    return interfaces.associateBy(Interface::id)
}

private fun getFunctionSignature(function: Function): TypeSignature {
    val argumentTypes = function.arguments.map(Argument::type)
    val typeParameters = function.typeParameters.map { id -> Type.NamedType.forParameter(id) }
    return TypeSignature(function.id, argumentTypes, function.returnType, typeParameters)
}

private fun getAllFunctionTypeSignatures(ownFunctionTypeSignatures: Map<FunctionId, TypeSignature>, upstreamContexts: List<ValidatedContext>): Map<FunctionId, TypeSignature> {
    return getAllEntities(ownFunctionTypeSignatures, ValidatedContext::getAllFunctionSignatures, upstreamContexts)
}

private fun getAllStructsInfo(ownStructs: List<UnvalidatedStruct>, upstreamContexts: List<ValidatedContext>): Map<FunctionId, StructTypeInfo> {
    val allStructsInfo = HashMap<FunctionId, StructTypeInfo>()
    upstreamContexts.forEach { context ->
        context.getAllStructs().forEach { (_, struct) ->
            allStructsInfo.put(struct.id, StructTypeInfo(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null))
        }
    }
    ownStructs.forEach { struct ->
        // TODO: We need something much more robust to prevent name collisions
        if (allStructsInfo.containsKey(struct.id)) {
            error("A struct with key ${struct.id} already exists!")
        }
        allStructsInfo.put(struct.id, StructTypeInfo(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null))
    }
    return allStructsInfo
}

private fun getAllInterfacesInfo(ownInterfaces: List<Interface>, upstreamContexts: List<ValidatedContext>): Map<FunctionId, InterfaceTypeInfo> {
    val allInterfaces = HashMap<FunctionId, Interface>()

    upstreamContexts.forEach { context ->
        allInterfaces.putAll(context.getAllInterfaces())
    }
    allInterfaces.putAll(ownInterfaces.associateBy(Interface::id))

    return allInterfaces.mapValues { (_, interfac) ->
        InterfaceTypeInfo(interfac.typeParameters, interfac.methods.associateBy(Method::name))
    }
}

private fun <T> getAllEntities(own: Map<FunctionId, T>, theirs: (ValidatedContext) -> Map<FunctionId, T>, upstreamContexts: List<ValidatedContext>): Map<FunctionId, T> {
    // TODO: Error on duplicate keys
    val results = HashMap<FunctionId, T>()

    upstreamContexts.forEach { context ->
        results.putAll(theirs(context))
    }
    results.putAll(own)

    return results
}
