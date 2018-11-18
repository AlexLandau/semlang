package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function
import java.util.regex.Pattern

/**
 * This transforms the context such that all variables within a given function or requires block have different names.
 *
 * In some cases when transpiling to another language, the scoping of variables shifts in such a way that two variables
 * come into the same scope despite being in separate Semlang scopes. This protects against that by giving all variables
 * across the function or block different names, regardless of scope.
 */
fun preventDuplicateVariableNames(context: RawContext): RawContext {
    val functions = context.functions.map(::renameVariablesUniquely)
    val structs = context.structs.map(::renameVariablesUniquely)
    return RawContext(functions, structs, context.interfaces, context.unions)
}

private fun renameVariablesUniquely(function: Function): Function {
    val argumentNames = function.arguments.map { it.name }
    val block = UniqueVariableRenamer(function.block, argumentNames).apply()
    return Function(function.id, function.typeParameters, function.arguments, function.returnType, block, function.annotations)
}

private fun renameVariablesUniquely(struct: UnvalidatedStruct): UnvalidatedStruct {
    val requires = struct.requires?.let { requires ->
        val memberNames = struct.members.map { it.name }
        UniqueVariableRenamer(requires, memberNames).apply()
    }
    return UnvalidatedStruct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations)
}

private val endingNumberPattern = Pattern.compile("_([0-9]+)$")

private class UniqueVariableRenamer(val initialBlock: Block, argumentNames: Collection<String>) {
    val alreadyUsedNames = HashSet<String>(argumentNames)

    fun apply(): Block {
        return apply(initialBlock, HashMap())
    }

    private fun apply(block: Block, varTransformations: MutableMap<String, String>): Block {
        val assignments = block.statements.map { statement ->
            val expression = applyTransformations(statement.expression, varTransformations)
            val originalVarName = statement.name
            if (originalVarName == null) {
                Statement(null, statement.type, expression)
            } else {
                val newVarName = ensureUnusedVarName(originalVarName)
                varTransformations[originalVarName] = newVarName
                alreadyUsedNames.add(newVarName)
                Statement(newVarName, statement.type, expression)
            }
        }
        val returnedExpression = applyTransformations(block.returnedExpression, varTransformations)
        return Block(assignments, returnedExpression)
    }

    private fun ensureUnusedVarName(originalVarName: String): String {
        if (!alreadyUsedNames.contains(originalVarName)) {
            return originalVarName
        }
        // Check if it already ends in an underscore followed by a number
        val endingNumber = getEndingNumberMaybe(originalVarName)
        val prefix = if (endingNumber != null) {
            originalVarName.dropLast(1 + endingNumber.toString().length)
        } else {
            originalVarName
        }
        var numberToTry = if (endingNumber != null) {
            endingNumber + 1
        } else {
            2
        }
        while (true) {
            val candidateVarName = prefix + "_" + numberToTry
            if (!alreadyUsedNames.contains(candidateVarName)) {
                return candidateVarName
            }
            numberToTry++
        }
    }


    private fun getEndingNumberMaybe(originalVarName: String): Int? {
        val matcher = endingNumberPattern.matcher(originalVarName)
        if (matcher.matches()) {
            val intString = matcher.group(1)
            return Integer.parseInt(intString)
        } else {
            return null
        }
    }

    private fun applyTransformations(expression: Expression, varTransformations: MutableMap<String, String>): Expression {
        return when (expression) {
            is Expression.Variable -> {
                varTransformations[expression.name]?.let { Expression.Variable(it) } ?: expression
            }
            is Expression.IfThen -> {
                val condition = applyTransformations(expression.condition, varTransformations)
                // Copy here to fix the scope
                val thenBlock = apply(expression.thenBlock, HashMap(varTransformations))
                val elseBlock = apply(expression.elseBlock, HashMap(varTransformations))
                Expression.IfThen(condition, thenBlock, elseBlock)
            }
            is Expression.NamedFunctionCall -> {
                val arguments = expression.arguments.map { applyTransformations(it, varTransformations) }
                Expression.NamedFunctionCall(expression.functionRef, arguments, expression.chosenParameters)
            }
            is Expression.ExpressionFunctionCall -> {
                val functionExpression = applyTransformations(expression.functionExpression, varTransformations)
                val arguments = expression.arguments.map { applyTransformations(it, varTransformations) }
                Expression.ExpressionFunctionCall(functionExpression, arguments, expression.chosenParameters)
            }
            is Expression.Literal -> expression
            is Expression.ListLiteral -> {
                val contents = expression.contents.map { applyTransformations(it, varTransformations) }
                Expression.ListLiteral(contents, expression.chosenParameter)
            }
            is Expression.NamedFunctionBinding -> {
                val bindings = expression.bindings.map { if (it == null) null else applyTransformations(it, varTransformations) }
                Expression.NamedFunctionBinding(expression.functionRef, bindings, expression.chosenParameters)
            }
            is Expression.ExpressionFunctionBinding -> {
                val functionExpression = applyTransformations(expression.functionExpression, varTransformations)
                val bindings = expression.bindings.map { if (it == null) null else applyTransformations(it, varTransformations) }
                Expression.ExpressionFunctionBinding(functionExpression, bindings, expression.chosenParameters)
            }
            is Expression.Follow -> {
                val structureExpression = applyTransformations(expression.structureExpression, varTransformations)
                Expression.Follow(structureExpression, expression.name)
            }
            is Expression.InlineFunction -> {
                val transformationsForInlineFunction = HashMap(varTransformations)
                val arguments = expression.arguments.map { argument ->
                    val originalVarName = argument.name
                    val newVarName = ensureUnusedVarName(originalVarName)
                    transformationsForInlineFunction[originalVarName] = newVarName
                    alreadyUsedNames.add(newVarName)
                    UnvalidatedArgument(newVarName, argument.type)
                }
                val block = apply(expression.block, transformationsForInlineFunction)
                Expression.InlineFunction(arguments, expression.returnType, block)
            }
        }
    }
}
