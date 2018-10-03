package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

// TODO: These might be better as functions (or even extension functions) on the API elements
fun invalidate(module: ValidatedModule): RawContext {
    // TODO: There might be better options for how to sort these or keep them sorted...
    val functions = module.ownFunctions.toSortedMap(EntityIdComparator).values.map(::invalidate)
    val structs = module.ownStructs.toSortedMap(EntityIdComparator).values.map(::invalidate)
    val interfaces = module.ownInterfaces.toSortedMap(EntityIdComparator).values.map(::invalidate)
    val unions = module.ownUnions.toSortedMap(EntityIdComparator).values.map(::invalidate)
    return RawContext(functions, structs, interfaces, unions)
}

fun invalidate(union: Union): UnvalidatedUnion {
    val options = union.options.map(::invalidateOption)
    return UnvalidatedUnion(union.id, union.typeParameters, options, union.annotations)
}

private fun invalidateOption(option: Option): UnvalidatedOption {
    val type = option.type?.let { invalidate(it) }
    return UnvalidatedOption(option.name, type)
}

fun invalidate(interfac: Interface): UnvalidatedInterface {
    val methods = interfac.methods.map(::invalidateMethod)
    return UnvalidatedInterface(interfac.id, interfac.typeParameters, methods, interfac.annotations)
}

private fun invalidateMethod(method: Method): UnvalidatedMethod {
    val arguments = method.arguments.map(::invalidate)
    return UnvalidatedMethod(method.name, method.typeParameters, arguments, invalidate(method.returnType))
}

fun invalidate(argument: Argument): UnvalidatedArgument {
    return UnvalidatedArgument(argument.name, invalidate(argument.type))
}

fun invalidate(struct: Struct): UnvalidatedStruct {
    val requires = struct.requires?.let { invalidate(it) }
    val members = struct.members.map(::invalidate)
    return UnvalidatedStruct(struct.id, struct.isThreaded, struct.typeParameters, members, requires, struct.annotations)
}

fun invalidate(block: TypedBlock): Block {
    val assignments = block.assignments.map(::invalidateAssignment)
    val returnedExpression = invalidateExpression(block.returnedExpression)
    return Block(assignments, returnedExpression)
}

fun invalidate(type: Type): UnvalidatedType {
    return when (type) {
        Type.INTEGER -> UnvalidatedType.Integer()
        Type.BOOLEAN -> UnvalidatedType.Boolean()
        is Type.List -> {
            UnvalidatedType.List(invalidate(type.parameter))
        }
        is Type.Maybe -> {
            UnvalidatedType.Maybe(invalidate(type.parameter))
        }
        is Type.FunctionType -> {
            val groundType = type.getDefaultGrounding()
            val argTypes = groundType.argTypes.map(::invalidate)
            val outputType = invalidate(groundType.outputType)
            UnvalidatedType.FunctionType(type.typeParameters, argTypes, outputType)
        }
        is Type.ParameterType -> {
            UnvalidatedType.NamedType(EntityRef.of(type.parameter.name), false)
        }
        is Type.NamedType -> {
            val parameters = type.parameters.map(::invalidate)
            UnvalidatedType.NamedType(type.originalRef, type.isThreaded(), parameters)
        }
        is Type.InternalParameterType -> error("This shouldn't happen")
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
            Expression.Variable(expression.name)
        }
        is TypedExpression.IfThen -> {
            val condition = invalidateExpression(expression.condition)
            val thenBlock = invalidate(expression.thenBlock)
            val elseBlock = invalidate(expression.elseBlock)
            Expression.IfThen(condition, thenBlock, elseBlock)
        }
        is TypedExpression.NamedFunctionCall -> {
            val arguments = expression.arguments.map(::invalidateExpression)
            val chosenParameters = expression.chosenParameters.map(::invalidate)
            Expression.NamedFunctionCall(expression.functionRef, arguments, chosenParameters)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val functionExpression = invalidateExpression(expression.functionExpression)
            val arguments = expression.arguments.map(::invalidateExpression)
            val chosenParameters = expression.chosenParameters.map(::invalidate)
            Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters)
        }
        is TypedExpression.Literal -> {
            val type = invalidate(expression.type)
            Expression.Literal(type, expression.literal)
        }
        is TypedExpression.ListLiteral -> {
            val contents = expression.contents.map(::invalidateExpression)
            val chosenParameter = invalidate(expression.chosenParameter)
            Expression.ListLiteral(contents, chosenParameter)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { if (it == null) null else invalidateExpression(it) }
            val chosenParameters = expression.chosenParameters.map { if (it == null) null else invalidate(it) }
            Expression.NamedFunctionBinding(expression.functionRef, bindings, chosenParameters)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val functionExpression = invalidateExpression(expression.functionExpression)
            val bindings = expression.bindings.map { if (it == null) null else invalidateExpression(it) }
            val chosenParameters = expression.chosenParameters.map { if (it == null) null else invalidate(it) }
            Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters)
        }
        is TypedExpression.Follow -> {
            val structureExpression = invalidateExpression(expression.structureExpression)
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

private fun invalidateAssignment(assignment: ValidatedAssignment): Assignment {
    val expression = invalidateExpression(assignment.expression)
    return Assignment(assignment.name, invalidate(assignment.type), expression)
}

fun invalidate(function: ValidatedFunction): Function {
    val arguments = function.arguments.map(::invalidate)
    val block = invalidate(function.block)
    val returnType = invalidate(function.returnType)
    return Function(function.id, function.typeParameters, arguments, returnType, block,
            function.annotations)
}

private object EntityIdComparator: Comparator<EntityId> {
    override fun compare(id1: EntityId, id2: EntityId): Int {
        val strings1 = id1.namespacedName
        val strings2 = id2.namespacedName
        var i = 0
        while (true) {
            // When they are otherwise the same, shorter precedes longer
            val done1 = i >= strings1.size
            val done2 = i >= strings2.size
            if (done1) {
                if (done2) {
                    return 0
                } else {
                    return -1
                }
            }
            if (done2) {
                return 1
            }
            val stringComparison = strings1[i].compareTo(strings2[i])
            if (stringComparison != 0) {
                return stringComparison
            }
            i++
        }
    }
}
