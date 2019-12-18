package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

fun getAllDeclaredVarNames(function: Function): Set<String> {
    return DeclaredVarNamesCollector.run(function)
}

fun getAllDeclaredVarNames(block: Block): Set<String> {
    return DeclaredVarNamesCollector.run(block)
}

private class DeclaredVarNamesCollector {
    val varNames = HashSet<String>()
    companion object {
        fun run(function: Function): Set<String> {
            val collector = DeclaredVarNamesCollector()
            collector.addFrom(function)
            return collector.varNames
        }

        fun run(block: Block): Set<String> {
            val collector = DeclaredVarNamesCollector()
            collector.addFrom(block)
            return collector.varNames
        }
    }

    private fun addFrom(function: Function) {
        for (argument in function.arguments) {
            varNames.add(argument.name)
        }
        addFrom(function.block)
    }

    private fun addFrom(block: Block) {
        for (statement in block.statements) {
            addFrom(statement)
        }
    }

    private fun addFrom(expression: Expression) {
        val unused: Unit = when (expression) {
            is Expression.IfThen -> {
                addFrom(expression.thenBlock)
                addFrom(expression.elseBlock)
            }
            is Expression.InlineFunction -> {
                for (argument in expression.arguments) {
                    varNames.add(argument.name)
                }
                addFrom(expression.block)
            }
            is Expression.Variable -> {
            }
            is Expression.NamedFunctionCall -> {
                for (argument in expression.arguments) {
                    addFrom(argument)
                }
            }
            is Expression.ExpressionFunctionCall -> {
                addFrom(expression.functionExpression)
                for (argument in expression.arguments) {
                    addFrom(argument)
                }
            }
            is Expression.Literal -> {
            }
            is Expression.ListLiteral -> {
                for (item in expression.contents) {
                    addFrom(item)
                }
            }
            is Expression.NamedFunctionBinding -> {
                for (binding in expression.bindings) {
                    if (binding != null) {
                        addFrom(binding)
                    }
                }
            }
            is Expression.ExpressionFunctionBinding -> {
                addFrom(expression.functionExpression)
                for (binding in expression.bindings) {
                    if (binding != null) {
                        addFrom(binding)
                    }
                }
            }
            is Expression.Follow -> {
                addFrom(expression.structureExpression)
            }
        }
    }

    private fun addFrom(statement: Statement) {
        val unused = when (statement) {
            is Statement.Assignment -> {
                varNames.add(statement.name)
                addFrom(statement.expression)
            }
            is Statement.Bare -> {
                addFrom(statement.expression)
            }
        }
    }
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
        return Block(assignments, block.location)
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
        }
    }
}
