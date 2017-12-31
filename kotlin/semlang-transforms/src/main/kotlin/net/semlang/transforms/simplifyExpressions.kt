package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

// TODO: Test
// TODO: Better document
// TODO: Refactor into a version where we can pull out only those expressions matching certain criteria (e.g. all IfThens)
fun simplifyExpressions(context: RawContext): RawContext {
    val functions = context.functions.map(::simplifyFunctionExpressions)
    val structs = simplifyRequiresBlocks(context.structs)
    val interfaces = context.interfaces
    return RawContext(functions, structs, interfaces)
}

private fun simplifyRequiresBlocks(oldStructs: List<UnvalidatedStruct>): List<UnvalidatedStruct> {
    return oldStructs.map { oldStruct ->
        val requires = oldStruct.requires
        oldStruct.copy(requires = if (requires == null) {
            null
        } else {
            simplifyBlockExpressions(requires, oldStruct.members.map(Member::name))
        })
    }
}

private fun simplifyFunctionExpressions(function: Function): Function {
    val varsInScope = function.arguments.map(UnvalidatedArgument::name)
    val newBlock = simplifyBlockExpressions(function.block, varsInScope)
    return Function(function.id, function.typeParameters, function.arguments, function.returnType, newBlock, function.annotations, null, null)
}

private fun simplifyBlockExpressions(block: Block, varsAlreadyInScope: Collection<String>): Block {
    val varsInScope = HashSet<String>(varsAlreadyInScope)
    val varNamesToPreserve = HashSet<String>(varsAlreadyInScope)
    varNamesToPreserve.addAll(getAllDeclaredVarNames(block))

    val newAssignments = ArrayList<Assignment>()
    for (assignment in block.assignments) {
        val splitResult = trySplitting(assignment.expression, varNamesToPreserve, varsInScope)
        newAssignments.addAll(splitResult.splitAssignments)
        newAssignments.add(Assignment(assignment.name, assignment.type, splitResult.modifiedExpression, null))
    }

    val splitResult = tryMakingIntoVar(block.returnedExpression, varNamesToPreserve, varsInScope)
    newAssignments.addAll(splitResult.newAssignments)
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
private fun trySplitting(expression: Expression, varNamesToPreserve: MutableSet<String>, varNamesInScope: MutableSet<String>): ExpressionMultisplitResult {
    return when (expression) {
        is Expression.Variable -> {
            ExpressionMultisplitResult(expression, listOf())
        }
        is Expression.Literal -> {
            ExpressionMultisplitResult(expression, listOf())
        }
        is Expression.ListLiteral -> {
            val newAssignments = ArrayList<Assignment>()

            val newContents = expression.contents.map { item ->
                val result = tryMakingIntoVar(item, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(result.newAssignments)
                result.variable
            }

            val replacementExpression = Expression.ListLiteral(newContents, expression.chosenParameter, null)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is Expression.Follow -> {
            val result = tryMakingIntoVar(expression.structureExpression, varNamesToPreserve, varNamesInScope)
            val replacementExpression = Expression.Follow(result.variable, expression.name, null)
            ExpressionMultisplitResult(replacementExpression, result.newAssignments)
        }
        is Expression.IfThen -> {
            val conditionResult = tryMakingIntoVar(expression.condition, varNamesToPreserve, varNamesInScope)
            val simplifiedThenBlock = simplifyBlockExpressions(expression.thenBlock, varNamesInScope)
            val simplifiedElseBlock = simplifyBlockExpressions(expression.elseBlock, varNamesInScope)

            val replacementExpression = Expression.IfThen(
                    conditionResult.variable,
                    simplifiedThenBlock,
                    simplifiedElseBlock,
                    null)

            ExpressionMultisplitResult(replacementExpression, conditionResult.newAssignments)
        }
        is Expression.ExpressionFunctionCall -> {
            val newAssignments = ArrayList<Assignment>()

            val newArguments = expression.arguments.map { argument ->
                val result = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(result.newAssignments)
                result.variable
            }

            val result = tryMakingIntoVar(expression.functionExpression, varNamesToPreserve, varNamesInScope)
            newAssignments.addAll(result.newAssignments)

            val replacementExpression = Expression.ExpressionFunctionCall(result.variable, newArguments, expression.chosenParameters, null)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is Expression.NamedFunctionCall -> {
            val newAssignments = ArrayList<Assignment>()

            val newArguments = expression.arguments.map { argument ->
                val result = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(result.newAssignments)
                result.variable
            }

            val replacementExpression = Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters, null, null)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is Expression.ExpressionFunctionBinding -> {
            val newAssignments = ArrayList<Assignment>()

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

            val replacementExpression = Expression.ExpressionFunctionBinding(result.variable, newBindings, expression.chosenParameters, null)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is Expression.NamedFunctionBinding -> {
            val newAssignments = ArrayList<Assignment>()

            val newBindings = expression.bindings.map { binding ->
                if (binding == null) {
                    null
                } else {
                    val result = tryMakingIntoVar(binding, varNamesToPreserve, varNamesInScope)
                    newAssignments.addAll(result.newAssignments)
                    result.variable
                }
            }

            val replacementExpression = Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters, null)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
        is Expression.InlineFunction -> {
            val newAssignments = ArrayList<Assignment>()

            val block = simplifyBlockExpressions(expression.block, varNamesInScope)
            val replacementExpression = Expression.InlineFunction(expression.arguments, block, null)
            ExpressionMultisplitResult(replacementExpression, newAssignments)
        }
    }
}

/**
 * Returns a variable of the same type and any additional assignments needed to define any newly required variables. The
 * assignments will give the variable the same value as the given expression.
 */
private fun tryMakingIntoVar(expression: Expression, varNamesToPreserve: MutableSet<String>,
                             varNamesInScope: MutableSet<String>): MakeIntoVarResult {
    return when (expression) {
        is Expression.Variable -> {
            MakeIntoVarResult(expression, listOf())
        }
        is Expression.Follow -> {
            val subresult = tryMakingIntoVar(expression.structureExpression, varNamesToPreserve, varNamesInScope)

            val newFollow = Expression.Follow(subresult.variable, expression.name, null)
            val replacementName = getNewVarName(varNamesToPreserve)

            val assignments = subresult.newAssignments + Assignment(replacementName, null, newFollow, null)
            val typedVariable = Expression.Variable(replacementName, null)

            //TODO: Handle these in a way that reduces possible inconsistencies
            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, assignments)
        }
        is Expression.Literal -> {
            val replacementName = getNewVarNameForLiteral(expression.literal, varNamesToPreserve)

            val assignment = Assignment(replacementName, null, expression, null)
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, listOf(assignment))
        }
        is Expression.ListLiteral -> {
            val newAssignments = ArrayList<Assignment>()

            val newContents = expression.contents.map { item ->
                val subresult = tryMakingIntoVar(item, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(subresult.newAssignments)
                subresult.variable
            }

            val newListLiteral = Expression.ListLiteral(newContents, expression.chosenParameter, null)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(Assignment(replacementName, null, newListLiteral, null))
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is Expression.IfThen -> {
            val subresult = tryMakingIntoVar(expression.condition, varNamesToPreserve, varNamesInScope)

            val simplifiedThenBlock = simplifyBlockExpressions(expression.thenBlock, varNamesInScope)
            val simplifiedElseBlock = simplifyBlockExpressions(expression.elseBlock, varNamesInScope)

            val newIfThen = Expression.IfThen(
                    subresult.variable,
                    simplifiedThenBlock,
                    simplifiedElseBlock,
                    null)
            val replacementName = getNewVarName(varNamesToPreserve)

            val assignments = subresult.newAssignments + Assignment(replacementName, null, newIfThen, null)
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, assignments)
        }
        is Expression.ExpressionFunctionCall -> {
            val newAssignments = ArrayList<Assignment>()

            val newArguments = expression.arguments.map { argument ->
                val subresult = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(subresult.newAssignments)
                subresult.variable
            }

            val subresult = tryMakingIntoVar(expression.functionExpression, varNamesToPreserve, varNamesInScope)
            newAssignments.addAll(subresult.newAssignments)

            val newFunctionCall = Expression.ExpressionFunctionCall(subresult.variable, newArguments, expression.chosenParameters, null)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is Expression.NamedFunctionCall -> {
            val newAssignments = ArrayList<Assignment>()

            val newArguments = expression.arguments.map { argument ->
                val subresult = tryMakingIntoVar(argument, varNamesToPreserve, varNamesInScope)
                newAssignments.addAll(subresult.newAssignments)
                subresult.variable
            }

            val newFunctionCall = Expression.NamedFunctionCall(expression.functionRef, newArguments, expression.chosenParameters, null, null)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is Expression.ExpressionFunctionBinding -> {
            val newAssignments = ArrayList<Assignment>()

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

            val newFunctionCall = Expression.ExpressionFunctionBinding(subresult.variable, newBindings, expression.chosenParameters, null)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is Expression.NamedFunctionBinding -> {
            val newAssignments = ArrayList<Assignment>()

            val newBindings = expression.bindings.map { binding ->
                if (binding == null) {
                    null
                } else {
                    val subresult = tryMakingIntoVar(binding, varNamesToPreserve, varNamesInScope)
                    newAssignments.addAll(subresult.newAssignments)
                    subresult.variable
                }
            }

            val newFunctionCall = Expression.NamedFunctionBinding(expression.functionRef, newBindings, expression.chosenParameters, null)

            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(Assignment(replacementName, null, newFunctionCall, null))
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, newAssignments)
        }
        is Expression.InlineFunction -> {
            val newAssignments = ArrayList<Assignment>()

            val block = simplifyBlockExpressions(expression.block, varNamesInScope)
            val newInlineFunction = Expression.InlineFunction(expression.arguments, block, null)
            val replacementName = getNewVarName(varNamesToPreserve)
            newAssignments.add(Assignment(replacementName, null, newInlineFunction, null))
            val typedVariable = Expression.Variable(replacementName, null)

            varNamesToPreserve.add(replacementName)
            varNamesInScope.add(replacementName)
            MakeIntoVarResult(typedVariable, newAssignments)
        }
    }
}

private fun getNewVarNameForLiteral(literalValue: String, varNamesToPreserve: Set<String>): String {
    // TODO: Implement. This is a little tricky in that we have to remove certain characters and avoid
    // empty and _-only strings
    return getNewVarName(varNamesToPreserve)
}

// TODO: Use better approaches than this to come up with names
private fun getNewVarName(varNamesToPreserve: Set<String>): String {
    var i = 1
    while (true) {
        val name = "temp_" + i
        if (!varNamesToPreserve.contains(name)) {
            return name
        }
        i++
    }
}

private data class MakeIntoVarResult(val variable: Expression.Variable, val newAssignments: List<Assignment>)

private data class ExpressionMultisplitResult(val modifiedExpression: Expression, val splitAssignments: List<Assignment>)
