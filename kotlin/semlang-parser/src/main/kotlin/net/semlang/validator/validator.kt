package net.semlang.validator

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.modules.computeFake0Version
import net.semlang.parser.ParsingResult
import net.semlang.parser.parseFile
import net.semlang.parser.parseString
import net.semlang.transforms.invalidate
import java.io.File
import java.util.*

// TODO: Remove once unused
private fun fail(e: Exception, text: String): Nothing {
    throw IllegalStateException(text, e)
}

// TODO: Remove once unused
private fun fail(text: String): Nothing {
    val fullMessage = "Validation error, position not recorded: $text"
    error(fullMessage)
}

/*
 * Warning: Doesn't validate that composed literals satisfy their requires blocks, which requires running semlang code to
 *   check (albeit code that can always be run in a vacuum)
 */
fun validateModule(context: RawContext, moduleName: ModuleName, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): ValidationResult {
    val fake0Version = computeFake0Version(context, upstreamModules)
    val moduleId = ModuleUniqueId(moduleName, fake0Version)

    // TODO: Actually implement this
    val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()

    val issuesList = ArrayList<Issue>()
    val typesInfo = getTypesInfo(context, moduleId, nativeModuleVersion, upstreamModules, moduleVersionMappings, { issue -> issuesList.add(issue) })
    val validator = Validator(moduleId, nativeModuleVersion, upstreamModules, moduleVersionMappings, typesInfo, issuesList)
    return validator.validate(context)
}

fun validate(parsingResult: ParsingResult, moduleName: ModuleName, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): ValidationResult {
    return when (parsingResult) {
        is ParsingResult.Success -> {
            validateModule(parsingResult.context, moduleName, nativeModuleVersion, upstreamModules)
        }
        is ParsingResult.Failure -> {
            val validationResult = validateModule(parsingResult.partialContext, moduleName, nativeModuleVersion, upstreamModules)
            when (validationResult) {
                is ValidationResult.Success -> {
                    ValidationResult.Failure(parsingResult.errors, validationResult.warnings)
                }
                is ValidationResult.Failure -> {
                    ValidationResult.Failure(parsingResult.errors + validationResult.errors, validationResult.warnings)
                }
            }
        }
    }
}

fun parseAndValidateFile(file: File, moduleName: ModuleName, nativeModuleVersion: String, upstreamModules: List<ValidatedModule> = listOf()): ValidationResult {
    val parsingResult = parseFile(file)
    return validate(parsingResult, moduleName, nativeModuleVersion, upstreamModules)
}

fun parseAndValidateString(text: String, documentUri: String, moduleName: ModuleName, nativeModuleVersion: String): ValidationResult {
    val parsingResult = parseString(text, documentUri)
    return validate(parsingResult, moduleName, nativeModuleVersion, listOf())
}

enum class IssueLevel {
    WARNING,
    ERROR,
}

data class Issue(val message: String, val location: Location?, val level: IssueLevel)

sealed class ValidationResult {
    abstract fun assumeSuccess(): ValidatedModule
    abstract fun getAllIssues(): List<Issue>
    data class Success(val module: ValidatedModule, val warnings: List<Issue>): ValidationResult() {
        override fun getAllIssues(): List<Issue> {
            return warnings
        }
        override fun assumeSuccess(): ValidatedModule {
            return module
        }
    }
    data class Failure(val errors: List<Issue>, val warnings: List<Issue>): ValidationResult() {
        override fun getAllIssues(): List<Issue> {
            return errors + warnings
        }
        override fun assumeSuccess(): ValidatedModule {
            error("Encountered errors in validation: ${formatForCliOutput(errors)}")
        }

    }
}

private fun formatForCliOutput(allErrors: List<Issue>): String {
    val sb = StringBuilder()
    val errorsByDocument: Map<String?, List<Issue>> = allErrors.groupBy { error -> if (error.location == null) null else error.location.documentUri }
    for ((document, errors) in errorsByDocument) {
        if (document == null) {
            sb.append("In an unknown location:\n")
        } else {
            sb.append("In ${document}:\n")
        }
        for (error in errors) {
            sb.append("  ")
            if (error.location != null) {
                sb.append(error.location.range).append(": ")
            }
            sb.append(error.message).append("\n")
        }
    }
    return sb.toString()
}

private class Validator(
        val moduleId: ModuleUniqueId,
        val nativeModuleVersion: String,
        val upstreamModules: List<ValidatedModule>,
        val moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>,
        val typesInfo: TypesInfo,
        initialIssues: List<Issue>) {
    val warnings = ArrayList<Issue>(initialIssues.filter { it.level == IssueLevel.WARNING })
    val errors = ArrayList<Issue>(initialIssues.filter { it.level == IssueLevel.ERROR })

    fun validate(context: RawContext): ValidationResult {
        val ownFunctions = validateFunctions(context.functions)
        val ownStructs = validateStructs(context.structs)
        val ownInterfaces = validateInterfaces(context.interfaces)
        val ownUnions = validateUnions(context.unions)

        if (errors.isEmpty()) {
            val createdModule = ValidatedModule.create(moduleId, nativeModuleVersion, ownFunctions, ownStructs, ownInterfaces, ownUnions, upstreamModules, moduleVersionMappings)
            return ValidationResult.Success(createdModule, warnings)
        } else {
            return ValidationResult.Failure(errors, warnings)
        }
    }

    private fun validateFunctions(functions: List<Function>): Map<EntityId, ValidatedFunction> {
        val validatedFunctions = LinkedHashMap<EntityId, ValidatedFunction>()
        for (function in functions) {
            val validatedFunction = validateFunction(function)
            if (validatedFunction != null && !typesInfo.duplicateLocalFunctionIds.contains(validatedFunction.id)) {
                validatedFunctions.put(function.id, validatedFunction)
            } else if (errors.isEmpty()) {
                fail("Something bad happened")
            }
        }
        return validatedFunctions
    }

    private fun validateFunction(function: Function): ValidatedFunction? {
        //TODO: Validate that no two arguments have the same name

        val arguments = validateArguments(function.arguments, function.typeParameters.associateBy(TypeParameter::name)) ?: return null
        val returnType = validateType(function.returnType, function.typeParameters.associateBy(TypeParameter::name)) ?: return null

//        if (returnType.isReference()) {
//            errors.add(Issue("Reference types cannot be returned from functions", function.returnTypeLocation, IssueLevel.ERROR))
//        }

        //TODO: Validate that type parameters don't share a name with something important
        val variableTypes = getArgumentVariableTypes(arguments)
        val block = validateBlock(function.block, variableTypes, function.typeParameters.associateBy(TypeParameter::name), function.id) ?: return null
        if (returnType != block.type) {
            errors.add(Issue("Stated return type ${function.returnType} does not match the block's actual return type ${block.type}", function.returnTypeLocation, IssueLevel.ERROR))
        }

        return ValidatedFunction(function.id, function.typeParameters, arguments, returnType, block, function.annotations)
    }

    private fun validateType(type: UnvalidatedType, typeParametersInScope: Map<String, TypeParameter>): Type? {
        return validateType(type, typeParametersInScope, listOf())
    }
    private fun validateType(type: UnvalidatedType, typeParametersInScope: Map<String, TypeParameter>, internalParameters: List<String>): Type? {
        return when (type) {
            is UnvalidatedType.Integer -> Type.INTEGER
            is UnvalidatedType.Boolean -> Type.BOOLEAN
            is UnvalidatedType.List -> {
                val parameter = validateType(type.parameter, typeParametersInScope, internalParameters) ?: return null
                Type.List(parameter)
            }
            is UnvalidatedType.Maybe -> {
                val parameter = validateType(type.parameter, typeParametersInScope, internalParameters) ?: return null
                Type.Maybe(parameter)
            }
            is UnvalidatedType.FunctionType -> {
                val newInternalParameters = ArrayList<String>()
                // Add the new parameters to the front of the list
                for (typeParameter in type.typeParameters) {
                    newInternalParameters.add(typeParameter.name)
                }
                newInternalParameters.addAll(internalParameters)

                val argTypes = type.argTypes.map { argType -> validateType(argType, typeParametersInScope, newInternalParameters) ?: return null }
                val outputType = validateType(type.outputType, typeParametersInScope, newInternalParameters) ?: return null
                Type.FunctionType.create(type.isReference, type.typeParameters, argTypes, outputType)
            }
            is UnvalidatedType.NamedType -> {
                if (type.parameters.isEmpty()
                        && type.ref.moduleRef == null
                        && type.ref.id.namespacedName.size == 1) {
                    val typeName = type.ref.id.namespacedName[0]
                    val internalParameterIndex = internalParameters.indexOf(typeName)
                    if (internalParameterIndex >= 0) {
                        return Type.InternalParameterType(internalParameterIndex)
                    }
                    val externalParameterType = typeParametersInScope[typeName]
                    if (externalParameterType != null) {
                        return Type.ParameterType(externalParameterType)
                    }
                }

                val typeInfo = typesInfo.getTypeInfo(type.ref)

                if (typeInfo == null) {
                    errors.add(Issue("Unresolved type reference: ${type.ref}", type.location, IssueLevel.ERROR))
                    return null
                }
                val shouldBeReference = typeInfo.isReference

                if (shouldBeReference && !type.isReference) {
                    errors.add(Issue("Type $type is a reference type and should be marked as such with '&'", type.location, IssueLevel.ERROR))
                    return null
                }
                if (type.isReference && !shouldBeReference) {
                    errors.add(Issue("Type $type is not a reference type and should not be marked with '&'", type.location, IssueLevel.ERROR))
                    return null
                }
                val parameters = type.parameters.map { parameter -> validateType(parameter, typeParametersInScope, internalParameters) ?: return null }
                Type.NamedType(typeInfo.resolvedRef, type.ref, type.isReference, parameters)
            }
            is UnvalidatedType.Invalid.ReferenceInteger -> {
                errors.add(Issue("Integer is not a reference type and should not be marked with &", type.location, IssueLevel.ERROR))
                null
            }
            is UnvalidatedType.Invalid.ReferenceBoolean -> {
                errors.add(Issue("Boolean is not a reference type and should not be marked with &", type.location, IssueLevel.ERROR))
                null
            }
        }
    }

    private fun validateArguments(arguments: List<UnvalidatedArgument>, typeParametersInScope: Map<String, TypeParameter>): List<Argument>? {
        val validatedArguments = ArrayList<Argument>()
        for (argument in arguments) {
            val type = validateType(argument.type, typeParametersInScope) ?: return null
            validatedArguments.add(Argument(argument.name, type))
        }
        return validatedArguments
    }

    private fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
        return arguments.associate { argument -> Pair(argument.name, argument.type) }
    }

    private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, containingFunctionId: EntityId): TypedBlock? {
        val variableTypes = HashMap(externalVariableTypes)
        val validatedStatements = ArrayList<ValidatedStatement>()
        for (statement in block.statements) {
            val varName = statement.name
            if (varName != null) {
                if (variableTypes.containsKey(varName)) {
                    errors.add(Issue("The already-assigned variable ${varName} cannot be reassigned", statement.nameLocation, IssueLevel.ERROR))
                }
                if (isInvalidVariableName(varName)) {
                    errors.add(Issue("Invalid variable name ${varName}", statement.nameLocation, IssueLevel.ERROR))
                }
            }

            val validatedExpression = validateExpression(statement.expression, variableTypes, typeParametersInScope, HashSet<String>(), containingFunctionId) ?: return null
            val unvalidatedAssignmentType = statement.type
            if (unvalidatedAssignmentType != null) {
                val assignmentType = validateType(unvalidatedAssignmentType, typeParametersInScope) ?: return null
                if (validatedExpression.type != assignmentType) {
                    fail("In function $containingFunctionId, the variable $varName is supposed to be of type $assignmentType, " +
                            "but the expression given has actual type ${validatedExpression.type}")
                }
            }

            val referentialActionsCount = countReferentialActions(validatedExpression)
            if (referentialActionsCount > 1) {
                // TODO: This should allow nested calls where the order is unambiguous, but I need to consider the general case more carefully
                // TODO: Another convenience we can add is allowing multiple referential "actions" if they are all read-only, e.g. reading a bunch of variables
                errors.add(Issue("The statement contains more than one referential action; move these to separate statements to disambiguate the order in which they should happen", statement.expression.location, IssueLevel.ERROR))
            }

            validatedStatements.add(ValidatedStatement(varName, validatedExpression.type, validatedExpression))
            if (varName != null) {
                if (validatedExpression.type.isReference() && validatedExpression.aliasType == AliasType.PossiblyAliased) {
                    errors.add(Issue("We are assigning a reference to the variable $varName, but the reference may already have an alias; references are not allowed to have more than one alias", statement.nameLocation, IssueLevel.ERROR))
                }
                variableTypes.put(varName, validatedExpression.type)
            }
        }
        val returnedExpression = validateExpression(block.returnedExpression, variableTypes, typeParametersInScope, HashSet<String>(), containingFunctionId) ?: return null
        val referentialActionsCount = countReferentialActions(returnedExpression)
        if (referentialActionsCount > 1) {
            // TODO: This should allow nested calls where the order is unambiguous, but I need to consider the general case more carefully
            errors.add(Issue("The statement contains more than one referential action; move these to separate statements to disambiguate the order in which they should happen", block.returnedExpression.location, IssueLevel.ERROR))
        }

        return TypedBlock(returnedExpression.type, validatedStatements, returnedExpression)
    }

    private fun countReferentialActions(expression: TypedExpression): Int {
        return when (expression) {
            is TypedExpression.Variable -> 0
            is TypedExpression.IfThen -> countReferentialActions(expression.condition)
            is TypedExpression.NamedFunctionCall -> {
                var count = 0
                if (expression.type.isReference() || expression.arguments.any { it.type.isReference() }) {
                    count++
                }
                for (argument in expression.arguments) {
                    count += countReferentialActions(argument)
                }
                count
            }
            is TypedExpression.ExpressionFunctionCall -> {
                var count = 0
                count += countReferentialActions(expression.functionExpression)
                if (expression.type.isReference() || expression.arguments.any { it.type.isReference() }) {
                    count++
                }
                for (argument in expression.arguments) {
                    count += countReferentialActions(argument)
                }
                count
            }
            is TypedExpression.Literal -> 0
            is TypedExpression.ListLiteral -> {
                expression.contents.map(this::countReferentialActions).sum()
            }
            is TypedExpression.NamedFunctionBinding -> 0
            is TypedExpression.ExpressionFunctionBinding -> countReferentialActions(expression.functionExpression)
            is TypedExpression.Follow -> 0
            is TypedExpression.InlineFunction -> 0
        }
    }

    //TODO: Construct this more sensibly from more centralized lists
    private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Boolean", "function", "let", "if", "else", "struct", "requires", "interface", "union")

    private fun isInvalidVariableName(name: String): Boolean {
        val nameAsEntityRef = EntityId.of(name).asRef()
        return typesInfo.getFunctionInfo(nameAsEntityRef) != null
                || INVALID_VARIABLE_NAMES.contains(name)
    }

    // TODO: Remove containingFunctionId argument when no longer needed
    private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        return when (expression) {
            is Expression.Variable -> validateVariableExpression(expression, variableTypes, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.Follow -> validateFollowExpression(expression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.NamedFunctionCall -> validateNamedFunctionCallExpression(expression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.ExpressionFunctionCall -> validateExpressionFunctionCallExpression(expression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.Literal -> validateLiteralExpression(expression, typeParametersInScope)
            is Expression.ListLiteral -> validateListLiteralExpression(expression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.NamedFunctionBinding -> validateNamedFunctionBinding(expression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.ExpressionFunctionBinding -> validateExpressionFunctionBinding(expression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            is Expression.InlineFunction -> validateInlineFunction(expression, variableTypes, typeParametersInScope, containingFunctionId)
        }
    }

    private fun validateInlineFunction(expression: Expression.InlineFunction, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, containingFunctionId: EntityId): TypedExpression? {
        for (arg in expression.arguments) {
            if (variableTypes.containsKey(arg.name)) {
                errors.add(Issue("Argument name ${arg.name} shadows an existing variable name", arg.location, IssueLevel.ERROR))
            }
        }
        val validatedArguments = validateArguments(expression.arguments, typeParametersInScope) ?: return null

        val incomingVariableTypes: Map<String, Type> = variableTypes + validatedArguments.asVariableTypesMap()
        val validatedBlock = validateBlock(expression.block, incomingVariableTypes, typeParametersInScope, containingFunctionId) ?: return null

        // Note: This is the source of the canonical in-memory ordering
        val varsToBind = ArrayList<String>(variableTypes.keys)
        varsToBind.retainAll(getVarsReferencedIn(validatedBlock))
        val varsToBindWithTypes = varsToBind.map { name -> Argument(name, variableTypes[name]!!)}
        var isReference = false
        for (varToBindWithType in varsToBindWithTypes) {
            if (varToBindWithType.type.isReference()) {
//                errors.add(Issue("The inline function implicitly binds ${varToBindWithType.name}, which has a reference type", expression.location, IssueLevel.ERROR))
                isReference = true
            }
        }

        val returnType = validateType(expression.returnType, typeParametersInScope) ?: return null
        if (validatedBlock.type != returnType) {
            errors.add(Issue("The inline function has a return type $returnType, but the actual type returned is ${validatedBlock.type}", expression.location, IssueLevel.ERROR))
        }

        val functionType = Type.FunctionType.create(isReference, listOf(), validatedArguments.map(Argument::type), returnType)

        return TypedExpression.InlineFunction(functionType, AliasType.NotAliased, validatedArguments, varsToBindWithTypes, returnType, validatedBlock)
    }

    private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("Attempting to bind $functionExpression like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        if (expression.bindings.size != functionType.getNumArguments()) {
            errors.add(Issue("The function binding here expects ${functionType.getNumArguments()} arguments, but ${expression.bindings.size} were given", expression.location, IssueLevel.ERROR))
            return null
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            }
        }
        val bindingTypes = bindings.map { if (it == null) null else it.type }

        val providedChoices = expression.chosenParameters.map { if (it == null) null else validateType(it, typeParametersInScope) }

        val inferredTypeParameters = inferChosenTypeParameters(functionType, providedChoices, bindingTypes, functionType.toString(), expression.location) ?: return null

        val typeWithNewParameters = functionType.rebindTypeParameters(inferredTypeParameters)
        val bindableArgumentTypes = typeWithNewParameters.getBindableArgumentTypes()

        var isBindingAReference = false
        for (entry in bindableArgumentTypes.zip(bindings)) {
            val expectedType = entry.first
            val binding = entry.second
            if (binding != null) {
                // TODO: Make a test where this is necessary
                if (expectedType == null) {
                    error("Invalid binding")
                }
                if (binding.type != expectedType) {
                    errors.add(Issue("A binding is of type ${binding.type} but the expected argument type is $expectedType", expression.location, IssueLevel.ERROR))
                }
                if (binding.type.isReference()) {
//                    errors.add(Issue("Reference objects can't be bound in function bindings", expression.location, IssueLevel.ERROR))
                    isBindingAReference = true
                }
            }
        }

        val postBindingType = typeWithNewParameters.rebindArguments(bindingTypes)
        // This is a defensive check of an expected property while I'm getting this implemented...
        if (isBindingAReference && !postBindingType.isReference()) {
            error("Something went wrong")
        }

        return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression.aliasType, functionExpression, bindings, inferredTypeParameters, providedChoices)
    }

    private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef
        val functionInfo = typesInfo.getFunctionInfo(functionRef)

        if (functionInfo == null) {
            fail("In function $containingFunctionId, resolved a function with ID $functionRef but could not find the signature")
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId)
            }
        }
        val bindingTypes = bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                binding.type
            }
        }

        if (expression.bindings.size != functionInfo.type.getNumArguments()) {
            errors.add(Issue("The function $functionRef expects ${functionInfo.type.getNumArguments()} arguments (with types ${functionInfo.type.argTypes}), but ${expression.bindings.size} were given", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        val providedChoices = expression.chosenParameters.map { if (it == null) null else validateType(it, typeParametersInScope) }

        val validatedFunctionType = validateType(functionInfo.type, typeParametersInScope) as? Type.FunctionType ?: return null
        val inferredTypeParameters = inferChosenTypeParameters(validatedFunctionType, providedChoices, bindingTypes, functionRef.toString(), expression.location) ?: return null

        val typeWithNewParameters = validatedFunctionType.rebindTypeParameters(inferredTypeParameters)
        val bindableArgumentTypes = typeWithNewParameters.getBindableArgumentTypes()

        var isBindingAReference = false
        for (entry in bindableArgumentTypes.zip(bindings)) {
            val expectedType = entry.first
            val binding = entry.second
            if (binding != null) {
                // TODO: Make a test where this is necessary
                if (expectedType == null) {
                    error("Invalid binding")
                }
                if (binding.type != expectedType) {
                    errors.add(Issue("A binding is of type ${binding.type} but the expected argument type is $expectedType", expression.location, IssueLevel.ERROR))
                }
                if (binding.type.isReference()) {
//                    errors.add(Issue("Reference objects can't be bound in function bindings", expression.location, IssueLevel.ERROR))
                    isBindingAReference = true
                }
            }
        }

        val postBindingType = typeWithNewParameters.rebindArguments(bindingTypes)
        // This is a defensive check of an expected property while I'm getting this implemented...
        if (isBindingAReference && !postBindingType.isReference()) {
            error("Something went wrong")
        }

        return TypedExpression.NamedFunctionBinding(postBindingType, AliasType.NotAliased, functionRef, functionInfo.resolvedRef, bindings, inferredTypeParameters, providedChoices)

    }

    private fun inferChosenTypeParameters(functionType: Type.FunctionType, providedChoices: List<Type?>, bindingTypes: List<Type?>, functionDescription: String, expressionLocation: Location?): List<Type?>? {
        // Deal with inference first?
        // What's left after that?
        if (functionType !is Type.FunctionType.Parameterized) {
            return listOf()
        }

        val chosenParameters = if (functionType.typeParameters.size == providedChoices.size) {
            providedChoices
        } else if (functionType.typeParameters.size < providedChoices.size) {
            errors.add(Issue("Too many type parameters were supplied for function call/binding for $functionDescription; provided ${providedChoices.size}, but ${functionType.typeParameters.size} were expected, function type was $functionType", expressionLocation, IssueLevel.ERROR))
            return null
        } else {
            // Apply type parameter inference
            val inferenceSourcesByArgument = functionType.getTypeParameterInferenceSources()
            val explicitParametersIterator = providedChoices.iterator()
            val types = inferenceSourcesByArgument.map { inferenceSources ->
                val inferredType = inferenceSources.stream().map { it.findType(bindingTypes) }.filter { it != null }.findFirst()
                if (inferredType.isPresent()) {
                    inferredType.get()
                } else {
                    if (!explicitParametersIterator.hasNext()) {
                        errors.add(Issue("Not enough type parameters were supplied for function call/binding for $functionDescription", expressionLocation, IssueLevel.ERROR))
                        return null
                    }
                    val chosenParameter = explicitParametersIterator.next()
                    chosenParameter ?: return null
                }
            }
            if (explicitParametersIterator.hasNext()) {
                errors.add(Issue("The function binding for $functionDescription did not supply all type parameters, but supplied more than the appropriate number for type parameter inference", expressionLocation, IssueLevel.ERROR))
                return null
            }
            types
        }
        if (chosenParameters.size != functionType.typeParameters.size) {
            // TODO: Hopefully this is impossible to hit now
            fail("Referenced a function $functionDescription with type parameters ${functionType.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
        }
        for ((typeParameter, chosenType) in functionType.typeParameters.zip(chosenParameters)) {
            if (chosenType != null) {
                validateTypeParameterChoice(typeParameter, chosenType, expressionLocation)
            }
        }
        return chosenParameters
    }

    private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val structureExpression = validateExpression(expression.structureExpression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId) ?: return null

        val structureNamedType = structureExpression.type as? Type.NamedType
        if (structureNamedType == null) {
            errors.add(Issue("Cannot dereference an expression $structureExpression of non-struct, non-interface type ${structureExpression.type}", expression.location, IssueLevel.ERROR))
            return null
        }

        val structureTypeInfo = typesInfo.getTypeInfo(structureNamedType.originalRef) ?: error("No type info for ${structureNamedType.originalRef}")

        return when (structureTypeInfo) {
            is TypeInfo.Struct -> {
                val memberType = structureTypeInfo.memberTypes[expression.name]
                if (memberType == null) {
                    errors.add(Issue("Struct type $structureNamedType does not have a member named '${expression.name}'", expression.location, IssueLevel.ERROR))
                    return null
                }

                // Type parameters come from the struct definition itself
                // Chosen types come from the struct type known for the variable
                val typeParameters = structureTypeInfo.typeParameters
                val chosenTypes = structureNamedType.parameters
                for (chosenParameter in chosenTypes) {
                    if (chosenParameter.isReference()) {
                        errors.add(Issue("Reference types cannot be used as parameters", expression.location, IssueLevel.ERROR))
                    }
                }

                val parameterizedType = replaceAndValidateExternalTypeParameters(memberType, typeParameters, chosenTypes)
                val type = validateType(parameterizedType, typeParametersInScope) ?: return null
                //TODO: Ground this if needed

                return TypedExpression.Follow(type, structureExpression.aliasType, structureExpression, expression.name)

            }
            is TypeInfo.Interface -> {
                val interfac = structureTypeInfo
                val interfaceType = structureNamedType
                val methodType = interfac.methodTypes[expression.name]
                if (methodType == null) {
                    errors.add(Issue("Interface type $structureNamedType does not have a method named '${expression.name}'", expression.location, IssueLevel.ERROR))
                    return null
                }

                val typeParameters = interfac.typeParameters
                val chosenTypes = interfaceType.parameters
                for (chosenParameter in chosenTypes) {
                    if (chosenParameter.isReference()) {
                        errors.add(Issue("Reference types cannot be used as parameters", expression.location, IssueLevel.ERROR))
                    }
                }


                val parameterizedType = replaceAndValidateExternalTypeParameters(methodType, typeParameters, chosenTypes)
                val type = validateType(parameterizedType, typeParametersInScope) ?: return null

                return TypedExpression.Follow(type, structureExpression.aliasType, structureExpression, expression.name)
            }
            is TypeInfo.Union -> {
                error("Currently we don't allow follows for unions")
            }
            is TypeInfo.OpaqueType -> {
                error("Currently we don't allow follows for opaque types")
            }
        }
    }

    private fun replaceAndValidateExternalTypeParameters(originalType: UnvalidatedType, typeParameters: List<TypeParameter>, chosenTypes: List<Type>): UnvalidatedType {
        // TODO: Check that the parameters are of appropriate types
        val parameterReplacementMap = typeParameters.map { it.name }.zip(chosenTypes.map(::invalidate)).toMap()
        val replacedType = originalType.replacingNamedParameterTypes(parameterReplacementMap)
        return replacedType
    }

    private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("The expression $functionExpression is called like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        if (expression.arguments.size != functionType.getNumArguments()) {
            errors.add(Issue("The function binding here expects ${functionType.getNumArguments()} arguments (with types ${functionType.getDefaultGrounding().argTypes}), but ${expression.arguments.size} were given", expression.location, IssueLevel.ERROR))
            return null
        }

        val providedChoices = expression.chosenParameters.map { validateType(it, typeParametersInScope) }

        val arguments = ArrayList<TypedExpression>()
        for (untypedArgument in expression.arguments) {
            val argument = validateExpression(untypedArgument, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        val inferredNullableTypeParameters = inferChosenTypeParameters(functionType, providedChoices, argumentTypes, functionType.toString(), expression.location) ?: return null
        val inferredTypeParameters = inferredNullableTypeParameters.filterNotNull()

        val groundFunctionType = functionType.groundWithTypeParameters(inferredTypeParameters)

        if (argumentTypes != groundFunctionType.argTypes) {
            errors.add(Issue("The function expression expects arguments with types ${groundFunctionType.argTypes}, but was given arguments with types $argumentTypes", expression.location, IssueLevel.ERROR))
            return null
        }

        return TypedExpression.ExpressionFunctionCall(groundFunctionType.outputType, AliasType.NotAliased, functionExpression, arguments, inferredTypeParameters, providedChoices.map { it ?: error("This case should be handled earlier") })
    }

    private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef

        val functionInfo = typesInfo.getFunctionInfo(functionRef)
        if (functionInfo == null) {
            errors.add(Issue("Function $functionRef not found", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        val providedChoices = expression.chosenParameters.map { validateType(it, typeParametersInScope) }

        val arguments = ArrayList<TypedExpression>()
        for (untypedArgument in expression.arguments) {
            val argument = validateExpression(untypedArgument, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        if (expression.arguments.size != functionInfo.type.getNumArguments()) {
            errors.add(Issue("The function $functionRef expects ${functionInfo.type.getNumArguments()} arguments (with types ${functionInfo.type.argTypes}), but ${expression.arguments.size} were given", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        val validatedFunctionType = validateType(functionInfo.type, typeParametersInScope) as Type.FunctionType
        val inferredNullableTypeParameters = inferChosenTypeParameters(validatedFunctionType, providedChoices, argumentTypes, functionRef.toString(), expression.location) ?: return null
        val inferredTypeParameters = inferredNullableTypeParameters.filterNotNull()

        val groundFunctionType = validatedFunctionType.groundWithTypeParameters(inferredTypeParameters)

        if (argumentTypes != groundFunctionType.argTypes) {
            val paramString = if (inferredTypeParameters.isEmpty()) "" else "<$inferredTypeParameters>"
            errors.add(Issue("The function $functionRef$paramString expects arguments with types ${groundFunctionType.argTypes}, but was given arguments with types $argumentTypes", expression.location, IssueLevel.ERROR))
            return null
        }

        return TypedExpression.NamedFunctionCall(groundFunctionType.outputType, AliasType.NotAliased, functionRef, functionInfo.resolvedRef, arguments, inferredTypeParameters, providedChoices.map { it ?: error("This case should be handled earlier") })
    }

    private fun validateTypeParameterChoice(typeParameter: TypeParameter, chosenType: Type, location: Location?) {
        val typeClass = typeParameter.typeClass
        if (chosenType.isReference()) {
            errors.add(Issue("Reference types cannot be used as parameters", location, IssueLevel.ERROR))
        }
        if (typeClass != null) {
            val unused: Any = when (typeClass) {
                TypeClass.Data -> {
                    if (!typesInfo.isDataType(chosenType)) {
                        errors.add(Issue("Type parameter ${typeParameter.name} requires a data type, but ${chosenType.getTypeString()} is not a data type", location, IssueLevel.ERROR))
                    } else {}
                }
            }
        }
    }

    private fun validateLiteralExpression(expression: Expression.Literal, typeParametersInScope: Map<String, TypeParameter>): TypedExpression? {
        val typeChain = getLiteralTypeChain(expression.type, expression.location, typeParametersInScope)

        if (typeChain != null) {
            val nativeLiteralType = typeChain[0]

            val validator = getTypeValidatorFor(nativeLiteralType) ?: error("No literal validator for type $nativeLiteralType")
            val isValid = validator.validate(expression.literal)
            if (!isValid) {
                errors.add(Issue("Invalid literal value '${expression.literal}' for type '${expression.type}'", expression.location, IssueLevel.ERROR))
            }
            // TODO: Someday we need to check for literal values that violate "requires" blocks at validation time
            return TypedExpression.Literal(typeChain.last(), AliasType.NotAliased, expression.literal)
        } else if (errors.isEmpty()) {
            fail("Something went wrong")
        }
        return null
    }

    /**
     * A little explanation:
     *
     * We can have literals for either types with native literals or structs with a single
     * member of a type that can have a literal.
     *
     * In the former case, we return a singleton list with just that type.
     *
     * In the latter case, we return a list starting with the innermost type (one with a
     * native literal implementation) and then following the chain in successive layers
     * outwards to the original type.
     */
    private fun getLiteralTypeChain(initialType: UnvalidatedType, literalLocation: Location?, typeParametersInScope: Map<String, TypeParameter>): List<Type>? {
        var type = validateType(initialType, typeParametersInScope) ?: return null
        val list = ArrayList<Type>()
        list.add(type)
        while (getTypeValidatorFor(type) == null) {
            if (type is Type.NamedType) {
                val structInfo = typesInfo.getTypeInfo(type.originalRef) as? TypeInfo.Struct
                if (structInfo == null) {
                    errors.add(Issue("Trying to get a literal of a non-struct or nonexistent type ${type.originalRef}", literalLocation, IssueLevel.ERROR))
                    return null
                }

                if (structInfo.typeParameters.isNotEmpty()) {
                    // See parameterizedStructLiteral1: this fails in parsing
                    fail("Can't have a literal of a type with type parameters: $type")
                }
                if (structInfo.memberTypes.size != 1) {
                    errors.add(Issue("Can't have a literal of type ${type.originalRef} because it is a struct type with more than one member", literalLocation, IssueLevel.ERROR))
                    return null
                }
                val unvalidatedMemberType = structInfo.memberTypes.values.single()
                val memberType = validateType(unvalidatedMemberType, structInfo.typeParameters.associateBy(TypeParameter::name)) ?: return null
                if (list.contains(memberType)) {
                    errors.add(Issue("Error: Literal type involves cycle of structs: ${list}", literalLocation, IssueLevel.ERROR))
                    return null
                }
                type = memberType
                list.add(type)
            } else {
                error("")
            }
        }

        list.reverse()
        return list
    }

    private fun validateListLiteralExpression(expression: Expression.ListLiteral, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val chosenParameter = validateType(expression.chosenParameter, typeParametersInScope) ?: return null
        if (chosenParameter.isReference()) {
            errors.add(Issue("Reference types cannot be used as parameters", expression.location, IssueLevel.ERROR))
        }

        val listType = Type.List(chosenParameter)

        val contents = expression.contents.map { item ->
            validateExpression(item, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId) ?: return null
        }
        for (item in contents) {
            if (item.type != chosenParameter) {
                error("Put an expression $item of type ${item.type} in a list literal of type ${listType}")
            }
        }

        return TypedExpression.ListLiteral(listType, AliasType.NotAliased, contents, chosenParameter)
    }

    private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val condition = validateExpression(expression.condition, variableTypes, typeParametersInScope, refsAlreadyUsedInStatement, containingFunctionId) ?: return null

        if (condition.type != Type.BOOLEAN) {
            fail("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
        }

        // TODO: Reconsider how references interact with if/then expressions
        val thenBlock = validateBlock(expression.thenBlock, variableTypes, typeParametersInScope, containingFunctionId) ?: return null
        val elseBlock = validateBlock(expression.elseBlock, variableTypes, typeParametersInScope, containingFunctionId) ?: return null

        val type = try {
            typeUnion(thenBlock.type, elseBlock.type)
        } catch (e: RuntimeException) {
            errors.add(Issue("Cannot reconcile types of 'then' block (${thenBlock.type}) and 'else' block (${elseBlock.type})", expression.location, IssueLevel.ERROR))
            return null
        }

        val aliasType = if (thenBlock.returnedExpression.aliasType == AliasType.NotAliased && elseBlock.returnedExpression.aliasType == AliasType.NotAliased) {
            AliasType.NotAliased
        } else {
            AliasType.PossiblyAliased
        }

        return TypedExpression.IfThen(type, aliasType, condition, thenBlock, elseBlock)
    }

    private fun typeUnion(type1: Type, type2: Type): Type {
        // TODO: Handle actual type unions, inheritance as these things get added
        // TODO: Then move this stuff to the API level
        if (type1 == type2) {
            return type1
        }
        fail("Types $type1 and $type2 cannot be unioned")
    }

    private fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, refsAlreadyUsedInStatement: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        if (refsAlreadyUsedInStatement.contains(expression.name)) {
            errors.add(Issue("Variable ${expression.name} is a reference and cannot be used more than once in a statement", expression.location, IssueLevel.ERROR))
            return null
        }
        val type = variableTypes[expression.name]
        if (type != null) {
            if (type.isReference()) {
                refsAlreadyUsedInStatement.add(expression.name)
            }
            return TypedExpression.Variable(type, AliasType.PossiblyAliased, expression.name)
        } else {
            errors.add(Issue("Unknown variable ${expression.name}", expression.location, IssueLevel.ERROR))
            return null
        }
    }

    private fun validateStructs(structs: List<UnvalidatedStruct>): Map<EntityId, Struct> {
        val validatedStructs = LinkedHashMap<EntityId, Struct>()
        for (struct in structs) {
            val validatedStruct = validateStruct(struct)
            if (validatedStruct != null) {
                validatedStructs.put(struct.id, validatedStruct)
            }
        }
        return validatedStructs
    }

    private fun validateStruct(struct: UnvalidatedStruct): Struct? {
        val members = validateMembers(struct, struct.typeParameters.associateBy(TypeParameter::name)) ?: return null

        val memberTypes = members.associate { member -> member.name to member.type }

        val fakeContainingFunctionId = EntityId(struct.id.namespacedName + "requires")
        val uncheckedRequires = struct.requires
        val requires = if (uncheckedRequires != null) {
            validateBlock(uncheckedRequires, memberTypes, struct.typeParameters.associateBy(TypeParameter::name), fakeContainingFunctionId) ?: return null
        } else {
            null
        }
        if (requires != null && requires.type != Type.BOOLEAN) {
            val message = "Struct ${struct.id} has a requires block with inferred type ${requires.type}, but the type should be Boolean"
            val location = struct.requires!!.location
            errors.add(Issue(message, location, IssueLevel.ERROR))
        }

        if (memberTypes.values.any(Type::isReference)) {
            errors.add(Issue("Struct ${struct.id} has members with reference types, which are not allowed", struct.idLocation, IssueLevel.ERROR))
        }

        return Struct(struct.id, moduleId, struct.typeParameters, members, requires, struct.annotations)
    }



    private fun validateMembers(struct: UnvalidatedStruct, typeParametersInScope: Map<String, TypeParameter>): List<Member>? {
        // Check for name duplication
        val allNames = HashSet<String>()
        for (member in struct.members) {
            if (allNames.contains(member.name)) {
                // TODO: Improve position of message
                errors.add(Issue("Struct ${struct.id} has multiple members named ${member.name}", struct.idLocation, IssueLevel.ERROR))
            }
            allNames.add(member.name)
        }

        return struct.members.map { member ->
            val type = validateType(member.type, typeParametersInScope) ?: return null
            Member(member.name, type)
        }
    }

    private fun validateInterfaces(interfaces: List<UnvalidatedInterface>): Map<EntityId, Interface> {
        val validatedInterfaces = LinkedHashMap<EntityId, Interface>()
        for (interfac in interfaces) {
            val validatedInterface = validateInterface(interfac)
            if (validatedInterface != null) {
                validatedInterfaces.put(interfac.id, validatedInterface)
            }
        }
        return validatedInterfaces
    }

    private fun validateInterface(interfac: UnvalidatedInterface): Interface? {
        // TODO: Do some actual validation of interfaces
        val methods = validateMethods(interfac.methods, interfac.typeParameters.associateBy(TypeParameter::name)) ?: return null
        return Interface(interfac.id, moduleId, interfac.typeParameters, methods, interfac.annotations)
    }

    private fun validateMethods(methods: List<UnvalidatedMethod>, interfaceTypeParameters: Map<String, TypeParameter>): List<Method>? {
        // TODO: Do some actual validation of methods
        return methods.map { method ->
            val typeParametersVisibleToMethod = interfaceTypeParameters + method.typeParameters.associateBy(TypeParameter::name)
            val arguments = validateArguments(method.arguments, typeParametersVisibleToMethod) ?: return null
            val returnType = validateType(method.returnType, typeParametersVisibleToMethod) ?: return null
            Method(method.name, method.typeParameters, arguments, returnType)
        }
    }

    private fun validateUnions(unions: List<UnvalidatedUnion>): Map<EntityId, Union> {
        val validatedUnions = LinkedHashMap<EntityId, Union>()
        for (union in unions) {
            val validatedUnion = validateUnion(union)
            if (validatedUnion != null) {
                validatedUnions.put(union.id, validatedUnion)
            }
        }
        return validatedUnions
    }

    private fun validateUnion(union: UnvalidatedUnion): Union? {
        // TODO: Do some additional validation of unions (e.g. no duplicate option IDs)
        if (union.options.isEmpty()) {
            errors.add(Issue("A union must include at least one option", union.idLocation, IssueLevel.ERROR))
            return null
        }
        val options = validateOptions(union.options, union.typeParameters.associateBy(TypeParameter::name)) ?: return null
        return Union(union.id, moduleId, union.typeParameters, options, union.annotations)
    }

    private fun validateOptions(options: List<UnvalidatedOption>, unionTypeParameters: Map<String, TypeParameter>): List<Option>? {
        return options.map { option ->
            val unvalidatedType = option.type
            val type = if (unvalidatedType == null) null else validateType(unvalidatedType, unionTypeParameters)
            if (type != null && type.isReference()) {
                errors.add(Issue("Reference types are not allowed in unions", option.idLocation, IssueLevel.ERROR))
            }
            if (option.name == "when") {
                errors.add(Issue("Union options cannot be named 'when'", option.idLocation, IssueLevel.ERROR))
                return null
            }
            Option(option.name, type)
        }
    }

}

private fun List<Argument>.asVariableTypesMap(): Map<String, Type> {
    return this.map{arg -> Pair(arg.name, arg.type)}.toMap()
}
