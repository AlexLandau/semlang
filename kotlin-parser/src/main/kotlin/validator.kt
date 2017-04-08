package semlang.parser

import semlang.api.*
import semlang.api.Function
import semlang.api.Type.NamedType.Companion.forParameter
import semlang.interpreter.getTypeValidatorFor
import java.util.*

// TODO: Probably need several overloads of this, and Positions stored in more places
private fun fail(expression: Expression, text: String): Nothing {
    val position = expression.position
    val fullMessage = "Validation error, ${position.lineNumber}:${position.column}: " + text
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

fun validateContext(context: InterpreterContext): ValidatedContext {
    // TODO: Validate the structs
    val functionTypeSignatures = getFunctionTypeSignatures(context)
    val structs = getStructs(context)
    val interfaces = getInterfaces(context)
    val interfacesByAdapterId = interfaces.values.associateBy(Interface::adapterId)
    val validatedFunctions = validateFunctions(context.functions, functionTypeSignatures, structs, interfaces)
    return ValidatedContext(validatedFunctions, structs, interfaces, interfacesByAdapterId)
}

private fun validateFunctions(functions: Map<FunctionId, Function>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>): Map<FunctionId, ValidatedFunction> {
    val validatedFunctions = HashMap<FunctionId, ValidatedFunction>()
    functions.entries.forEach { entry ->
        val (id, function) = entry
        val validatedFunction = validateFunction(function, functionTypeSignatures, structs, interfaces)
        validatedFunctions.put(id, validatedFunction)
    }
    return validatedFunctions
}

private fun validateFunction(function: Function, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>): ValidatedFunction {
    //TODO: Validate that no two arguments have the same name
    //TODO: Validate that type parameters don't share a name with something important
    val variableTypes = getArgumentVariableTypes(function.arguments)
    val block = validateBlock(function.block, variableTypes, functionTypeSignatures, structs, interfaces, function.id)
    if (function.returnType != block.type) {
        fail("Function ${function.id}'s stated return type ${function.returnType} does " +
                "not match the block's actual return type ${block.type}")
    } else {
        return ValidatedFunction(function.id, function.typeParameters, function.arguments, function.returnType, block)
    }
}

private fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
    return arguments.associate { argument -> Pair(argument.name, argument.type) }
}

private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedBlock {
    val variableTypes = HashMap(externalVariableTypes)
    val validatedAssignments = ArrayList<ValidatedAssignment>()
    block.assignments.forEach { assignment ->
        if (variableTypes.containsKey(assignment.name)) {
            fail("In function $containingFunctionId, there is a reassignment of the already-assigned variable ${assignment.name}")
        }
        if (isInvalidVariableName(assignment.name, functionTypeSignatures, structs)) {
            fail("In function $containingFunctionId, there is an invalid variable name ${assignment.name}")
        }

        val validatedExpression = validateExpression(assignment.expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
        if (validatedExpression.type != assignment.type) {
            fail("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type ${assignment.type}, " +
                    "but the expression given has actual type ${validatedExpression.type}")
        }

        validatedAssignments.add(ValidatedAssignment(assignment.name, assignment.type, validatedExpression))
        variableTypes.put(assignment.name, assignment.type)
    }
    val returnedExpression = validateExpression(block.returnedExpression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
    return TypedBlock(returnedExpression.type, validatedAssignments, returnedExpression)
}

//TODO: Construct this more sensibly from more centralized lists
private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Natural", "Boolean", "function", "let", "return", "if", "else", "struct")

private fun isInvalidVariableName(name: String, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>): Boolean {
    val nameAsFunctionId = FunctionId.of(name)
    return functionTypeSignatures.containsKey(nameAsFunctionId) || structs.containsKey(nameAsFunctionId) || INVALID_VARIABLE_NAMES.contains(name)
}

private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedExpression {
    return when (expression) {
        is Expression.Variable -> validateVariableExpression(expression, variableTypes, containingFunctionId)
        is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
        is Expression.Follow -> validateFollowExpression(expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
        is Expression.NamedFunctionCall -> validateNamedFunctionCallExpression(expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
        is Expression.ExpressionFunctionCall -> validateExpressionFunctionCallExpression(expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)

        is Expression.Literal -> validateLiteralExpression(expression)
        is Expression.NamedFunctionBinding -> validateNamedFunctionBinding(expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
        is Expression.ExpressionFunctionBinding -> validateExpressionFunctionBinding(expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
    }
}

fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedExpression {
    val functionExpression = validateExpression(expression.functionExpression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)

    val functionType = functionExpression.type as? Type.FunctionType ?: fail("The function $containingFunctionId tries to call $functionExpression like a function, but it has a non-function type ${functionExpression.type}")

    val preBindingArgumentTypes = functionType.argTypes

    if (preBindingArgumentTypes.size != expression.bindings.size) {
        fail("In function $containingFunctionId, tried to bind $functionExpression with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments")
    }

    val bindings = expression.bindings.map { binding ->
        if (binding == null) {
            null
        } else {
            validateExpression(binding, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
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

    return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings)
}

fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedExpression {
    val functionId = expression.functionId
    val signature = functionTypeSignatures.get(functionId)
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
            validateExpression(binding, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
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

    return TypedExpression.NamedFunctionBinding(postBindingType, functionId, bindings)
}

private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedExpression {
    val innerExpression = validateExpression(expression.expression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)

    val structType = innerExpression.type as? Type.NamedType ?: fail("In function $containingFunctionId, we try to dereference an expression $innerExpression of non-struct type ${innerExpression.type}")

    val struct = structs[structType.id]
    if (struct != null) {
        val index = struct.getIndexForName(expression.id)
        if (index < 0) {
            fail("In function $containingFunctionId, we try to dereference a non-existent member '${expression.id}' of the struct type $structType")
        }
        // Type parameters come from the struct definition itself
        // Chosen types come from the struct type known for the variable
        val typeParameters = struct.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
        val chosenTypes = structType.getParameterizedTypes()
        val type = parameterizeType(struct.members[index].type, typeParameters, chosenTypes, struct.id)
        //TODO: Ground this if needed

        return TypedExpression.Follow(type, innerExpression, expression.id)
    }

    val interfac = interfaces[structType.id]
    if (interfac != null) {
        val interfaceType = structType
        val index = interfac.getIndexForName(expression.id)
        if (index < 0) {
            fail("In function $containingFunctionId, we try to reference a non-existent method '${expression.id}' of the interface type $interfaceType")
        }

        val method = interfac.methods[index]
        val typeParameters = interfac.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
        val chosenTypes = interfaceType.getParameterizedTypes()
        val type = parameterizeType(method.functionType, typeParameters, chosenTypes, interfac.id)

        return TypedExpression.Follow(type, innerExpression, expression.id)
    }

    fail("In function $containingFunctionId, we try to dereference an expression $innerExpression of a type $structType that is not recognized as a struct or interface")
}

private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedExpression {
    val functionExpression = validateExpression(expression.functionExpression, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)

    val functionType = functionExpression.type as? Type.FunctionType ?: fail("The function $containingFunctionId tries to call $functionExpression like a function, but it has a non-function type ${functionExpression.type}")

    val arguments = ArrayList<TypedExpression>()
    expression.arguments.forEach { untypedArgument ->
        val argument = validateExpression(untypedArgument, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
        arguments.add(argument)
    }
    val argumentTypes = arguments.map(TypedExpression::type)

    if (argumentTypes != functionType.argTypes) {
        fail("The function $containingFunctionId tries to call the result of $functionExpression with argument types $argumentTypes, but the function expects argument types ${functionType.argTypes}")
    }

    return TypedExpression.ExpressionFunctionCall(functionType.outputType, functionExpression, arguments)
}

private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedExpression {
    val functionId = expression.functionId

    val arguments = ArrayList<TypedExpression>()
    expression.arguments.forEach { untypedArgument ->
        val argument = validateExpression(untypedArgument, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
        arguments.add(argument)
    }
    val argumentTypes = arguments.map(TypedExpression::type)

    val signature = functionTypeSignatures[functionId]
    if (signature == null) {
        fail("The function $containingFunctionId references a function $functionId that was not found")
    }
    //TODO: Maybe compare argument size before grounding?

    //Ground the signature
    val groundSignature = ground(signature, expression.chosenParameters, functionId)
    if (argumentTypes != groundSignature.argumentTypes) {
        fail("The function $containingFunctionId tries to call $functionId with argument types $argumentTypes, but the function expects argument types ${groundSignature.argumentTypes}")
    }

    return TypedExpression.NamedFunctionCall(groundSignature.outputType, functionId, arguments)
}

private fun ground(signature: TypeSignature, chosenTypes: List<Type>, functionId: FunctionId): GroundedTypeSignature {
    val groundedArgumentTypes = signature.argumentTypes.map { t -> parameterizeType(t, signature.typeParameters, chosenTypes, functionId) }
    val groundedOutputType = parameterizeType(signature.outputType, signature.typeParameters, chosenTypes, functionId)
    return GroundedTypeSignature(signature.id, groundedArgumentTypes, groundedOutputType)
}

// TODO: We're disagreeing in multiple places on List<Type> vs. List<String>, should fix that at some point
private fun makeParameterMap(parameters: List<Type>, chosenTypes: List<Type>, functionId: FunctionId): Map<Type, Type> {
    if (parameters.size != chosenTypes.size) {
        fail("Disagreement in type parameter list lengths for function $functionId")
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

private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, interfaces: Map<FunctionId, Interface>, containingFunctionId: FunctionId): TypedExpression {
    val condition = validateExpression(expression.condition, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)

    if (condition.type != Type.BOOLEAN) {
        fail("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
    }

    val elseBlock = validateBlock(expression.elseBlock, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)
    val thenBlock = validateBlock(expression.thenBlock, variableTypes, functionTypeSignatures, structs, interfaces, containingFunctionId)

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

private fun getFunctionTypeSignatures(context: InterpreterContext): Map<FunctionId, TypeSignature> {
    val signatures = HashMap<FunctionId, TypeSignature>()
    addNativeFunctions(signatures)
    context.structs.entries.forEach { entry ->
        val (id, struct) = entry
        if (signatures.containsKey(id)) {
            fail("Struct name $id has an overlap with a native function")
        }
        signatures.put(id, getStructConstructorSignature(struct))
    }
    context.interfaces.entries.forEach { entry ->
        val (id, interfac) = entry
        if (signatures.containsKey(id)) {
            fail("Interface name $id has an overlap with a native function or struct")
        }
        signatures.put(interfac.adapterId, getAdapterConstructorSignature(interfac))
        signatures.put(id, getInstanceConstructorSignature(interfac))
    }
    context.functions.entries.forEach { entry ->
        val (id, function) = entry
        if (signatures.containsKey(id)) {
            if (context.structs.keys.contains(id)) {
                fail("Function name $id overlaps with a struct's or interface's constructor name")
            } else {
                fail("Function name $id overlaps with a native function")
            }
        }
        signatures.put(id, getFunctionSignature(function))
    }
    return signatures
}

// TODO: Make this reusable in sem1 -> sem0 transformation
fun getAdapterConstructorSignature(interfac: Interface): TypeSignature {
    val explicitTypeParameters = interfac.typeParameters
    val dataTypeParameter = getUnusedTypeParameterName(explicitTypeParameters)
    val allTypeParameters = ArrayList(explicitTypeParameters)
    allTypeParameters.add(0, dataTypeParameter) // Data type parameter comes first

    val argumentTypes = ArrayList<Type>()
    val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
    interfac.methods.forEach { method ->
        argumentTypes.add(getInterfaceMethodReferenceType(dataStructType, method))
    }

    val outputType = Type.NamedType(interfac.adapterId, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })

    return TypeSignature(interfac.adapterId, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
}

// TODO: Make this reusable in sem1 -> sem0 transformation
fun getInstanceConstructorSignature(interfac: Interface): TypeSignature {
    val explicitTypeParameters = interfac.typeParameters
    val dataTypeParameter = getUnusedTypeParameterName(explicitTypeParameters)
    val allTypeParameters = ArrayList(explicitTypeParameters)
    allTypeParameters.add(0, dataTypeParameter) // Data type parameter comes first

    val argumentTypes = ArrayList<Type>()
    val dataStructType = Type.NamedType.forParameter(dataTypeParameter)
    argumentTypes.add(dataStructType)

    val adapterType = Type.NamedType(interfac.adapterId, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
    argumentTypes.add(adapterType)

    val outputType = Type.NamedType(interfac.id, explicitTypeParameters.map { name -> Type.NamedType.forParameter(name) })

    return TypeSignature(interfac.id, argumentTypes, outputType, allTypeParameters.map { name -> Type.NamedType.forParameter(name) })
}

fun getUnusedTypeParameterName(explicitTypeParameters: List<String>): String {
    if (!explicitTypeParameters.contains("A")) {
        return "A"
    }
    var index = 2
    while (true) {
        val name = "A" + index
        if (!explicitTypeParameters.contains(name)) {
            return name
        }
        index++
    }
}

fun getInterfaceMethodReferenceType(intrinsicStructType: Type.NamedType, method: Method): Type {
    val argTypes = ArrayList<Type>()
    argTypes.add(intrinsicStructType)
    method.arguments.forEach { argument ->
        argTypes.add(argument.type)
    }

    return Type.FunctionType(argTypes, method.returnType)
}

private fun addNativeFunctions(signatures: HashMap<FunctionId, TypeSignature>) {
    signatures.putAll(getNativeFunctionDefinitions())
}

fun getStructs(context: InterpreterContext): Map<FunctionId, Struct> {
    val structs = HashMap<FunctionId, Struct>()
    structs.putAll(context.structs)
    structs.putAll(getNativeStructs())
    return structs
}

fun getInterfaces(context: InterpreterContext): Map<FunctionId, Interface> {
    val interfaces = HashMap<FunctionId, Interface>()
    interfaces.putAll(context.interfaces)
    interfaces.putAll(getNativeInterfaces())
    return interfaces
}

private fun getFunctionSignature(function: Function): TypeSignature {
    val argumentTypes = function.arguments.map(Argument::type)
    val typeParameters = function.typeParameters.map { id -> Type.NamedType.forParameter(id) }
    return TypeSignature(function.id, argumentTypes, function.returnType, typeParameters)
}

private fun getStructConstructorSignature(struct: Struct): TypeSignature {
    val argumentTypes = struct.members.map(Member::type)
    val typeParameters = struct.typeParameters.map { id -> Type.NamedType.forParameter(id) }
    // TODO: Method for making a type parameter type (String -> Type)
    val outputType = Type.NamedType(struct.id, typeParameters)
    return TypeSignature(struct.id, argumentTypes, outputType, typeParameters)
}
