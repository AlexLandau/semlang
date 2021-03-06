package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

// TODO: These might be better as functions (or even extension functions) on the API elements
fun invalidate(module: ValidatedModule): RawContext {
    val functions = module.ownFunctions.values.map(::invalidate)
    val structs = module.ownStructs.values.map(::invalidate)
    val unions = module.ownUnions.values.map(::invalidate)
    return RawContext(functions, structs, unions)
}

fun invalidate(union: Union): UnvalidatedUnion {
    val options = union.options.map(::invalidateOption)
    return UnvalidatedUnion(union.id, union.typeParameters, options, union.annotations)
}

private fun invalidateOption(option: Option): UnvalidatedOption {
    val type = option.type?.let { invalidate(it) }
    return UnvalidatedOption(option.name, type)
}

fun invalidate(argument: Argument): UnvalidatedArgument {
    return UnvalidatedArgument(argument.name, invalidate(argument.type))
}

fun invalidate(struct: Struct): UnvalidatedStruct {
    val requires = struct.requires?.let { invalidate(it) }
    val members = struct.members.map(::invalidate)
    return UnvalidatedStruct(struct.id, struct.typeParameters, members, requires, struct.annotations)
}

fun invalidate(block: TypedBlock): Block {
    val statements = block.statements.map(::invalidate)
    return Block(statements)
}

fun invalidateFunctionType(type: Type.FunctionType): UnvalidatedType.FunctionType {
    val groundType = type.getDefaultGrounding()
    val argTypes = groundType.argTypes.map(::invalidate)
    val outputType = invalidate(groundType.outputType)
    return UnvalidatedType.FunctionType(type.isReference(), type.typeParameters, argTypes, outputType)
}

fun invalidate(type: Type): UnvalidatedType {
    return when (type) {
        is Type.FunctionType -> {
            invalidateFunctionType(type)
        }
        is Type.ParameterType -> {
            UnvalidatedType.NamedType(EntityRef.of(type.parameter.name), false)
        }
        is Type.NamedType -> {
            val parameters = type.parameters.map(::invalidate)
            UnvalidatedType.NamedType(type.originalRef, type.isReference(), parameters)
        }
        is Type.InternalParameterType -> error("This shouldn't happen")
    }
}

fun invalidate(functionSignature: FunctionSignature): UnvalidatedFunctionSignature {
    val argTypes = functionSignature.argumentTypes.map(::invalidate)
    val outputType = invalidate(functionSignature.outputType)
    return UnvalidatedFunctionSignature(functionSignature.id, argTypes, outputType, functionSignature.typeParameters)
}

fun invalidate(member: Member): UnvalidatedMember {
    val type = invalidate(member.type)
    return UnvalidatedMember(member.name, type)
}

private fun invalidate(expression: TypedExpression): Expression {
    return when (expression) {
        is TypedExpression.Variable -> {
            Expression.Variable(expression.name)
        }
        is TypedExpression.IfThen -> {
            val condition = invalidate(expression.condition)
            val thenBlock = invalidate(expression.thenBlock)
            val elseBlock = invalidate(expression.elseBlock)
            Expression.IfThen(condition, thenBlock, elseBlock)
        }
        is TypedExpression.NamedFunctionCall -> {
            val arguments = expression.arguments.map(::invalidate)
            val chosenParameters = expression.originalChosenParameters.map(::invalidate)
            Expression.NamedFunctionCall(expression.functionRef, arguments, chosenParameters)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val functionExpression = invalidate(expression.functionExpression)
            val arguments = expression.arguments.map(::invalidate)
            val chosenParameters = expression.originalChosenParameters.map(::invalidate)
            Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters)
        }
        is TypedExpression.Literal -> {
            val type = invalidate(expression.type)
            Expression.Literal(type, expression.literal)
        }
        is TypedExpression.ListLiteral -> {
            val contents = expression.contents.map(::invalidate)
            val chosenParameter = invalidate(expression.chosenParameter)
            Expression.ListLiteral(contents, chosenParameter)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { if (it == null) null else invalidate(it) }
            val chosenParameters = expression.originalChosenParameters.map { if (it == null) null else invalidate(it) }
            Expression.NamedFunctionBinding(expression.functionRef, bindings, chosenParameters)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val functionExpression = invalidate(expression.functionExpression)
            val bindings = expression.bindings.map { if (it == null) null else invalidate(it) }
            val chosenParameters = expression.originalChosenParameters.map { if (it == null) null else invalidate(it) }
            Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters)
        }
        is TypedExpression.Follow -> {
            val structureExpression = invalidate(expression.structureExpression)
            Expression.Follow(structureExpression, expression.name)
        }
        is TypedExpression.InlineFunction -> {
            val arguments = expression.arguments.map(::invalidate)
            val returnType = invalidate(expression.returnType)
            val block = invalidate(expression.block)
            Expression.InlineFunction(arguments, returnType, block)
        }
    }
}

private fun invalidate(statement: ValidatedStatement): Statement {
    return when (statement) {
        is ValidatedStatement.Assignment -> {
            Statement.Assignment(statement.name,
                invalidate(statement.type),
                invalidate(statement.expression)
            )
        }
        is ValidatedStatement.Bare -> {
            Statement.Bare(invalidate(statement.expression))
        }
    }
}

fun invalidate(function: ValidatedFunction): Function {
    val arguments = function.arguments.map(::invalidate)
    val block = invalidate(function.block)
    val returnType = invalidate(function.returnType)
    return Function(function.id, function.typeParameters, arguments, returnType, block,
            function.annotations)
}
