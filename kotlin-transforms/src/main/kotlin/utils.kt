package semlang.transforms;

import semlang.api.TypedBlock
import semlang.api.TypedExpression
import semlang.api.ValidatedAssignment
import semlang.api.ValidatedFunction

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
