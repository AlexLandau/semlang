package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

/**
 * Replaces the names of any variables or arguments in the context in a consistent way.
 *
 * Note: This currently drops locations from the context.
 */
fun constrainVariableNames(context: RawContext, renamingStrategy: RenamingStrategy): RawContext {
    val validatingStrategy = getValidatingStrategy(renamingStrategy)
    val functions = renameWithinFunctions(context.functions, validatingStrategy)
    return RawContext(functions, context.structs, context.unions)
}

private fun getValidatingStrategy(delegate: RenamingStrategy): RenamingStrategy {
    return fun (varName: String, allVarNamesPresent: Set<String>): String {
        if (varName.isBlank()) {
            error("Variable names should not be blank; incoming variable name was $varName")
        }
        val newName = delegate(varName, allVarNamesPresent)
        if (newName != varName && allVarNamesPresent.contains(newName)) {
            error("A variable renaming strategy resulted in a name that is already in use: '$varName' to '$newName'")
        }
        if (newName.isBlank()) {
            error("A variable renaming strategy resulted in a blank variable name: '$varName' to '$newName'")
        }
        //TODO: Further validation
        return newName
    }
}

private fun renameWithinFunctions(functions: List<Function>, rename: RenamingStrategy): List<Function> {
    return functions.map { function -> renameWithinFunction(function, rename) }
}

private fun renameWithinFunction(function: Function, rename: RenamingStrategy): Function {
    val originalVarsInFunction = getAllDeclaredVarNames(function)
    val allVarsInFunction = HashSet<String>(originalVarsInFunction)
    val renamingMap = HashMap<String, String>()
    for (varName in originalVarsInFunction) {
        val newName = rename(varName, allVarsInFunction)
        renamingMap.put(varName, newName)
        allVarsInFunction.add(newName)
    }
    return VariableRenamer(renamingMap).apply(function)
}

// Note: This can't just be replaced by the PostvisitExpressionReplacer because we also need to replace
// assignment variable names and arguments in inline functions.
private class VariableRenamer(val renamingMap: Map<String, String>) {
    fun apply(function: Function): Function {
        val arguments = function.arguments.map { argument -> apply(argument) }
        val block = apply(function.block)
        return function.copy(arguments = arguments, block = block)
    }

    private fun apply(block: Block): Block {
        val statements = block.statements.map { statement -> apply(statement) }
        return Block(statements)
    }

    private fun apply(statement: Statement): Statement {
        return when (statement) {
            is Statement.Assignment -> {
                val varName = statement.name
                val newName = renamingMap[varName] ?: error("Bug in renaming")
                val expression = apply(statement.expression)
                return Statement.Assignment(newName, statement.type, expression)
            }
            is Statement.Bare -> Statement.Bare(apply(statement.expression))
        }
    }

    private fun apply(expression: Expression): Expression {
        return when (expression) {
            is Expression.Variable -> {
                val newName = renamingMap[expression.name] ?: error("Bug in renaming")
                Expression.Variable(newName)
            }
            is Expression.IfThen -> {
                val condition = apply(expression.condition)
                val thenBlock = apply(expression.thenBlock)
                val elseBlock = apply(expression.elseBlock)
                Expression.IfThen(condition, thenBlock, elseBlock)
            }
            is Expression.NamedFunctionCall -> {
                val arguments = expression.arguments.map { argument -> apply(argument) }
                Expression.NamedFunctionCall(expression.functionRef, arguments, expression.chosenParameters)
            }
            is Expression.ExpressionFunctionCall -> {
                val functionExpression = apply(expression.functionExpression)
                val arguments = expression.arguments.map { argument -> apply(argument) }
                Expression.ExpressionFunctionCall(functionExpression, arguments, expression.chosenParameters)
            }
            is Expression.Literal -> {
                expression
            }
            is Expression.ListLiteral -> {
                val contents = expression.contents.map { item -> apply(item) }
                Expression.ListLiteral(contents, expression.chosenParameter)
            }
            is Expression.Follow -> {
                val structureExpression = apply(expression.structureExpression)
                Expression.Follow(structureExpression, expression.name)
            }
            is Expression.NamedFunctionBinding -> {
                val bindings = expression.bindings.map { binding ->
                    if (binding == null) null else apply(binding)
                }
                Expression.NamedFunctionBinding(expression.functionRef, bindings, expression.chosenParameters)
            }
            is Expression.ExpressionFunctionBinding -> {
                val functionExpression = apply(expression.functionExpression)
                val bindings = expression.bindings.map { binding ->
                    if (binding == null) null else apply(binding)
                }
                Expression.ExpressionFunctionBinding(functionExpression, bindings, expression.chosenParameters)
            }
            is Expression.InlineFunction -> {
                val arguments = expression.arguments.map { argument -> apply(argument) }
                val block = apply(expression.block)
                Expression.InlineFunction(arguments, expression.returnType, block)
            }
        }
    }

    private fun apply(argument: UnvalidatedArgument): UnvalidatedArgument {
        return UnvalidatedArgument(
            renamingMap[argument.name] ?: error("Bug in renaming; name is ${argument.name}, map is $renamingMap"),
            argument.type
        )
    }
}