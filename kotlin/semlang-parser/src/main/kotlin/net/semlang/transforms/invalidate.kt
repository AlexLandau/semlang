package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

// TODO: These might be better as functions (or even extension functions) on the API elements
fun invalidate(module: ValidatedModule): RawContext {
    val functions = module.ownFunctions.values.map(::invalidate)
    val structs = module.ownStructs.values.map(::invalidate)
    val interfaces = module.ownInterfaces.values.map(::invalidate)
    return RawContext(functions, structs, interfaces)
}

fun invalidate(interfac: Interface): UnvalidatedInterface {
    val methods = interfac.methods.map(::invalidateMethod)
    return UnvalidatedInterface(interfac.id, interfac.typeParameters, methods, interfac.annotations, null)
}

private fun invalidateMethod(method: Method): UnvalidatedMethod {
    val arguments = method.arguments.map(::invalidate)
    return UnvalidatedMethod(method.name, method.typeParameters, arguments, invalidate(method.returnType))
}

fun invalidate(argument: Argument): UnvalidatedArgument {
    return UnvalidatedArgument(argument.name, invalidate(argument.type), null)
}

fun invalidate(struct: Struct): UnvalidatedStruct {
    val requires = struct.requires?.let { invalidate(it) }
    val members = struct.members.map(::invalidate)
    return UnvalidatedStruct(struct.id, struct.typeParameters, members, requires, struct.annotations, null)
}

fun invalidate(block: TypedBlock): Block {
    val assignments = block.assignments.map(::invalidateAssignment)
    val returnedExpression = invalidateExpression(block.returnedExpression)
    return Block(assignments, returnedExpression, null)
}

fun invalidate(type: Type): UnvalidatedType {
    return when (type) {
        Type.INTEGER -> UnvalidatedType.INTEGER
        Type.BOOLEAN -> UnvalidatedType.BOOLEAN
        is Type.List -> {
            UnvalidatedType.List(invalidate(type.parameter))
        }
        is Type.Try -> {
            UnvalidatedType.Try(invalidate(type.parameter))
        }
        is Type.FunctionType -> {
            val argTypes = type.argTypes.map(::invalidate)
            val outputType = invalidate(type.outputType)
            UnvalidatedType.FunctionType(argTypes, outputType)
        }
        is Type.ParameterType -> {
            UnvalidatedType.NamedType(EntityRef.of(type.name), false)
        }
        is Type.NamedType -> {
            val parameters = type.parameters.map(::invalidate)
            UnvalidatedType.NamedType(type.originalRef, type.isThreaded(), parameters)
        }
    }
}

fun invalidate(typeSignature: TypeSignature): UnvalidatedTypeSignature {
    val argTypes = typeSignature.argumentTypes.map(::invalidate)
    val outputType = invalidate(typeSignature.outputType)
    return UnvalidatedTypeSignature(typeSignature.id, argTypes, outputType, typeSignature.typeParameters)
}

fun invalidate(member: Member): UnvalidatedMember {
    val type = invalidate(member.type)
    return UnvalidatedMember(member.name, type)
}

private fun invalidateExpression(expression: TypedExpression): Expression {
    return when (expression) {
        is TypedExpression.Variable -> {
            Expression.Variable(expression.name, null)
        }
        is TypedExpression.IfThen -> {
            val condition = invalidateExpression(expression.condition)
            val thenBlock = invalidate(expression.thenBlock)
            val elseBlock = invalidate(expression.elseBlock)
            Expression.IfThen(condition, thenBlock, elseBlock, null)
        }
        is TypedExpression.NamedFunctionCall -> {
            val arguments = expression.arguments.map(::invalidateExpression)
            val chosenParameters = expression.chosenParameters.map(::invalidate)
            Expression.NamedFunctionCall(expression.functionRef, arguments, chosenParameters, null, null)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val functionExpression = invalidateExpression(expression.functionExpression)
            val arguments = expression.arguments.map(::invalidateExpression)
            val chosenParameters = expression.chosenParameters.map(::invalidate)
            Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters, null)
        }
        is TypedExpression.Literal -> {
            val type = invalidate(expression.type)
            Expression.Literal(type, expression.literal, null)
        }
        is TypedExpression.ListLiteral -> {
            val contents = expression.contents.map(::invalidateExpression)
            val chosenParameter = invalidate(expression.chosenParameter)
            Expression.ListLiteral(contents, chosenParameter, null)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { if (it == null) null else invalidateExpression(it) }
            val chosenParameters = expression.chosenParameters.map(::invalidate)
            Expression.NamedFunctionBinding(expression.functionRef, bindings, chosenParameters, null)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val functionExpression = invalidateExpression(expression.functionExpression)
            val bindings = expression.bindings.map { if (it == null) null else invalidateExpression(it) }
            val chosenParameters = expression.chosenParameters.map(::invalidate)
            Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters, null)
        }
        is TypedExpression.Follow -> {
            val structureExpression = invalidateExpression(expression.structureExpression)
            Expression.Follow(structureExpression, expression.name, null)
        }
        is TypedExpression.InlineFunction -> {
            val arguments = expression.arguments.map(::invalidate)
            val block = invalidate(expression.block)
            Expression.InlineFunction(arguments, block, null)
        }
    }
}

private fun invalidateAssignment(assignment: ValidatedAssignment): Assignment {
    val expression = invalidateExpression(assignment.expression)
    return Assignment(assignment.name, invalidate(assignment.type), expression, null)
}

fun invalidate(function: ValidatedFunction): Function {
    val arguments = function.arguments.map(::invalidate)
    val block = invalidate(function.block)
    val returnType = invalidate(function.returnType)
    return Function(function.id, function.typeParameters, arguments, returnType, block,
            function.annotations, null, null)
}
