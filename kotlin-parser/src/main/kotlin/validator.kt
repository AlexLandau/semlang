package semlang.parser

import semlang.api.*
import semlang.api.Function
import java.util.*

sealed class Try<T> {
    abstract fun <U> ifGood(function: (T) -> Try<U>): Try<U>
    abstract fun assume(): T
    class Success<T>(val result: T) : Try<T>() {
        override fun assume(): T {
            return result
        }

        override fun <U> ifGood(function: (T) -> Try<U>): Try<U> {
            return function.invoke(result)
        }
    }
    class Error<T>(val errorMessage: String) : Try<T>() {
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

fun validateFunctions(functions: Map<FunctionId, Function>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>): Try<Map<FunctionId, ValidatedFunction>> {
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

fun validateFunction(function: Function, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>): Try<ValidatedFunction> {
    //TODO: Validate that no two arguments have the same name
    val variableTypes = getArgumentVariableTypes(function.arguments)
    return validateBlock(function.block, variableTypes, functionTypeSignatures, structs, function.id).ifGood { block ->
        if (function.returnType != block.type) {
            Try.Error<ValidatedFunction>("Function ${function.id}'s stated return type ${function.returnType} does " +
                    "not match the block's actual return type ${block.type}")
        } else {
            Try.Success(ValidatedFunction(function.id, function.arguments, function.returnType, block))
        }
    }
}

fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
    return arguments.associate { argument -> Pair(argument.name, argument.type) }
}

fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedBlock> {
    val variableTypes = HashMap(externalVariableTypes)
    val validatedAssignments = ArrayList<ValidatedAssignment>()
    block.assignments.forEach { assignment ->
        if (variableTypes.containsKey(assignment.name)) {
            return Try.Error("In function $containingFunctionId, there is a reassignment of the already-assigned variable $assignment.name")
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

fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
    return when (expression) {
        is Expression.Variable -> validateVariableExpression(expression, variableTypes, containingFunctionId)
        is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        is Expression.Follow -> validateFollowExpression(expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        is Expression.FunctionCall -> validateFunctionCallExpression(expression, variableTypes, functionTypeSignatures, structs, containingFunctionId)
        is Expression.Literal -> validateLiteralExpression(expression)
    }
}

fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
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
    val type = struct.members[index].type

    return Try.Success(TypedExpression.Follow(type, innerExpression, expression.id))
}

fun validateFunctionCallExpression(expression: Expression.FunctionCall, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
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
    if (argumentTypes != signature.argumentTypes) {
        return Try.Error("The function $containingFunctionId tries to call $functionId with argument types $argumentTypes, but the function expects argument types ${signature.argumentTypes}")
    }

    return Try.Success(TypedExpression.FunctionCall(signature.outputType, functionId, arguments))
}

fun validateLiteralExpression(expression: Expression.Literal): Try<TypedExpression> {
    // TODO: Some day we'll validate literals at compile-time; not yet, though
    return Try.Success(TypedExpression.Literal(expression.type, expression.literal))
}

fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, functionTypeSignatures: Map<FunctionId, TypeSignature>, structs: Map<FunctionId, Struct>, containingFunctionId: FunctionId): Try<TypedExpression> {
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

fun typeUnion(type1: Type, type2: Type): Try<Type> {
    // TODO: Handle actual type unions, inheritance as these things get added
    // TODO: Then move this stuff to the API level
    if (type1 == type2) {
        return Try.Success(type1)
    }
    return Try.Error("Types $type1 and $type2 cannot be unioned")
}

fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, containingFunctionId: FunctionId): Try<TypedExpression> {
    val type = variableTypes[expression.name]
    if (type != null) {
        return Try.Success(TypedExpression.Variable(type, expression.name))
    } else {
        return Try.Error("In function $containingFunctionId, reference to unknown variable ${expression.name}")
    }
}

fun getFunctionTypeSignatures(context: InterpreterContext): Try<Map<FunctionId, TypeSignature>> {
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

fun addNativeFunctions(signatures: HashMap<FunctionId, TypeSignature>) {
    signatures.putAll(getNativeFunctionDefinitions())
}

fun getFunctionSignature(function: Function): TypeSignature {
    val argumentTypes = function.arguments.map(Argument::type)
    return TypeSignature(function.id, argumentTypes, function.returnType)
}

fun getStructSignature(struct: Struct): TypeSignature {
    val argumentTypes = struct.members.map(Member::type)
    return TypeSignature(struct.id, argumentTypes, Type.NamedType(struct.id))
}
