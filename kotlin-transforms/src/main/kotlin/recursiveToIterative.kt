package net.semlang.transforms

import net.semlang.api.*

fun recursiveToIterative(module: ValidatedModule): ValidatedModule {
    return ValidatedModule.create(module.id,
            module.nativeModuleVersion,
            recursiveToIterative(module.ownFunctions.values),
            module.ownStructs,
            module.ownInterfaces,
            module.upstreamModules.values)
}

private fun recursiveToIterative(functions: Iterable<ValidatedFunction>): Map<EntityId, ValidatedFunction> {
    val results = HashMap<EntityId, ValidatedFunction>()
    for (function in functions) {
        if (hasSelfCall(function)) {
            val transformed = transform(function)
            results.put(transformed.id, transformed)
        } else {
            results.put(function.id, function)
        }
    }
    return results
}

fun hasSelfCall(function: ValidatedFunction): Boolean {
    val functionId = function.id
    return containsExpressionSatisfying(function.block, { expression ->
        val functionCall = expression as? TypedExpression.NamedFunctionCall
        // TODO: Use the resolved ID, not the ref
        (functionCall != null && functionCall.functionRef.id == functionId)
    })
}

/*
 * TODO: It would be nicer if we could express this in some kind of "match-and-replace" format...
 *
 * First, pretend the last value is in the same format as the assignments (i.e., just a list of expressions)
 * Then one thing we want is "any number of expressions", then "an expression matching this form"
 * Then some kind of manipulations?
 *
 * TODO: Use some kind of "isolateExpressionsMatching" functionality to make this more general
 * TODO: Match recursion in the "else" block
 */
fun transform(function: ValidatedFunction): ValidatedFunction {
    val assignmentIndex = getAssignmentIndexToTransform(function.block.assignments)
    if (assignmentIndex == null) {
        error("Do something better here")
    }
    val precedingAssignments = function.block.assignments.subList(0, assignmentIndex)
    val recursiveAssignment = function.block.assignments[assignmentIndex]
    val followingAssignments = function.block.assignments.subList(assignmentIndex + 1, function.block.assignments.size)
}

fun getAssignmentIndexToTransform(assignments: List<ValidatedAssignment>): Int? {
    assignments.forEachIndexed { index, assignment ->
        val expression = assignment.expression
        if (expression is TypedExpression.IfThen) {
            val thenBlock = expression.thenBlock
            thenBlock.assignments.forEach { innerAssignment ->
                val innerExpression = innerAssignment.expression
                if (innerExpression is TypedExpression.NamedFunctionCall) {

                }
            }
        }
    }
}
