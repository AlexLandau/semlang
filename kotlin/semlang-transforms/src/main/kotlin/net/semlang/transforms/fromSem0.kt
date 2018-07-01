package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Annotation
import net.semlang.api.Function

/**
 * Note: Converting from sem0 to sem1 will not give you back the same code you had before, but its behavior should be
 * equivalent.
 */
fun convertFromSem0(context: S0Context): RawContext {
    return Sem0To1Converter(context).apply()
}

private class Sem0To1Converter(val input: S0Context) {
    fun apply(): RawContext {
        val functions = input.functions.map(this::apply)
        val structs = input.structs.map(this::apply)
        return RawContext(functions, structs, listOf())
    }

    private fun apply(function: S0Function): Function {
        val id = convertId(function.id)
        val typeParameters = function.typeParameters.map(this::apply)
        val arguments = function.arguments.map(this::apply)
        val returnType = apply(function.returnType)
        val block = apply(function.block)
        val annotations = function.annotations.map(this::apply)
        return Function(id, typeParameters, arguments, returnType, block, annotations)
    }

    private fun apply(typeParameter: S0TypeParameter): TypeParameter {
        val typeClass = apply(typeParameter.typeClass)
        return TypeParameter(typeParameter.name, typeClass)
    }

    private fun apply(typeClass: S0TypeClass?): TypeClass? {
        return if (typeClass == null) {
            null
        } else {
            when (typeClass) {
                S0TypeClass.Data -> TypeClass.Data
            }
        }
    }

    private fun apply(struct: S0Struct): UnvalidatedStruct {
        val id = convertId(struct.id)
        val typeParameters = struct.typeParameters.map(this::apply)
        val members = struct.members.map(this::apply)
        val requires = struct.requires?.let(this::apply)
        val annotations = struct.annotations.map(this::apply)
        return UnvalidatedStruct(id, struct.markedAsThreaded, typeParameters, members, requires, annotations)
    }

    private fun apply(member: S0Member): UnvalidatedMember {
        val type = apply(member.type)
        return UnvalidatedMember(member.name, type)
    }

    private fun apply(annotation: S0Annotation): Annotation {
        return Annotation(EntityId.parse(annotation.name), annotation.values.map(this::apply))
    }

    private fun apply(annotationArg: S0AnnotationArg): AnnotationArgument {
        return when (annotationArg) {
            is S0AnnotationArg.Literal -> {
                AnnotationArgument.Literal(annotationArg.value)
            }
            is S0AnnotationArg.List -> {
                AnnotationArgument.List(annotationArg.values.map(this::apply))
            }
        }
    }

    private fun apply(block: S0Block): Block {
        val assignments = block.assignments.map(this::apply)
        val returnedExpression = apply(block.returnedExpression)
        return Block(assignments, returnedExpression)
    }

    private fun apply(expression: S0Expression): Expression {
        return when (expression) {
            is S0Expression.Variable -> {
                Expression.Variable(expression.name)
            }
            is S0Expression.IfThen -> {
                val condition = convertVarName(expression.conditionVarName)
                val thenBlock = apply(expression.thenBlock)
                val elseBlock = apply(expression.elseBlock)
                Expression.IfThen(condition, thenBlock, elseBlock)
            }
            is S0Expression.NamedFunctionCall -> {
                val functionRef = convertRef(expression.functionId)
                val arguments = expression.argumentVarNames.map(this::convertVarName)
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.NamedFunctionCall(functionRef, arguments, chosenParameters)
            }
            is S0Expression.ExpressionFunctionCall -> {
                val functionExpression = convertVarName(expression.functionVarName)
                val arguments = expression.argumentVarNames.map(this::convertVarName)
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters)
            }
            is S0Expression.Literal -> {
                val type = apply(expression.type)
                Expression.Literal(type, expression.literal)
            }
            is S0Expression.ListLiteral -> {
                val contents = expression.itemVarNames.map(this::convertVarName)
                val chosenParameter = apply(expression.chosenParameter)
                Expression.ListLiteral(contents, chosenParameter)
            }
            is S0Expression.NamedFunctionBinding -> {
                val functionRef = convertRef(expression.functionId)
                val bindings = expression.bindingVarNames.map { if (it == null) null else convertVarName(it) }
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.NamedFunctionBinding(functionRef, bindings, chosenParameters)
            }
            is S0Expression.ExpressionFunctionBinding -> {
                val functionExpression = convertVarName(expression.functionVarName)
                val bindings = expression.bindingVarNames.map { if (it == null) null else convertVarName(it) }
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters)
            }
            is S0Expression.Follow -> {
                val structureExpression = convertVarName(expression.structureVarName)
                Expression.Follow(structureExpression, expression.name)
            }
        }
    }

    private fun convertVarName(expressionVarName: String): Expression {
        return Expression.Variable(expressionVarName)
    }

    private fun apply(assignment: S0Assignment): Assignment {
        val expression = apply(assignment.expression)
        return Assignment(assignment.name, null, expression)
    }

    private fun apply(type: S0Type): UnvalidatedType {
        return when (type) {
            S0Type.Integer -> UnvalidatedType.Integer()
            S0Type.Boolean -> UnvalidatedType.Boolean()
            is S0Type.List -> {
                UnvalidatedType.List(apply(type.parameter))
            }
            is S0Type.Maybe -> {
                UnvalidatedType.Maybe(apply(type.parameter))
            }
            is S0Type.FunctionType -> {
                val argTypes = type.argTypes.map(this::apply)
                val outputType = apply(type.outputType)
                UnvalidatedType.FunctionType(argTypes, outputType)
            }
            is S0Type.NamedType -> {
                val ref = convertRef(type.id)
                val parameters = type.parameters.map(this::apply)
                UnvalidatedType.NamedType(ref, type.isThreaded, parameters)
            }
        }
    }

    private fun convertRef(id: String): EntityRef {
        return EntityRef(null, convertId(id))
    }

    private fun convertId(id: String): EntityId {
        // TODO: This may have to be updated when we add linking
        return EntityId(id.split("."))
    }

    private fun apply(argument: S0Argument): UnvalidatedArgument {
        val type = apply(argument.type)
        return UnvalidatedArgument(argument.name, type)
    }
}
