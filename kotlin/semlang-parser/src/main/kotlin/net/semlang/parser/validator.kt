package net.semlang.parser

import net.semlang.api.*
import net.semlang.api.Function
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
    val validator = Validator(moduleId, nativeModuleVersion, upstreamModules)
    return validator.validate(context)
}

fun validate(parsingResult: ParsingResult, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): ValidationResult {
    return when (parsingResult) {
        is ParsingResult.Success -> {
            validateModule(parsingResult.context, moduleId, nativeModuleVersion, upstreamModules)
        }
        is ParsingResult.Failure -> {
            ValidationResult.Failure(parsingResult.errors, listOf())
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
            validate(combinedParsingResult, parsedConfig.info.id, nativeModuleVersion, listOf())
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

        for (struct in context.structs) {
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

        for (interfac in context.interfaces) {
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
                localTypes.put(interfac.adapterId, getTypeInfo(interfac.getAdapterStruct()))
            }
            if (!duplicateLocalFunctionIds.contains(interfac.adapterId)) {
                localFunctions.put(interfac.adapterId, FunctionInfo(interfac.getAdapterConstructorSignature(), interfac.idLocation))
            }
        }

        for (function in context.functions) {
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
        for (struct in getNativeStructs().values) {
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamTypes.put(ref, getTypeInfo(struct, null))
        }
        for (interfac in getNativeInterfaces().values) {
            val ref = ResolvedEntityRef(nativeModuleId, interfac.id)
            upstreamTypes.put(ref, getTypeInfo(interfac, null))
            val adapterRef = ResolvedEntityRef(nativeModuleId, interfac.adapterId)
            upstreamTypes.put(adapterRef, getTypeInfo(interfac.getAdapterStruct(), null))
        }

        for (module in upstreamModules) {
            for (struct in module.getAllExportedStructs().values) {
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamTypes.put(ref, getTypeInfo(struct, null))
            }
            for (interfac in module.getAllExportedInterfaces().values) {
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamTypes.put(ref, getTypeInfo(interfac, null))
                val adapterRef = ResolvedEntityRef(module.id, interfac.adapterId)
                upstreamTypes.put(adapterRef, getTypeInfo(interfac.getAdapterStruct(), null))
            }
        }
        return upstreamTypes
    }

    private fun getUpstreamFunctions(nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, FunctionInfo> {
        val upstreamFunctions = HashMap<ResolvedEntityRef, FunctionInfo>()

        // (We don't need the idPosition for functions in upstream modules)
        val functionInfo = fun(signature: TypeSignature): FunctionInfo { return FunctionInfo(invalidate(signature), null) }

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        for (struct in getNativeStructs().values) {
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamFunctions.put(ref, functionInfo(struct.getConstructorSignature()))
        }
        for (interfac in getNativeInterfaces().values) {
            val ref = ResolvedEntityRef(nativeModuleId, interfac.id)
            upstreamFunctions.put(ref, functionInfo(interfac.getInstanceConstructorSignature()))
            val adapterRef = ResolvedEntityRef(nativeModuleId, interfac.adapterId)
            upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterConstructorSignature()))
        }
        for (function in getNativeFunctionOnlyDefinitions().values) {
            val ref = ResolvedEntityRef(nativeModuleId, function.id)
            upstreamFunctions.put(ref, functionInfo(function))
        }

        for (module in upstreamModules) {
            for (struct in module.getAllExportedStructs().values) {
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamFunctions.put(ref, functionInfo(struct.getConstructorSignature()))
            }
            for (interfac in module.getAllExportedInterfaces().values) {
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamFunctions.put(ref, functionInfo(interfac.getInstanceConstructorSignature()))
                val adapterRef = ResolvedEntityRef(module.id, interfac.adapterId)
                upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterConstructorSignature()))
            }
            for (function in module.getAllExportedFunctions().values) {
                val ref = ResolvedEntityRef(module.id, function.id)
                upstreamFunctions.put(ref, functionInfo(function.getTypeSignature()))
            }
        }
        return upstreamFunctions
    }

    private fun validateFunctions(functions: List<Function>, typeInfo: AllTypeInfo): Map<EntityId, ValidatedFunction> {
        val validatedFunctions = HashMap<EntityId, ValidatedFunction>()
        for (function in functions) {
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

        val arguments = validateArguments(function.arguments, typeInfo, function.typeParameters.toSet()) ?: return null
        val returnType = validateType(function.returnType, typeInfo, function.typeParameters.toSet()) ?: return null

        //TODO: Validate that type parameters don't share a name with something important
        val variableTypes = getArgumentVariableTypes(arguments)
        val block = validateBlock(function.block, variableTypes, typeInfo, function.typeParameters.toSet(), HashSet(), function.id) ?: return null
        if (returnType != block.type) {
            errors.add(Issue("Stated return type ${function.returnType} does not match the block's actual return type ${block.type}", function.returnTypeLocation, IssueLevel.ERROR))
        }

        return ValidatedFunction(function.id, function.typeParameters, arguments, returnType, block, function.annotations)
    }

    private fun validateType(type: UnvalidatedType, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): Type? {
        return when (type) {
            UnvalidatedType.INTEGER -> Type.INTEGER
            UnvalidatedType.BOOLEAN -> Type.BOOLEAN
            is UnvalidatedType.List -> {
                val parameter = validateType(type.parameter, typeInfo, typeParametersInScope) ?: return null
                if (parameter.isThreaded()) {
                    errors.add(Issue("Lists cannot have a threaded parameter type", null, IssueLevel.ERROR))
                }
                Type.List(parameter)
            }
            is UnvalidatedType.Try -> {
                val parameter = validateType(type.parameter, typeInfo, typeParametersInScope) ?: return null
                if (parameter.isThreaded()) {
                    errors.add(Issue("Tries cannot have a threaded parameter type", null, IssueLevel.ERROR))
                }
                Type.Try(parameter)
            }
            is UnvalidatedType.FunctionType -> {
                val argTypes = type.argTypes.map { argType -> validateType(argType, typeInfo, typeParametersInScope) ?: return null }
                val outputType = validateType(type.outputType, typeInfo, typeParametersInScope) ?: return null
                Type.FunctionType(argTypes, outputType)
            }
            is UnvalidatedType.NamedType -> {
                if (type.parameters.isEmpty()
                        && type.ref.moduleRef == null
                        && type.ref.id.namespacedName.size == 1
                        && typeParametersInScope.contains(type.ref.id.namespacedName[0])) {
                    if (type.isThreaded) {
                        error("Type parameters shouldn't be marked as threaded")
                    }
                    Type.ParameterType(type.ref.id.namespacedName[0])
                } else {
                    val resolved = typeInfo.resolver.resolve(type.ref)
                    if (resolved == null) {
                        // TODO: Give this a location (which probably requires putting locations on UnvalidatedTypes; try to make locations sane first)
                        errors.add(Issue("Unresolved type reference: ${type.ref}", null, IssueLevel.ERROR))
                        return null
                    }
                    // TODO: This will probably need to change; the resolver will probably need to know which types are threaded ahead of time
                    val shouldBeThreaded = (resolved.type == FunctionLikeType.OPAQUE_TYPE)
                    if (shouldBeThreaded && !type.isThreaded) {
                        // TODO: Give this a location (which probably requires putting locations on UnvalidatedTypes; try to make locations sane first)
                        errors.add(Issue("Type is threaded and should be marked as such with '~'", null, IssueLevel.ERROR))
                        return null
                    }
                    if (type.isThreaded && !shouldBeThreaded) {
                        // TODO: Give this a location (which probably requires putting locations on UnvalidatedTypes; try to make locations sane first)
                        errors.add(Issue("Type is not threaded and should not be marked with '~'", null, IssueLevel.ERROR))
                        return null
                    }
                    val parameters = type.parameters.map { parameter -> validateType(parameter, typeInfo, typeParametersInScope) ?: return null }
                    Type.NamedType(resolved.entityRef, type.ref, type.isThreaded, parameters)
                }
            }
        }
    }

    private fun validateArguments(arguments: List<UnvalidatedArgument>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): List<Argument>? {
        val validatedArguments = ArrayList<Argument>()
        for (argument in arguments) {
            val type = validateType(argument.type, typeInfo, typeParametersInScope) ?: return null
            validatedArguments.add(Argument(argument.name, type))
        }
        return validatedArguments
    }

    private fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
        return arguments.associate { argument -> Pair(argument.name, argument.type) }
    }


    private sealed class TypeInfo {
        abstract val idLocation: Location?
        data class Struct(val typeParameters: List<String>, val memberTypes: Map<String, UnvalidatedType>, val usesRequires: Boolean, override val idLocation: Location?): TypeInfo()
        data class Interface(val typeParameters: List<String>, val methodTypes: Map<String, UnvalidatedType.FunctionType>, override val idLocation: Location?): TypeInfo()
    }
    private data class FunctionInfo(val signature: UnvalidatedTypeSignature, val idLocation: Location?)

    private fun getTypeInfo(struct: UnvalidatedStruct): TypeInfo.Struct {
        return TypeInfo.Struct(struct.typeParameters, getUnvalidatedMemberTypeMap(struct.members), struct.requires != null, struct.idLocation)
    }
    private fun getTypeInfo(struct: Struct, idLocation: Location?): TypeInfo.Struct {
        return TypeInfo.Struct(struct.typeParameters, getMemberTypeMap(struct.members), struct.requires != null, idLocation)
    }

    private fun getTypeInfo(interfac: UnvalidatedInterface): TypeInfo.Interface {
        return TypeInfo.Interface(interfac.typeParameters, getUnvalidatedMethodTypeMap(interfac.methods), interfac.idLocation)
    }
    private fun getTypeInfo(interfac: Interface, idLocation: Location?): TypeInfo.Interface {
        return TypeInfo.Interface(interfac.typeParameters, getMethodTypeMap(interfac.methods), idLocation)
    }

    private fun getUnvalidatedMemberTypeMap(members: List<UnvalidatedMember>): Map<String, UnvalidatedType> {
        val typeMap = HashMap<String, UnvalidatedType>()
        for (member in members) {
            if (typeMap.containsKey(member.name)) {
                // TODO: Fix this...
//                error("Duplicate member name ${member.name}")
            } else {
                typeMap.put(member.name, member.type)
            }
        }
        return typeMap
    }
    private fun getMemberTypeMap(members: List<Member>): Map<String, UnvalidatedType> {
        val typeMap = HashMap<String, UnvalidatedType>()
        for (member in members) {
            if (typeMap.containsKey(member.name)) {
                error("Duplicate member name ${member.name}")
            } else {
                typeMap.put(member.name, invalidate(member.type))
            }
        }
        return typeMap
    }
    private fun getUnvalidatedMethodTypeMap(methods: List<UnvalidatedMethod>): Map<String, UnvalidatedType.FunctionType> {
        val typeMap = HashMap<String, UnvalidatedType.FunctionType>()
        for (method in methods) {
            if (typeMap.containsKey(method.name)) {
                error("Duplicate method name ${method.name}")
            } else {
                typeMap.put(method.name, method.functionType)
            }
        }
        return typeMap
    }
    private fun getMethodTypeMap(methods: List<Method>): Map<String, UnvalidatedType.FunctionType> {
        val typeMap = HashMap<String, UnvalidatedType.FunctionType>()
        for (method in methods) {
            if (typeMap.containsKey(method.name)) {
                error("Duplicate method name ${method.name}")
            } else {
                typeMap.put(method.name, invalidate(method.functionType) as UnvalidatedType.FunctionType)
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

    private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedBlock? {
        val variableTypes = HashMap(externalVariableTypes)
        val validatedAssignments = ArrayList<ValidatedAssignment>()
        for (assignment in block.assignments) {
            if (variableTypes.containsKey(assignment.name)) {
                errors.add(Issue("The already-assigned variable ${assignment.name} cannot be reassigned", assignment.nameLocation, IssueLevel.ERROR))
            }
            if (isInvalidVariableName(assignment.name, typeInfo)) {
                errors.add(Issue("Invalid variable name ${assignment.name}", assignment.nameLocation, IssueLevel.ERROR))
            }

            val validatedExpression = validateExpression(assignment.expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
            val unvalidatedAssignmentType = assignment.type
            if (unvalidatedAssignmentType != null) {
                val assignmentType = validateType(unvalidatedAssignmentType, typeInfo, typeParametersInScope)
                if (validatedExpression.type != assignmentType) {
                    fail("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type ${assignment.type}, " +
                            "but the expression given has actual type ${validatedExpression.type}")
                }
            }

            validatedAssignments.add(ValidatedAssignment(assignment.name, validatedExpression.type, validatedExpression))
            variableTypes.put(assignment.name, validatedExpression.type)
        }
        val returnedExpression = validateExpression(block.returnedExpression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
        return TypedBlock(returnedExpression.type, validatedAssignments, returnedExpression)
    }

    //TODO: Construct this more sensibly from more centralized lists
    private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Boolean", "function", "let", "return", "if", "else", "struct", "requires")

    private fun isInvalidVariableName(name: String, typeInfo: AllTypeInfo): Boolean {
        val nameAsEntityRef = EntityId.of(name).asRef()
        return typeInfo.resolver.resolve(nameAsEntityRef) != null
                || INVALID_VARIABLE_NAMES.contains(name)
    }

    // TODO: Remove containingFunctionId argument when no longer needed
    private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        return when (expression) {
            is Expression.Variable -> validateVariableExpression(expression, variableTypes, consumedThreadedVars, containingFunctionId)
            is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.Follow -> validateFollowExpression(expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.NamedFunctionCall -> validateNamedFunctionCallExpression(expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.ExpressionFunctionCall -> validateExpressionFunctionCallExpression(expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.Literal -> validateLiteralExpression(expression, typeInfo, typeParametersInScope)
            is Expression.ListLiteral -> validateListLiteralExpression(expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.NamedFunctionBinding -> validateNamedFunctionBinding(expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.ExpressionFunctionBinding -> validateExpressionFunctionBinding(expression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            is Expression.InlineFunction -> validateInlineFunction(expression, variableTypes, typeInfo, typeParametersInScope, containingFunctionId)
        }
    }

    private fun validateInlineFunction(expression: Expression.InlineFunction, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, containingFunctionId: EntityId): TypedExpression? {
        for (arg in expression.arguments) {
            if (variableTypes.containsKey(arg.name)) {
                errors.add(Issue("Argument name ${arg.name} shadows an existing variable name", arg.location, IssueLevel.ERROR))
            }
        }
        val validatedArguments = validateArguments(expression.arguments, typeInfo, typeParametersInScope) ?: return null

        val incomingVariableTypes: Map<String, Type> = variableTypes + validatedArguments.asVariableTypesMap()
        val validatedBlock = validateBlock(expression.block, incomingVariableTypes, typeInfo, typeParametersInScope, HashSet(), containingFunctionId) ?: return null

        // Note: This is the source of the canonical in-memory ordering
        val varsToBind = ArrayList<String>(variableTypes.keys)
        varsToBind.retainAll(getVarsReferencedIn(validatedBlock))
        val varsToBindWithTypes = varsToBind.map { name -> Argument(name, variableTypes[name]!!)}
        for (varToBindWithType in varsToBindWithTypes) {
            if (varToBindWithType.type.isThreaded()) {
                errors.add(Issue("The inline function implicitly binds ${varToBindWithType.name}, which has a threaded type", expression.location, IssueLevel.ERROR))
            }
        }

        val functionType = Type.FunctionType(validatedArguments.map(Argument::type), validatedBlock.type)

        return TypedExpression.InlineFunction(functionType, validatedArguments, varsToBindWithTypes, validatedBlock)
    }

    private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("Attempting to bind $functionExpression like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        val preBindingArgumentTypes = functionType.argTypes

        if (preBindingArgumentTypes.size != expression.bindings.size) {
            errors.add(Issue("Tried to re-bind $functionExpression with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments", expression.location, IssueLevel.ERROR))
            return null
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
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
                if (binding.type.isThreaded()) {
                    errors.add(Issue("Threaded objects can't be bound in function bindings", expression.location, IssueLevel.ERROR))
                }
            }
        }
        val postBindingType = Type.FunctionType(
                postBindingArgumentTypes,
                functionType.outputType)
        val chosenParameters = expression.chosenParameters.map { chosenParameter -> validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null }
        for (chosenParameter in chosenParameters) {
            if (chosenParameter.isThreaded()) {
                errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
            }
        }

        return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings, chosenParameters)
    }

    private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef

        val resolvedRef = typeInfo.resolver.resolve(functionRef) ?: fail("In function $containingFunctionId, could not find a declaration of a function with ID $functionRef")
        val functionInfo = typeInfo.getFunctionInfo(resolvedRef.entityRef)
        if (functionInfo == null) {
            fail("In function $containingFunctionId, resolved a function with ID $functionRef but could not find the signature")
        }
        val signature = functionInfo.signature
        val chosenParameters = expression.chosenParameters.map { chosenParameter -> validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null }
        if (chosenParameters.size != signature.typeParameters.size) {
            fail("In function $containingFunctionId, referenced a function $functionRef with type parameters ${signature.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
        }
        for (chosenParameter in chosenParameters) {
            if (chosenParameter.isThreaded()) {
                errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
            }
        }

        val expectedFunctionType = parameterizeAndValidateSignature(signature, chosenParameters, typeInfo, typeParametersInScope) ?: return null

        if (expectedFunctionType.argTypes.size != expression.bindings.size) {
            errors.add(Issue("Tried to bind function $functionRef with ${expression.bindings.size} bindings, but it takes ${expectedFunctionType.argTypes.size} arguments", expression.location, IssueLevel.ERROR))
            return null
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            }
        }

        val postBindingArgumentTypes = ArrayList<Type>()
        for (entry in expectedFunctionType.argTypes.zip(bindings)) {
            val type = entry.first
            val binding = entry.second
            if (binding == null) {
                postBindingArgumentTypes.add(type)
            } else {
                if (binding.type != type) {
                    fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
                }
                if (binding.type.isThreaded()) {
                    errors.add(Issue("Threaded objects can't be bound in function bindings", expression.location, IssueLevel.ERROR))
                }
            }
        }
        val postBindingType = Type.FunctionType(
                postBindingArgumentTypes,
                expectedFunctionType.outputType)

        return TypedExpression.NamedFunctionBinding(postBindingType, functionRef, resolvedRef.entityRef, bindings, chosenParameters)
    }

    private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val structureExpression = validateExpression(expression.structureExpression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        val parentNamedType = structureExpression.type as? Type.NamedType
        if (parentNamedType == null) {
            errors.add(Issue("Cannot dereference an expression $structureExpression of non-struct, non-interface type ${structureExpression.type}", expression.location, IssueLevel.ERROR))
            return null
        }

        val resolvedParentType = typeInfo.resolver.resolve(parentNamedType.originalRef)
        if (resolvedParentType == null) {
            errors.add(Issue("Cannot dereference an expression $structureExpression of unrecognized type ${structureExpression.type}", expression.location, IssueLevel.ERROR))
            return null
        }
        val parentTypeInfo = typeInfo.getTypeInfo(resolvedParentType.entityRef) ?: error("No type info for ${resolvedParentType.entityRef}")

        return when (parentTypeInfo) {
            is TypeInfo.Struct -> {
                val memberType = parentTypeInfo.memberTypes[expression.name]
                if (memberType == null) {
                    errors.add(Issue("Struct type $parentNamedType does not have a member named '${expression.name}'", expression.location, IssueLevel.ERROR))
                    return null
                }

                // Type parameters come from the struct definition itself
                // Chosen types come from the struct type known for the variable
                val typeParameters = parentTypeInfo.typeParameters
                val chosenTypes = parentNamedType.getParameterizedTypes()
                for (chosenParameter in chosenTypes) {
                    if (chosenParameter.isThreaded()) {
                        errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
                    }
                }
                val type = parameterizeAndValidateType(memberType, typeParameters.map(Type::ParameterType), chosenTypes, typeInfo, typeParametersInScope) ?: return null
                //TODO: Ground this if needed

                return TypedExpression.Follow(type, structureExpression, expression.name)

            }
            is TypeInfo.Interface -> {
                val interfac = parentTypeInfo
                val interfaceType = parentNamedType
                val methodType = interfac.methodTypes[expression.name]
                if (methodType == null) {
                    errors.add(Issue("Interface type $parentNamedType does not have a method named '${expression.name}'", expression.location, IssueLevel.ERROR))
                    return null
                }

                val typeParameters = interfac.typeParameters
                val chosenTypes = interfaceType.getParameterizedTypes()
                for (chosenParameter in chosenTypes) {
                    if (chosenParameter.isThreaded()) {
                        errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
                    }
                }

                val type = parameterizeAndValidateType(methodType, typeParameters.map(Type::ParameterType), chosenTypes, typeInfo, typeParametersInScope) ?: return null

                return TypedExpression.Follow(type, structureExpression, expression.name)
            }
        }
    }

    private fun parameterizeAndValidateType(unvalidatedType: UnvalidatedType, typeParameters: List<Type.ParameterType>, chosenTypes: List<Type>, typeInfo: Validator.AllTypeInfo, typeParametersInScope: Set<String>): Type? {
        val type = validateType(unvalidatedType, typeInfo, typeParametersInScope + typeParameters.map(Type.ParameterType::name)) ?: return null

        if (typeParameters.size != chosenTypes.size) {
            error("Give me a better error message")
        }
        val parameterMap = typeParameters.zip(chosenTypes).toMap()

        return type.replacingParameters(parameterMap)
    }

    private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("The expression $functionExpression is called like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        val arguments = ArrayList<TypedExpression>()
        for (untypedArgument in expression.arguments) {
            val argument = validateExpression(untypedArgument, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        if (argumentTypes != functionType.argTypes) {
            errors.add(Issue("The bound function $functionExpression expects argument types ${functionType.argTypes}, but we give it types $argumentTypes", expression.location, IssueLevel.ERROR))
            return null
        }
        val chosenParameters = expression.chosenParameters.map { chosenParameter -> validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null }
        for (chosenParameter in chosenParameters) {
            if (chosenParameter.isThreaded()) {
                errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
            }
        }

        return TypedExpression.ExpressionFunctionCall(functionType.outputType, functionExpression, arguments, chosenParameters)
    }

    private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef
        val functionResolvedRef = typeInfo.resolver.resolve(functionRef)
        if (functionResolvedRef == null) {
            errors.add(Issue("Function $functionRef not found", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        val arguments = ArrayList<TypedExpression>()
        for (untypedArgument in expression.arguments) {
            val argument = validateExpression(untypedArgument, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        val signature = typeInfo.getFunctionInfo(functionResolvedRef.entityRef)?.signature
        if (signature == null) {
            fail("The function $containingFunctionId references a function $functionRef that was not found")
        }
        //TODO: Maybe compare argument size before grounding?

        //Ground the signature
        val chosenParameters = expression.chosenParameters.map { chosenParameter -> validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null }
        if (signature.typeParameters.size != chosenParameters.size) {
            errors.add(Issue("Expected ${signature.typeParameters.size} type parameters, but got ${chosenParameters.size}", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }
        for (chosenParameter in chosenParameters) {
            if (chosenParameter.isThreaded()) {
                errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
            }
        }
        val functionType = parameterizeAndValidateSignature(signature, chosenParameters, typeInfo, typeParametersInScope) ?: return null
        if (argumentTypes != functionType.argTypes) {
            errors.add(Issue("The function $functionRef expects argument types ${functionType.argTypes}, but is given arguments with types $argumentTypes", expression.location, IssueLevel.ERROR))
        }

        return TypedExpression.NamedFunctionCall(functionType.outputType, functionRef, functionResolvedRef.entityRef, arguments, chosenParameters)
    }

    private fun parameterizeAndValidateSignature(signature: UnvalidatedTypeSignature, chosenParameters: List<Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): Type.FunctionType? {
        val typeParameters = signature.typeParameters.map(Type::ParameterType)
        try {
            return parameterizeAndValidateType(signature.getFunctionType(), typeParameters, chosenParameters, typeInfo, typeParametersInScope) as Type.FunctionType?
        } catch (e: RuntimeException) {
            throw RuntimeException("Signature being parameterized is $signature", e)
        }
    }

    private fun validateLiteralExpression(expression: Expression.Literal, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): TypedExpression? {
        val typeChain = getLiteralTypeChain(expression.type, expression.location, typeInfo, typeParametersInScope)

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
    private fun getLiteralTypeChain(initialType: UnvalidatedType, literalLocation: Location?, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): List<Type>? {
        var type = validateType(initialType, typeInfo, typeParametersInScope) ?: return null
        val list = ArrayList<Type>()
        list.add(type)
        while (getTypeValidatorFor(type) == null) {
            if (type is Type.NamedType) {
                val resolvedType = typeInfo.resolver.resolve(type.originalRef) ?: fail("Could not resolve type ${type.ref}")
                val struct = typeInfo.getTypeInfo(resolvedType.entityRef) as? TypeInfo.Struct ?: fail("Trying to get a literal of a non-struct named type $resolvedType")

                if (struct.typeParameters.isNotEmpty()) {
                    fail("Can't have a literal of a type with type parameters: $type")
                }
                if (struct.memberTypes.size != 1) {
                    fail("Can't have a literal of a struct type with more than one member")
                }
                val unvalidatedMemberType = struct.memberTypes.values.single()
                val memberType = validateType(unvalidatedMemberType, typeInfo, struct.typeParameters.toSet()) ?: return null
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

    private fun validateListLiteralExpression(expression: Expression.ListLiteral, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val chosenParameter = validateType(expression.chosenParameter, typeInfo, typeParametersInScope) ?: return null
        if (chosenParameter.isThreaded()) {
            errors.add(Issue("Threaded types cannot be used as parameters", expression.location, IssueLevel.ERROR))
        }

        val listType = Type.List(chosenParameter)

        val contents = expression.contents.map { item ->
            validateExpression(item, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null
        }
        for (item in contents) {
            if (item.type != chosenParameter) {
                error("Put an expression $item of type ${item.type} in a list literal of type ${listType}")
            }
        }

        return TypedExpression.ListLiteral(listType, contents, chosenParameter)
    }

    private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val condition = validateExpression(expression.condition, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        if (condition.type != Type.BOOLEAN) {
            fail("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
        }

        // Threaded variables can be consumed in each of the two blocks, but is considered used if used in either
        val thenConsumedThreadedVars = HashSet(consumedThreadedVars)
        val elseConsumedThreadedVars = HashSet(consumedThreadedVars)
        val thenBlock = validateBlock(expression.thenBlock, variableTypes, typeInfo, typeParametersInScope, thenConsumedThreadedVars, containingFunctionId) ?: return null
        val elseBlock = validateBlock(expression.elseBlock, variableTypes, typeInfo, typeParametersInScope, elseConsumedThreadedVars, containingFunctionId) ?: return null
        // Don't add variable names that were defined in the blocks themselves
        consumedThreadedVars.addAll(thenConsumedThreadedVars.intersect(variableTypes.keys))
        consumedThreadedVars.addAll(elseConsumedThreadedVars.intersect(variableTypes.keys))

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
        val members = validateMembers(struct, typeInfo, struct.typeParameters.toSet()) ?: return null

        val memberTypes = members.associate { member -> member.name to member.type }

        val fakeContainingFunctionId = EntityId(struct.id.namespacedName + "requires")
        val uncheckedRequires = struct.requires
        val requires = if (uncheckedRequires != null) {
            validateBlock(uncheckedRequires, memberTypes, typeInfo, struct.typeParameters.toSet(), HashSet(), fakeContainingFunctionId) ?: return null
        } else {
            null
        }
        if (requires != null && requires.type != Type.BOOLEAN) {
            val message = "Struct ${struct.id} has a requires block with inferred type ${requires.type}, but the type should be Boolean"
            val position = struct.requires!!.location
            val issue: Issue = Issue(message, position, IssueLevel.ERROR)
            errors.add(issue)
        }

        return Struct(struct.id, moduleId, struct.typeParameters, members, requires, struct.annotations)
    }



    private fun validateMembers(struct: UnvalidatedStruct, typeInfo: AllTypeInfo, typeParametersInScope: Set<String>): List<Member>? {
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
            val type = validateType(member.type, typeInfo, typeParametersInScope) ?: return null
            if (type.isThreaded()) {
                // TODO: Improve position of message
                errors.add(Issue("Structs cannot have members with threaded types", struct.idLocation, IssueLevel.ERROR))
            }
            Member(member.name, type)
        }
    }

    private fun validateInterfaces(interfaces: List<UnvalidatedInterface>, typeInfo: AllTypeInfo): Map<EntityId, Interface> {
        val validatedInterfaces = HashMap<EntityId, Interface>()
        for (interfac in interfaces) {
            val validatedInterface = validateInterface(interfac, typeInfo)
            if (validatedInterface != null) {
                validatedInterfaces.put(interfac.id, validatedInterface)
            }
        }
        return validatedInterfaces
    }

    private fun validateInterface(interfac: UnvalidatedInterface, typeInfo: AllTypeInfo): Interface? {
        // TODO: Do some actual validation of interfaces
        val methods = validateMethods(interfac.methods, typeInfo, interfac.typeParameters.toSet()) ?: return null
        return Interface(interfac.id, moduleId, interfac.typeParameters, methods, interfac.annotations)
    }

    private fun validateMethods(methods: List<UnvalidatedMethod>, typeInfo: AllTypeInfo, interfaceTypeParameters: Set<String>): List<Method>? {
        // TODO: Do some actual validation of methods
        return methods.map { method ->
            val typeParametersVisibleToMethod = interfaceTypeParameters + method.typeParameters.toSet()
            val arguments = validateArguments(method.arguments, typeInfo, typeParametersVisibleToMethod) ?: return null
            val returnType = validateType(method.returnType, typeInfo, typeParametersVisibleToMethod) ?: return null
            Method(method.name, method.typeParameters, arguments, returnType)
        }
    }
}

private fun List<Argument>.asVariableTypesMap(): Map<String, Type> {
    return this.map{arg -> Pair(arg.name, arg.type)}.toMap()
}
