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
    return renameWithinFunction(function, renamingMap)
}

private fun renameWithinFunction(function: Function, renamingMap: Map<String, String>): Function {
    val arguments = function.arguments.map { argument -> renameArgument(argument, renamingMap) }
    val block = renameBlock(function.block, renamingMap)
    return function.copy(arguments = arguments, block = block)
}

private fun renameBlock(block: Block, renamingMap: Map<String, String>): Block {
    val assignments = block.statements.map { statement -> renameWithinStatement(statement, renamingMap) }
    val lastStatement = renameWithinStatement(block.lastStatement, renamingMap)
    return Block(assignments, lastStatement)
}

private fun renameWithinStatement(statement: Statement, renamingMap: Map<String, String>): Statement {
    return when (statement) {
        is Statement.Assignment -> {
            val varName = statement.name
            val newName = renamingMap[varName] ?: error("Bug in renaming")
            val expression = renameWithinExpression(statement.expression, renamingMap)
            return Statement.Assignment(newName, statement.type, expression)
        }
        is Statement.Bare -> Statement.Bare(renameWithinExpression(statement.expression, renamingMap))
        is Statement.Return -> Statement.Return(renameWithinExpression(statement.expression, renamingMap))
    }
}

private fun renameWithinExpression(expression: Expression, renamingMap: Map<String, String>): Expression {
    return when (expression) {
        is Expression.Variable -> {
            val newName = renamingMap[expression.name] ?: error("Bug in renaming")
            Expression.Variable(newName)
        }
        is Expression.IfThen -> {
            val condition = renameWithinExpression(expression.condition, renamingMap)
            val thenBlock = renameBlock(expression.thenBlock, renamingMap)
            val elseBlock = renameBlock(expression.elseBlock, renamingMap)
            Expression.IfThen(condition, thenBlock, elseBlock)
        }
        is Expression.NamedFunctionCall -> {
            val arguments = expression.arguments.map { argument -> renameWithinExpression(argument, renamingMap) }
            Expression.NamedFunctionCall(expression.functionRef, arguments, expression.chosenParameters)
        }
        is Expression.ExpressionFunctionCall -> {
            val functionExpression = renameWithinExpression(expression.functionExpression, renamingMap)
            val arguments = expression.arguments.map { argument -> renameWithinExpression(argument, renamingMap) }
            Expression.ExpressionFunctionCall(functionExpression, arguments, expression.chosenParameters)
        }
        is Expression.Literal -> {
            expression
        }
        is Expression.ListLiteral -> {
            val contents = expression.contents.map { item -> renameWithinExpression(item, renamingMap) }
            Expression.ListLiteral(contents, expression.chosenParameter)
        }
        is Expression.Follow -> {
            val structureExpression = renameWithinExpression(expression.structureExpression, renamingMap)
            Expression.Follow(structureExpression, expression.name)
        }
        is Expression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { binding -> if (binding == null) null else renameWithinExpression(binding, renamingMap) }
            Expression.NamedFunctionBinding(expression.functionRef, bindings, expression.chosenParameters)
        }
        is Expression.ExpressionFunctionBinding -> {
            val functionExpression = renameWithinExpression(expression.functionExpression, renamingMap)
            val bindings = expression.bindings.map { binding -> if (binding == null) null else renameWithinExpression(binding, renamingMap) }
            Expression.ExpressionFunctionBinding(functionExpression, bindings, expression.chosenParameters)
        }
        is Expression.InlineFunction -> {
            val arguments = expression.arguments.map { argument -> renameArgument(argument, renamingMap) }
            val block = renameBlock(expression.block, renamingMap)
            Expression.InlineFunction(arguments, expression.returnType, block)
        }
    }
}

private fun renameArgument(argument: UnvalidatedArgument, renamingMap: Map<String, String>): UnvalidatedArgument {
    return UnvalidatedArgument(renamingMap[argument.name] ?: error("Bug in renaming; name is ${argument.name}, map is $renamingMap"), argument.type)
}
