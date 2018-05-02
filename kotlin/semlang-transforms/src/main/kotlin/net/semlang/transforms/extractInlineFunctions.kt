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
        set.addAll(inputModule.getAllInternalInterfaces().keys)
        for (interfac in inputModule.getAllInternalInterfaces()) {
            set.add(interfac.value.adapterId)
        }
        set
    }()
    var nextLambdaNumber = 0

    fun apply(): RawContext {
        val transformedFunctions = transformFunctions(inputModule.ownFunctions)
        val transformedStructs = transformStructs(inputModule.ownStructs)
        processExtractedFunctions()
        return RawContext(transformedFunctions + processedExtractedFunctions, transformedStructs, inputModule.ownInterfaces.values.toList().map(::invalidate))
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
        return UnvalidatedStruct(struct.id, struct.isThreaded, struct.typeParameters, members, requires, struct.annotations)
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
        val assignments = block.assignments.map { assignment ->
            Assignment(assignment.name, invalidate(assignment.type), transformExpression(assignment.expression))
        }
        val returnedExpression = transformExpression(block.returnedExpression)
        return Block(assignments, returnedExpression)
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
                val chosenParameters = expression.chosenParameters.map(::invalidate)
                Expression.NamedFunctionBinding(expression.functionRef, bindings, chosenParameters)
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                val functionExpression = transformExpression(expression.functionExpression)
                val bindings = expression.bindings.map{ it?.let(this::transformExpression) }
                val chosenParameters = expression.chosenParameters.map(::invalidate)
                Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters)
            }
            is TypedExpression.Follow -> {
                val structureExpression = transformExpression(expression.structureExpression)
                Expression.Follow(structureExpression, expression.name)
            }
            is TypedExpression.InlineFunction -> {
                // Create a new function
                val newFunctionId = addNewFunctionFromInlined(expression)

                // TODO: Is this correct?
                val chosenParameters = listOf<UnvalidatedType>()

                // Bindings: Explicit arguments come first and are blank
                val bindings = ArrayList<Expression?>()
                for (argument in expression.arguments) {
                    bindings.add(null)
                }
                for ((name) in expression.boundVars) {
                    bindings.add(Expression.Variable(name))
                }

                Expression.NamedFunctionBinding(newFunctionId.asRef(), bindings, chosenParameters)
            }
        }
    }

    private fun addNewFunctionFromInlined(inlineFunction: TypedExpression.InlineFunction): EntityId {
        val newId = getUnusedEntityId()

        // TODO: Is this correct?
        val typeParameters = listOf<String>()

        // We need to include both implicit and explicit parameters
        val explicitArguments = inlineFunction.arguments
        val implicitArguments = inlineFunction.boundVars.map { (name, type) -> Argument(name, type) }
        val arguments = explicitArguments + implicitArguments

        val block = inlineFunction.block
        val returnType = inlineFunction.block.type
        val annotations = listOf<Annotation>()

        val function = ValidatedFunction(newId, typeParameters, arguments, returnType, block, annotations)
        rawExtractedFunctions.add(function)
        return newId
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
