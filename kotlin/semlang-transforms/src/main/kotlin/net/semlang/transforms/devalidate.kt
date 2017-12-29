package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

fun devalidate(module: ValidatedModule): RawContext {
    val functions = module.ownFunctions.values.map(::devalidate)
    val structs = module.ownStructs.values.map(::devalidate)
    val interfaces = module.ownInterfaces.values.map(::devalidate)
    return RawContext(functions, structs, interfaces)
}

fun devalidate(interfac: Interface): UnvalidatedInterface {
    val methods = interfac.methods.map(::devalidateMethod)
    return UnvalidatedInterface(interfac.id, interfac.typeParameters, methods, interfac.annotations, null)
}

private fun devalidateMethod(method: Method): UnvalidatedMethod {
    val arguments = method.arguments.map(::devalidateArgument)
    return UnvalidatedMethod(method.name, method.typeParameters, arguments, method.returnType)
}

private fun devalidateArgument(argument: Argument): UnvalidatedArgument {
    return UnvalidatedArgument(argument.name, argument.type, null)
}

fun devalidate(struct: Struct): UnvalidatedStruct {
    val requires = struct.requires?.let { devalidateBlock(it) }
    return UnvalidatedStruct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations, null)
}

private fun devalidateBlock(block: TypedBlock): Block {
    val assignments = block.assignments.map(::devalidateAssignment)
    val returnedExpression = devalidateExpression(block.returnedExpression)
    return Block(assignments, returnedExpression, null)
}

private fun devalidateExpression(expression: TypedExpression): Expression {
    return when (expression) {
        is TypedExpression.Variable -> {
            Expression.Variable(expression.name, null)
        }
        is TypedExpression.IfThen -> {
            val condition = devalidateExpression(expression.condition)
            val thenBlock = devalidateBlock(expression.thenBlock)
            val elseBlock = devalidateBlock(expression.elseBlock)
            Expression.IfThen(condition, thenBlock, elseBlock, null)
        }
        is TypedExpression.NamedFunctionCall -> {
            val arguments = expression.arguments.map(::devalidateExpression)
            Expression.NamedFunctionCall(expression.functionRef, arguments, expression.chosenParameters, null, null)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val functionExpression = devalidateExpression(expression.functionExpression)
            val arguments = expression.arguments.map(::devalidateExpression)
            Expression.ExpressionFunctionCall(functionExpression, arguments, expression.chosenParameters, null)
        }
        is TypedExpression.Literal -> {
            Expression.Literal(expression.type, expression.literal, null)
        }
        is TypedExpression.ListLiteral -> {
            val contents = expression.contents.map(::devalidateExpression)
            Expression.ListLiteral(contents, expression.chosenParameter, null)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { if (it == null) null else devalidateExpression(it) }
            Expression.NamedFunctionBinding(expression.functionRef, expression.chosenParameters, bindings, null)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val functionExpression = devalidateExpression(expression.functionExpression)
            val bindings = expression.bindings.map { if (it == null) null else devalidateExpression(it) }
            Expression.ExpressionFunctionBinding(functionExpression, expression.chosenParameters, bindings, null)
        }
        is TypedExpression.Follow -> {
            val structure = devalidateExpression(expression.expression)
            Expression.Follow(structure, expression.name, null)
        }
        is TypedExpression.InlineFunction -> {
            val arguments = expression.arguments.map(::devalidateArgument)
            val block = devalidateBlock(expression.block)
            Expression.InlineFunction(arguments, block, null)
        }
    }
}

private fun devalidateAssignment(assignment: ValidatedAssignment): Assignment {
    val expression = devalidateExpression(assignment.expression)
    return Assignment(assignment.name, assignment.type, expression, null)
}

fun devalidate(function: ValidatedFunction): Function {
    val arguments = function.arguments.map(::devalidateArgument)
    val block = devalidateBlock(function.block)
    return Function(function.id, function.typeParameters, arguments, function.returnType, block,
            function.annotations, null, null)
}
