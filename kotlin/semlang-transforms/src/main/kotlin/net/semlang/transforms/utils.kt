package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

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
    val unused: Unit = when (expression) {
        is TypedExpression.IfThen -> {
            addAllDeclaredVarNames(expression.thenBlock, varNames)
            addAllDeclaredVarNames(expression.elseBlock, varNames)
        }
        is TypedExpression.InlineFunction -> {
            expression.arguments.forEach { varNames.add(it.name) }
            addAllDeclaredVarNames(expression.block, varNames)
        }
        is TypedExpression.Variable -> {}
        is TypedExpression.NamedFunctionCall -> {
            expression.arguments.forEach { addAllDeclaredVarNames(it, varNames) }
        }
        is TypedExpression.ExpressionFunctionCall -> {
            addAllDeclaredVarNames(expression.functionExpression, varNames)
            expression.arguments.forEach { addAllDeclaredVarNames(it, varNames) }
        }
        is TypedExpression.Literal -> {}
        is TypedExpression.ListLiteral -> {
            expression.contents.forEach { addAllDeclaredVarNames(it, varNames) }
        }
        is TypedExpression.NamedFunctionBinding -> {
            expression.bindings.forEach { binding ->
                if (binding != null) {
                    addAllDeclaredVarNames(binding, varNames)
                }
            }
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            addAllDeclaredVarNames(expression.functionExpression, varNames)
            expression.bindings.forEach { binding ->
                if (binding != null) {
                    addAllDeclaredVarNames(binding, varNames)
                }
            }
        }
        is TypedExpression.Follow -> {
            addAllDeclaredVarNames(expression.structureExpression, varNames)
        }
    }
}

private fun addAllDeclaredVarNames(assignment: ValidatedAssignment, varNames: HashSet<String>) {
    varNames.add(assignment.name)
    addAllDeclaredVarNames(assignment.expression, varNames)
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
        val assignments = block.assignments.map(this::apply)
        val returnedExpression = apply(block.returnedExpression)
        return Block(assignments, returnedExpression, block.location)
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
                Expression.NamedFunctionBinding(expression.functionRef, expression.chosenParameters, bindings, expression.location)
            }
            is Expression.ExpressionFunctionBinding -> {
                val functionExpression = apply(expression.functionExpression)
                val bindings = expression.bindings.map { if (it == null) null else apply(it) }
                Expression.ExpressionFunctionBinding(functionExpression, expression.chosenParameters, bindings, expression.location)
            }
            is Expression.Follow -> {
                val structureExpression = apply(expression.structureExpression)
                Expression.Follow(structureExpression, expression.name, expression.location)
            }
            is Expression.InlineFunction -> {
                val block = apply(expression.block)
                Expression.InlineFunction(expression.arguments, block, expression.location)
            }
        }
        val transformationOutput = transformation(withInnerTransformations)
        if (transformationOutput != null) {
            return transformationOutput
        } else {
            return withInnerTransformations
        }
    }

    private fun apply(assignment: Assignment): Assignment {
        val expression = apply(assignment.expression)
        return Assignment(assignment.name, assignment.type, expression, assignment.nameLocation)
    }
}
