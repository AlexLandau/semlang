package net.semlang.validator

import net.semlang.api.*
import net.semlang.api.Function
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
    val fullMessage = "Validation error, position not recorded: " + text
    error(fullMessage)
}

/*
 * Warning: Doesn't validate that composed literals satisfy their requires blocks, which requires running semlang code to
 *   check (albeit code that can always be run in a vacuum)
 */
fun validateModule(context: RawContext, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): ValidationResult {
    val issuesList = ArrayList<Issue>()
    val typesInfo = getTypesInfo(context, moduleId, nativeModuleVersion, upstreamModules, { issue -> issuesList.add(issue) })
    val validator = Validator(moduleId, nativeModuleVersion, upstreamModules, typesInfo, issuesList)
    return validator.validate(context)
}

fun validate(parsingResult: ParsingResult, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): ValidationResult {
    return when (parsingResult) {
        is ParsingResult.Success -> {
            validateModule(parsingResult.context, moduleId, nativeModuleVersion, upstreamModules)
        }
        is ParsingResult.Failure -> {
            val validationResult = validateModule(parsingResult.partialContext, moduleId, nativeModuleVersion, upstreamModules)
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

fun parseAndValidateFile(file: File, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule> = listOf()): ValidationResult {
    val parsingResult = parseFile(file)
    return validate(parsingResult, moduleId, nativeModuleVersion, upstreamModules)
}

fun parseAndValidateString(text: String, documentUri: String, moduleId: ModuleId, nativeModuleVersion: String): ValidationResult {
    val parsingResult = parseString(text, documentUri)
    return validate(parsingResult, moduleId, nativeModuleVersion, listOf())
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
        val moduleId: ModuleId,
        val nativeModuleVersion: String,
        val upstreamModules: List<ValidatedModule>,
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
            val createdModule = ValidatedModule.create(moduleId, nativeModuleVersion, ownFunctions, ownStructs, ownInterfaces, ownUnions, upstreamModules)
            return ValidationResult.Success(createdModule, warnings)
        } else {
            return ValidationResult.Failure(errors, warnings)
        }
    }

    private fun validateFunctions(functions: List<Function>): Map<EntityId, ValidatedFunction> {
        val validatedFunctions = HashMap<EntityId, ValidatedFunction>()
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

        //TODO: Validate that type parameters don't share a name with something important
        val variableTypes = getArgumentVariableTypes(arguments)
        val block = validateBlock(function.block, variableTypes, function.typeParameters.associateBy(TypeParameter::name), HashSet(), function.id) ?: return null
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
                if (parameter.isThreaded()) {
                    errors.add(Issue("Lists cannot have a threaded parameter type", null, IssueLevel.ERROR))
                }
                Type.List(parameter)
            }
            is UnvalidatedType.Maybe -> {
                val parameter = validateType(type.parameter, typeParametersInScope, internalParameters) ?: return null
                if (parameter.isThreaded()) {
                    errors.add(Issue("Tries cannot have a threaded parameter type", null, IssueLevel.ERROR))
                }
                Type.Maybe(parameter)
            }
            is UnvalidatedType.FunctionType -> {
                // TODO: Add validation of these
//                val typeParameters = type.typeParameters
//                val newTypeParameterScope = HashMap<String, TypeParameter>(typeParametersInScope)

                val newInternalParameters = ArrayList<String>()
                // Add the new parameters to the front of the list
                for (typeParameter in type.typeParameters) {
                    newInternalParameters.add(typeParameter.name)
//                    if (newTypeParameterScope.containsKey(typeParameter.name)) {
                        // TODO: Do stuff here?
//                        error("Already using type parameter name ${typeParameter.name}; type is $type, existing type parameters are $newTypeParameterScope")
//                    }
//                    newTypeParameterScope.put(typeParameter.name, typeParameter)
                }
                newInternalParameters.addAll(internalParameters)

                val argTypes = type.argTypes.map { argType -> validateType(argType, typeParametersInScope, newInternalParameters) ?: return null }
                val outputType = validateType(type.outputType, typeParametersInScope, newInternalParameters) ?: return null
                Type.FunctionType(type.typeParameters, argTypes, outputType)
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
//                    if (type.isThreaded) {
//                        error("Type parameters shouldn't be marked as threaded")
//                    }
//                    val resolved = typesInfo.resolver.resolve(type.ref)
                // TODO: Getting this for ListBuilder, TextOut, T (in one case)
                val typeInfo = typesInfo.getTypeInfo(type.ref)

                if (typeInfo == null) {
                    errors.add(Issue("Unresolved type reference: ${type.ref}", type.location, IssueLevel.ERROR))
                    return null
                }
                val shouldBeThreaded = typeInfo.isThreaded

                if (shouldBeThreaded && !type.isThreaded) {
                    errors.add(Issue("Type $type is threaded and should be marked as such with '~'", type.location, IssueLevel.ERROR))
                    return null
                }
                if (type.isThreaded && !shouldBeThreaded) {
                    errors.add(Issue("Type $type is not threaded and should not be marked with '~'", type.location, IssueLevel.ERROR))
                    return null
                }
                val parameters = type.parameters.map { parameter -> validateType(parameter, typeParametersInScope, internalParameters) ?: return null }
                Type.NamedType(typeInfo.resolvedRef, type.ref, type.isThreaded, parameters)
            }
            is UnvalidatedType.Invalid.ThreadedInteger -> {
                errors.add(Issue("Integer is not a threaded type and should not be marked with ~", type.location, IssueLevel.ERROR))
                null
            }
            is UnvalidatedType.Invalid.ThreadedBoolean -> {
                errors.add(Issue("Boolean is not a threaded type and should not be marked with ~", type.location, IssueLevel.ERROR))
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


//    private sealed class TypeInfo {
//        abstract val idLocation: Location?
//        data class Struct(val typeParameters: List<TypeParameter>, val memberTypes: Map<String, UnvalidatedType>, val usesRequires: Boolean, override val idLocation: Location?): TypeInfo()
//        data class Interface(val typeParameters: List<TypeParameter>, val methodTypes: Map<String, UnvalidatedType.FunctionType>, override val idLocation: Location?): TypeInfo()
//        data class Union(val typeParameters: List<TypeParameter>, val optionTypes: Map<String, Optional<UnvalidatedType>>, override val idLocation: Location?): TypeInfo()
//    }
    // TODO: Should this contain unvalidated types or validated types? I think we really want something in between... (maybe?)
    // We can't confirm that named types exist while we put these together; however, we should be able to assemble the correct
    // types in terms of type parameters and getting the InternalParameterTypes
    // So either we introduce a third stage for Type objects or allow creating invalid ResolvedEntityRef objects for local
    // objects (to be checked later)
//    private data class FunctionInfo(val signature: TypeSignature, val idLocation: Location?)


    // TODO: Move this closer to the function that creates it...
//    private inner class AllTypeInfo(val resolver: EntityResolver,
//                                    val localTypes: Map<EntityId, TypeInfo>,
//                                    val duplicateLocalTypeIds: Set<EntityId>,
//                                    val localFunctions: Map<EntityId, FunctionInfo>,
//                                    val duplicateLocalFunctionIds: Set<EntityId>,
//                                    val upstreamTypes: Map<ResolvedEntityRef, TypeInfo>,
//                                    val upstreamFunctions: Map<ResolvedEntityRef, FunctionInfo>) {
//        fun getTypeInfo(resolvedRef: ResolvedEntityRef): TypeInfo? {
//            return if (resolvedRef.module == moduleId) {
//                if (duplicateLocalTypeIds.contains(resolvedRef.id)) {
//                    fail("There are multiple declarations of the type name ${resolvedRef.id}")
//                }
//                localTypes[resolvedRef.id]
//            } else {
//                upstreamTypes[resolvedRef]
//            }
//        }
//        fun getFunctionInfo(resolvedRef: ResolvedEntityRef): FunctionInfo? {
//            return if (resolvedRef.module == moduleId) {
//                if (duplicateLocalFunctionIds.contains(resolvedRef.id)) {
//                    fail("There are multiple declarations of the function name ${resolvedRef.id}")
//                }
//                localFunctions[resolvedRef.id]
//            } else {
//                upstreamFunctions[resolvedRef]
//            }
//        }
        // TODO: We shouldn't have two functions doing this on Types and UnvalidatedTypes
//        private fun isDataType(type: UnvalidatedType): Boolean {
//            return when (type) {
//                is UnvalidatedType.Integer -> true
//                is UnvalidatedType.Boolean -> true
//                is UnvalidatedType.List -> isDataType(type.parameter)
//                is UnvalidatedType.Maybe -> isDataType(type.parameter)
//                is UnvalidatedType.FunctionType -> false
//                is UnvalidatedType.NamedType -> {
//                    // TODO: We might want some caching here
//                    if (type.isThreaded) {
//                        false
//                    } else {
//                        val resolvedRef = resolver.resolve(type.ref)!!
//                        val typeInfo = getTypeInfo(resolvedRef.entityRef)!!
//                        when (typeInfo) {
//                            is TypeInfo.Struct -> {
//                                // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
//                                // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
//                                typeInfo.memberTypes.values.all { isDataType(it) }
//                            }
//                            is TypeInfo.Interface -> typeInfo.methodTypes.isEmpty()
//                            is TypeInfo.Union -> {
//                                // TODO: Need to handle recursive references here, too
//                                typeInfo.optionTypes.values.all { !it.isPresent || isDataType(it.get()) }
//                            }
//                        }
//                    }
//                }
//                is UnvalidatedType.Invalid.ThreadedInteger -> false
//                is UnvalidatedType.Invalid.ThreadedBoolean -> false
//            }
//        }
//    }

    private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedBlock? {
        val variableTypes = HashMap(externalVariableTypes)
        val validatedAssignments = ArrayList<ValidatedAssignment>()
        for (assignment in block.assignments) {
            if (variableTypes.containsKey(assignment.name)) {
                errors.add(Issue("The already-assigned variable ${assignment.name} cannot be reassigned", assignment.nameLocation, IssueLevel.ERROR))
            }
            if (isInvalidVariableName(assignment.name)) {
                errors.add(Issue("Invalid variable name ${assignment.name}", assignment.nameLocation, IssueLevel.ERROR))
            }

            val validatedExpression = validateExpression(assignment.expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
            val unvalidatedAssignmentType = assignment.type
            if (unvalidatedAssignmentType != null) {
                val assignmentType = validateType(unvalidatedAssignmentType, typeParametersInScope) ?: return null
                if (validatedExpression.type != assignmentType) {
                    fail("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type $assignmentType, " +
                            "but the expression given has actual type ${validatedExpression.type}")
                }
            }

            validatedAssignments.add(ValidatedAssignment(assignment.name, validatedExpression.type, validatedExpression))
            variableTypes.put(assignment.name, validatedExpression.type)
        }
        val returnedExpression = validateExpression(block.returnedExpression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
        return TypedBlock(returnedExpression.type, validatedAssignments, returnedExpression)
    }

    //TODO: Construct this more sensibly from more centralized lists
    private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Boolean", "function", "let", "if", "else", "struct", "requires", "interface", "union")

    private fun isInvalidVariableName(name: String): Boolean {
        val nameAsEntityRef = EntityId.of(name).asRef()
        return typesInfo.getFunctionInfo(nameAsEntityRef) != null
                || INVALID_VARIABLE_NAMES.contains(name)
    }

    // TODO: Remove containingFunctionId argument when no longer needed
    private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        return when (expression) {
            is Expression.Variable -> validateVariableExpression(expression, variableTypes, consumedThreadedVars, containingFunctionId)
            is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.Follow -> validateFollowExpression(expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.NamedFunctionCall -> validateNamedFunctionCallExpression(expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.ExpressionFunctionCall -> validateExpressionFunctionCallExpression(expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.Literal -> validateLiteralExpression(expression, typeParametersInScope)
            is Expression.ListLiteral -> validateListLiteralExpression(expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.NamedFunctionBinding -> validateNamedFunctionBinding(expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.ExpressionFunctionBinding -> validateExpressionFunctionBinding(expression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
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
        val validatedBlock = validateBlock(expression.block, incomingVariableTypes, typeParametersInScope, HashSet(), containingFunctionId) ?: return null

        // Note: This is the source of the canonical in-memory ordering
        val varsToBind = ArrayList<String>(variableTypes.keys)
        varsToBind.retainAll(getVarsReferencedIn(validatedBlock))
        val varsToBindWithTypes = varsToBind.map { name -> Argument(name, variableTypes[name]!!)}
        for (varToBindWithType in varsToBindWithTypes) {
            if (varToBindWithType.type.isThreaded()) {
                errors.add(Issue("The inline function implicitly binds ${varToBindWithType.name}, which has a threaded type", expression.location, IssueLevel.ERROR))
            }
        }

        val returnType = validateType(expression.returnType, typeParametersInScope) ?: return null
        if (validatedBlock.type != returnType) {
            errors.add(Issue("The inline function has a return type $returnType, but the actual type returned is ${validatedBlock.type}", expression.location, IssueLevel.ERROR))
        }

        val functionType = Type.FunctionType(listOf(), validatedArguments.map(Argument::type), validatedBlock.type)

        return TypedExpression.InlineFunction(functionType, validatedArguments, varsToBindWithTypes, returnType, validatedBlock)
    }

    private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("Attempting to bind $functionExpression like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            }
        }
        val bindingTypes = bindings.map { if (it == null) null else it.type }

        // TODO: Implement this
        val providedChoices = expression.chosenParameters.map { if (it == null) null else validateType(it, typeParametersInScope) }

        val inferredTypeParameters = inferChosenTypeParameters(functionType, providedChoices, bindingTypes, functionType.toString(), expression.location) ?: return null

        val argTypes = functionType.getArgTypes(inferredTypeParameters)
        val outputType = functionType.getOutputType(inferredTypeParameters)

        val postBindingType = Type.FunctionType(
                functionType.typeParameters.zip(inferredTypeParameters).filter { it.second == null }.map { it.first },
                argTypes.zip(bindingTypes).filter { it.second == null }.map { it.first },
                outputType)

        return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings, inferredTypeParameters)

//        TODO()
//
//        val preBindingArgumentTypes = functionType.argTypes
//
//        if (preBindingArgumentTypes.size != expression.bindings.size) {
//            errors.add(Issue("Tried to re-bind $functionExpression with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments", expression.location, IssueLevel.ERROR))
//            return null
//        }
//
//
//        val postBindingArgumentTypes = ArrayList<Type>()
//        for (entry in preBindingArgumentTypes.zip(bindings)) {
//            val type = entry.first
//            val binding = entry.second
//            if (binding == null) {
//                postBindingArgumentTypes.add(type)
//            } else {
//                if (binding.type != type) {
//                    fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
//                }
//                if (binding.type.isThreaded()) {
//                    errors.add(Issue("Threaded objects can't be bound in function bindings", expression.location, IssueLevel.ERROR))
//                }
//            }
//        }
//        val postBindingType = Type.FunctionType(
//                listOf(),
//                postBindingArgumentTypes,
//                functionType.outputType)
//
//        return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings)
    }

    private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef

//        val resolvedRef = typesInfo.resolver.resolve(functionRef) ?: fail("In function $containingFunctionId, could not find a declaration of a function with ID $functionRef")
        val functionInfo = typesInfo.getFunctionInfo(functionRef)

        if (functionInfo == null) {
            fail("In function $containingFunctionId, resolved a function with ID $functionRef but could not find the signature")
        }
        val functionType = functionInfo.type

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            }
        }
        val bindingTypes = bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                binding.type
            }
        }

        val providedChoices = expression.chosenParameters.map { if (it == null) null else validateType(it, typeParametersInScope) }

        val inferredTypeParameters = inferChosenTypeParameters(functionType, providedChoices, bindingTypes, functionRef.toString(), expression.location) ?: return null

        val argTypes = functionInfo.type.getArgTypes(inferredTypeParameters)
        val outputType = functionInfo.type.getOutputType(inferredTypeParameters)

        val postBindingType = Type.FunctionType(
                functionInfo.type.typeParameters.zip(inferredTypeParameters).filter { it.second == null }.map { it.first },
                argTypes.zip(bindingTypes).filter { it.second == null }.map { it.first },
                outputType)

        return TypedExpression.NamedFunctionBinding(postBindingType, functionRef, functionInfo.resolvedRef, bindings, inferredTypeParameters)


        // So what do we want this new world to look like?
        // Given the original signature of the function (function type -- this would be all we get coming from the expression),
        // there are certain patterns we do and do not allow in terms of maintaining or defining the parameters and bindings:
        // - If an argument type contains a reference to a type parameter and the argument is bound, then the type parameter
        //   must also be defined in the binding
        // This might be the only rule here in terms of disallowing certain bindings. It's allowed to define more types
        // than necessary given the bindings involved.

        // So here are the inputs:
        // - Original function type (including type parameters, argument types, output type)
        // - Type parameters, possibly including underscores, possibly not, possibly using type parameter inference
        // - Bindings

        // And here are the outputs we want:
        // - New function binding type (including type parameters, argument types, output types)

        // And the error cases we need to consider:
        // - Wrong number of type parameters or bindings
        // - Types of bindings are wrong given the arguments
        // - A binding is provided where a type is not fully defined yet

//        val providedParameters = expression.chosenParameters.map {
//            if (it == null) null else (validateType(it, typeInfo, typeParametersInScope) ?: return null)
//        }
//
//        val chosenParameters = getFinalParametersForBinding(signature.getFunctionType(), providedParameters, bindingTypes, typeInfo, typeParametersInScope, functionRef, expression.location) ?: return null

//        val chosenParameters = if (signature.typeParameters.size == expression.chosenParameters.size) {
//            expression.chosenParameters.map { chosenParameter -> validateType(chosenParameter!!, typeInfo, typeParametersInScope) ?: return null }
//        } else if (signature.typeParameters.size < expression.chosenParameters.size) {
//            errors.add(Issue("Too many type parameters were supplied for function binding for $functionRef", expression.location, IssueLevel.ERROR))
//            return null
//        } else {
//            // Apply type parameter inference
//            val inferenceSourcesByArgument = signature.getTypeParameterInferenceSources()
//            val explicitParametersIterator = expression.chosenParameters.iterator()
//            val types = inferenceSourcesByArgument.map { inferenceSources ->
//                val inferredType = inferenceSources.stream().map { it.findType(bindingTypes) }.filter { it != null }.findFirst()
//                if (inferredType.isPresent()) {
//                    inferredType.get()
//                } else {
//                    if (!explicitParametersIterator.hasNext()) {
//                        errors.add(Issue("Not enough type parameters were supplied for function binding for ${functionRef}", expression.location, IssueLevel.ERROR))
//                        return null
//                    }
//                    val chosenParameter = explicitParametersIterator.next()
//                    validateType(chosenParameter!!, typeInfo, typeParametersInScope) ?: return null
//                }
//            }
//            if (explicitParametersIterator.hasNext()) {
//                errors.add(Issue("The function binding for $functionRef did not supply all type parameters, but supplied more than the appropriate number for type parameter inference", expression.location, IssueLevel.ERROR))
//                return null
//            }
//            types
//        }
//        if (chosenParameters.size != signature.typeParameters.size) {
//            fail("In function $containingFunctionId, referenced a function $functionRef with type parameters ${signature.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
//        }
//        for ((typeParameter, chosenType) in signature.typeParameters.zip(chosenParameters)) {
//            validateTypeParameterChoice(typeParameter, chosenType, expression.location, typeInfo)
//        }

//        val expectedFunctionType = applyChosenParametersToFunctionType(signature.getFunctionType(), chosenParameters, typeInfo, typeParametersInScope) ?: return null
//
//        if (expectedFunctionType.argTypes.size != expression.bindings.size) {
//            errors.add(Issue("Tried to bind function $functionRef with ${expression.bindings.size} bindings, but it takes ${expectedFunctionType.argTypes.size} arguments", expression.location, IssueLevel.ERROR))
//            return null
//        }
//
//        val postBindingArgumentTypes = ArrayList<Type>()
//        for (entry in expectedFunctionType.argTypes.zip(bindings)) {
//            val type = entry.first
//            val binding = entry.second
//            if (binding == null) {
//                postBindingArgumentTypes.add(type)
//            } else {
//                if (binding.type != type) {
//                    fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
//                }
//                if (binding.type.isThreaded()) {
//                    errors.add(Issue("Threaded objects can't be bound in function bindings", expression.location, IssueLevel.ERROR))
//                }
//            }
//        }
//        val postBindingType = Type.FunctionType(
//                listOf(),
//                postBindingArgumentTypes,
//                expectedFunctionType.outputType)
//
//        return TypedExpression.NamedFunctionBinding(postBindingType, functionRef, resolvedRef.entityRef, bindings, chosenParameters)
    }

    private fun inferChosenTypeParameters(functionType: Type.FunctionType, providedChoices: List<Type?>, bindingTypes: List<Type?>, functionDescription: String, expressionLocation: Location?): List<Type?>? {
        // Deal with inference first?
        // What's left after that?

        val chosenParameters = if (functionType.typeParameters.size == providedChoices.size) {
            providedChoices
        } else if (functionType.typeParameters.size < providedChoices.size) {
            errors.add(Issue("Too many type parameters were supplied for function call/binding for $functionDescription; provided ${providedChoices.size}, but ${functionType.typeParameters.size} were expected", expressionLocation, IssueLevel.ERROR))
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

    private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val structureExpression = validateExpression(expression.structureExpression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        val structureNamedType = structureExpression.type as? Type.NamedType
        if (structureNamedType == null) {
            errors.add(Issue("Cannot dereference an expression $structureExpression of non-struct, non-interface type ${structureExpression.type}", expression.location, IssueLevel.ERROR))
            return null
        }

//        val resolvedStructureType = typesInfo.resolver.resolve(structureNamedType.originalRef)
//        if (resolvedStructureType == null) {
//            errors.add(Issue("Cannot dereference an expression $structureExpression of unrecognized type ${structureExpression.type}", expression.location, IssueLevel.ERROR))
//            return null
//        }
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
                    if (chosenParameter.isThreaded()) {
                        errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
                    }
                }

                val parameterizedType = replaceAndValidateExternalTypeParameters(memberType, typeParameters, chosenTypes)
                val type = validateType(parameterizedType, typeParametersInScope) ?: return null
//                TODO()
//                val type = parameterizeAndValidateType(memberType, typeParameters.map(Type::ParameterType), chosenTypes, typeInfo, typeParametersInScope) ?: return null
                //TODO: Ground this if needed

                return TypedExpression.Follow(type, structureExpression, expression.name)

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
                    if (chosenParameter.isThreaded()) {
                        errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
                    }
                }
                TODO()
//                val type = parameterizeAndValidateType(methodType, typeParameters.map(Type::ParameterType), chosenTypes, typeInfo, typeParametersInScope) ?: return null

//                return TypedExpression.Follow(type, structureExpression, expression.name)
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

    // TODO: Disentangle the typeParameters and chosenTypes mess here
    /*
    This is called from:

    - parameterizeAndValidateSignature
    - validateFollowExpression
     */
    /*
     * Applies the chosen parameters to the function type, removing them from the function type's type parameters, and
     * then validates the resulting function type.
     *
     * So this is weird because we want to distinguish between the cases where the type involved here has explicitly
     * listed type parameters and where it doesn't.
     */
//    private fun parameterizeAndValidateType(unvalidatedType: UnvalidatedType, typeParameters: List<Type.ParameterType>, chosenTypes: List<Type?>, typeInfo: Validator.AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): Type? {
//        val type = validateType(unvalidatedType, typeInfo, typeParametersInScope + typeParameters.map(Type.ParameterType::parameter).associateBy(TypeParameter::name)) ?: return null
//
//        if (typeParameters.size != chosenTypes.size) {
//            error("Give me a better error message")
//        }
//        // TODO: Is this all that's needed to handle the case of null chosen types?
//        val parameterMap = typeParameters.zip(chosenTypes).filter { it.second != null }.toMap().mapValues { it.value!! }
//
//        return type.replacingParameters(parameterMap)
//    }

    private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("The expression $functionExpression is called like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        val providedChoices = expression.chosenParameters.map { validateType(it, typeParametersInScope) }

        val arguments = ArrayList<TypedExpression>()
        for (untypedArgument in expression.arguments) {
            val argument = validateExpression(untypedArgument, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        val inferredNullableTypeParameters = inferChosenTypeParameters(functionType, providedChoices, argumentTypes, functionType.toString(), expression.location) ?: return null
        val inferredTypeParameters = inferredNullableTypeParameters.filterNotNull()

        val argTypes = functionType.getArgTypes(inferredTypeParameters)
        val outputType = functionType.getOutputType(inferredTypeParameters)

        return TypedExpression.ExpressionFunctionCall(outputType, functionExpression, arguments, inferredTypeParameters)

//        TODO()

//
//        val chosenParameters = expression.chosenParameters.map { TODO() }
//
//        val arguments = ArrayList<TypedExpression>()
//        for (untypedArgument in expression.arguments) {
//            val argument = validateExpression(untypedArgument, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
//            arguments.add(argument)
//        }
//        val argumentTypes = arguments.map(TypedExpression::type)
//
//        if (argumentTypes != functionType.argTypes) {
//            errors.add(Issue("The bound function $functionExpression expects argument types ${functionType.argTypes}, but we give it types $argumentTypes", expression.location, IssueLevel.ERROR))
//            return null
//        }
//
//        return TypedExpression.ExpressionFunctionCall(functionType.outputType, functionExpression, arguments, chosenParameters)
    }

//    - Arguments to type inference: Given values for type parameters (should be validated as individual types),
//    validated types of arguments/bindings given to the function, the function type itself

    private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        System.out.println("Validating named function call $expression")
        val functionRef = expression.functionRef

        val functionInfo = typesInfo.getFunctionInfo(functionRef)
        if (functionInfo == null) {
            errors.add(Issue("Function $functionRef not found", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        val providedChoices = expression.chosenParameters.map { validateType(it, typeParametersInScope) }

        val arguments = ArrayList<TypedExpression>()
        for (untypedArgument in expression.arguments) {
            val argument = validateExpression(untypedArgument, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        /*
        There are a few things we're going to want to gather for each of these:

        - What is the type of the function? (Either via a signature from our typeInfo or an expression)
          - These should be Types, not UnvalidatedTypes, so we should know parameters and everything
        - What are the chosen values of the type parameters?
          - Type inference may apply here -- apply that early and in its own shared code path
            - Arguments to type inference: Given values for type parameters (should be validated as individual types),
              validated types of arguments/bindings given to the function, the function type itself

        After we get these things, then we can:
        - Check that the type parameters are valid given their type classes
        - Check that the arguments or bindings match their appropriate argument types
        - Determine and return the output type

         */

        val inferredNullableTypeParameters = inferChosenTypeParameters(functionInfo.type, providedChoices, argumentTypes, functionRef.toString(), expression.location) ?: return null
        val inferredTypeParameters = inferredNullableTypeParameters.filterNotNull()

        val argTypes = functionInfo.type.getArgTypes(inferredTypeParameters)
        val outputType = functionInfo.type.getOutputType(inferredTypeParameters)

        System.out.println("Function type: ${functionInfo.type}")
        System.out.println("Inferred type parameters: $inferredTypeParameters")
        System.out.println("outputType: $outputType")

        return TypedExpression.NamedFunctionCall(outputType, functionRef, functionInfo.resolvedRef, arguments, inferredTypeParameters)
//        TODO()
//
//        val signature = typeInfo.getFunctionInfo(functionResolvedRef.entityRef)?.signature
//        if (signature == null) {
//            errors.add(Issue("Entity $functionRef is not a function", expression.functionRefLocation, IssueLevel.ERROR))
//            return null
//        }
//        if (expression.arguments.size != signature.argumentTypes.size) {
//            errors.add(Issue("The function $functionRef expects ${signature.argumentTypes.size} arguments types (${signature.argumentTypes}), but ${expression.arguments.size} were given", expression.functionRefLocation, IssueLevel.ERROR))
//            return null
//        }
//
//        //Ground the signature
//
//        val fullTypeParameterCount = signature.typeParameters.size
//        val requiredTypeParameterCount = signature.getFunctionType().getRequiredTypeParameterCount()
//        val chosenParameters = if (expression.chosenParameters.size == fullTypeParameterCount) {
//            expression.chosenParameters.map { chosenParameter -> validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null }
//        } else if (expression.chosenParameters.size == requiredTypeParameterCount) {
//            val inferenceSourcesByArgument = signature.getFunctionType().getTypeParameterInferenceSources()
//            val explicitParametersIterator = expression.chosenParameters.iterator()
//            val types = inferenceSourcesByArgument.map { inferenceSources ->
//                if (inferenceSources.isEmpty()) {
//                    val chosenParameter = explicitParametersIterator.next()
//                    validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null
//                } else {
//                    // Since we know all our arguments are present, we can cheat a little and just take the first one
//                    inferenceSources.stream().map { it.findType(argumentTypes) }.filter { it != null }.findFirst().get()
//                }
//            }
//            if (explicitParametersIterator.hasNext()) {
//                error("Validator internal logic for type parameter inference was invalid")
//            }
//            types
//        } else {
//            // TODO: Update issue message to account for multiple possible lengths
//            errors.add(Issue("Expected ${signature.typeParameters.size} type parameters, but got ${expression.chosenParameters.size}", expression.functionRefLocation, IssueLevel.ERROR))
//            return null
//        }
//
//        if (signature.typeParameters.size != chosenParameters.size) {
//            error("Got the incorrect number of type parameters somehow: ${chosenParameters.size} instead of $fullTypeParameterCount or $requiredTypeParameterCount")
//        }
//        for ((typeParameter, chosenType) in signature.typeParameters.zip(chosenParameters)) {
//            validateTypeParameterChoice(typeParameter, chosenType, expression.location, typeInfo)
//        }
//
//
//        val functionType = applyChosenParametersToFunctionType(signature.getFunctionType(), chosenParameters, typeInfo, typeParametersInScope) ?: return null
//        if (argumentTypes != functionType.argTypes) {
//            errors.add(Issue("The function $functionRef expects argument types ${functionType.argTypes}, but is given arguments with types $argumentTypes", expression.location, IssueLevel.ERROR))
//        }
//
//        return TypedExpression.NamedFunctionCall(functionType.outputType, functionRef, functionResolvedRef.entityRef, arguments, chosenParameters)
    }

    private fun validateTypeParameterChoice(typeParameter: TypeParameter, chosenType: Type, location: Location?) {
        if (chosenType.isThreaded()) {
            errors.add(Issue("Threaded types cannot be used as parameters", location, IssueLevel.ERROR))
        }
        val typeClass = typeParameter.typeClass
        if (typeClass != null) {
            val unused: Any = when (typeClass) {
                TypeClass.Data -> {
                    if (!typesInfo.isDataType(chosenType)) {
                        errors.add(Issue("Type parameter ${typeParameter.name} requires a data type, but $chosenType is not a data type", location, IssueLevel.ERROR))
                    } else {}
                }
            }
        }
    }

    /*
     * Applies the chosen parameters to the function type, removing them from the function type's type parameters, and
     * then validates the resulting function type.
     */
//    private fun applyChosenParametersToFunctionType(functionType: UnvalidatedType.FunctionType, chosenParameters: List<Type?>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): Type.FunctionType? {
//        val parameterTypes = functionType.typeParameters.map(Type::ParameterType)
//        return parameterizeAndValidateType(functionType, parameterTypes, chosenParameters, typeInfo, typeParametersInScope) as Type.FunctionType?
//    }

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
            return TypedExpression.Literal(typeChain.last(), expression.literal)
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
//                val resolvedType = typesInfo.resolver.resolve(type.originalRef) ?: fail("Could not resolve type ${type.ref}")
//                val struct = typesInfo.getTypeInfo(resolvedType.entityRef) as? TypeInfo.Struct ?: fail("Trying to get a literal of a non-struct named type $resolvedType")
                val structInfo = typesInfo.getTypeInfo(type.originalRef) as? TypeInfo.Struct ?: fail("Trying to get a literal of a nonexistent or non-struct type ${type.originalRef}")

                if (structInfo.typeParameters.isNotEmpty()) {
                    fail("Can't have a literal of a type with type parameters: $type")
                }
                if (structInfo.memberTypes.size != 1) {
                    fail("Can't have a literal of a struct type with more than one member")
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

    private fun validateListLiteralExpression(expression: Expression.ListLiteral, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val chosenParameter = validateType(expression.chosenParameter, typeParametersInScope) ?: return null
        if (chosenParameter.isThreaded()) {
            errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
        }

        val listType = Type.List(chosenParameter)

        val contents = expression.contents.map { item ->
            validateExpression(item, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
        }
        for (item in contents) {
            if (item.type != chosenParameter) {
                error("Put an expression $item of type ${item.type} in a list literal of type ${listType}")
            }
        }

        return TypedExpression.ListLiteral(listType, contents, chosenParameter)
    }

    private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val condition = validateExpression(expression.condition, variableTypes, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        if (condition.type != Type.BOOLEAN) {
            fail("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
        }

        // Threaded variables can be consumed in each of the two blocks, but is considered used if used in either
        val thenConsumedThreadedVars = HashSet(consumedThreadedVars)
        val elseConsumedThreadedVars = HashSet(consumedThreadedVars)
        val thenBlock = validateBlock(expression.thenBlock, variableTypes, typeParametersInScope, thenConsumedThreadedVars, containingFunctionId) ?: return null
        val elseBlock = validateBlock(expression.elseBlock, variableTypes, typeParametersInScope, elseConsumedThreadedVars, containingFunctionId) ?: return null
        // Don't add variable names that were defined in the blocks themselves
        consumedThreadedVars.addAll(thenConsumedThreadedVars.intersect(variableTypes.keys))
        consumedThreadedVars.addAll(elseConsumedThreadedVars.intersect(variableTypes.keys))

        val type = try {
            typeUnion(thenBlock.type, elseBlock.type)
        } catch (e: RuntimeException) {
            errors.add(Issue("Cannot reconcile types of 'then' block (${thenBlock.type}) and 'else' block (${elseBlock.type})", expression.location, IssueLevel.ERROR))
            return null
        }

        return TypedExpression.IfThen(type, condition, thenBlock, elseBlock)
    }

    private fun typeUnion(type1: Type, type2: Type): Type {
        // TODO: Handle actual type unions, inheritance as these things get added
        // TODO: Then move this stuff to the API level
        if (type1 == type2) {
            return type1
        }
        fail("Types $type1 and $type2 cannot be unioned")
    }

    private fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        if (consumedThreadedVars.contains(expression.name)) {
            errors.add(Issue("Variable ${expression.name} is threaded and cannot be used more than once", expression.location, IssueLevel.ERROR))
            return null
        }
        val type = variableTypes[expression.name]
        if (type != null) {
            if (type.isThreaded()) {
                consumedThreadedVars.add(expression.name)
            }
            return TypedExpression.Variable(type, expression.name)
        } else {
            errors.add(Issue("Unknown variable ${expression.name}", expression.location, IssueLevel.ERROR))
            return null
        }
    }

    private fun validateStructs(structs: List<UnvalidatedStruct>): Map<EntityId, Struct> {
        val validatedStructs = HashMap<EntityId, Struct>()
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
            validateBlock(uncheckedRequires, memberTypes, struct.typeParameters.associateBy(TypeParameter::name), HashSet(), fakeContainingFunctionId) ?: return null
        } else {
            null
        }
        if (requires != null && requires.type != Type.BOOLEAN) {
            val message = "Struct ${struct.id} has a requires block with inferred type ${requires.type}, but the type should be Boolean"
            val location = struct.requires!!.location
            errors.add(Issue(message, location, IssueLevel.ERROR))
        }

        val anyMemberTypesAreThreaded = memberTypes.values.any(Type::isThreaded)
        if (struct.markedAsThreaded && !anyMemberTypesAreThreaded) {
            errors.add(Issue("Struct ${struct.id} is marked as threaded but has no members with threaded types", struct.idLocation, IssueLevel.ERROR))
        }
        if (!struct.markedAsThreaded && anyMemberTypesAreThreaded) {
            errors.add(Issue("Struct ${struct.id} is not marked as threaded but has members with threaded types", struct.idLocation, IssueLevel.ERROR))
        }

        return Struct(struct.id, struct.markedAsThreaded, moduleId, struct.typeParameters, members, requires, struct.annotations)
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
        val validatedInterfaces = HashMap<EntityId, Interface>()
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
        val validatedUnions = HashMap<EntityId, Union>()
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
            if (type != null && type.isThreaded()) {
                error("Threaded types are currently not allowed in unions; this case needs to be considered further")
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
