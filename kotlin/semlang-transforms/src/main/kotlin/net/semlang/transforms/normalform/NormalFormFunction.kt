package net.semlang.transforms.normalform

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.parser.getVarsReferencedIn
import net.semlang.transforms.MutableUniqueList
import net.semlang.transforms.extractInlineFunctions
import net.semlang.transforms.replaceSomeExpressionsPostvisit

// TODO: Steps for this addition:
// Step 1: Have a corpus test showing that turning a function into its normal form, then back again, does not change its
//   meaning (i.e. check that its tests still work).
// Step 2: As part of the test, verify that the output of normalization has all its expected qualities.
// Step 3: Expand to also include requires blocks?

// Subsequent steps: Write an optimization using the normal form; write some framework around applying multiple such
// optimizations.

// TODO: Could there be bad consequences if transformations are applied to unvalidated, incorrect code?
// In particular, it could be bad if said code ended up valid (and with a different semantic meaning) as
// a result; who is responsible for preventing that, the framework or the transformations?

fun convertFunctionsToNormalForm(module: ValidatedModule): RawContext {
    val withoutInlineFunctions = extractInlineFunctions(module)
    val functions = withoutInlineFunctions.functions.map { function ->
        val normalFormContents = getNormalFormContents(function)
        replaceFunctionBlock(function, normalFormContents)
    }
    return withoutInlineFunctions.copy(functions = functions)
}

fun getNormalFormContents(function: Function): NormalFormFunctionContents {
    // TODO: Can we require that inline functions be removed before this is applied? They kind of add their own set of
    // headaches. (Yeah, let's do that and add a function transforming whole modules.)

    // Replace the variable names in our subexpressions with "a"- and "v"- variables
    val oldVarsToNewVars = HashMap<String, Expression.Variable>()
    function.arguments.forEachIndexed { index, argument ->
        oldVarsToNewVars.put(argument.name, Expression.Variable("a${index}", null))
    }

    val untranslatedExpressionList = ArrayList<Expression>()
    untranslatedExpressionList.add(function.block.returnedExpression)

    // TODO: In theory, this should work without the "reversed"...
    function.block.assignments.reversed().forEachIndexed { index, assignment ->
        oldVarsToNewVars.put(assignment.name, Expression.Variable("v${index + 1}", null))
        untranslatedExpressionList.add(assignment.expression)
    }

    val expressionList = translateExpressions(untranslatedExpressionList, oldVarsToNewVars)
    val seminormalContents = NormalFormFunctionContents(expressionList)
    // TODO: Normalize before returning
    val normalContents = seminormalContents.normalize()
    System.out.println(normalContents.components.joinToString("\n  "))
    return normalContents
}

// TODO: This is a bad API
fun replaceFunctionBlock(function: Function, contents: NormalFormFunctionContents): Function {
    val arguments = function.arguments.mapIndexed { index, argument ->
        UnvalidatedArgument("a${index}", argument.type, argument.location)
    }

    // TODO: Do something more intelligent for if blocks
    val returnedExpression = contents.components[0]
    val assignments = contents.components.drop(1).mapIndexed { index, component ->
        Assignment("v${index + 1}", null, component, null)
    }.reversed()
    val block = Block(assignments, returnedExpression, null)

    return Function(function.id, function.typeParameters, arguments, function.returnType, block, function.annotations, null, null)
}

fun translateExpressions(untranslatedExpressionList: ArrayList<Expression>, oldVarsToNewVars: HashMap<String, Expression.Variable>): List<Expression> {
    return untranslatedExpressionList.map { expression ->
        replaceSomeExpressionsPostvisit(expression, fun(expr: Expression): Expression? {
            if (expr is Expression.Variable) {
                val replacement = oldVarsToNewVars.get(expr.name)
//                if (replacement != null) {
//                    System.out.println("$expression -> $replacement")
//                }
                return replacement
            }
            return null
        })
    }
}

/**
 * Represents the "normal form" of a function. This is an alternative representation of the
 * function than the familiar imperative-style system. Objects of this type may be either a
 * "semi-normal" or "normal" form, the latter of which fulfills additional properties.
 *
 * The biggest difference from the imperative representation is that here, the last step of a function is the first
 * component of the list, and components generally depend on the outputs of later components rather than earlier
 * assignments. In other words, the order is backwards from what you would expect in a Function.
 *
 * The following is true of code in both the semi-normal and normal forms:
 * - The first expression represents the value returned by the function.
 * - Variables consist of the letter "a" or "v" followed by a number.
 *   - Variables starting with "a" represent the function's arguments. The number represents the position
 *     among the arguments, zero-indexed.
 *   - Variables starting with "v" represent values defined elsewhere in the function. The number
 *     represents the position among the components, also zero-indexed. (Note that this includes the
 *     return expression, which cannot be referenced, so "v0" will be unused.) References among
 *     expressions in the list do not contain cycles.
 *
 *  TODO: It might actually be nicer if I didn't enforce components only referencing later components.
 *
 * The following is also true of code in the normal form:
 * - Expressions in the list are not variables, with one exception: the first expression (representing the
 *   value to be returned) may be an "a"-type variable.
 * - No two expressions in the list are equal.
 * - Every expression in the list has only variables as sub-expressions.
 *   - Each block in an if-then expression has only a single variable as its contents.
 * - Expressions do not contain variables referencing earlier expressions in the list.
 * - The relative order of expressions in the list corresponds to the relative locations of their first
 *   reference earlier in the list.
 *
 * When transforming a function into normal form, it is usual to first transform it into
 * semi-normal form, then [normalize] it to obtain the normal form.
 *
 * Certain transformations and optimizations of the code are more easily applied to functions
 * when they are in normal form, especially when leveraging the normalization procedure to
 * clean up afterwards. There is also a reasonable chance that two equivalent functions with
 * superficial code differences will have the same normal-form representation.
 *
 * (Design note: It is meant to be easy to identify certain code patterns -- like those that should be replaced by an
 * optimization -- in a function that is in its normal form. It should then be easy to make the appropriate
 * modifications to the normal form of the function to apply the optimization while leaving it in a semi-normal form.
 * This is, in large part, what drives the requirements of the normal form to be strict and the requirements of the
 * semi-normal form to be lenient.)
 *
 * This form does not currently carry type information.
 */
data class NormalFormFunctionContents(val components: List<Expression>) {
    init {
        if (components.isEmpty()) {
            error("Semi-normal form functions must have at least one component")
        }
    }

    fun normalize(): NormalFormFunctionContents {
        // So what are the things we need to fix up here?
        // 1) Expressions that are more than one "level" should be split up so their subexpressions are out in their own
        //    variables. This should probably happen early.
        // 2) Identical expressions should be deduplicated. This should happen after (1) and probably needs to be
        //    repeated until quiescence.
        // 3) Expressions shouldn't be single variables. This might happen around the same time as (2), though I'm not
        //    sure if it needs to be applied multiple times like (2) will be.
        // 4) The order of expressions should be rearranged to the specific order we expect, with the names of the
        //    variables changing accordingly. This should happen last.

        // TODO: Implement all these things. For now, add each component and its testing jointly.
        val flattenedContents = flatten(components)

        // Then we'll also need to do the reordering
        // TODO: This won't give the same result from different inputs just yet
        val orderedContents = toposort(flattenedContents)

        // TODO: Issue that needs to be resolved before future work: We want the ordering of expressions to be consistent when
        // the function is identical, but the assumption was that a simple rule like "opening function call is always f(v1, v2, v3, ...)"
        // would take care of it. But that ends up being inconsistent with the toposorted and deduplicated assumptions, i.e. v3
        // (as defined in this way) could depend on v2.
        //
        // Is there some clever way to solve this? Would it perhaps be sufficient to first change things into this order (or use
        // it to add the elements in a deterministic order) and then use a deterministic toposorting algorithm?


        val normalized = NormalFormFunctionContents(orderedContents)
        if (!normalized.isFullyNormal()) {
            error("Normalization did not work as expected")
        }
        return normalized
    }

    fun isFullyNormal(): Boolean {
        // TODO: Implement
        return true
    }
}

private fun flatten(components: List<Expression>): List<Expression> {
    val mutatingContents = ArrayList<Expression>()
    mutatingContents.addAll(components)

    // Extracts the subexpression to the end of mutatingContents if needed.
    // Returns the appropriate variable name.
    val extractIntoContentsIfNeeded: (Expression) -> Expression.Variable = fun(expression: Expression): Expression.Variable {
        if (expression is Expression.Variable) {
            return expression
        }
        mutatingContents.add(expression)
        return Expression.Variable("v${mutatingContents.size - 1}", null)
    }

    val extractBlockIntoContents: (Block) -> Block = fun(block: Block): Block {
        // TODO: This will suck =( need to deal with variable names
        // Alternative would be to pre-substitute all variables... which could lead to perf bugs in pathological cases
        TODO()
    }

    var i = 0
    // Note: The size of mutatingContents is expected to change while this loop is in progress
    while (i < mutatingContents.size) {
        val originalExpr = mutatingContents[i]

        val newExpr = when (originalExpr) {
            is Expression.Variable -> originalExpr
            is Expression.IfThen -> {
                val condition = extractIntoContentsIfNeeded(originalExpr.condition)
                val thenBlock = extractBlockIntoContents(originalExpr.thenBlock)
                val elseBlock = extractBlockIntoContents(originalExpr.elseBlock)
                Expression.IfThen(condition, thenBlock, elseBlock, null)
            }
            is Expression.NamedFunctionCall -> {
                val arguments = originalExpr.arguments.map(extractIntoContentsIfNeeded)
                Expression.NamedFunctionCall(originalExpr.functionRef, arguments, originalExpr.chosenParameters, null, null)
            }
            is Expression.ExpressionFunctionCall -> {
                val functionExpression = extractIntoContentsIfNeeded(originalExpr.functionExpression)
                val arguments = originalExpr.arguments.map(extractIntoContentsIfNeeded)
                Expression.ExpressionFunctionCall(functionExpression, arguments, originalExpr.chosenParameters, null)
            }
            is Expression.Literal -> originalExpr
            is Expression.ListLiteral -> {
                val contents = originalExpr.contents.map(extractIntoContentsIfNeeded)
                Expression.ListLiteral(contents, originalExpr.chosenParameter, null)
            }
            is Expression.NamedFunctionBinding -> {
                val bindings = originalExpr.bindings.map { if (it == null) null else extractIntoContentsIfNeeded(it) }
                Expression.NamedFunctionBinding(originalExpr.functionRef, bindings, originalExpr.chosenParameters, null)
            }
            is Expression.ExpressionFunctionBinding -> {
                val functionExpression = extractIntoContentsIfNeeded(originalExpr.functionExpression)
                val bindings = originalExpr.bindings.map { if (it == null) null else extractIntoContentsIfNeeded(it) }
                Expression.ExpressionFunctionBinding(functionExpression, bindings, originalExpr.chosenParameters, null)
            }
            is Expression.Follow -> {
                val structureExpression = extractIntoContentsIfNeeded(originalExpr.structureExpression)
                Expression.Follow(structureExpression, originalExpr.name, null)
            }
            is Expression.InlineFunction -> error("All inline functions must be removed before converting to normal form")
        }

        mutatingContents.set(i, newExpr)

        i++
    }

    return mutatingContents
}

// Note: This implementation is intentionally deterministic, hence the otherwise odd choices of data structures.
// TODO: Actually make fully deterministic
private fun toposort(originalContents: List<Expression>): List<Expression> {
    // Note: I'm sorting indices instead of Expression values because I'm paranoid about issues where two Expressions
    // in the list are value-equals
    val outputOrder = MutableUniqueList<Int>()

    val referencingExpressions = HashMap<Int, MutableUniqueList<Int>>()
    for (i in 0..(originalContents.size - 1)) {
        referencingExpressions.put(i, MutableUniqueList())
    }
    originalContents.forEachIndexed { index, expression ->
        // TODO: getVarsReferencedIn is currently not deterministic, fix that
        for (varName in getVarsReferencedIn(expression)) {
            if (varName.startsWith("v")) {
                val varNumber = varName.drop(1).toInt()
                // varNumber is referenced by this expression
                referencingExpressions[varNumber]!!.add(index)
            }
        }
    }

    fun toposortAdd(indexToAdd: Int) {
        // Add all the things that reference this before adding this
        for (expressionReferencingUs in referencingExpressions[indexToAdd]!!) {
            toposortAdd(expressionReferencingUs)
        }
        outputOrder.add(indexToAdd)
    }

    for (i in 0..(originalContents.size - 1)) {
        toposortAdd(i)
    }

    // Use the ordering we found to reorder the expressions, then translate them
    val oldVarsToNewVars = HashMap<String, Expression.Variable>()
    for (newIndex in 0..(outputOrder.size - 1)) {
        val oldIndex = outputOrder[newIndex]
        oldVarsToNewVars.put("v$oldIndex", Expression.Variable("v$newIndex", null))
    }
    val reorderedOutput = ArrayList<Expression>()
    for (index in outputOrder) {
        reorderedOutput.add(originalContents[index])
    }
    val translatedOutput = translateExpressions(reorderedOutput, oldVarsToNewVars)
    return translatedOutput
}


