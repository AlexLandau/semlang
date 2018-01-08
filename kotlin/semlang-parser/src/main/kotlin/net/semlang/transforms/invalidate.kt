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
    return UnvalidatedMethod(method.name, method.typeParameters, arguments, method.returnType)
}

fun invalidate(argument: Argument): UnvalidatedArgument {
    return UnvalidatedArgument(argument.name, argument.type, null)
}

fun invalidate(struct: Struct): UnvalidatedStruct {
    val requires = struct.requires?.let { invalidate(it) }
    return UnvalidatedStruct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations, null)
}

fun invalidate(block: TypedBlock): Block {
    val assignments = block.assignments.map(::invalidateAssignment)
    val returnedExpression = invalidateExpression(block.returnedExpression)
    return Block(assignments, returnedExpression, null)
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
            Expression.NamedFunctionCall(expression.functionRef, arguments, expression.chosenParameters, null, null)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val functionExpression = invalidateExpression(expression.functionExpression)
            val arguments = expression.arguments.map(::invalidateExpression)
            Expression.ExpressionFunctionCall(functionExpression, arguments, expression.chosenParameters, null)
        }
        is TypedExpression.Literal -> {
            Expression.Literal(expression.type, expression.literal, null)
        }
        is TypedExpression.ListLiteral -> {
            val contents = expression.contents.map(::invalidateExpression)
            Expression.ListLiteral(contents, expression.chosenParameter, null)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { if (it == null) null else invalidateExpression(it) }
            Expression.NamedFunctionBinding(expression.functionRef, bindings, expression.chosenParameters, null)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val functionExpression = invalidateExpression(expression.functionExpression)
            val bindings = expression.bindings.map { if (it == null) null else invalidateExpression(it) }
            Expression.ExpressionFunctionBinding(functionExpression, bindings, expression.chosenParameters, null)
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
    return Assignment(assignment.name, assignment.type, expression, null)
}

fun invalidate(function: ValidatedFunction): Function {
    val arguments = function.arguments.map(::invalidate)
    val block = invalidate(function.block)
    return Function(function.id, function.typeParameters, arguments, function.returnType, block,
            function.annotations, null, null)
}