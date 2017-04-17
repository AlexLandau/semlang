package semlang.transforms;

import semlang.api.*

// TODO: Test
// TODO: Better document
// TODO: We aren't taking full advantage of this unless/until we switch to a different set of
// data structures that acknowledge the more limited set of possible expressions/assignments
fun simplifyExpressions(context: ValidatedContext): ValidatedContext {
    return ValidatedContext.create(simplifyFunctionExpressions(context.ownFunctionImplementations), context.ownStructs, context.ownInterfaces, context.upstreamContexts)
}

private fun simplifyFunctionExpressions(functions: Map<FunctionId, ValidatedFunction>): Map<FunctionId, ValidatedFunction> {
    val map = HashMap<FunctionId, ValidatedFunction>()

    functions.forEach { id, function ->
        map.put(id, simplifyFunctionExpressions(function))
    }

    return map
}

private fun simplifyFunctionExpressions(function: ValidatedFunction): ValidatedFunction {
    val varsInScope = function.arguments.map(Argument::name)
    val newBlock = simplifyBlockExpressions(function.block, varsInScope)
    return ValidatedFunction(function.id, function.typeParameters, function.arguments, function.returnType, newBlock, function.annotations)
}

private fun simplifyBlockExpressions(block: TypedBlock, varsAlreadyInScope: Collection<String>): TypedBlock {
    val varsInScope = HashSet<String>(varsAlreadyInScope)
    val varNamesToPreserve = HashSet<String>(varsAlreadyInScope)
    varNamesToPreserve.addAll(getAllDeclaredVarNames(block))

    val newAssignments = ArrayList<ValidatedAssignment>()
    for (assignment in block.assignments) {
        val splitResult = trySplitting(assignment.expression, varNamesToPreserve, varsInScope)
        newAssignments.addAll(splitResult.splitAssignments)
        newAssignments.add(ValidatedAssignment(assignment.name, assignment.type, splitResult.modifiedExpression))
    }

    val splitResult = tryMakingIntoVar(block.returnedExpression, varNamesToPreserve, varsInScope)
    newAssignments.addAll(splitResult.newAssignments)
    val newReturnedExpression = splitResult.variable

    return TypedBlock(block.type, newAssignments, newReturnedExpression)
}

/**
 * Returns an expression of the simplest possible form of the same expression type and any additional assignments
 * needed to define any newly required variables.
 *
 * Note that expressions containing blocks (namely if-then expressions) will have their blocks simplified, but will
 * not have their contents flattened (i.e. moved outside of the blocks).
 */
private fun trySplitting(expression: TypedExpression, varNamesToPreserve: MutableSet<String>, varNamesInScope: MutableSet<String>): ExpressionMultisplitResult {
    return when (expression) {
        is TypedExpression.Variable -> {
            ExpressionMultisplitResult(expression, listOf())
        }
        is TypedExpression.Literal -> {
            ExpressionMultisplitResult(expression, listOf())
        }
        is TypedExpression.Follow -> {
            val result = tryMakingIntoVar(expression.expression, varNamesToPreserve, varNamesInScope)
            val replacementExpression = TypedExpression.Follow(expression.type, result.variable, expression.id)
            ExpressionMultisplitResult(replacementExpression, result.newAssignments)
        }
        is TypedExpression.IfThen -> {
            val conditionResult = tryMakingIntoVar(expression.condition, varNamesToPreserve, varNamesInScope)
            val simplifiedThenBlock = simplifyBlockExpressions(expression.thenBlock, varNamesInScope)
            val simplifiedElseBlock = simplifyBlockExpressions(expression.elseBlock, varNamesInScope)

            val replacementExpression = TypedExpression.IfThen(expression.type,
                    conditionResult.variable,
                    simplifiedThenBlock,
                    simplifiedElseBlock)

            ExpressionMultisplitResult(replacementExpression, conditionResult.newAssignments)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newArguments = expression.arguments.map { argument ->
                val result = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(result.newAssignments)
                result.variable
            }

            val result = tryMakingIntoVar(expression.functionExpression, varNamesToPreserve, varNamesInScope)
            newAssignments.addAll(result.newAssignments)

            val replacementExpression = TypedExpression.ExpressionFunctionCall(expression.type, result.variable, newArguments, expression.chosenParameters)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is TypedExpression.NamedFunctionCall -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newArguments = expression.arguments.map { argument ->
                val result = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(result.newAssignments)
                result.variable
            }

            val replacementExpression = TypedExpression.NamedFunctionCall(expression.type, expression.functionId, newArguments, expression.chosenParameters)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newBindings = expression.bindings.map { binding ->
                if (binding == null) {
                    null
                } else {
                    val result = tryMakingIntoVar(binding, varNamesToPreserve, varNamesInScope)
                    newAssignments.addAll(result.newAssignments)
                    result.variable
                }
            }

            val result = tryMakingIntoVar(expression.functionExpression, varNamesToPreserve, varNamesInScope)
            newAssignments.addAll(result.newAssignments)

            val replacementExpression = TypedExpression.ExpressionFunctionBinding(expression.type, result.variable, newBindings, expression.chosenParameters)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newBindings = expression.bindings.map { binding ->
                if (binding == null) {
                    null
                } else {
                    val result = tryMakingIntoVar(binding, varNamesToPreserve, varNamesInScope)
                    newAssignments.addAll(result.newAssignments)
                    result.variable
                }
            }

            val replacementExpression = TypedExpression.NamedFunctionBinding(expression.type, expression.functionId, newBindings, expression.chosenParameters)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
    }
}

/**
 * Returns a variable of the same type and any additional assignments needed to define any newly required variables. The
 * assignments will give the variable the same value as the given expression.
 */
private fun tryMakingIntoVar(expression: TypedExpression, varNamesToPreserve: MutableSet<String>,
                             varNamesInScope: MutableSet<String>): MakeIntoVarResult {
    return when (expression) {
        is TypedExpression.Variable -> {
            MakeIntoVarResult(expression, listOf())
        }
        is TypedExpression.Follow -> {
            val subresult = tryMakingIntoVar(expression.expression, varNamesToPreserve, varNamesInScope)

            val newFollow = TypedExpression.Follow(expression.type, subresult.variable, expression.id)
            val replacementName = getNewVarName(varNamesToPreserve)

            val assignments = subresult.newAssignments + ValidatedAssignment(replacementName, newFollow.type, newFollow)
            val typedVariable = TypedExpression.Variable(expression.type, replacementName)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, assignments)
        }
        is TypedExpression.Literal -> {
            val replacementName = getNewVarNameForLiteral(expression.literal, varNamesToPreserve)

            val assignment = ValidatedAssignment(replacementName, expression.type, expression)
            val typedVariable = TypedExpression.Variable(expression.type, replacementName)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, listOf(assignment))
        }
        is TypedExpression.IfThen -> {
            val subresult = tryMakingIntoVar(expression.condition, varNamesToPreserve, varNamesInScope)

            val simplifiedThenBlock = simplifyBlockExpressions(expression.thenBlock, varNamesInScope)
            val simplifiedElseBlock = simplifyBlockExpressions(expression.elseBlock, varNamesInScope)

            val newIfThen = TypedExpression.IfThen(expression.type,
                    subresult.variable,
                    simplifiedThenBlock,
                    simplifiedElseBlock)
            val replacementName = getNewVarName(varNamesToPreserve)

            val assignments = subresult.newAssignments + ValidatedAssignment(replacementName, newIfThen.type, newIfThen)
            val typedVariable = TypedExpression.Variable(expression.type, replacementName)

            MakeIntoVarResult(typedVariable, assignments)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newArguments = expression.arguments.map { argument ->
                val subresult = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(subresult.newAssignments)
                subresult.variable
            }

            val subresult = tryMakingIntoVar(expression.functionExpression, varNamesToPreserve, varNamesInScope)
            newAssignments.addAll(subresult.newAssignments)

            val newFunctionCall = TypedExpression.ExpressionFunctionCall(expression.type, subresult.variable, newArguments, expression.chosenParameters)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(ValidatedAssignment(replacementName, newFunctionCall.type, newFunctionCall))
            val typedVariable = TypedExpression.Variable(expression.type, replacementName)

            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is TypedExpression.NamedFunctionCall -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newArguments = expression.arguments.map { argument ->
                val subresult = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(subresult.newAssignments)
                subresult.variable
            }

            val newFunctionCall = TypedExpression.NamedFunctionCall(expression.type, expression.functionId, newArguments, expression.chosenParameters)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(ValidatedAssignment(replacementName, newFunctionCall.type, newFunctionCall))
            val typedVariable = TypedExpression.Variable(expression.type, replacementName)

            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newBindings = expression.bindings.map { binding ->
                if (binding == null) {
                    null
                } else {
                    val subresult = tryMakingIntoVar(binding, varNamesToPreserve, varNamesInScope)
                    newAssignments.addAll(subresult.newAssignments)
                    subresult.variable
                }
            }

            val subresult = tryMakingIntoVar(expression.functionExpression, varNamesToPreserve, varNamesInScope)
            newAssignments.addAll(subresult.newAssignments)

            val newFunctionCall = TypedExpression.ExpressionFunctionBinding(expression.type, subresult.variable, newBindings, expression.chosenParameters)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(ValidatedAssignment(replacementName, newFunctionCall.type, newFunctionCall))
            val typedVariable = TypedExpression.Variable(expression.type, replacementName)

            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val newAssignments = ArrayList<ValidatedAssignment>()

            val newBindings = expression.bindings.map { binding ->
                if (binding == null) {
                    null
                } else {
                    val subresult = tryMakingIntoVar(binding, varNamesToPreserve, varNamesInScope)
                    newAssignments.addAll(subresult.newAssignments)
                    subresult.variable
                }
            }

            val newFunctionCall = TypedExpression.NamedFunctionBinding(expression.type, expression.functionId, newBindings, expression.chosenParameters)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(ValidatedAssignment(replacementName, newFunctionCall.type, newFunctionCall))
            val typedVariable = TypedExpression.Variable(expression.type, replacementName)

            MakeIntoVarResult(typedVariable, newAssignments)
        }
    }
}

private fun getNewVarNameForLiteral(literalValue: String, varNamesToPreserve: MutableSet<String>): String {
    // TODO: Implement. This is a little tricky in that we have to remove certain characters and avoid
    // empty and _-only strings
    return getNewVarName(varNamesToPreserve)
}

// TODO: Use better approaches than this to come up with names
private fun getNewVarName(varNamesToPreserve: MutableSet<String>): String {
    var i = 1
    while (true) {
        val name = "temp_" + i
        if (!varNamesToPreserve.contains(name)) {
            return name;
        }
        i++
    }
}

private data class MakeIntoVarResult(val variable: TypedExpression.Variable, val newAssignments: List<ValidatedAssignment>)

private data class ExpressionMultisplitResult(val modifiedExpression: TypedExpression, val splitAssignments: List<ValidatedAssignment>)
