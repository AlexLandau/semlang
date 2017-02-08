package semlang.parser

import semlang.api.*
import semlang.api.Function
import semlang.interpreter.TypeValidator
import semlang.interpreter.getTypeValidatorFor
import java.util.*

// TODO: This type is probably not the right long-term solution vs. using well-thought-out exception handling
sealed class Try<out T> {
    abstract fun <U> ifGood(function: (T) -> Try<U>): Try<U>
    abstract fun assume(): T
    class Success<out T>(val result: T) : Try<T>() {
        override fun assume(): T {
            return result
        }

        override fun <U> ifGood(function: (T) -> Try<U>): Try<U> {
            return function.invoke(result)
        }
    }
    class Error<out T>(val errorMessage: String) : Try<T>() {
        override fun assume(): T {
            throw IllegalArgumentException("Try did not complete successfully: " + errorMessage)
        }

        override fun <U> ifGood(function: (T) -> Try<U>): Try<U> {
            return cast()
        }

        fun <U> cast(): Try.Error<U> {
            @Suppress("UNCHECKED_CAST") // Two Errors contain the same content regardless of T
            return this as Try.Error<U>
        }
    }
}

data class GroundedTypeSignature(val id: FunctionId, val argumentTypes: List<Type>, val outputType: Type)

fun validateContext(context: InterpreterContext): Try<ValidatedContext> {
    // TODO: Validate the structs
    val functionTypeSignatures = getFunctionTypeSignatures(context)
    return functionTypeSignatures.ifGood { functionTypeSignatures ->
        val validatedFunctions = validateFunctions(context.functions, functionTypeSignatures, context.structs)
        validatedFunctions.ifGood { validatedFunctions ->
            Try.Success(ValidatedContext(validatedFunctions, context.structs))
        }
    }
}

private fun validateFunctions(functions: Map<FunctionId, Function>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>): Try<Map<FunctionId, ValidatedFunction>> {
    val validatedFunctions = HashMap<FunctionId, ValidatedFunction>()
    functions.entries.forEach { entry ->
        val (id, function) = entry
        val validatedFunction = validateFunction(function, functionTypeSignatures, structs)
        when (validatedFunction) {
            is Try.Success -> validatedFunctions.put(id, validatedFunction.result)
            is Try.Error -> return validatedFunction.cast()
        }
    }
    return Try.Success(validatedFunctions)
}

private fun validateFunction(function: Function, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>): Try<ValidatedFunction> {
    //TODO: Validate that no two arguments have the same name
    //TODO: Validate that type parameters don't share a name with something important
    val variableTypes = getArgumentVariableTypes(function.arguments)
    return validateBlock(function.block, variableTypes, functionTypeSignatures, structs, function.id).ifGood { block ->
        if (function.returnType != block.type) {
            Try.Error<ValidatedFunction>("Function ${function.id}'s stated return type ${function.returnType} does " +
                    "not match the block's actual return type ${block.type}")
        } else {
            Try.Success(ValidatedFunction(function.id, function.typeParameters, function.arguments, function.returnType, block))
        }
    }
}

private fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
    return arguments.associate { argument -> Pair(argument.name, argument.type) }
}

private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedBlock> {
    val variableTypes = HashMap(externalVariableTypes)
    val validatedAssignments = ArrayList<ValidatedAssignment>()
    block.assignments.forEach { assignment ->
        if (variableTypes.containsKey(assignment.name)) {
            return Try.Error("In function $containingFunctionId, there is a reassignment of the already-assigned variable ${assignment.name}")
        }
        if (isInvalidVariableName(assignment.name, functionTypeSignatures, structs)) {
            return Try.Error("In function $containingFunctionId, there is an invalid variable name ${assignment.name}")
        }

        val validatedExpression = validateExpression(assignment.expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        when (validatedExpression) {
            is Try.Error -> return validatedExpression.cast()
            is Try.Success -> {
                if (validatedExpression.result.type != assignment.type) {
                    return Try.Error("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type ${assignment.type}, " +
                            "but the expression given has actual type ${validatedExpression.result.type}")
                }

                validatedAssignments.add(ValidatedAssignment(assignment.name, assignment.type, validatedExpression.result))
                variableTypes.put(assignment.name, assignment.type)
            }
        }
    }
    return validateExpression(block.returnedExpression, variableTypes, functionTypeSignatures, structs, containingFunctionId).ifGood { returnedExpression ->
        Try.Success(TypedBlock(returnedExpression.type, validatedAssignments, returnedExpression))
    }
}

//TODO: Construct this more sensibly from more centralized lists
private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Natural", "Boolean", "function", "let", "return", "if", "else", "struct")

private fun isInvalidVariableName(name: String, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>): Boolean {
    val nameAsFunctionId = FunctionId(Package.EMPTY, name)
    return functionTypeSignatures.containsKey(nameAsFunctionId) || structs.containsKey(nameAsFunctionId) || INVALID_VARIABLE_NAMES.contains(name)
}

private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
    return when (expression) {
        is Expression.Variable -> validateVariableExpression(expression, variableTypes, containingFunctionId)
        is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        is Expression.Follow -> validateFollowExpression(expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        is Expression.FunctionCall -> validateFunctionCallExpression(expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        is Expression.Literal -> validateLiteralExpression(expression)
    }
}

private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
    val innerExpressionMaybe = validateExpression(expression.expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
    val innerExpression = when (innerExpressionMaybe) {
        is Try.Error -> return innerExpressionMaybe
        is Try.Success -> innerExpressionMaybe.result
    }

    val structType = innerExpression.type
    if (structType !is Type.NamedType) {
        return Try.Error("In function $containingFunctionId, we try to dereference an expression $innerExpression of non-struct type $structType")
    }
    val struct = structs[structType.id]
    if (struct == null) {
        return Try.Error("In function $containingFunctionId, we try to dereference an expression $innerExpression of a type $structType that is not recognized as a struct")
    }

    val index = struct.getIndexForName(expression.id)
    if (index < 0) {
        return Try.Error("In function $containingFunctionId, we try to dereference a non-existent member '${expression.id}' of the struct type $structType")
    }
    // Type parameters come from the struct definition itself
    // Chosen types come from the struct type known for the variable
    val typeParameters = struct.typeParameters
    val chosenTypes = structType.getParameterizedTypes()
    val typeMaybe = parameterizeType(struct.members[index].type, typeParameters, chosenTypes)
    val type = when (typeMaybe) {
        is Try.Error -> return typeMaybe.cast()
        is Try.Success -> typeMaybe.result
    }
    //TODO: Ground this if needed

    return Try.Success(TypedExpression.Follow(type, innerExpression, expression.id))
}

private fun validateFunctionCallExpression(expression: Expression.FunctionCall, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
    val functionId = expression.functionId

    val arguments = ArrayList<TypedExpression>()
    expression.arguments.forEach { untypedArgument ->
        val argumentMaybe = validateExpression(untypedArgument, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        val argument = when (argumentMaybe) {
            is Try.Error -> return argumentMaybe
            is Try.Success -> argumentMaybe.result
        }
        arguments.add(argument)
    }
    val argumentTypes = arguments.map(TypedExpression::type)

    val signature = functionTypeSignatures[functionId]
    if (signature == null) {
        return Try.Error("The function $containingFunctionId references a function $functionId that was not found")
    }
    //TODO: Maybe compare argument size before grounding?

    //Ground the signature
    val groundSignatureMaybe = ground(signature, expression.chosenParameters)
    val groundSignature = when (groundSignatureMaybe) {
        is Try.Error -> return groundSignatureMaybe.cast()
        is Try.Success -> groundSignatureMaybe.result
    }
    if (argumentTypes != groundSignature.argumentTypes) {
        return Try.Error("The function $containingFunctionId tries to call $functionId with argument types $argumentTypes, but the function expects argument types ${groundSignature.argumentTypes}")
    }

    return Try.Success(TypedExpression.FunctionCall(groundSignature.outputType, functionId, arguments))
}

private fun ground(signature: TypeSignature, chosenTypes: List<Type>): Try<GroundedTypeSignature> {
    val groundedArgumentTypes = signature.argumentTypes.map { t -> parameterizeType(t, signature.typeParameters, chosenTypes) }
            .map { tMaybe -> when (tMaybe) {
                is Try.Error -> return tMaybe.cast()
                is Try.Success -> tMaybe.result
            } }
    val groundedOutputTypeMaybe = parameterizeType(signature.outputType, signature.typeParameters, chosenTypes)
    val groundedOutputType = when (groundedOutputTypeMaybe) {
        is Try.Error -> return groundedOutputTypeMaybe.cast()
        is Try.Success -> groundedOutputTypeMaybe.result
    }
    return Try.Success(GroundedTypeSignature(signature.id, groundedArgumentTypes, groundedOutputType))
}

private fun makeParameterMap(parameters: List<Type>, chosenTypes: List<Type>): Try<Map<Type, Type>> {
    if (parameters.size != chosenTypes.size) {
        return Try.Error("Disagreement in type parameter list lengths")
    }
    val map: MutableMap<Type, Type> = HashMap()

    parameters.zip(chosenTypes).forEach { pair ->
        val (parameter, chosenType) = pair
        val existingValue = map.get(parameter)
        if (existingValue != null) {
            // I think this is correct behavior...
            return Try.Error("Not anticipating seeing the same parameter type twice")
        }
        map.put(parameter, chosenType)
    }

    return Try.Success(map)
}

private fun parameterizeType(typeWithWrongParameters: Type, typeParameterStrings: List<String>, chosenTypes: List<Type>): Try<Type> {
    val typeParameters = typeParameterStrings.map { string -> Type.NamedType(FunctionId(Package.EMPTY, string), listOf()) }
    val parameterMapMaybe = makeParameterMap(typeParameters, chosenTypes)
    val parameterMap = when (parameterMapMaybe) {
        is Try.Error -> return parameterMapMaybe.cast()
        is Try.Success -> parameterMapMaybe.result
    }
    return Try.Success(typeWithWrongParameters.replacingParameters(parameterMap));
}

private fun validateLiteralExpression(expression: Expression.Literal): Try<TypedExpression> {
    val nativeType = getTypeValidatorFor(expression.type)
    val isValid = nativeType.validate(expression.literal)
    if (!isValid) {
        return Try.Error("Invalid literal value '${expression.literal}' for type '${expression.type}'")
    }
    return Try.Success(TypedExpression.Literal(expression.type, expression.literal))
}

private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
    val conditionMaybe = validateExpression(expression.condition, variableTypes, functionTypeSignatures, structs, containingFunctionId)
    //TODO: Use this pattern elsewhere
    val condition = when (conditionMaybe) {
        is Try.Error -> return conditionMaybe
        is Try.Success -> conditionMaybe.result
    }

    if (condition.type != Type.BOOLEAN) {
        return Try.Error("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
    }

    val thenBlockMaybe = validateBlock(expression.thenBlock, variableTypes, functionTypeSignatures, structs, containingFunctionId)
    val thenBlock = when (thenBlockMaybe) {
        is Try.Error -> return thenBlockMaybe.cast()
        is Try.Success -> thenBlockMaybe.result
    }
    val elseBlockMaybe = validateBlock(expression.elseBlock, variableTypes, functionTypeSignatures, structs, containingFunctionId)
    val elseBlock = when (elseBlockMaybe) {
        is Try.Error -> return elseBlockMaybe.cast()
        is Try.Success -> elseBlockMaybe.result
    }

    val typeMaybe = typeUnion(thenBlock.type, elseBlock.type)
    val type = when (typeMaybe) {
        is Try.Error -> return Try.Error("In function $containingFunctionId, could not reconcile 'then' and 'else' block types: " + typeMaybe.errorMessage)
        is Try.Success -> typeMaybe.result
    }

    return Try.Success(TypedExpression.IfThen(type, condition, thenBlock, elseBlock))
}

private fun typeUnion(type1: Type, type2: Type): Try<Type> {
    // TODO: Handle actual type unions, inheritance as these things get added
    // TODO: Then move this stuff to the API level
    if (type1 == type2) {
        return Try.Success(type1)
    }
    return Try.Error("Types $type1 and $type2 cannot be unioned")
}

private fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, containingFunctionId: FunctionId): Try<TypedExpression> {
    val type = variableTypes[expression.name]
    if (type != null) {
        return Try.Success(TypedExpression.Variable(type, expression.name))
    } else {
        return Try.Error("In function $containingFunctionId, reference to unknown variable ${expression.name}")
    }
}

private fun getFunctionTypeSignatures(context: InterpreterContext): Try<Map<FunctionId, TypeSignature>> {
    val signatures = HashMap<FunctionId, TypeSignature>()
    addNativeFunctions(signatures)
    context.structs.entries.forEach { entry ->
        val (id, struct) = entry
        if (signatures.containsKey(id)) {
            return Try.Error("Struct name $id has an overlap with a native function")
        }
        signatures.put(id, getStructSignature(struct))
    }
    context.functions.entries.forEach { entry ->
        val (id, function) = entry
        if (signatures.containsKey(id)) {
            if (context.structs.keys.contains(id)) {
                return Try.Error("Function name $id overlaps with a struct's constructor name")
            } else {
                return Try.Error("Function name $id overlaps with a native function")
            }
        }
        signatures.put(id, getFunctionSignature(function))
    }
    return Try.Success(signatures)
}

private fun addNativeFunctions(signatures: HashMap<FunctionId, TypeSignature>) {
    signatures.putAll(getNativeFunctionDefinitions())
}

private fun getFunctionSignature(function: Function): TypeSignature {
    val argumentTypes = function.arguments.map(Argument::type)
    return TypeSignature(function.id, argumentTypes, function.returnType, function.typeParameters)
}

private fun getStructSignature(struct: Struct): TypeSignature {
    val argumentTypes = struct.members.map(Member::type)
    val typeParameters = struct.typeParameters
    // TODO: Method for making a type parameter type (String -> Type)
    val outputType = Type.NamedType(struct.id, typeParameters.map { id -> Type.NamedType(FunctionId(Package.EMPTY, id), listOf()) })
    return TypeSignature(struct.id, argumentTypes, outputType, typeParameters)
}
