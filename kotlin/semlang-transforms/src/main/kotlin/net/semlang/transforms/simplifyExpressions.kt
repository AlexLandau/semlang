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
            newAssignments.add(Assignment(assignment.name, assignment.type, splitResult, null))
        }

        val splitResult = tryMakingIntoVar(block.returnedExpression)
        val newReturnedExpression = splitResult

        return Block(newAssignments, newReturnedExpression, null)
    }

    /**
     * Returns an expression of the simplest possible form of the same expression type. As a side effect, adds any
     * additional needed assignments to [newAssignments].
     *
     * Note that expressions containing blocks (namely if-then expressions) will have their blocks simplified, but will
     * not have their contents flattened (i.e. moved outside of the blocks).
     */
    private fun trySplitting(expression: Expression): Expression {
        return when (expression) {
            is Expression.Variable -> {
                expression
            }
            is Expression.Literal -> {
                expression
            }
            is Expression.ListLiteral -> {
                val newContents = expression.contents.map { item ->
                    val result = tryMakingIntoVar(item)
                    result
                }

                Expression.ListLiteral(newContents, expression.chosenParameter, null)
            }
            is Expression.Follow -> {
                val result = tryMakingIntoVar(expression.structureExpression)
                Expression.Follow(result, expression.name, null)
            }
            is Expression.IfThen -> {
                val conditionResult = tryMakingIntoVar(expression.condition)
                val simplifiedThenBlock = hoistExpressionsInBlock(expression.thenBlock, varNamesInScope)
                val simplifiedElseBlock = hoistExpressionsInBlock(expression.elseBlock, varNamesInScope)

                Expression.IfThen(
                        conditionResult,
                        simplifiedThenBlock,
                        simplifiedElseBlock,
                        null)
            }
            is Expression.ExpressionFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val result = tryMakingIntoVar(argument)
                    result
                }

                val result = tryMakingIntoVar(expression.functionExpression)

                Expression.ExpressionFunctionCall(result, newArguments, expression.chosenParameters, null)
            }
            is Expression.NamedFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val result = tryMakingIntoVar(argument)
                    result
                }

                Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters, null, null)
            }
            is Expression.ExpressionFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val result = tryMakingIntoVar(binding)
                        result
                    }
                }

                val result = tryMakingIntoVar(expression.functionExpression)

                Expression.ExpressionFunctionBinding(result, newBindings, expression.chosenParameters, null)
            }
            is Expression.NamedFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val result = tryMakingIntoVar(binding)
                        result
                    }
                }

                Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters, null)
            }
            is Expression.InlineFunction -> {
                val block = hoistExpressionsInBlock(expression.block, varNamesInScope)
                Expression.InlineFunction(expression.arguments, block, null)
            }
        }
    }

    /**
     * Returns a variable. As a side effect, adds any additional assignments needed to define any newly required
     * variables to [newAssignments]. The assignments will give the variable the same value as the given expression.
     */
    private fun tryMakingIntoVar(expression: Expression): Expression.Variable {
        return when (expression) {
            is Expression.Variable -> {
                expression
            }
            is Expression.Follow -> {
                val subresult = tryMakingIntoVar(expression.structureExpression)

                val newFollow = Expression.Follow(subresult, expression.name, null)
                val replacementName = createAndRecordNewVarName()

                newAssignments.add(Assignment(replacementName, null, newFollow, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.Literal -> {
                val replacementName = createAndRecordNewVarNameForLiteral(expression.literal)

                newAssignments.add(Assignment(replacementName, null, expression, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.ListLiteral -> {
                val newContents = expression.contents.map { item ->
                    val subresult = tryMakingIntoVar(item)
                    subresult
                }

                val newListLiteral = Expression.ListLiteral(newContents, expression.chosenParameter, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newListLiteral, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.IfThen -> {
                val subresult = tryMakingIntoVar(expression.condition)

                val simplifiedThenBlock = hoistExpressionsInBlock(expression.thenBlock, varNamesInScope)
                val simplifiedElseBlock = hoistExpressionsInBlock(expression.elseBlock, varNamesInScope)

                val newIfThen = Expression.IfThen(
                        subresult,
                        simplifiedThenBlock,
                        simplifiedElseBlock,
                        null)
                val replacementName = createAndRecordNewVarName()

                newAssignments.add(Assignment(replacementName, null, newIfThen, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.ExpressionFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val subresult = tryMakingIntoVar(argument)
                    subresult
                }

                val subresult = tryMakingIntoVar(expression.functionExpression)

                val newFunctionCall = Expression.ExpressionFunctionCall(subresult, newArguments, expression.chosenParameters, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.NamedFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val subresult = tryMakingIntoVar(argument)
                    subresult
                }

                val newFunctionCall = Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters, null, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.ExpressionFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val subresult = tryMakingIntoVar(binding)
                        subresult
                    }
                }

                val subresult = tryMakingIntoVar(expression.functionExpression)

                val newFunctionCall = Expression.ExpressionFunctionBinding(subresult, newBindings, expression.chosenParameters, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.NamedFunctionBinding -> {
                val newBindings = expression.bindings.map { binding ->
                    if (binding == null) {
                        null
                    } else {
                        val subresult = tryMakingIntoVar(binding)
                        subresult
                    }
                }

                val newFunctionCall = Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters, null)

                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
                Expression.Variable(replacementName, null)
            }
            is Expression.InlineFunction -> {

                val block = hoistExpressionsInBlock(expression.block, varNamesInScope)
                val newInlineFunction = Expression.InlineFunction(expression.arguments, block, null)
                val replacementName = createAndRecordNewVarName()
                newAssignments.add(Assignment(replacementName, null, newInlineFunction, null))
                Expression.Variable(replacementName, null)
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
