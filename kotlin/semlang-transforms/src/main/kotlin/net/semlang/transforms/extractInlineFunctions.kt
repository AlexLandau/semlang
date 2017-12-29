package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Annotation

// TODO: We should do validation again afterwards...
fun extractInlineFunctions(module: ValidatedModule): ValidatedModule {
    val extractor = InlineFunctionExtractor(module)
    return extractor.apply()
}

private class InlineFunctionExtractor(val inputModule: ValidatedModule) {
    val extractedFunctions = HashMap<EntityId, ValidatedFunction>()
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

    fun apply(): ValidatedModule {
        val transformedFunctions = transformFunctions(inputModule.ownFunctions)
        val transformedStructs = transformStructs(inputModule.ownStructs)
        return ValidatedModule.create(
                inputModule.id,
                inputModule.nativeModuleVersion,
                transformedFunctions + extractedFunctions,
                transformedStructs,
                inputModule.ownInterfaces,
                inputModule.upstreamModules.values
        )
    }

    private fun transformStructs(structs: Map<EntityId, Struct>): Map<EntityId, Struct> {
        return structs.mapValues { (key, value) -> transformStruct(value) }
    }

    private fun transformStruct(struct: Struct): Struct {
        val requires = struct.requires?.let { transformBlock(it) }
        return Struct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations)
    }

    private fun transformFunctions(functions: Map<EntityId, ValidatedFunction>): Map<EntityId, ValidatedFunction> {
        return functions.mapValues { (key, value) -> transformFunction(value) }
    }

    private fun transformFunction(function: ValidatedFunction): ValidatedFunction {
        val block = transformBlock(function.block)
        return ValidatedFunction(function.id, function.typeParameters, function.arguments, function.returnType, block, function.annotations)
    }

    private fun transformBlock(block: TypedBlock): TypedBlock {
        val assignments = block.assignments.map { assignment ->
            ValidatedAssignment(assignment.name, assignment.type, transformExpression(assignment.expression))
        }
        val returnedExpression = transformExpression(block.returnedExpression)
        return TypedBlock(block.type, assignments, returnedExpression)
    }

    private fun transformExpression(expression: TypedExpression): TypedExpression {
        return when (expression) {
            is TypedExpression.Variable -> expression
            is TypedExpression.IfThen -> {
                val condition = transformExpression(expression.condition)
                val thenBlock = transformBlock(expression.thenBlock)
                val elseBlock = transformBlock(expression.elseBlock)
                TypedExpression.IfThen(expression.type, condition, thenBlock, elseBlock)
            }
            is TypedExpression.NamedFunctionCall -> {
                val arguments = expression.arguments.map(this::transformExpression)
                TypedExpression.NamedFunctionCall(expression.type, expression.functionRef, arguments, expression.chosenParameters)
            }
            is TypedExpression.ExpressionFunctionCall -> {
                val functionExpression = transformExpression(expression.functionExpression)
                val arguments = expression.arguments.map(this::transformExpression)
                TypedExpression.ExpressionFunctionCall(expression.type, functionExpression, arguments, expression.chosenParameters)
            }
            is TypedExpression.Literal -> expression
            is TypedExpression.ListLiteral -> {
                val contents = expression.contents.map(this::transformExpression)
                TypedExpression.ListLiteral(expression.type, contents, expression.chosenParameter)
            }
            is TypedExpression.NamedFunctionBinding -> {
                val bindings = expression.bindings.map{ it?.let(this::transformExpression) }
                TypedExpression.NamedFunctionBinding(expression.type, expression.functionRef, bindings, expression.chosenParameters)
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                val functionExpression = transformExpression(expression.functionExpression)
                val bindings = expression.bindings.map{ it?.let(this::transformExpression) }
                TypedExpression.ExpressionFunctionBinding(expression.type, functionExpression, bindings, expression.chosenParameters)
            }
            is TypedExpression.Follow -> {
                val followedExpression = transformExpression(expression.structureExpression)
                TypedExpression.Follow(expression.type, followedExpression, expression.name)
            }
            is TypedExpression.InlineFunction -> {
                // Create a new function
                val newFunction = addNewFunctionFromInlined(expression)
                val newFunctionId = newFunction.id

                // TODO: Is this correct?
                val chosenParameters = listOf<Type>()

                // Bindings: Explicit arguments come first and are blank
                val bindings = ArrayList<TypedExpression?>()
                expression.arguments.forEach { _ ->
                    bindings.add(null)
                }
                expression.boundVars.forEach { (name, type) ->
                    bindings.add(TypedExpression.Variable(type, name))
                }

                TypedExpression.NamedFunctionBinding(expression.type, newFunctionId.asRef(), bindings, chosenParameters)
            }
        }
    }

    private fun addNewFunctionFromInlined(inlineFunction: TypedExpression.InlineFunction): ValidatedFunction {
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
        extractedFunctions.put(newId, function)
        return function
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
