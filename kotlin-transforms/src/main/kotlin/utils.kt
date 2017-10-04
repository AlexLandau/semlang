package net.semlang.transforms

import net.semlang.api.TypedBlock
import net.semlang.api.TypedExpression
import net.semlang.api.ValidatedAssignment
import net.semlang.api.ValidatedFunction

fun getAllDeclaredVarNames(function: ValidatedFunction): Set<String> {
    val varNames = LinkedHashSet<String>()
    function.arguments.forEach { argument ->
        varNames.add(argument.name)
    }
    addAllDeclaredVarNames(function.block, varNames)
    return varNames
}

fun getAllDeclaredVarNames(block: TypedBlock): Set<String> {
    val varNames = LinkedHashSet<String>()
    addAllDeclaredVarNames(block, varNames)
    return varNames
}

private fun addAllDeclaredVarNames(block: TypedBlock, varNames: HashSet<String>) {
    block.assignments.forEach { addAllDeclaredVarNames(it, varNames) }
    addAllDeclaredVarNames(block.returnedExpression, varNames)
}

private fun addAllDeclaredVarNames(expression: TypedExpression, varNames: HashSet<String>) {
    when (expression) {
        is TypedExpression.IfThen -> {
            addAllDeclaredVarNames(expression.thenBlock, varNames)
            addAllDeclaredVarNames(expression.elseBlock, varNames)
        }
    }
}

private fun addAllDeclaredVarNames(assignment: ValidatedAssignment, varNames: HashSet<String>) {
    varNames.add(assignment.name)
    addAllDeclaredVarNames(assignment.expression, varNames)
}

fun containsExpressionSatisfying(block: TypedBlock, predicate: (TypedExpression) -> Boolean): Boolean {
    val reducer = ExpressionReducer<Boolean>(
            { _, expression ->
                predicate(expression)
            },
            { bool -> bool }
    )
    return reducer.reduceExpressionsInBlock(block, false)
}


/**
 * Note: This visits subexpressions before their containing expressions.
 */
// TODO: Consider an implementation where early termination is part of the reducer output vs. checked repeatedly
class ExpressionReducer<T>(private val reducer: (T, TypedExpression) -> T,
                           private val earlyTerminationCondition: ((T) -> Boolean)?) {

    private fun shouldEndEarly(value: T): Boolean {
        return earlyTerminationCondition != null && earlyTerminationCondition.invoke(value)
    }

    fun reduceExpression(expression: TypedExpression,
                             incomingValue: T): T {
        var value = incomingValue
        return when (expression) {
            is TypedExpression.Literal -> {
                value = reducer(value, expression)
                value
            }
            is TypedExpression.Variable -> {
                value = reducer(value, expression)
                value
            }
            is TypedExpression.IfThen -> {
                value = reduceExpression(expression.condition, value)
                if (shouldEndEarly(value)) {
                    return value
                }
                value = reduceExpressionsInBlock(expression.thenBlock, value)
                if (shouldEndEarly(value)) {
                    return value
                }
                value = reduceExpressionsInBlock(expression.elseBlock, value)
                if (shouldEndEarly(value)) {
                    return value
                }
                value = reducer(value, expression)
                value
            }
            is TypedExpression.NamedFunctionCall -> {
                expression.arguments.forEach { argument ->
                    value = reduceExpression(argument, value)
                    if (shouldEndEarly(value)) {
                        return value
                    }
                }
                value = reducer(value, expression)
                value
            }
            is TypedExpression.ExpressionFunctionCall -> {
                value = reduceExpression(expression.functionExpression, value)
                if (shouldEndEarly(value)) {
                    return value
                }
                expression.arguments.forEach { argument ->
                    value = reduceExpression(argument, value)
                    if (shouldEndEarly(value)) {
                        return value
                    }
                }
                value = reducer(value, expression)
                value
            }
            is TypedExpression.Follow -> {
                value = reduceExpression(expression.expression, value)
                if (shouldEndEarly(value)) {
                    return value
                }
                value = reducer(value, expression)
                value
            }
            is TypedExpression.NamedFunctionBinding -> {
                expression.bindings.forEach { binding ->
                    if (binding != null) {
                        value = reduceExpression(binding, value)
                        if (shouldEndEarly(value)) {
                            return value
                        }
                    }
                }
                value = reducer(value, expression)
                value
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                value = reduceExpression(expression.functionExpression, value)
                if (shouldEndEarly(value)) {
                    return value
                }
                expression.bindings.forEach { binding ->
                    if (binding != null) {
                        value = reduceExpression(binding, value)
                        if (shouldEndEarly(value)) {
                            return value
                        }
                    }
                }
                value = reducer(value, expression)
                value
            }
        }
    }

    fun reduceExpressionsInBlock(block: TypedBlock, incomingValue: T): T {
        var value = incomingValue
        block.assignments.forEach { assignment ->
            value = reduceExpression(assignment.expression, value)
            if (earlyTerminationCondition != null && earlyTerminationCondition.invoke(value)) {
                return value
            }
        }
        value = reduceExpression(block.returnedExpression, value)
        return value
    }
}
