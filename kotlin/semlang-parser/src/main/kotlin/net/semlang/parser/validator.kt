package net.semlang.parser

import net.semlang.api.*
import net.semlang.api.Function
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

data class GroundedTypeSignature(val id: EntityId, val argumentTypes: List<Type>, val outputType: Type)

/*
 * Warning: Doesn't validate that composed literals satisfy their requires blocks, which requires running semlang code to
 *   check (albeit code that can always be run in a vacuum)
 */
fun validateModule(context: RawContext, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): ValidationResult {
    val validator = Validator(moduleId, nativeModuleVersion, upstreamModules)
    return validator.validate(context)
}

fun validate(parsingResult: ParsingResult, moduleId: ModuleId, nativeModuleVersion: String): ValidationResult {
    return when (parsingResult) {
        is ParsingResult.Success -> {
            validateModule(parsingResult.context, moduleId, nativeModuleVersion, listOf())
        }
        is ParsingResult.Failure -> {
            ValidationResult.Failure(parsingResult.errors, listOf())
        }
    }
}

fun parseAndValidateFile(file: File, moduleId: ModuleId, nativeModuleVersion: String): ValidationResult {
    val parsingResult = parseFile(file)
    return validate(parsingResult, moduleId, nativeModuleVersion)
}

fun parseAndValidateString(text: String, documentUri: String, moduleId: ModuleId, nativeModuleVersion: String): ValidationResult {
    val parsingResult = parseString(text, documentUri)
    return validate(parsingResult, moduleId, nativeModuleVersion)
}

fun parseAndValidateModuleDirectory(directory: File, nativeModuleVersion: String): ValidationResult {
    val configFile = File(directory, "module.conf")
    val parsedConfig = parseConfigFile(configFile)
    return when (parsedConfig) {
        is ModuleInfoParsingResult.Failure -> {
            val error = Issue("Couldn't parse module.conf: ${parsedConfig.error.message}", null, IssueLevel.ERROR)
            ValidationResult.Failure(listOf(error), listOf())
        }
        is ModuleInfoParsingResult.Success -> {
            val semFiles = directory.listFiles { dir, name -> name.endsWith(".sem") }
            val parsingResults = semFiles.map { parseFile(it) }
            val combinedParsingResult = combineParsingResults(parsingResults)

            // TODO: Dependencies should figure in here at some point...
            validate(combinedParsingResult, parsedConfig.info.id, nativeModuleVersion)
        }
    }
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
            error("Encountered errors in validation: $errors")
        }
    }

}

private class Validator(val moduleId: ModuleId, val nativeModuleVersion: String, val upstreamModules: List<ValidatedModule>) {
    val warnings = ArrayList<Issue>()
    val errors = ArrayList<Issue>()

    fun validate(context: RawContext): ValidationResult {
        val typeInfo = collectTypeInfo(context, moduleId, nativeModuleVersion, upstreamModules)

        val ownFunctions = validateFunctions(context.functions, typeInfo)
        val ownStructs = validateStructs(context.structs, typeInfo)
        val ownInterfaces = validateInterfaces(context.interfaces, typeInfo)

        if (errors.isEmpty()) {
            val createdModule = ValidatedModule.create(moduleId, nativeModuleVersion, ownFunctions, ownStructs, ownInterfaces, upstreamModules)
            return ValidationResult.Success(createdModule, warnings)
        } else {
            return ValidationResult.Failure(errors, warnings)
        }
    }

    private fun collectTypeInfo(context: RawContext, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): AllTypeInfo {
        /*
         * Some notes about how we deal with error cases here:
         * - Name collisions involving other modules (which are assumed valid at this point) aren't a problem at this
         *   level. Instead, there will be an error found at the location of the reference, when the reference is
         *   ambiguous.
         * - Name collisions within the module may occur either for both types and functions, or for functions only.
         * - Collisions should be noted in both locations. References to these types/functions (when they resolve to the
         *   current module) will then be their own errors.
         */
        val resolver = EntityResolver.create(moduleId,
                nativeModuleVersion,
                context.functions.map(Function::id),
                context.structs.map(UnvalidatedStruct::id),
                context.interfaces.map(UnvalidatedInterface::id),
                upstreamModules)

        val localTypes = HashMap<EntityId, TypeInfo>()
        val duplicateLocalTypeIds = HashSet<EntityId>()

        val localFunctions = HashMap<EntityId, FunctionInfo>()
        val duplicateLocalFunctionIds = HashSet<EntityId>()

        // For sanity-checking the results of the spaghetti logic below
        val seenTypeIds = HashSet<EntityId>()
        val seenFunctionIds = HashSet<EntityId>()

        val addDuplicateIdError = fun(id: EntityId, idLocation: Location?) { errors.add(Issue("Duplicate ID ${id}", idLocation, IssueLevel.ERROR)) }

        context.structs.forEach { struct ->
            val id = struct.id
            seenTypeIds.add(id)
            seenFunctionIds.add(id)

            var isDuplicate = false
            // Check for duplicate declarations
            if (duplicateLocalTypeIds.contains(id)) {
                isDuplicate = true
            } else if (localTypes.containsKey(id)) {
                isDuplicate = true
                duplicateLocalTypeIds.add(id)
                addDuplicateIdError(id, localTypes[id]?.idLocation)
                localTypes.remove(id)
            }
            if (duplicateLocalFunctionIds.contains(id)) {
                isDuplicate = true
            } else if (localFunctions.containsKey(id)) {
                isDuplicate = true
                duplicateLocalFunctionIds.add(id)
                addDuplicateIdError(id, localFunctions[id]?.idLocation)
                localFunctions.remove(id)
            }
            if (isDuplicate) {
                addDuplicateIdError(id, struct.idLocation)
            }

            if (!duplicateLocalTypeIds.contains(id)) {
                // Collect the type info
                localTypes.put(id, getTypeInfo(struct))
            }
            if (!duplicateLocalFunctionIds.contains(id)) {
                // Collect the constructor function info
                localFunctions.put(id, FunctionInfo(struct.getConstructorSignature(), struct.idLocation))
            }
        }

        context.interfaces.forEach { interfac ->
            seenTypeIds.add(interfac.id)
            seenFunctionIds.add(interfac.id)
            seenTypeIds.add(interfac.adapterId)
            seenFunctionIds.add(interfac.adapterId)

            // Check for duplicate declarations
            for (id in listOf(interfac.id, interfac.adapterId)) {
                var isDuplicate = false
                if (duplicateLocalTypeIds.contains(id)) {
                    isDuplicate = true
                } else if (localTypes.containsKey(id)) {
                    isDuplicate = true
                    duplicateLocalTypeIds.add(id)
                    addDuplicateIdError(id, localTypes[id]?.idLocation)
                    localTypes.remove(id)
                }
                if (duplicateLocalFunctionIds.contains(id)) {
                    isDuplicate = true
                } else if (localFunctions.containsKey(id)) {
                    isDuplicate = true
                    duplicateLocalFunctionIds.add(id)
                    addDuplicateIdError(id, localFunctions[id]?.idLocation)
                    localFunctions.remove(id)
                }
                if (isDuplicate) {
                    addDuplicateIdError(id, interfac.idLocation)
                }
            }

            if (!duplicateLocalTypeIds.contains(interfac.id)) {
                localTypes.put(interfac.id, getTypeInfo(interfac))
            }
            if (!duplicateLocalFunctionIds.contains(interfac.id)) {
                localFunctions.put(interfac.id, FunctionInfo(interfac.getInstanceConstructorSignature(), interfac.idLocation))
            }
            if (!duplicateLocalTypeIds.contains(interfac.adapterId)) {
                localTypes.put(interfac.adapterId, getTypeInfo(interfac.adapterStruct))
            }
            if (!duplicateLocalFunctionIds.contains(interfac.adapterId)) {
                localFunctions.put(interfac.adapterId, FunctionInfo(interfac.getAdapterConstructorSignature(), interfac.idLocation))
            }
        }

        context.functions.forEach { function ->
            val id = function.id
            seenFunctionIds.add(id)

            if (duplicateLocalFunctionIds.contains(id)) {
                addDuplicateIdError(id, function.idLocation)
            } else if (localFunctions.containsKey(id)) {
                addDuplicateIdError(id, function.idLocation)
                duplicateLocalFunctionIds.add(id)
                addDuplicateIdError(id, localFunctions[id]?.idLocation)
                localFunctions.remove(id)
            }

            if (!duplicateLocalFunctionIds.contains(id)) {
                localFunctions.put(id, FunctionInfo(function.getTypeSignature(), function.idLocation))
            }
        }

        // TODO: Check a few invariants here...
        // Hopefully we only register one error per duplicate, but that can be dealt with later
        for (id in duplicateLocalTypeIds) {
            if (localTypes.containsKey(id)) {
                error("localTypes has key $id that is also listed in duplicates")
            }
        }
        for (id in seenTypeIds) {
            if (!localTypes.containsKey(id) && !duplicateLocalTypeIds.contains(id)) {
                error("We saw a type ID $id that did not end up in the local types or duplicates")
            }
        }
        for (id in duplicateLocalFunctionIds) {
            if (localFunctions.containsKey(id)) {
                error("localTypes has key $id that is also listed in duplicates")
            }
        }
        for (id in seenFunctionIds) {
            if (!localFunctions.containsKey(id) && !duplicateLocalFunctionIds.contains(id)) {
                error("We saw a function ID $id that did not end up in the local functions or duplicates")
            }
        }
        if ((duplicateLocalTypeIds.isNotEmpty() || duplicateLocalFunctionIds.isNotEmpty()) && errors.isEmpty()) {
            error("Should have at least one error when there are duplicate IDs")
        }

        val upstreamTypes = getUpstreamTypes(nativeModuleVersion, upstreamModules)
        val upstreamFunctions = getUpstreamFunctions(nativeModuleVersion, upstreamModules)

        return AllTypeInfo(resolver, localTypes, duplicateLocalTypeIds, localFunctions, duplicateLocalFunctionIds, upstreamTypes, upstreamFunctions)
    }

    private fun getUpstreamTypes(nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, TypeInfo> {
        val upstreamTypes = HashMap<ResolvedEntityRef, TypeInfo>()

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        getNativeStructs().values.forEach { struct ->
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamTypes.put(ref, getTypeInfo(struct, null))
        }
        getNativeInterfaces().values.forEach { interfac ->
            val ref = ResolvedEntityRef(nativeModuleId, interfac.id)
            upstreamTypes.put(ref, getTypeInfo(interfac, null))
            val adapterRef = ResolvedEntityRef(nativeModuleId, interfac.adapterId)
            upstreamTypes.put(adapterRef, getTypeInfo(interfac.adapterStruct, null))
        }

        upstreamModules.forEach { module ->
            module.getAllExportedStructs().values.forEach { struct ->
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamTypes.put(ref, getTypeInfo(struct, null))
            }
            module.getAllExportedInterfaces().values.forEach { interfac ->
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamTypes.put(ref, getTypeInfo(interfac, null))
                val adapterRef = ResolvedEntityRef(module.id, interfac.adapterId)
                upstreamTypes.put(adapterRef, getTypeInfo(interfac.adapterStruct, null))
            }
        }
        return upstreamTypes
    }

    private fun getUpstreamFunctions(nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, FunctionInfo> {
        val upstreamFunctions = HashMap<ResolvedEntityRef, FunctionInfo>()

        // (We don't need the idPosition for functions in upstream modules)
        val functionInfo = fun(signature: TypeSignature): FunctionInfo { return FunctionInfo(signature, null) }

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        getNativeStructs().values.forEach { struct ->
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamFunctions.put(ref, functionInfo(struct.getConstructorSignature()))
        }
        getNativeInterfaces().values.forEach { interfac ->
            val ref = ResolvedEntityRef(nativeModuleId, interfac.id)
            upstreamFunctions.put(ref, functionInfo(interfac.getInstanceConstructorSignature()))
            val adapterRef = ResolvedEntityRef(nativeModuleId, interfac.adapterId)
            upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterConstructorSignature()))
        }
        getNativeFunctionOnlyDefinitions().values.forEach { function ->
            val ref = ResolvedEntityRef(nativeModuleId, function.id)
            upstreamFunctions.put(ref, functionInfo(function))
        }

        upstreamModules.forEach { module ->
            module.getAllExportedStructs().values.forEach { struct ->
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamFunctions.put(ref, functionInfo(struct.getConstructorSignature()))
            }
            module.getAllExportedInterfaces().values.forEach { interfac ->
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamFunctions.put(ref, functionInfo(interfac.getInstanceConstructorSignature()))
                val adapterRef = ResolvedEntityRef(module.id, interfac.adapterId)
                upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterConstructorSignature()))
            }
            module.getAllExportedFunctions().values.forEach { function ->
                val ref = ResolvedEntityRef(module.id, function.id)
                upstreamFunctions.put(ref, functionInfo(function.getTypeSignature()))
            }
        }
        return upstreamFunctions
    }

    private fun validateFunctions(functions: List<Function>, typeInfo: AllTypeInfo): Map<EntityId, ValidatedFunction> {
        val validatedFunctions = HashMap<EntityId, ValidatedFunction>()
        functions.forEach { function ->
            val validatedFunction = validateFunction(function, typeInfo)
            if (validatedFunction != null && !typeInfo.duplicateLocalFunctionIds.contains(validatedFunction.id)) {
                validatedFunctions.put(function.id, validatedFunction)
            } else if (errors.isEmpty()) {
                fail("Something bad happened")
            }
        }
        return validatedFunctions
    }

    private fun validateFunction(function: Function, typeInfo: AllTypeInfo): ValidatedFunction? {
        //TODO: Validate that no two arguments have the same name

        val arguments = validateArguments(function.arguments, typeInfo, function.typeParameters.toSet())


        //TODO: Validate that type parameters don't share a name with something important
        val variableTypes = getArgumentVariableTypes(arguments)
        val block = validateBlock(function.block, variableTypes, typeInfo, function.typeParameters.toSet(), function.id) ?: return null
        if (function.returnType != block.type) {
            errors.add(Issue("Stated return type ${function.returnType} does not match the block's actual return type ${block.type}", function.returnTypeLocation, IssueLevel.ERROR))
        }

        return ValidatedFunction(function.id, function.typeParameters, arguments, function.returnType, block, function.annotations)
    }

    private fun validateArguments(arguments: List<UnvalidatedArgument>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): List<Argument> {
        val validatedArguments = ArrayList<Argument>()
        arguments.forEach { argument ->
            val type = argument.type
            if (typeNotRecognized(type, typeInfo, typeParametersInScope)) {
                errors.add(Issue("Unrecognized type $type", argument.location, IssueLevel.ERROR))
            }
            validatedArguments.add(Argument(argument.name, argument.type))
        }
        return validatedArguments
    }

    private fun typeNotRecognized(type: Type, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): Boolean {
        return !typeRecognized(type, typeInfo, typeParametersInScope)
    }

    private fun typeRecognized(type: Type, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): Boolean {
        return when (type) {
            Type.INTEGER -> true
            Type.NATURAL -> true
            Type.BOOLEAN -> true
            is Type.List -> typeRecognized(type.parameter, typeInfo, typeParametersInScope)
            is Type.Try -> typeRecognized(type.parameter, typeInfo, typeParametersInScope)
            is Type.FunctionType -> {
                typeRecognized(type.outputType, typeInfo, typeParametersInScope) && type.argTypes.all { typeRecognized(it, typeInfo, typeParametersInScope) }
            }
            is Type.NamedType -> {
                val typeNameRecognized = typeNameRecognized(type.ref, typeInfo, typeParametersInScope)
                val resolution = typeInfo.resolver.resolve(type.ref)
                if (!typeNameRecognized) {
                    false
                } else {
                    type.parameters.all { typeRecognized(it, typeInfo, typeParametersInScope) }
                }
            }
        }
    }

    private fun typeNameRecognized(ref: EntityRef, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): Boolean {
        val resolution = typeInfo.resolver.resolve(ref)
        if (resolution != null) {
            return true
        }
        // Check the type parameters
        if (ref.moduleRef == null) {
            val namespacedName = ref.id.namespacedName
            val onlyOne = namespacedName.singleOrNull()
            if (onlyOne != null) {
                return typeParametersInScope.contains(onlyOne)
            }
        }
        return false
    }

    private fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
        return arguments.associate { argument -> Pair(argument.name, argument.type) }
    }


    private sealed class TypeInfo {
        abstract val idLocation: Location?
        data class Struct(val typeParameters: List<String>, val members: Map<String, Member>, val usesRequires: Boolean, override val idLocation: Location?): TypeInfo()
        data class Interface(val typeParameters: List<String>, val methodTypes: Map<String, Type.FunctionType>, override val idLocation: Location?): TypeInfo()
    }
    private data class FunctionInfo(val signature: TypeSignature, val idLocation: Location?)

    private fun getTypeInfo(struct: UnvalidatedStruct): TypeInfo.Struct {
        return TypeInfo.Struct(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null, struct.idLocation)
    }
    // TODO: Null position
    private fun getTypeInfo(struct: Struct, idLocation: Location?): TypeInfo.Struct {
        return TypeInfo.Struct(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null, idLocation)
    }

    private fun getTypeInfo(interfac: UnvalidatedInterface): TypeInfo.Interface {
        return TypeInfo.Interface(interfac.typeParameters, getTypeMap(interfac.methods), interfac.idLocation)
    }
    // TODO: Null position
    private fun getTypeInfo(interfac: Interface, idLocation: Location?): TypeInfo.Interface {
        return TypeInfo.Interface(interfac.typeParameters, getTypeMapPostValidation(interfac.methods), idLocation)
    }

    private fun getTypeMap(methods: List<UnvalidatedMethod>): Map<String, Type.FunctionType> {
        val typeMap = HashMap<String, Type.FunctionType>()
        for (method in methods) {
            if (typeMap.containsKey(method.name)) {
                error("Duplicate method name ${method.name}")
            } else {
                typeMap.put(method.name, method.functionType)
            }
        }
        return typeMap
    }
    private fun getTypeMapPostValidation(methods: List<Method>): Map<String, Type.FunctionType> {
        val typeMap = HashMap<String, Type.FunctionType>()
        for (method in methods) {
            if (typeMap.containsKey(method.name)) {
                error("Duplicate method name ${method.name}")
            } else {
                typeMap.put(method.name, method.functionType)
            }
        }
        return typeMap
    }

    private inner class AllTypeInfo(val resolver: EntityResolver,
                                    val localTypes: Map<EntityId, TypeInfo>,
                                    val duplicateLocalTypeIds: Set<EntityId>,
                                    val localFunctions: Map<EntityId, FunctionInfo>,
                                    val duplicateLocalFunctionIds: Set<EntityId>,
                                    val upstreamTypes: Map<ResolvedEntityRef, TypeInfo>,
                                    val upstreamFunctions: Map<ResolvedEntityRef, FunctionInfo>) {
        fun getTypeInfo(resolvedRef: ResolvedEntityRef): TypeInfo? {
            return if (resolvedRef.module == moduleId) {
                if (duplicateLocalTypeIds.contains(resolvedRef.id)) {
                    fail("There are multiple declarations of the type name ${resolvedRef.id}")
                }
                localTypes[resolvedRef.id]
            } else {
                upstreamTypes[resolvedRef]
            }
        }
        fun getFunctionInfo(resolvedRef: ResolvedEntityRef): FunctionInfo? {
            return if (resolvedRef.module == moduleId) {
                if (duplicateLocalFunctionIds.contains(resolvedRef.id)) {
                    fail("There are multiple declarations of the function name ${resolvedRef.id}")
                }
                localFunctions[resolvedRef.id]
            } else {
                upstreamFunctions[resolvedRef]
            }
        }
    }

    private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedBlock? {
        val variableTypes = HashMap(externalVariableTypes)
        val validatedAssignments = ArrayList<ValidatedAssignment>()
        block.assignments.forEach { assignment ->
            if (variableTypes.containsKey(assignment.name)) {
                errors.add(Issue("The already-assigned variable ${assignment.name} cannot be reassigned", assignment.nameLocation, IssueLevel.ERROR))
            }
            if (isInvalidVariableName(assignment.name, typeInfo)) {
                errors.add(Issue("Invalid variable name ${assignment.name}", assignment.nameLocation, IssueLevel.ERROR))
            }

            val validatedExpression = validateExpression(assignment.expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null
            if (assignment.type != null && (validatedExpression.type != assignment.type)) {
                fail("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type ${assignment.type}, " +
                        "but the expression given has actual type ${validatedExpression.type}")
            }

            validatedAssignments.add(ValidatedAssignment(assignment.name, validatedExpression.type, validatedExpression))
            variableTypes.put(assignment.name, validatedExpression.type)
        }
        val returnedExpression = validateExpression(block.returnedExpression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null
        return TypedBlock(returnedExpression.type, validatedAssignments, returnedExpression)
    }

    //TODO: Construct this more sensibly from more centralized lists
    private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Natural", "Boolean", "function", "let", "return", "if", "else", "struct", "requires")

    private fun isInvalidVariableName(name: String, typeInfo: AllTypeInfo): Boolean {
        val nameAsEntityRef = EntityId.of(name).asRef()
        return typeInfo.resolver.resolve(nameAsEntityRef) != null
                || INVALID_VARIABLE_NAMES.contains(name)
    }

    // TODO: Remove containingFunctionId argument when no longer needed
    private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        return when (expression) {
            is Expression.Variable -> validateVariableExpression(expression, variableTypes, containingFunctionId)
            is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            is Expression.Follow -> validateFollowExpression(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            is Expression.NamedFunctionCall -> validateNamedFunctionCallExpression(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            is Expression.ExpressionFunctionCall -> validateExpressionFunctionCallExpression(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            is Expression.Literal -> validateLiteralExpression(expression, typeInfo)
            is Expression.ListLiteral -> validateListLiteralExpression(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            is Expression.NamedFunctionBinding -> validateNamedFunctionBinding(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            is Expression.ExpressionFunctionBinding -> validateExpressionFunctionBinding(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            is Expression.InlineFunction -> validateInlineFunction(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
        }
    }

    private fun validateInlineFunction(expression: Expression.InlineFunction, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        for (arg in expression.arguments) {
            if (variableTypes.containsKey(arg.name)) {
                errors.add(Issue("Argument name ${arg.name} shadows an existing variable name", arg.location, IssueLevel.ERROR))
            }
        }
        val validatedArguments = validateArguments(expression.arguments, typeInfo, typeParametersInScope)

        val incomingVariableTypes: Map<String, Type> = variableTypes + validatedArguments.asVariableTypesMap()
        val validatedBlock = validateBlock(expression.block, incomingVariableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null

        // Note: This is the source of the canonical in-memory ordering
        val varsToBind = ArrayList<String>(variableTypes.keys)
        varsToBind.retainAll(getVarsReferencedIn(validatedBlock))
        val varsToBindWithTypes = varsToBind.map { name -> Argument(name, variableTypes[name]!!)}

        val functionType = Type.FunctionType(validatedArguments.map(Argument::type), validatedBlock.type)

        return TypedExpression.InlineFunction(functionType, validatedArguments, varsToBindWithTypes, validatedBlock)
    }

    private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("Attempting to bind $functionExpression like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        val preBindingArgumentTypes = functionType.argTypes

        if (preBindingArgumentTypes.size != expression.bindings.size) {
            fail("In function $containingFunctionId, tried to bind $functionExpression with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments")
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            }
        }

        val postBindingArgumentTypes = ArrayList<Type>()
        for (entry in preBindingArgumentTypes.zip(bindings)) {
            val type = entry.first
            val binding = entry.second
            if (binding == null) {
                postBindingArgumentTypes.add(type)
            } else {
                if (binding.type != type) {
                    fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
                }
            }
        }
        val postBindingType = Type.FunctionType(
                postBindingArgumentTypes,
                functionType.outputType)

        return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings, expression.chosenParameters)
    }

    private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef

        val resolvedRef = typeInfo.resolver.resolve(functionRef) ?: fail("In function $containingFunctionId, could not find a declaration of a function with ID $functionRef")
        val functionInfo = typeInfo.getFunctionInfo(resolvedRef.entityRef)
        if (functionInfo == null) {
            fail("In function $containingFunctionId, resolved a function with ID $functionRef but could not find the signature")
        }
        val signature = functionInfo.signature
        val chosenParameters = expression.chosenParameters
        if (chosenParameters.size != signature.typeParameters.size) {
            fail("In function $containingFunctionId, referenced a function $functionRef with type parameters ${signature.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
        }
        val typeParameters = signature.typeParameters

        val parameterMap = makeParameterMap(typeParameters, chosenParameters, resolvedRef.entityRef.id)
        val preBindingArgumentTypes = signature.argumentTypes.map { type -> type.replacingParameters(parameterMap) }

        if (preBindingArgumentTypes.size != expression.bindings.size) {
            errors.add(Issue("Tried to bind function $functionRef with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments", expression.location, IssueLevel.ERROR))
            return null
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
            }
        }

        val postBindingArgumentTypes = ArrayList<Type>()
        for (entry in preBindingArgumentTypes.zip(bindings)) {
            val type = entry.first
            val binding = entry.second
            if (binding == null) {
                postBindingArgumentTypes.add(type)
            } else {
                if (binding.type != type) {
                    fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
                }
            }
        }
        val postBindingType = Type.FunctionType(
                postBindingArgumentTypes,
                signature.outputType.replacingParameters(parameterMap))

        return TypedExpression.NamedFunctionBinding(postBindingType, functionRef, bindings, chosenParameters)
    }

    private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        val innerExpression = validateExpression(expression.expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null

        val parentNamedType = innerExpression.type as? Type.NamedType
        if (parentNamedType == null) {
            errors.add(Issue("Cannot dereference an expression $innerExpression of non-struct, non-interface type ${innerExpression.type}", expression.location, IssueLevel.ERROR))
            return null
        }

        val resolvedParentType = typeInfo.resolver.resolve(parentNamedType.ref) ?: error("In function $containingFunctionId, we try to dereference an expression $innerExpression of unrecognized type ${innerExpression.type}")
        val parentTypeInfo = typeInfo.getTypeInfo(resolvedParentType.entityRef) ?: error("No type info for ${resolvedParentType.entityRef}")

        return when (parentTypeInfo) {
            is TypeInfo.Struct -> {
                val member = parentTypeInfo.members[expression.name]
                if (member == null) {
                    errors.add(Issue("Struct type $parentNamedType does not have a member named '${expression.name}'", expression.location, IssueLevel.ERROR))
                    return null
                }

                // Type parameters come from the struct definition itself
                // Chosen types come from the struct type known for the variable
                val typeParameters = parentTypeInfo.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
                val chosenTypes = parentNamedType.getParameterizedTypes()
                val type = parameterizeType(member.type, typeParameters, chosenTypes, resolvedParentType.entityRef.id)
                //TODO: Ground this if needed

                return TypedExpression.Follow(type, innerExpression, expression.name)

            }
            is TypeInfo.Interface -> {
                val interfac = parentTypeInfo
                val interfaceType = parentNamedType
                val methodType = interfac.methodTypes[expression.name]
                if (methodType == null) {
                    errors.add(Issue("Interface type $parentNamedType does not have a method named '${expression.name}'", expression.location, IssueLevel.ERROR))
                    return null
                }

                val typeParameters = interfac.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
                val chosenTypes = interfaceType.getParameterizedTypes()
                val type = parameterizeType(methodType, typeParameters, chosenTypes, resolvedParentType.entityRef.id)

                return TypedExpression.Follow(type, innerExpression, expression.name)
            }
        }
    }

    private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("The expression $functionExpression is called like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        val arguments = ArrayList<TypedExpression>()
        expression.arguments.forEach { untypedArgument ->
            val argument = validateExpression(untypedArgument, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        if (argumentTypes != functionType.argTypes) {
            fail("The function $containingFunctionId tries to call the result of $functionExpression with argument types $argumentTypes, but the function expects argument types ${functionType.argTypes}")
        }

        return TypedExpression.ExpressionFunctionCall(functionType.outputType, functionExpression, arguments, expression.chosenParameters)
    }

    private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef
        val functionResolvedRef = typeInfo.resolver.resolve(functionRef)
        if (functionResolvedRef == null) {
            errors.add(Issue("Function $functionRef not found", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        val arguments = ArrayList<TypedExpression>()
        expression.arguments.forEach { untypedArgument ->
            val argument = validateExpression(untypedArgument, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        val signature = typeInfo.getFunctionInfo(functionResolvedRef.entityRef)?.signature
        if (signature == null) {
            fail("The function $containingFunctionId references a function $functionRef that was not found")
        }
        //TODO: Maybe compare argument size before grounding?

        //Ground the signature
        val groundSignature = ground(signature, expression.chosenParameters, functionRef.id)
        if (argumentTypes != groundSignature.argumentTypes) {
            errors.add(Issue("The function $functionRef expects argument types ${groundSignature.argumentTypes}, but is given arguments with types $argumentTypes", expression.location, IssueLevel.ERROR))
        }

        return TypedExpression.NamedFunctionCall(groundSignature.outputType, functionRef, arguments, expression.chosenParameters)
    }

    private fun ground(signature: TypeSignature, chosenTypes: List<Type>, functionId: EntityId): GroundedTypeSignature {
        val groundedArgumentTypes = signature.argumentTypes.map { t -> parameterizeType(t, signature.typeParameters, chosenTypes, functionId) }
        val groundedOutputType = parameterizeType(signature.outputType, signature.typeParameters, chosenTypes, functionId)
        return GroundedTypeSignature(signature.id, groundedArgumentTypes, groundedOutputType)
    }

    // TODO: We're disagreeing in multiple places on List<Type> vs. List<String>, should fix that at some point
    private fun makeParameterMap(parameters: List<Type>, chosenTypes: List<Type>, functionId: EntityId): Map<Type, Type> {
        if (parameters.size != chosenTypes.size) {
            fail("Disagreement in type parameter list lengths for function $functionId; ${parameters.size} required, but ${chosenTypes.size} provided")
        }
        val map: MutableMap<Type, Type> = HashMap()

        parameters.zip(chosenTypes).forEach { pair ->
            val (parameter, chosenType) = pair
            val existingValue = map.get(parameter)
            if (existingValue != null) {
                // I think this is correct behavior...
                fail("Not anticipating seeing the same parameter type twice")
            }
            map.put(parameter, chosenType)
        }

        return map
    }

    private fun parameterizeType(typeWithWrongParameters: Type, typeParameters: List<Type>, chosenTypes: List<Type>, functionId: EntityId): Type {
        val parameterMap = makeParameterMap(typeParameters, chosenTypes, functionId)
        return typeWithWrongParameters.replacingParameters(parameterMap)
    }

    private fun validateLiteralExpression(expression: Expression.Literal, typeInfo: AllTypeInfo): TypedExpression {
        val typeChain = getLiteralTypeChain(expression.type, expression.location, typeInfo)

        if (typeChain != null) {
            val nativeLiteralType = typeChain[0]

            val validator = getTypeValidatorFor(nativeLiteralType) ?: error("No literal validator for type $nativeLiteralType")
            val isValid = validator.validate(expression.literal)
            if (!isValid) {
                errors.add(Issue("Invalid literal value '${expression.literal}' for type '${expression.type}'", expression.location, IssueLevel.ERROR))
            }
        } else if (errors.isEmpty()) {
            fail("Something went wrong")
        }
        // TODO: Someday we need to check for invalid literal values at validation time
        return TypedExpression.Literal(expression.type, expression.literal)
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
    private fun getLiteralTypeChain(initialType: Type, literalLocation: Location?, typeInfo: AllTypeInfo): List<Type>? {
        var type = initialType
        val list = ArrayList<Type>()
        list.add(type)
        while (getTypeValidatorFor(type) == null) {
            if (type is Type.NamedType) {
                val resolvedType = typeInfo.resolver.resolve(type.ref) ?: fail("Could not resolve type ${type.ref}")
                val struct = typeInfo.getTypeInfo(resolvedType.entityRef) as? TypeInfo.Struct ?: fail("Trying to get a literal of a non-struct named type $resolvedType")

                if (struct.typeParameters.isNotEmpty()) {
                    fail("Can't have a literal of a type with type parameters: $type")
                }
                if (struct.members.size != 1) {
                    fail("Can't have a literal of a struct type with more than one member")
                }
                val memberType = struct.members.values.single().type
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

    private fun validateListLiteralExpression(expression: Expression.ListLiteral, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        val listType = Type.List(expression.chosenParameter)

        val contents = expression.contents.map { item ->
            validateExpression(item, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null
        }
        contents.forEach { item ->
            if (item.type != expression.chosenParameter) {
                error("Put an expression $item of type ${item.type} in a list literal of type ${listType}")
            }
        }

        return TypedExpression.ListLiteral(listType, contents, expression.chosenParameter)
    }

    private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        val condition = validateExpression(expression.condition, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null

        if (condition.type != Type.BOOLEAN) {
            fail("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
        }

        val elseBlock = validateBlock(expression.elseBlock, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null
        val thenBlock = validateBlock(expression.thenBlock, variableTypes, typeInfo, typeParametersInScope, containingFunctionId) ?: return null

        val type = try {
            typeUnion(thenBlock.type, elseBlock.type)
        } catch (e: RuntimeException) {
            fail(e, "In function $containingFunctionId, could not reconcile 'then' and 'else' block types")
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

    private fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, containingFunctionId: EntityId): TypedExpression? {
        val type = variableTypes[expression.name]
        if (type != null) {
            return TypedExpression.Variable(type, expression.name)
        } else {
            errors.add(Issue("Unknown variable ${expression.name}", expression.location, IssueLevel.ERROR))
            return null
        }
    }

    private fun validateStructs(structs: List<UnvalidatedStruct>, typeInfo: AllTypeInfo): Map<EntityId, Struct> {
        val validatedStructs = HashMap<EntityId, Struct>()
        for (struct in structs) {
            val validatedStruct = validateStruct(struct, typeInfo)
            if (validatedStruct != null) {
                validatedStructs.put(struct.id, validatedStruct)
            }
        }
        return validatedStructs
    }

    private fun validateStruct(struct: UnvalidatedStruct, typeInfo: AllTypeInfo): Struct? {
        validateMemberNames(struct)
        val memberTypes = struct.members.associate { member -> member.name to member.type }

        val fakeContainingFunctionId = EntityId(struct.id.namespacedName + "requires")
        val uncheckedRequires = struct.requires
        val requires = if (uncheckedRequires != null) {
            validateBlock(uncheckedRequires, memberTypes, typeInfo, struct.typeParameters.toSet(), fakeContainingFunctionId) ?: return null
        } else {
            null
        }
        if (requires != null && requires.type != Type.BOOLEAN) {
            val message = "Struct ${struct.id} has a requires block with inferred type ${requires.type}, but the type should be Boolean"
            val position = struct.requires!!.location
            val issue: Issue = Issue(message, position, IssueLevel.ERROR)
            errors.add(issue)
        }

        return Struct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations)
    }

    private fun validateMemberNames(struct: UnvalidatedStruct) {
        val allNames = HashSet<String>()
        for (member in struct.members) {
            if (allNames.contains(member.name)) {
                // TODO: Improve position of message
                errors.add(Issue("Struct ${struct.id} has multiple members named ${member.name}", struct.idLocation, IssueLevel.ERROR))
            }
            allNames.add(member.name)
        }
    }

    private fun validateInterfaces(interfaces: List<UnvalidatedInterface>, typeInfo: AllTypeInfo): Map<EntityId, Interface> {
        val validatedInterfaces = HashMap<EntityId, Interface>()
        for (interfac in interfaces) {
            validatedInterfaces.put(interfac.id, validateInterface(interfac, typeInfo))
        }
        return validatedInterfaces
    }

    private fun validateInterface(interfac: UnvalidatedInterface, typeInfo: AllTypeInfo): Interface {
        // TODO: Do some actual validation of interfaces
        val methods = validateMethods(interfac.methods, typeInfo, interfac.typeParameters.toSet())
        return Interface(interfac.id, interfac.typeParameters, methods, interfac.annotations)
    }

    private fun validateMethods(methods: List<UnvalidatedMethod>, typeInfo: AllTypeInfo, interfaceTypeParameters: Set<String>): List<Method> {
        // TODO: Do some actual validation of methods
        return methods.map { method ->
            val typeParametersVisibleToMethod = interfaceTypeParameters + method.typeParameters.toSet()
            val arguments = validateArguments(method.arguments, typeInfo, typeParametersVisibleToMethod)
            Method(method.name, method.typeParameters, arguments, method.returnType)
        }
    }
}

private fun List<Argument>.asVariableTypesMap(): Map<String, Type> {
    return this.map{arg -> Pair(arg.name, arg.type)}.toMap()
}
