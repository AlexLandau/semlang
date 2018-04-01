package net.semlang.internal.test

import net.semlang.api.*
import net.semlang.api.Function
import org.junit.Assert

fun assertModulesEqual(expected: ValidatedModule, actual: ValidatedModule) {
    // TODO: Check the upstream contexts

    Assert.assertEquals(expected.ownFunctions, actual.ownFunctions)
    Assert.assertEquals(expected.ownStructs, actual.ownStructs)
    Assert.assertEquals(expected.ownInterfaces, actual.ownInterfaces)
    // TODO: Maybe check more?
}

fun assertRawContextsEqual(expected: RawContext, actual: RawContext) {
    // TODO: Check the upstream contexts

    Assert.assertEquals(expected.functions.map(::stripLocations), actual.functions.map(::stripLocations))
    Assert.assertEquals(expected.structs.map(::stripLocations), actual.structs.map(::stripLocations))
    Assert.assertEquals(expected.interfaces.map(::stripLocations), actual.interfaces.map(::stripLocations))
}

private fun stripLocations(function: Function): Function {
    val arguments = function.arguments.map(::stripLocations)
    val block = stripLocations(function.block)
    return Function(function.id, function.typeParameters, arguments, function.returnType, block, function.annotations)
}

private fun stripLocations(block: Block): Block {
    val assignments = block.assignments.map(::stripLocations)
    val returnedExpression = stripLocations(block.returnedExpression)
    return Block(assignments, returnedExpression)
}

private fun stripLocations(assignment: Assignment): Assignment {
    val expression = stripLocations(assignment.expression)
    return Assignment(assignment.name, assignment.type, expression)
}

private fun stripLocations(expression: Expression): Expression {
    return when (expression) {
        is Expression.Variable -> {
            Expression.Variable(expression.name)
        }
        is Expression.IfThen -> {
            val condition = stripLocations(expression.condition)
            val thenBlock = stripLocations(expression.thenBlock)
            val elseBlock = stripLocations(expression.elseBlock)
            Expression.IfThen(condition, thenBlock, elseBlock)
        }
        is Expression.NamedFunctionCall -> {
            val arguments = expression.arguments.map(::stripLocations)
            Expression.NamedFunctionCall(expression.functionRef, arguments, expression.chosenParameters)
        }
        is Expression.ExpressionFunctionCall -> {
            val functionExpression = stripLocations(expression.functionExpression)
            val arguments = expression.arguments.map(::stripLocations)
            Expression.ExpressionFunctionCall(functionExpression, arguments, expression.chosenParameters)
        }
        is Expression.Literal -> {
            Expression.Literal(expression.type, expression.literal)
        }
        is Expression.ListLiteral -> {
            val contents = expression.contents.map(::stripLocations)
            Expression.ListLiteral(contents, expression.chosenParameter)
        }
        is Expression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { if (it == null) null else stripLocations(it) }
            Expression.NamedFunctionBinding(expression.functionRef, bindings, expression.chosenParameters)
        }
        is Expression.ExpressionFunctionBinding -> {
            val functionExpression = stripLocations(expression.functionExpression)
            val bindings = expression.bindings.map { if (it == null) null else stripLocations(it) }
            Expression.ExpressionFunctionBinding(functionExpression, bindings, expression.chosenParameters)
        }
        is Expression.Follow -> {
            val structureExpression = stripLocations(expression.structureExpression)
            Expression.Follow(structureExpression, expression.name)
        }
        is Expression.InlineFunction -> {
            val arguments = expression.arguments.map(::stripLocations)
            val block = stripLocations(expression.block)
            Expression.InlineFunction(arguments, expression.returnType, block)
        }
    }
}

private fun stripLocations(argument: UnvalidatedArgument): UnvalidatedArgument {
    return UnvalidatedArgument(argument.name, argument.type)
}

private fun stripLocations(struct: UnvalidatedStruct): UnvalidatedStruct {
    val requires = struct.requires?.let(::stripLocations)
    return UnvalidatedStruct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations)
}

private fun stripLocations(interfac: UnvalidatedInterface): UnvalidatedInterface {
    val methods = interfac.methods.map(::stripLocations)
    return UnvalidatedInterface(interfac.id, interfac.typeParameters, methods, interfac.annotations)
}

private fun stripLocations(method: UnvalidatedMethod): UnvalidatedMethod {
    val arguments = method.arguments.map(::stripLocations)
    return UnvalidatedMethod(method.name, method.typeParameters, arguments, method.returnType)
}
