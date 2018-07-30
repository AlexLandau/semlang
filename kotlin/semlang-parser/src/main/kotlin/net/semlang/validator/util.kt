package net.semlang.validator

import net.semlang.api.TypedBlock
import net.semlang.api.TypedExpression
import java.util.ArrayList

internal fun getVarsReferencedIn(validatedBlock: TypedBlock): Set<String> {
    val varsReferenced = HashSet<String>()
    collectVars(varsReferenced, validatedBlock)
    return varsReferenced
}

private fun collectVars(varsReferenced: MutableCollection<String>, block: TypedBlock) {
    for (assignment in block.assignments) {
        collectVars(varsReferenced, assignment.expression)
    }
    collectVars(varsReferenced, block.returnedExpression)
}

private fun collectVars(varsReferenced: MutableCollection<String>, expression: TypedExpression) {
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
            for (argument in expression.arguments) {
                collectVars(varsReferenced, argument)
            }
        }
        is TypedExpression.ExpressionFunctionCall -> {
            collectVars(varsReferenced, expression.functionExpression)
            for (argument in expression.arguments) {
                collectVars(varsReferenced, argument)
            }
        }
        is TypedExpression.Literal -> {}
        is TypedExpression.ListLiteral -> {
            for (item in expression.contents) {
                collectVars(varsReferenced, item)
            }
        }
        is TypedExpression.NamedFunctionBinding -> {
            for (binding in expression.bindings) {
                if (binding != null) {
                    collectVars(varsReferenced, binding)
                }
            }
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            collectVars(varsReferenced, expression.functionExpression)
            for (binding in expression.bindings) {
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

/**
 * This (somewhat) mimics the behavior of a Guava ListMultimap.
 */
internal fun <K, V> MutableMap<K, MutableList<V>>.multimapPut(key: K, value: V) {
    val existingListMaybe = this[key]
    if (existingListMaybe != null) {
        existingListMaybe.add(value)
        return
    }
    val newList = ArrayList<V>()
    newList.add(value)
    this.put(key, newList)
}
