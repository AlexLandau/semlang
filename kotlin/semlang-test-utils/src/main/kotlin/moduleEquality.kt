package net.semlang.internal.test

import net.semlang.api.*
import net.semlang.api.Function
import org.junit.Assert

fun assertModulesEqual(expected: ValidatedModule, actual: ValidatedModule) {
    // TODO: Check the upstream contexts

    Assert.assertEquals(expected.ownFunctions, actual.ownFunctions)
    Assert.assertEquals(expected.ownStructs, actual.ownStructs)
    Assert.assertEquals(expected.ownUnions, actual.ownUnions)
    // TODO: Maybe check more?
}

fun assertRawContextsEqual(expected: RawContext, actual: RawContext) {
    // TODO: Check the upstream contexts

    Assert.assertEquals(expected.functions.map(::stripLocations), actual.functions.map(::stripLocations))
    Assert.assertEquals(expected.structs.map(::stripLocations), actual.structs.map(::stripLocations))
    Assert.assertEquals(expected.unions.map(::stripLocations), actual.unions.map(::stripLocations))
}

private fun stripLocations(function: Function): Function {
    val arguments = function.arguments.map(::stripLocations)
    val block = stripLocations(function.block)
    val returnType = stripLocations(function.returnType)
    return Function(function.id, function.typeParameters, arguments, returnType, block, function.annotations)
}

private fun stripLocations(type: UnvalidatedType): UnvalidatedType {
    return when (type) {
        is UnvalidatedType.FunctionType -> {
            val typeParameters = type.typeParameters
            val argTypes = type.argTypes.map(::stripLocations)
            val outputType = stripLocations(type.outputType)
            UnvalidatedType.FunctionType(type.isReference(), typeParameters, argTypes, outputType)
        }
        is UnvalidatedType.NamedType -> {
            val parameters = type.parameters.map(::stripLocations)
            UnvalidatedType.NamedType(type.ref, type.isReference(), parameters)
        }
    }
}

private fun stripLocations(block: Block): Block {
    val statements = block.statements.map(::stripLocations)
    return Block(statements)
}

private fun stripLocations(statement: Statement): Statement {
    return when (statement) {
        is Statement.Assignment -> {
            val type = statement.type?.let(::stripLocations)
            val expression = stripLocations(statement.expression)
            Statement.Assignment(statement.name, type, expression)
        }
        is Statement.Bare -> {
            Statement.Bare(stripLocations(statement.expression))
        }
    }
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
            val chosenParameters = expression.chosenParameters.map { if (it == null) null else stripLocations(it) }
            Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters)
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
    return UnvalidatedStruct(struct.id, struct.typeParameters, members, requires, struct.annotations)
}

private fun stripLocations(member: UnvalidatedMember): UnvalidatedMember {
    val type = stripLocations(member.type)
    return UnvalidatedMember(member.name, type)
}

private fun stripLocations(union: UnvalidatedUnion): UnvalidatedUnion {
    val options = union.options.map(::stripLocations)
    return UnvalidatedUnion(union.id, union.typeParameters, options, union.annotations)
}

private fun stripLocations(option: UnvalidatedOption): UnvalidatedOption {
    val type = option.type?.let(::stripLocations)
    return UnvalidatedOption(option.name, type)
}
