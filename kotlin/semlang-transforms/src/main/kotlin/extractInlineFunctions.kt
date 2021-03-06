package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Annotation
import net.semlang.api.Function
import java.util.*

fun extractInlineFunctions(module: ValidatedModule): RawContext {
    val extractor = InlineFunctionExtractor(module)
    return extractor.apply()
}

private class InlineFunctionExtractor(val inputModule: ValidatedModule) {
    val rawExtractedFunctions = ArrayDeque<ValidatedFunction>()
    val processedExtractedFunctions = ArrayList<Function>()
    val usedEntityIds = {
        val set = HashSet<EntityId>()
        set.addAll(inputModule.getAllInternalFunctions().keys)
        set.addAll(inputModule.getAllInternalStructs().keys)
        set
    }()
    var nextLambdaNumber = 0

    fun apply(): RawContext {
        val transformedFunctions = transformFunctions(inputModule.ownFunctions)
        val transformedStructs = transformStructs(inputModule.ownStructs)
        processExtractedFunctions()
        return RawContext(
                transformedFunctions + processedExtractedFunctions,
                transformedStructs,
                inputModule.ownUnions.values.toList().map(::invalidate)
        )
    }

    private fun processExtractedFunctions() {
        while (rawExtractedFunctions.isNotEmpty()) {
            val toProcess = rawExtractedFunctions.remove()
            processedExtractedFunctions.add(transformFunction(toProcess))
        }
    }

    private fun transformStructs(structs: Map<EntityId, Struct>): List<UnvalidatedStruct> {
        return structs.values.map(this::transformStruct)
    }

    private fun transformStruct(struct: Struct): UnvalidatedStruct {
        val members = struct.members.map(::invalidate)
        val requires = struct.requires?.let { transformBlock(it) }
        return UnvalidatedStruct(struct.id, struct.typeParameters, members, requires, struct.annotations)
    }

    private fun transformFunctions(functions: Map<EntityId, ValidatedFunction>): List<Function> {
        return functions.values.map(this::transformFunction)
    }

    private fun transformFunction(function: ValidatedFunction): Function {
        val arguments = function.arguments.map(::invalidate)
        val block = transformBlock(function.block)
        val returnType = invalidate(function.returnType)
        return Function(function.id, function.typeParameters, arguments, returnType, block, function.annotations)
    }

    private fun transformBlock(block: TypedBlock): Block {
        val statements = block.statements.map(::transform)
        return Block(statements)
    }

    private fun transform(statement: ValidatedStatement): Statement {
        return when (statement) {
            is ValidatedStatement.Assignment -> {
                Statement.Assignment(statement.name, invalidate(statement.type), transformExpression(statement.expression))
            }
            is ValidatedStatement.Bare -> {
                Statement.Bare(transformExpression(statement.expression))
            }
        }
    }

    private fun transformExpression(expression: TypedExpression): Expression {
        return when (expression) {
            is TypedExpression.Variable -> {
                Expression.Variable(expression.name)
            }
            is TypedExpression.IfThen -> {
                val condition = transformExpression(expression.condition)
                val thenBlock = transformBlock(expression.thenBlock)
                val elseBlock = transformBlock(expression.elseBlock)
                Expression.IfThen(condition, thenBlock, elseBlock)
            }
            is TypedExpression.NamedFunctionCall -> {
                val arguments = expression.arguments.map(this::transformExpression)
                val chosenParameters = expression.chosenParameters.map(::invalidate)
                Expression.NamedFunctionCall(expression.functionRef, arguments, chosenParameters)
            }
            is TypedExpression.ExpressionFunctionCall -> {
                val functionExpression = transformExpression(expression.functionExpression)
                val arguments = expression.arguments.map(this::transformExpression)
                val chosenParameters = expression.chosenParameters.map(::invalidate)
                Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters)
            }
            is TypedExpression.Literal -> {
                Expression.Literal(invalidate(expression.type), expression.literal)
            }
            is TypedExpression.ListLiteral -> {
                val contents = expression.contents.map(this::transformExpression)
                Expression.ListLiteral(contents, invalidate(expression.chosenParameter))
            }
            is TypedExpression.NamedFunctionBinding -> {
                val bindings = expression.bindings.map{ it?.let(this::transformExpression) }
                val chosenParameters = expression.chosenParameters.map { it?.let(::invalidate) }
                Expression.NamedFunctionBinding(expression.functionRef, bindings, chosenParameters)
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                val functionExpression = transformExpression(expression.functionExpression)
                val bindings = expression.bindings.map{ it?.let(this::transformExpression) }
                val chosenParameters = expression.chosenParameters.map { it?.let(::invalidate) }
                Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters)
            }
            is TypedExpression.Follow -> {
                val structureExpression = transformExpression(expression.structureExpression)
                Expression.Follow(structureExpression, expression.name)
            }
            is TypedExpression.InlineFunction -> {
                // Create a new function
                val newFunctionInfo = addNewFunctionFromInlined(expression)

                val chosenParameters = newFunctionInfo.typeParameters.map { UnvalidatedType.NamedType.forParameter(it) }

                // Bindings: Explicit arguments come first and are blank
                val bindings = ArrayList<Expression?>()
                for (argument in expression.arguments) {
                    bindings.add(null)
                }
                for ((name) in expression.boundVars) {
                    bindings.add(Expression.Variable(name))
                }

                Expression.NamedFunctionBinding(newFunctionInfo.id.asRef(), bindings, chosenParameters)
            }
        }
    }

    private data class ExtractedFunctionInfo(val id: EntityId, val typeParameters: List<TypeParameter>)

    private fun addNewFunctionFromInlined(inlineFunction: TypedExpression.InlineFunction): ExtractedFunctionInfo {
        val newId = getUnusedEntityId()

        val typeParameters = collectTypeParameters(inlineFunction).toList()

        // We need to include both implicit and explicit parameters
        val explicitArguments = inlineFunction.arguments
        val implicitArguments = inlineFunction.boundVars.map { (name, type) -> Argument(name, type) }
        val arguments = explicitArguments + implicitArguments

        val block = inlineFunction.block
        val returnType = inlineFunction.block.type
        val annotations = listOf<Annotation>()

        val function = ValidatedFunction(newId, typeParameters, arguments, returnType, block, annotations)
        rawExtractedFunctions.add(function)
        return ExtractedFunctionInfo(newId, typeParameters)
    }

    private fun collectTypeParameters(inlineFunction: TypedExpression.InlineFunction): Set<TypeParameter> {
        val typeParameters = HashSet<TypeParameter>()

        for (argument in inlineFunction.arguments) {
            addTypeParameters(typeParameters, argument.type)
        }
        addTypeParameters(typeParameters, inlineFunction.returnType)
        for (boundVar in inlineFunction.boundVars) {
            addTypeParameters(typeParameters, boundVar.type)
        }

        return typeParameters
    }

    private fun addTypeParameters(set: MutableSet<TypeParameter>, type: Type) {
        val unused: Any = when (type) {
            is Type.FunctionType.Ground -> {
                for (argType in type.argTypes) {
                    addTypeParameters(set, argType)
                }
                addTypeParameters(set, type.outputType)
            }
            is Type.FunctionType.Parameterized -> {
                for (argType in type.argTypes) {
                    addTypeParameters(set, argType)
                }
                addTypeParameters(set, type.outputType)
            }
            is Type.ParameterType -> {
                set.add(type.parameter)
            }
            is Type.NamedType -> {
                for (parameter in type.parameters) {
                    addTypeParameters(set, parameter)
                }
            }
            is Type.InternalParameterType -> {
                // This doesn't count
            }
        }
    }

    private fun getUnusedEntityId(): EntityId {
        while (true) {
            val lambdaNumber = nextLambdaNumber
            nextLambdaNumber++

            val entityName = EntityId.of("lambda" + lambdaNumber)
            if (!this.usedEntityIds.contains(entityName)) {
                usedEntityIds.add(entityName)
                return entityName
            }
        }
    }
}
