package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

typealias ExpressionPredicate = (Expression) -> Boolean

// TODO: Test
// TODO: Better document
fun simplifyAllExpressions(context: RawContext): RawContext {
    return ExpressionHoister(context, {true}).apply()
}

fun hoistMatchingExpressions(context: RawContext, shouldHoist: ExpressionPredicate): RawContext {
    return ExpressionHoister(context, shouldHoist).apply()
}

private class ExpressionHoister(val originalContext: RawContext, val shouldHoist: ExpressionPredicate) {
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
            hoistExpressionsInBlock(requires, oldStruct.members.map(UnvalidatedMember::name), shouldHoist)
        })
    }

    private fun simplifyFunctionExpressions(function: Function): Function {
        val varsInScope = function.arguments.map(UnvalidatedArgument::name)
        val newBlock = hoistExpressionsInBlock(function.block, varsInScope, shouldHoist)
        return Function(function.id, function.typeParameters, function.arguments, function.returnType, newBlock, function.annotations)
    }
}

private fun hoistExpressionsInBlock(block: Block, varsAlreadyInScope: Collection<String>, shouldHoist: ExpressionPredicate): Block {
    return ExpressionsInBlockHoister(block, varsAlreadyInScope, shouldHoist).apply()
}

private class ExpressionsInBlockHoister(val block: Block, varsAlreadyInScope: Collection<String>, val shouldHoist: ExpressionPredicate) {
    val varNamesInScope = HashSet<String>(varsAlreadyInScope)
    val varNamesToPreserve = HashSet<String>(varsAlreadyInScope + getAllDeclaredVarNames(block))

    val newAssignments = ArrayList<Assignment>()

    fun apply(): Block {
        for (assignment in block.assignments) {
            val splitResult = trySplitting(assignment.expression)
            newAssignments.add(Assignment(assignment.name, assignment.type, splitResult))
        }

        val newReturnedExpression = tryMakingIntoVar(block.returnedExpression)

        return Block(newAssignments, newReturnedExpression)
    }

    /**
     * Returns an expression of the same expression type, with this transformation applied. As a side effect, adds any
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

                Expression.ListLiteral(newContents, expression.chosenParameter)
            }
            is Expression.Follow -> {
                val result = tryMakingIntoVar(expression.structureExpression)
                Expression.Follow(result, expression.name)
            }
            is Expression.IfThen -> {
                val conditionResult = tryMakingIntoVar(expression.condition)
                val simplifiedThenBlock = hoistExpressionsInBlock(expression.thenBlock, varNamesInScope, shouldHoist)
                val simplifiedElseBlock = hoistExpressionsInBlock(expression.elseBlock, varNamesInScope, shouldHoist)

                Expression.IfThen(
                        conditionResult,
                        simplifiedThenBlock,
                        simplifiedElseBlock)
            }
            is Expression.ExpressionFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val result = tryMakingIntoVar(argument)
                    result
                }

                val result = tryMakingIntoVar(expression.functionExpression)

                Expression.ExpressionFunctionCall(result, newArguments, expression.chosenParameters)
            }
            is Expression.NamedFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val result = tryMakingIntoVar(argument)
                    result
                }

                Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters)
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

                Expression.ExpressionFunctionBinding(result, newBindings, expression.chosenParameters)
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

                Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters)
            }
            is Expression.InlineFunction -> {
                val block = hoistExpressionsInBlock(expression.block, varNamesInScope, shouldHoist)
                Expression.InlineFunction(expression.arguments, expression.returnType, block)
            }
        }
    }

    /**
     * Returns a transformed version of the expression or a variable. This transformation will also be applied to any
     * components of the expression. As a side effect, adds any additional assignments needed to define any newly
     * required variables to [newAssignments]. The assignments will give the new expression the same value as the given
     * expression.
     */
    private fun tryMakingIntoVar(expression: Expression): Expression {
        if (expression is Expression.Variable) {
            return expression
        }

        val expressionWithNewContents = when (expression) {
            is Expression.Variable -> {
                error("This case should already have been handled")
            }
            is Expression.Follow -> {
                val subresult = tryMakingIntoVar(expression.structureExpression)

                Expression.Follow(subresult, expression.name)
            }
            is Expression.Literal -> {
                expression
            }
            is Expression.ListLiteral -> {
                val newContents = expression.contents.map { item ->
                    val subresult = tryMakingIntoVar(item)
                    subresult
                }

                Expression.ListLiteral(newContents, expression.chosenParameter)
            }
            is Expression.IfThen -> {
                val subresult = tryMakingIntoVar(expression.condition)

                val simplifiedThenBlock = hoistExpressionsInBlock(expression.thenBlock, varNamesInScope, shouldHoist)
                val simplifiedElseBlock = hoistExpressionsInBlock(expression.elseBlock, varNamesInScope, shouldHoist)

                Expression.IfThen(
                        subresult,
                        simplifiedThenBlock,
                        simplifiedElseBlock)
            }
            is Expression.ExpressionFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val subresult = tryMakingIntoVar(argument)
                    subresult
                }

                val subresult = tryMakingIntoVar(expression.functionExpression)

                Expression.ExpressionFunctionCall(subresult, newArguments, expression.chosenParameters)
            }
            is Expression.NamedFunctionCall -> {
                val newArguments = expression.arguments.map { argument ->
                    val subresult = tryMakingIntoVar(argument)
                    subresult
                }

                Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters)
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

                Expression.ExpressionFunctionBinding(subresult, newBindings, expression.chosenParameters)
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

                Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters)
            }
            is Expression.InlineFunction -> {
                val block = hoistExpressionsInBlock(expression.block, varNamesInScope, shouldHoist)
                Expression.InlineFunction(expression.arguments, expression.returnType, block)
            }
        }
        return if (shouldHoist(expressionWithNewContents)) {
            val replacementName = createAndRecordNewVarName()
            newAssignments.add(Assignment(replacementName, null, expressionWithNewContents))
            Expression.Variable(replacementName)
        } else {
            expressionWithNewContents
        }
    }

    private fun createAndRecordNewVarName(): String {
        val replacementName = getNewVarName()
        varNamesToPreserve.add(replacementName)
        varNamesInScope.add(replacementName)
        return replacementName
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
