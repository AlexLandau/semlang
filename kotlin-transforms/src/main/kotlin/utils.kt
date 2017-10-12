package net.semlang.transforms

import net.semlang.api.*

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

fun replaceLocalFunctionNameReferences(function: ValidatedFunction, replacements: Map<EntityId, EntityId>): ValidatedFunction {
    return function.copy(
            block = replaceLocalFunctionNameReferences(function.block, replacements)
    )
}

fun replaceLocalFunctionNameReferences(block: TypedBlock, replacements: Map<EntityId, EntityId>): TypedBlock {
    return TypedBlock(block.type, block.assignments.map { assignment ->
        ValidatedAssignment(assignment.name, assignment.type, replaceLocalFunctionNameReferences(assignment.expression, replacements))
    }, replaceLocalFunctionNameReferences(block.returnedExpression, replacements))
}

// TODO: There should be a more generalizable version of this
fun replaceLocalFunctionNameReferences(expression: TypedExpression, replacements: Map<EntityId, EntityId>): TypedExpression {
    return when (expression) {
        is TypedExpression.Variable -> expression
        is TypedExpression.IfThen -> {
            val condition = replaceLocalFunctionNameReferences(expression.condition, replacements)
            val thenBlock = replaceLocalFunctionNameReferences(expression.thenBlock, replacements)
            val elseBlock = replaceLocalFunctionNameReferences(expression.elseBlock, replacements)
            TypedExpression.IfThen(expression.type, condition, thenBlock, elseBlock)
        }
        is TypedExpression.NamedFunctionCall -> {
            // TODO: Do we need something subtler around the reference as a whole?
            val oldName = expression.functionRef.id
            val newName = replacements[oldName]?.asRef() ?: expression.functionRef
            val arguments = expression.arguments.map { argument ->
                replaceLocalFunctionNameReferences(argument, replacements)
            }
            TypedExpression.NamedFunctionCall(expression.type, newName, arguments, expression.chosenParameters)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val functionExpression = replaceLocalFunctionNameReferences(expression.functionExpression, replacements)
            val arguments = expression.arguments.map { argument ->
                replaceLocalFunctionNameReferences(argument, replacements)
            }
            TypedExpression.ExpressionFunctionCall(expression.type, functionExpression, arguments, expression.chosenParameters)
        }
        is TypedExpression.Literal -> expression
        is TypedExpression.ListLiteral -> {
            val contents = expression.contents.map { item ->
                replaceLocalFunctionNameReferences(item, replacements)
            }
            TypedExpression.ListLiteral(expression.type, contents, expression.chosenParameter)
        }
        is TypedExpression.Follow -> {
            val innerExpression = replaceLocalFunctionNameReferences(expression.expression, replacements)
            TypedExpression.Follow(expression.type, innerExpression, expression.name)
        }
        is TypedExpression.NamedFunctionBinding -> {
            // TODO: Do we need something subtler around the reference as a whole?
            val oldName = expression.functionRef.id
            val newName = replacements[oldName]?.asRef() ?: expression.functionRef
            val bindings = expression.bindings.map { binding ->
                if (binding != null) {
                    replaceLocalFunctionNameReferences(binding, replacements)
                } else {
                    null
                }
            }
            TypedExpression.NamedFunctionBinding(expression.type, newName, bindings, expression.chosenParameters)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val functionExpression = replaceLocalFunctionNameReferences(expression.functionExpression, replacements)
            val bindings = expression.bindings.map { binding ->
                if (binding != null) {
                    replaceLocalFunctionNameReferences(binding, replacements)
                } else {
                    null
                }
            }
            TypedExpression.ExpressionFunctionBinding(expression.type, functionExpression, bindings, expression.chosenParameters)
        }
    }
}
