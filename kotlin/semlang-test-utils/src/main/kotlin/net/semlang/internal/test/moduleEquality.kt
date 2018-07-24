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
    val returnType = stripLocations(function.returnType)
    return Function(function.id, function.typeParameters, arguments, returnType, block, function.annotations)
}

private fun stripLocations(type: UnvalidatedType): UnvalidatedType {
    return when (type) {
        is UnvalidatedType.Integer -> UnvalidatedType.Integer()
        is UnvalidatedType.Boolean -> UnvalidatedType.Boolean()
        is UnvalidatedType.List -> {
            val parameter = stripLocations(type.parameter)
            UnvalidatedType.List(parameter)
        }
        is UnvalidatedType.Maybe -> {
            val parameter = stripLocations(type.parameter)
            UnvalidatedType.Maybe(parameter)
        }
        is UnvalidatedType.FunctionType -> {
            val typeParameters = type.typeParameters
            val argTypes = type.argTypes.map(::stripLocations)
            val outputType = stripLocations(type.outputType)
            UnvalidatedType.FunctionType(typeParameters, argTypes, outputType)
        }
        is UnvalidatedType.NamedType -> {
            val parameters = type.parameters.map(::stripLocations)
            UnvalidatedType.NamedType(type.ref, type.isThreaded, parameters)
        }
        is UnvalidatedType.Invalid.ThreadedInteger -> UnvalidatedType.Invalid.ThreadedInteger()
        is UnvalidatedType.Invalid.ThreadedBoolean -> UnvalidatedType.Invalid.ThreadedBoolean()
    }
}

private fun stripLocations(block: Block): Block {
    val assignments = block.assignments.map(::stripLocations)
    val returnedExpression = stripLocations(block.returnedExpression)
    return Block(assignments, returnedExpression)
}

private fun stripLocations(assignment: Assignment): Assignment {
    val type = assignment.type?.let(::stripLocations)
    val expression = stripLocations(assignment.expression)
    return Assignment(assignment.name, type, expression)
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
            val chosenParameters = expression.chosenParameters.map(::stripLocations)
            Expression.NamedFunctionCall(expression.functionRef, arguments, chosenParameters)
        }
        is Expression.ExpressionFunctionCall -> {
            val functionExpression = stripLocations(expression.functionExpression)
            val arguments = expression.arguments.map(::stripLocations)
            val chosenParameters = expression.chosenParameters.map(::stripLocations)
            Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters)
        }
        is Expression.Literal -> {
            val type = stripLocations(expression.type)
            Expression.Literal(type, expression.literal)
        }
        is Expression.ListLiteral -> {
            val contents = expression.contents.map(::stripLocations)
            val chosenParameter = stripLocations(expression.chosenParameter)
            Expression.ListLiteral(contents, chosenParameter)
        }
        is Expression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { if (it == null) null else stripLocations(it) }
            val chosenParameters = expression.chosenParameters.map { if (it == null) null else stripLocations(it) }
            Expression.NamedFunctionBinding(expression.functionRef, bindings, chosenParameters)
        }
        is Expression.ExpressionFunctionBinding -> {
            val functionExpression = stripLocations(expression.functionExpression)
            val bindings = expression.bindings.map { if (it == null) null else stripLocations(it) }
            Expression.ExpressionFunctionBinding(functionExpression, bindings)
        }
        is Expression.Follow -> {
            val structureExpression = stripLocations(expression.structureExpression)
            Expression.Follow(structureExpression, expression.name)
        }
        is Expression.InlineFunction -> {
            val arguments = expression.arguments.map(::stripLocations)
            val returnType = stripLocations(expression.returnType)
            val block = stripLocations(expression.block)
            Expression.InlineFunction(arguments, returnType, block)
        }
    }
}

private fun stripLocations(argument: UnvalidatedArgument): UnvalidatedArgument {
    val type = stripLocations(argument.type)
    return UnvalidatedArgument(argument.name, type)
}

private fun stripLocations(struct: UnvalidatedStruct): UnvalidatedStruct {
    val requires = struct.requires?.let(::stripLocations)
    val members = struct.members.map(::stripLocations)
    return UnvalidatedStruct(struct.id, struct.markedAsThreaded, struct.typeParameters, members, requires, struct.annotations)
}

private fun stripLocations(interfac: UnvalidatedInterface): UnvalidatedInterface {
    val methods = interfac.methods.map(::stripLocations)
    return UnvalidatedInterface(interfac.id, interfac.typeParameters, methods, interfac.annotations)
}

private fun stripLocations(method: UnvalidatedMethod): UnvalidatedMethod {
    val arguments = method.arguments.map(::stripLocations)
    val returnType = stripLocations(method.returnType)
    return UnvalidatedMethod(method.name, method.typeParameters, arguments, returnType)
}

private fun stripLocations(member: UnvalidatedMember): UnvalidatedMember {
    val type = stripLocations(member.type)
    return UnvalidatedMember(member.name, type)
}
