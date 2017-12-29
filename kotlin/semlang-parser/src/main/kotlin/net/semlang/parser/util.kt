package net.semlang.parser

import net.semlang.api.TypedBlock
import net.semlang.api.TypedExpression

fun getVarsReferencedIn(validatedBlock: TypedBlock): Set<String> {
    val varsReferenced = HashSet<String>()
    collectVars(varsReferenced, validatedBlock)
    return varsReferenced
}

fun collectVars(varsReferenced: MutableCollection<String>, block: TypedBlock) {
    block.assignments.forEach { assignment ->
        collectVars(varsReferenced, assignment.expression)
    }
    collectVars(varsReferenced, block.returnedExpression)
}

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
