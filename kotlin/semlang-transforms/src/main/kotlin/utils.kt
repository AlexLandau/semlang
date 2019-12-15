package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

fun getAllDeclaredVarNames(function: Function): Set<String> {
    val varNames = LinkedHashSet<String>()
    for (argument in function.arguments) {
        varNames.add(argument.name)
    }
    addAllDeclaredVarNames(function.block, varNames)
    return varNames
}

fun getAllDeclaredVarNames(block: Block): Set<String> {
    val varNames = LinkedHashSet<String>()
    addAllDeclaredVarNames(block, varNames)
    return varNames
}

private fun addAllDeclaredVarNames(block: Block, varNames: HashSet<String>) {
    for (statement in block.statements) {
        addAllDeclaredVarNames(statement, varNames)
    }
    addAllDeclaredVarNames(block.lastStatement, varNames)
}

private fun addAllDeclaredVarNames(expression: Expression, varNames: HashSet<String>) {
    val unused: Unit = when (expression) {
        is Expression.IfThen -> {
            addAllDeclaredVarNames(expression.thenBlock, varNames)
            addAllDeclaredVarNames(expression.elseBlock, varNames)
        }
        is Expression.InlineFunction -> {
            for (argument in expression.arguments) {
                varNames.add(argument.name)
            }
            addAllDeclaredVarNames(expression.block, varNames)
        }
        is Expression.Variable -> {}
        is Expression.NamedFunctionCall -> {
            for (argument in expression.arguments) {
                addAllDeclaredVarNames(argument, varNames)
            }
        }
        is Expression.ExpressionFunctionCall -> {
            addAllDeclaredVarNames(expression.functionExpression, varNames)
            for (argument in expression.arguments) {
                addAllDeclaredVarNames(argument, varNames)
            }
        }
        is Expression.Literal -> {}
        is Expression.ListLiteral -> {
            for (item in expression.contents) {
                addAllDeclaredVarNames(item, varNames)
            }
        }
        is Expression.NamedFunctionBinding -> {
            for (binding in expression.bindings) {
                if (binding != null) {
                    addAllDeclaredVarNames(binding, varNames)
                }
            }
        }
        is Expression.ExpressionFunctionBinding -> {
            addAllDeclaredVarNames(expression.functionExpression, varNames)
            for (binding in expression.bindings) {
                if (binding != null) {
                    addAllDeclaredVarNames(binding, varNames)
                }
            }
        }
        is Expression.Follow -> {
            addAllDeclaredVarNames(expression.structureExpression, varNames)
        }
    }
}

private fun addAllDeclaredVarNames(statement: Statement, varNames: HashSet<String>) {
    val unused = when (statement) {
        is Statement.Assignment -> {
            varNames.add(statement.name)
            addAllDeclaredVarNames(statement.expression, varNames)
        }
        is Statement.Bare -> {
            addAllDeclaredVarNames(statement.expression, varNames)
        }
        is Statement.Return -> {
            addAllDeclaredVarNames(statement.expression, varNames)
        }
    }
}

fun replaceLocalFunctionNameReferences(function: Function, replacements: Map<EntityId, EntityId>): Function {
    return function.copy(
            block = replaceLocalFunctionNameReferences(function.block, replacements)
    )
}

fun replaceLocalFunctionNameReferences(block: Block, replacements: Map<EntityId, EntityId>): Block {
    return replaceSomeExpressionsPostvisit(block, fun(expression: Expression): Expression? {
        if (expression is Expression.NamedFunctionCall) {
            // TODO: Do we need something subtler around the reference as a whole?
            val oldName = expression.functionRef.id
            val replacement = replacements[oldName]
            if (replacement != null) {
                return expression.copy(functionRef = replacement.asRef())
            }
        } else if (expression is Expression.NamedFunctionBinding) {
            // TODO: Do we need something subtler around the reference as a whole?
            val oldName = expression.functionRef.id
            val replacement = replacements[oldName]
            if (replacement != null) {
                return expression.copy(functionRef = replacement.asRef())
            }
        }
        return null
    })
}

/**
 * Given a function from Expression to Expression?, replaces expressions for which the function
 * returns a non-null value with the value returned.
 *
 * As indicated by "postvisit", subexpressions and subblocks of the expression will be replaced
 * *before* the function is applied to the expression itself.
 */
fun replaceSomeExpressionsPostvisit(block: Block, transformation: (Expression) -> Expression?): Block {
    return PostvisitExpressionReplacer(transformation).apply(block)

}

private class PostvisitExpressionReplacer(val transformation: (Expression) -> Expression?) {
    fun apply(block: Block): Block {
        val assignments = block.statements.map(this::apply)
        val lastStatement = apply(block.lastStatement)
        return Block(assignments, lastStatement, block.location)
    }

    private fun apply(expression: Expression): Expression {
        val withInnerTransformations = when (expression) {
            is Expression.Variable -> expression
            is Expression.IfThen -> {
                val condition = apply(expression.condition)
                val thenBlock = apply(expression.thenBlock)
                val elseBlock = apply(expression.elseBlock)
                Expression.IfThen(condition, thenBlock, elseBlock, expression.location)
            }
            is Expression.NamedFunctionCall -> {
                val arguments = expression.arguments.map(this::apply)
                Expression.NamedFunctionCall(expression.functionRef, arguments, expression.chosenParameters, expression.location, expression.functionRefLocation)
            }
            is Expression.ExpressionFunctionCall -> {
                val functionExpression = apply(expression.functionExpression)
                val arguments = expression.arguments.map(this::apply)
                Expression.ExpressionFunctionCall(functionExpression, arguments, expression.chosenParameters, expression.location)
            }
            is Expression.Literal -> expression
            is Expression.ListLiteral -> {
                val contents = expression.contents.map(this::apply)
                Expression.ListLiteral(contents, expression.chosenParameter, expression.location)
            }
            is Expression.NamedFunctionBinding -> {
                val bindings = expression.bindings.map { if (it == null) null else apply(it) }
                Expression.NamedFunctionBinding(expression.functionRef, bindings, expression.chosenParameters, expression.location, expression.functionRefLocation)
            }
            is Expression.ExpressionFunctionBinding -> {
                val functionExpression = apply(expression.functionExpression)
                val bindings = expression.bindings.map { if (it == null) null else apply(it) }
                Expression.ExpressionFunctionBinding(functionExpression, bindings, expression.chosenParameters, expression.location)
            }
            is Expression.Follow -> {
                val structureExpression = apply(expression.structureExpression)
                Expression.Follow(structureExpression, expression.name, expression.location)
            }
            is Expression.InlineFunction -> {
                val block = apply(expression.block)
                Expression.InlineFunction(expression.arguments, expression.returnType, block, expression.location)
            }
        }
        val transformationOutput = transformation(withInnerTransformations)
        if (transformationOutput != null) {
            return transformationOutput
        } else {
            return withInnerTransformations
        }
    }

    private fun apply(statement: Statement): Statement {
        return when (statement) {
            is Statement.Assignment -> {
                val expression = apply(statement.expression)
                Statement.Assignment(statement.name, statement.type, expression, statement.nameLocation)
            }
            is Statement.Bare -> {
                Statement.Bare(apply(statement.expression))
            }
            is Statement.Return -> {
                Statement.Return(apply(statement.expression))
            }
        }
    }
}
