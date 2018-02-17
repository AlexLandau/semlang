package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

// TODO: Test
// TODO: Better document
// TODO: Refactor into a version where we can pull out only those expressions matching certain criteria (e.g. all IfThens)
fun simplifyExpressions(context: RawContext): RawContext {
    return ExpressionHoister(context).apply()
}

private class ExpressionHoister(val originalContext: RawContext) {
    fun apply(): RawContext {
        val functions = originalContext.functions.map(this::simplifyFunctionExpressions)
        val structs = originalContext.structs.map(this::applyToRequiresBlock)
        val interfaces = originalContext.interfaces
        return RawContext(functions, structs, interfaces)
    }

    private fun applyToRequiresBlock(oldStruct: UnvalidatedStruct): UnvalidatedStruct {
        val requires = oldStruct.requires
        return oldStruct.copy(requires = if (requires == null) {
            null
        } else {
            hoistExpressionsInBlock(requires, oldStruct.members.map(UnvalidatedMember::name))
        })
    }

    private fun simplifyFunctionExpressions(function: Function): Function {
        val varsInScope = function.arguments.map(UnvalidatedArgument::name)
        val newBlock = hoistExpressionsInBlock(function.block, varsInScope)
        return Function(function.id, function.typeParameters, function.arguments, function.returnType, newBlock, function.annotations, null, null)
    }
}

private fun hoistExpressionsInBlock(block: Block, varsAlreadyInScope: Collection<String>): Block {
    return ExpressionsInBlockHoister(block, varsAlreadyInScope).apply()
}

private class ExpressionsInBlockHoister(val block: Block, varsAlreadyInScope: Collection<String>) {
    val varNamesInScope = HashSet<String>(varsAlreadyInScope)
    val varNamesToPreserve = HashSet<String>(varsAlreadyInScope + getAllDeclaredVarNames(block))

    val newAssignments = ArrayList<Assignment>()

    fun apply(): Block {
        for (assignment in block.assignments) {
            val splitResult = trySplitting(assignment.expression)
            newAssignments.add(Assignment(assignment.name, assignment.type, splitResult.modifiedExpression, null))
        }

        val splitResult = tryMakingIntoVar(block.returnedExpression)
        val newReturnedExpression = splitResult.variable

        return Block(newAssignments, newReturnedExpression, null)
    }

    /**
     * Returns an expression of the simplest possible form of the same expression type and any additional assignments
     * needed to define any newly required variables.
     *
     * Note that expressions containing blocks (namely if-then expressions) will have their blocks simplified, but will
     * not have their contents flattened (i.e. moved outside of the blocks).
     */
    private fun trySplitting(expression: Expression): ExpressionMultisplitResult {
        return when (expression) {
            is Expression.Variable -> {
                ExpressionMultisplitResult(expression)
            }
            is Expression.Literal -> {
                ExpressionMultisplitResult(expression)
            }
            is Expression.ListLiteral -> {
                val newContents = expression.contents.map { item ->
                    val result = tryMakingIntoVar(item)
                    result.variable
                }

                val replacementExpression = Expression.ListLiteral(newContents, expression.chosenParameter, null)
                ExpressionMultisplitResult(replacementExpression)
            }
            is Expression.Follow -> {
                val result = tryMakingIntoVar(expression.structureExpression)
                val replacementExpression = Expression.Follow(result.variable, expression.name, null)
                ExpressionMultisplitResult(replacementExpression)
            }
            is Expression.IfThen -> {
                val conditionResult = tryMakingIntoVar(expression.condition)
                val simplifiedThenBlock = hoistExpressionsInBlock(expression.thenBlock, varNamesInScope)
                val simplifiedElseBlock = hoistExpressionsInBlock(expression.elseBlock, varNamesInScope)

                val replacementExpression = Expression.IfThen(
                        conditionResult.variable,
                        simplifiedThenBlock,
                        simplifiedElseBlock,
                        null)

                ExpressionMultisplitResult(replacementExpression)
            }
            is Expression.ExpressionFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val result = tryMakingIntoVar(argument)
                    result.variable
                }

                val result = tryMakingIntoVar(expression.functionExpression)

                val replacementExpression = Expression.ExpressionFunctionCall(result.variable, newArguments, expression.chosenParameters, null)
                ExpressionMultisplitResult(replacementExpression)
            }
            is Expression.NamedFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val result = tryMakingIntoVar(argument)
                    result.variable
                }

                val replacementExpression = Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters, null, null)
                ExpressionMultisplitResult(replacementExpression)
            }
            is Expression.ExpressionFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val result = tryMakingIntoVar(binding)
                        result.variable
                    }
                }

                val result = tryMakingIntoVar(expression.functionExpression)

                val replacementExpression = Expression.ExpressionFunctionBinding(result.variable, newBindings, expression.chosenParameters, null)
                ExpressionMultisplitResult(replacementExpression)
            }
            is Expression.NamedFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val result = tryMakingIntoVar(binding)
                        result.variable
                    }
                }

                val replacementExpression = Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters, null)
                ExpressionMultisplitResult(replacementExpression)
            }
            is Expression.InlineFunction -> {
                val block = hoistExpressionsInBlock(expression.block, varNamesInScope)
                val replacementExpression = Expression.InlineFunction(expression.arguments, block, null)
                ExpressionMultisplitResult(replacementExpression)
            }
        }
    }

    /**
     * Returns a variable of the same type and any additional assignments needed to define any newly required variables. The
     * assignments will give the variable the same value as the given expression.
     */
    private fun tryMakingIntoVar(expression: Expression): MakeIntoVarResult {
        return when (expression) {
            is Expression.Variable -> {
                MakeIntoVarResult(expression)
            }
            is Expression.Follow -> {
                val subresult = tryMakingIntoVar(expression.structureExpression)

                val newFollow = Expression.Follow(subresult.variable, expression.name, null)
                val replacementName = createAndRecordNewVarName()

                newAssignments.add(Assignment(replacementName, null, newFollow, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.Literal -> {
                val replacementName = createAndRecordNewVarNameForLiteral(expression.literal)

                newAssignments.add(Assignment(replacementName, null, expression, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.ListLiteral -> {
                val newContents = expression.contents.map { item ->
                    val subresult = tryMakingIntoVar(item)
                    subresult.variable
                }

                val newListLiteral = Expression.ListLiteral(newContents, expression.chosenParameter, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newListLiteral, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.IfThen -> {
                val subresult = tryMakingIntoVar(expression.condition)

                val simplifiedThenBlock = hoistExpressionsInBlock(expression.thenBlock, varNamesInScope)
                val simplifiedElseBlock = hoistExpressionsInBlock(expression.elseBlock, varNamesInScope)

                val newIfThen = Expression.IfThen(
                        subresult.variable,
                        simplifiedThenBlock,
                        simplifiedElseBlock,
                        null)
                val replacementName = createAndRecordNewVarName()

                newAssignments.add(Assignment(replacementName, null, newIfThen, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.ExpressionFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val subresult = tryMakingIntoVar(argument)
                    subresult.variable
                }

                val subresult = tryMakingIntoVar(expression.functionExpression)

                val newFunctionCall = Expression.ExpressionFunctionCall(subresult.variable, newArguments, expression.chosenParameters, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.NamedFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val subresult = tryMakingIntoVar(argument)
                    subresult.variable
                }

                val newFunctionCall = Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters, null, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.ExpressionFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val subresult = tryMakingIntoVar(binding)
                        subresult.variable
                    }
                }

                val subresult = tryMakingIntoVar(expression.functionExpression)

                val newFunctionCall = Expression.ExpressionFunctionBinding(subresult.variable, newBindings, expression.chosenParameters, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.NamedFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val subresult = tryMakingIntoVar(binding)
                        subresult.variable
                    }
                }

                val newFunctionCall = Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
            is Expression.InlineFunction -> {

                val block = hoistExpressionsInBlock(expression.block, varNamesInScope)
                val newInlineFunction = Expression.InlineFunction(expression.arguments, block, null)
                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newInlineFunction, null))
                val typedVariable = Expression.Variable(replacementName, null)

                MakeIntoVarResult(typedVariable)
            }
        }
    }

    private fun createAndRecordNewVarName(): String {
        val replacementName = getNewVarName()
        varNamesToPreserve.add(replacementName)
        varNamesInScope.add(replacementName)
        return replacementName
    }

    private fun createAndRecordNewVarNameForLiteral(literalValue: String): String {
        val replacementName = getNewVarNameForLiteral(literalValue)
        varNamesToPreserve.add(replacementName)
        varNamesInScope.add(replacementName)
        return replacementName
    }

    private fun getNewVarNameForLiteral(literalValue: String): String {
        // TODO: Implement. This is a little tricky in that we have to remove certain characters and avoid
        // empty and _-only strings
        return getNewVarName()
    }

    // TODO: Use better approaches than this to come up with names
    private fun getNewVarName(): String {
        var i = 1
        while (true) {
            val name = "temp_" + i
            if (!varNamesToPreserve.contains(name)) {
                return name
            }
            i++
        }
    }
}

private data class MakeIntoVarResult(val variable: Expression.Variable)

private data class ExpressionMultisplitResult(val modifiedExpression: Expression)
