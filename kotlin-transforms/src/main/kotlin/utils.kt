package semlang.transforms;

import semlang.api.TypedBlock
import semlang.api.TypedExpression
import semlang.api.ValidatedAssignment


fun getAllDeclaredVarNames(block: TypedBlock): Set<String> {
    val varNames = HashSet<String>()
    addAllDeclaredVarNames(block, varNames)
    return varNames
}

fun addAllDeclaredVarNames(block: TypedBlock, varNames: HashSet<String>) {
    block.assignments.forEach { addAllDeclaredVarNames(it, varNames) }
    addAllDeclaredVarNames(block.returnedExpression, varNames)
}

fun addAllDeclaredVarNames(expression: TypedExpression, varNames: HashSet<String>) {
    when (expression) {
        is TypedExpression.IfThen -> {
            addAllDeclaredVarNames(expression.thenBlock, varNames)
            addAllDeclaredVarNames(expression.elseBlock, varNames)
        }
    }
}

fun addAllDeclaredVarNames(assignment: ValidatedAssignment, varNames: HashSet<String>) {
    varNames.add(assignment.name)
    addAllDeclaredVarNames(assignment.expression, varNames)
}
