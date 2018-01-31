package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Annotation
import net.semlang.api.Function

// TODO: Test this on multi-module cases
fun convertToSem0(module: ValidatedModule): S0Context {
    // TODO: Link non-native modules

    // Apply some pre-transformations...
    val v2 = extractInlineFunctions(module)
    val v3 = transformInterfacesToStructs(v2)
    val v4 = simplifyExpressions(v3)

    return Sem1To0Converter(v4).apply()
}

private class Sem1To0Converter(val input: RawContext) {
    fun apply(): S0Context {
        if (input.interfaces.isNotEmpty()) {
            error("Interfaces should have been removed")
        }
        val functions = input.functions.map(this::apply)
        val structs = input.structs.map(this::apply)
        return S0Context(functions, structs)
    }

    private fun apply(function: Function): S0Function {
        val id = convertId(function.id)
        val arguments = function.arguments.map(this::apply)
        val returnType = apply(function.returnType)
        val block = apply(function.block)
        val annotations = function.annotations.map(this::apply)
        return S0Function(id, function.typeParameters, arguments, returnType, block, annotations)
    }

    private fun convertId(id: EntityId): String {
        return id.toString()
    }

    private fun apply(struct: UnvalidatedStruct): S0Struct {
        val id = convertId(struct.id)
        val members = struct.members.map(this::apply)
        val requires = struct.requires?.let(this::apply)
        val annotations = struct.annotations.map(this::apply)
        return S0Struct(id, struct.typeParameters, members, requires, annotations)
    }

    private fun apply(member: Member): S0Member {
        val type = apply(member.type)
        return S0Member(member.name, type)
    }

    private fun apply(argument: UnvalidatedArgument): S0Argument {
        val type = apply(argument.type)
        return S0Argument(argument.name, type)
    }

    private fun apply(annotation: Annotation): S0Annotation {
        return S0Annotation(annotation.name, annotation.values.map(this::apply))
    }

    private fun apply(annotationArg: AnnotationArgument): S0AnnotationArg {
        return when (annotationArg) {
            is AnnotationArgument.Literal -> {
                S0AnnotationArg.Literal(annotationArg.value)
            }
            is AnnotationArgument.List -> {
                S0AnnotationArg.List(annotationArg.values.map(this::apply))
            }
        }
    }

    private fun apply(type: Type): S0Type {
        return when (type) {
            Type.INTEGER -> S0Type.Integer
            Type.NATURAL -> S0Type.Natural
            Type.BOOLEAN -> S0Type.Boolean
            is Type.List -> S0Type.List(apply(type.parameter))
            is Type.Try -> S0Type.Try(apply(type.parameter))
            is Type.FunctionType -> {
                val argTypes = type.argTypes.map(this::apply)
                val outputType = apply(type.outputType)
                S0Type.FunctionType(argTypes, outputType)
            }
            is Type.NamedType -> {
                val id = convertRef(type.ref)
                val parameters = type.parameters.map(this::apply)
                S0Type.NamedType(id, parameters)
            }
        }
    }

    private fun convertRef(ref: EntityRef): String {
        // TODO: This won't handle cross-module references correctly yet
        if (ref.moduleRef != null) {
            TODO("Implement this")
        }
        return ref.id.toString()
    }

    private fun apply(block: Block): S0Block {
        val assignments = block.assignments.map(this::apply)
        val returnedExpression = apply(block.returnedExpression)
        return S0Block(assignments, returnedExpression)
    }

    private fun apply(assignment: Assignment): S0Assignment {
        val expression = apply(assignment.expression)
        return S0Assignment(assignment.name, expression)
    }

    private fun apply(expression: Expression): S0Expression {
        return when (expression) {
            is Expression.Variable -> {
                S0Expression.Variable(expression.name)
            }
            is Expression.IfThen -> {
                val conditionVarName = convertVarExpression(expression.condition)
                val thenBlock = apply(expression.thenBlock)
                val elseBlock = apply(expression.elseBlock)
                S0Expression.IfThen(conditionVarName, thenBlock, elseBlock)
            }
            is Expression.NamedFunctionCall -> {
                val functionId = convertRef(expression.functionRef)
                val argumentVarNames = expression.arguments.map(this::convertVarExpression)
                val chosenParameters = expression.chosenParameters.map(this::apply)
                S0Expression.NamedFunctionCall(functionId, argumentVarNames, chosenParameters)
            }
            is Expression.ExpressionFunctionCall -> {
                val functionVarName = convertVarExpression(expression.functionExpression)
                val argumentVarNames = expression.arguments.map(this::convertVarExpression)
                val chosenParameters = expression.chosenParameters.map(this::apply)
                S0Expression.ExpressionFunctionCall(functionVarName, argumentVarNames, chosenParameters)
            }
            is Expression.Literal -> {
                val type = apply(expression.type)
                S0Expression.Literal(type, expression.literal)
            }
            is Expression.ListLiteral -> {
                val itemVarNames = expression.contents.map(this::convertVarExpression)
                val chosenParameter = apply(expression.chosenParameter)
                S0Expression.ListLiteral(itemVarNames, chosenParameter)
            }
            is Expression.NamedFunctionBinding -> {
                val functionId = convertRef(expression.functionRef)
                val bindingVarNames = expression.bindings.map { if (it == null) null else convertVarExpression(it) }
                val chosenParameters = expression.chosenParameters.map(this::apply)
                S0Expression.NamedFunctionBinding(functionId, bindingVarNames, chosenParameters)
            }
            is Expression.ExpressionFunctionBinding -> {
                val functionVarName = convertVarExpression(expression.functionExpression)
                val bindingVarNames = expression.bindings.map { if (it == null) null else convertVarExpression(it) }
                val chosenParameters = expression.chosenParameters.map(this::apply)
                S0Expression.ExpressionFunctionBinding(functionVarName, bindingVarNames, chosenParameters)
            }
            is Expression.Follow -> {
                val structureVarName = convertVarExpression(expression.structureExpression)
                S0Expression.Follow(structureVarName, expression.name)
            }
            is Expression.InlineFunction -> {
                error("Inline functions should have been removed")
            }
        }
    }

    private fun convertVarExpression(expression: Expression): String {
        val variable = expression as? Expression.Variable ?: simplificationFailed()
        return variable.name
    }

    private fun simplificationFailed(): Nothing {
        error("Simplification failed")
    }
}
