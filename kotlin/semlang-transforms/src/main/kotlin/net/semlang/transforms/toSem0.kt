package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Annotation
import net.semlang.api.Function
import net.semlang.parser.validate
import net.semlang.parser.validateModule

// TODO: Test this on multi-module cases
fun convertToSem0(module: ValidatedModule): S0Context {
    // TODO: Link non-native modules

    // Apply some pre-transformations...
    val v2 = invalidate(module)
    val v3 = transformInterfacesToStructs(v2)
    // TODO: Find a way to simplify and avoid re-validating here
    val v4 = validateModule(v3, module.id, module.nativeModuleVersion, module.upstreamModules.values.toList()).assumeSuccess()
    val v5 = extractInlineFunctions(v4)
    val v6 = simplifyAllExpressions(v5)

    return Sem1To0Converter(v6).apply()
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
        val typeParameters = function.typeParameters.map(this::apply)
        val arguments = function.arguments.map(this::apply)
        val returnType = apply(function.returnType)
        val block = apply(function.block)
        val annotations = function.annotations.map(this::apply)
        return S0Function(id, typeParameters, arguments, returnType, block, annotations)
    }

    private fun convertId(id: EntityId): String {
        return id.toString()
    }

    private fun apply(typeParameter: TypeParameter): S0TypeParameter {
        val typeClass = apply(typeParameter.typeClass)
        return S0TypeParameter(typeParameter.name, typeClass)
    }

    private fun apply(typeClass: TypeClass?): S0TypeClass? {
        return if (typeClass == null) {
            null
        } else {
            when (typeClass) {
                TypeClass.Data -> S0TypeClass.Data
            }
        }
    }

    private fun apply(struct: UnvalidatedStruct): S0Struct {
        val id = convertId(struct.id)
        val typeParameters = struct.typeParameters.map(this::apply)
        val members = struct.members.map(this::apply)
        val requires = struct.requires?.let(this::apply)
        val annotations = struct.annotations.map(this::apply)
        return S0Struct(id, struct.markedAsThreaded, typeParameters, members, requires, annotations)
    }

    private fun apply(member: UnvalidatedMember): S0Member {
        val type = apply(member.type)
        return S0Member(member.name, type)
    }

    private fun apply(argument: UnvalidatedArgument): S0Argument {
        val type = apply(argument.type)
        return S0Argument(argument.name, type)
    }

    private fun apply(annotation: Annotation): S0Annotation {
        return S0Annotation(annotation.name.toString(), annotation.values.map(this::apply))
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

    private fun apply(type: UnvalidatedType): S0Type {
        return when (type) {
            is UnvalidatedType.Integer -> S0Type.Integer
            is UnvalidatedType.Boolean -> S0Type.Boolean
            is UnvalidatedType.List -> S0Type.List(apply(type.parameter))
            is UnvalidatedType.Maybe -> S0Type.Maybe(apply(type.parameter))
            is UnvalidatedType.FunctionType -> {
                val argTypes = type.argTypes.map(this::apply)
                val outputType = apply(type.outputType)
                S0Type.FunctionType(argTypes, outputType)
            }
            is UnvalidatedType.NamedType -> {
                val id = convertRef(type.ref)
                val parameters = type.parameters.map(this::apply)
                S0Type.NamedType(id, type.isThreaded, parameters)
            }
            is UnvalidatedType.Invalid.ThreadedInteger -> error("Invalid type ~Integer")
            is UnvalidatedType.Invalid.ThreadedBoolean -> error("Invalid type ~Boolean")
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
