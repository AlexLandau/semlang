package net.semlang.transforms.normalform

import net.semlang.api.Expression
import net.semlang.api.Function
import net.semlang.transforms.replaceSomeExpressionsPostvisit

// TODO: Steps for this addition:
// Step 1: Have a corpus test showing that turning a function into its normal form, then back again, does not change its
//   meaning (i.e. check that its tests still work).
// Step 2: As part of the test, verify that the output of normalization has all its expected qualities.

// Subsequent steps: Write an optimization using the normal form; write some framework around applying multiple such
// optimizations.

// TODO: Could there be bad consequences if transformations are applied to unvalidated, incorrect code?
// In particular, it could be bad if said code ended up valid (and with a different semantic meaning) as
// a result; who is responsible for preventing that, the framework or the transformations?
fun getNormalFormContents(function: Function): NormalFormFunctionContents {
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
    System.out.println(seminormalContents.components.joinToString("\n  "))
    return seminormalContents
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

}