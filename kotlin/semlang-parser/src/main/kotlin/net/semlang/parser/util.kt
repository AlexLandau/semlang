package net.semlang.parser

import net.semlang.api.Block
import net.semlang.api.Expression
import net.semlang.api.TypedBlock
import net.semlang.api.TypedExpression

fun getVarsReferencedIn(validatedBlock: TypedBlock): Set<String> {
    val varsReferenced = HashSet<String>()
    collectVars(varsReferenced, validatedBlock)
    return varsReferenced
}

fun getVarsReferencedIn(expression: Expression): Set<String> {
    val varsReferenced = HashSet<String>()
    collectVars(varsReferenced, expression)
    return varsReferenced
}

fun collectVars(varsReferenced: MutableCollection<String>, block: TypedBlock) {
    block.assignments.forEach { assignment ->
        collectVars(varsReferenced, assignment.expression)
    }
    collectVars(varsReferenced, block.returnedExpression)
}

fun collectVars(varsReferenced: MutableCollection<String>, block: Block) {
    block.assignments.forEach { assignment ->
        collectVars(varsReferenced, assignment.expression)
    }
    collectVars(varsReferenced, block.returnedExpression)
}

// TODO: Is it necessary to have both of these?
fun collectVars(varsReferenced: MutableCollection<String>, expression: TypedExpression) {
    val unused: Any = when (expression) {
        is TypedExpression.Variable -> {
            varsReferenced.add(expression.name)
        }
        is TypedExpression.IfThen -> {
            collectVars(varsReferenced, expression.condition)
            collectVars(varsReferenced, expression.thenBlock)
            collectVars(varsReferenced, expression.elseBlock)
        }
        is TypedExpression.NamedFunctionCall -> {
            expression.arguments.forEach { argument ->
                collectVars(varsReferenced, argument)
            }
        }
        is TypedExpression.ExpressionFunctionCall -> {
            collectVars(varsReferenced, expression.functionExpression)
            expression.arguments.forEach { argument ->
                collectVars(varsReferenced, argument)
            }
        }
        is TypedExpression.Literal -> {}
        is TypedExpression.ListLiteral -> {
            expression.contents.forEach { item ->
                collectVars(varsReferenced, item)
            }
        }
        is TypedExpression.NamedFunctionBinding -> {
            expression.bindings.forEach { binding ->
                if (binding != null) {
                    collectVars(varsReferenced, binding)
                }
            }
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            collectVars(varsReferenced, expression.functionExpression)
            expression.bindings.forEach { binding ->
                if (binding != null) {
                    collectVars(varsReferenced, binding)
                }
            }
        }
        is TypedExpression.Follow -> {
            collectVars(varsReferenced, expression.structureExpression)
        }
        is TypedExpression.InlineFunction -> {
            collectVars(varsReferenced, expression.block)
        }
    }
}

fun collectVars(varsReferenced: MutableCollection<String>, expression: Expression) {
    val unused: Any = when (expression) {
        is Expression.Variable -> {
            varsReferenced.add(expression.name)
        }
        is Expression.IfThen -> {
            collectVars(varsReferenced, expression.condition)
            collectVars(varsReferenced, expression.thenBlock)
            collectVars(varsReferenced, expression.elseBlock)
        }
        is Expression.NamedFunctionCall -> {
            expression.arguments.forEach { argument ->
                collectVars(varsReferenced, argument)
            }
        }
        is Expression.ExpressionFunctionCall -> {
            collectVars(varsReferenced, expression.functionExpression)
            expression.arguments.forEach { argument ->
                collectVars(varsReferenced, argument)
            }
        }
        is Expression.Literal -> {}
        is Expression.ListLiteral -> {
            expression.contents.forEach { item ->
                collectVars(varsReferenced, item)
            }
        }
        is Expression.NamedFunctionBinding -> {
            expression.bindings.forEach { binding ->
                if (binding != null) {
                    collectVars(varsReferenced, binding)
                }
            }
        }
        is Expression.ExpressionFunctionBinding -> {
            collectVars(varsReferenced, expression.functionExpression)
            expression.bindings.forEach { binding ->
                if (binding != null) {
                    collectVars(varsReferenced, binding)
                }
            }
        }
        is Expression.Follow -> {
            collectVars(varsReferenced, expression.structureExpression)
        }
        is Expression.InlineFunction -> {
            collectVars(varsReferenced, expression.block)
        }
    }
}
