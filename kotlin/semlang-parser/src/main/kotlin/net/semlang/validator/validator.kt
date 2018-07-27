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
    val validator = Validator(moduleId, nativeModuleVersion, upstreamModules)
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

private class Validator(val moduleId: ModuleId, val nativeModuleVersion: String, val upstreamModules: List<ValidatedModule>) {
    val warnings = ArrayList<Issue>()
    val errors = ArrayList<Issue>()

    fun validate(context: RawContext): ValidationResult {
        val typeInfo = collectTypeInfo(context, moduleId, nativeModuleVersion, upstreamModules)

        val ownFunctions = validateFunctions(context.functions, typeInfo)
        val ownStructs = validateStructs(context.structs, typeInfo)
        val ownInterfaces = validateInterfaces(context.interfaces, typeInfo)
        val ownUnions = validateUnions(context.unions, typeInfo)

        if (errors.isEmpty()) {
            val createdModule = ValidatedModule.create(moduleId, nativeModuleVersion, ownFunctions, ownStructs, ownInterfaces, ownUnions, upstreamModules)
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
                context.structs.associateBy { it.id }.mapValues { it.value.markedAsThreaded },
                context.interfaces.map(UnvalidatedInterface::id),
                context.unions.associateBy { it.id }.mapValues { it.value.options.map(UnvalidatedOption::name).toSet() },
                upstreamModules)

        val localTypesMultimap = HashMap<EntityId, MutableList<TypeInfo>>()
        val localFunctionsMultimap = HashMap<EntityId, MutableList<FunctionInfo>>()

        for (struct in context.structs) {
            val id = struct.id
            localTypesMultimap.multimapPut(id, getTypeInfo(struct))
            localFunctionsMultimap.multimapPut(id, FunctionInfo(struct.getConstructorSignature(), struct.idLocation))
        }

        for (interfac in context.interfaces) {
            localTypesMultimap.multimapPut(interfac.id, getTypeInfo(interfac))
            localFunctionsMultimap.multimapPut(interfac.id, FunctionInfo(interfac.getInstanceConstructorSignature(), interfac.idLocation))
            localFunctionsMultimap.multimapPut(interfac.adapterId, FunctionInfo(interfac.getAdapterFunctionSignature(), interfac.idLocation))
        }

        for (function in context.functions) {
            val id = function.id
            localFunctionsMultimap.multimapPut(id, FunctionInfo(function.getTypeSignature(), function.idLocation))
        }

        for (union in context.unions) {
            val id = union.id
            val whenId = EntityId(union.id.namespacedName + "when")

            localTypesMultimap.multimapPut(id, getTypeInfo(union))
            for (option in union.options) {
                val optionId = EntityId(union.id.namespacedName + option.name)
                val signature = union.getConstructorSignature(option)
                localFunctionsMultimap.multimapPut(optionId, FunctionInfo(signature, union.idLocation))
            }
            val signature = union.getWhenSignature()
            localFunctionsMultimap.multimapPut(whenId, FunctionInfo(signature, union.idLocation))
        }

        val addDuplicateIdError = fun(id: EntityId, idLocation: Location?) { errors.add(Issue("Duplicate ID ${id}", idLocation, IssueLevel.ERROR)) }

        val duplicateLocalTypeIds = HashSet<EntityId>()
        val uniqueLocalTypes = HashMap<EntityId, TypeInfo>()
        for ((id, typeInfoList) in localTypesMultimap.entries) {
            if (typeInfoList.size > 1) {
                for (typeInfo in typeInfoList) {
                    addDuplicateIdError(id, typeInfo.idLocation)
                }
                duplicateLocalTypeIds.add(id)
            } else {
                uniqueLocalTypes.put(id, typeInfoList[0])
            }
        }

        val duplicateLocalFunctionIds = HashSet<EntityId>()
        val uniqueLocalFunctions = HashMap<EntityId, FunctionInfo>()
        for ((id, functionInfoList) in localFunctionsMultimap.entries) {
            if (functionInfoList.size > 1) {
                for (functionInfo in functionInfoList) {
                    addDuplicateIdError(id, functionInfo.idLocation)
                }
                duplicateLocalFunctionIds.add(id)
            } else {
                uniqueLocalFunctions.put(id, functionInfoList[0])
            }
        }

        val upstreamTypes = getUpstreamTypes(nativeModuleVersion, upstreamModules)
        val upstreamFunctions = getUpstreamFunctions(nativeModuleVersion, upstreamModules)

        return AllTypeInfo(resolver, uniqueLocalTypes, duplicateLocalTypeIds, uniqueLocalFunctions, duplicateLocalFunctionIds, upstreamTypes, upstreamFunctions)
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
        }

        for (module in upstreamModules) {
            for (struct in module.getAllExportedStructs().values) {
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamTypes.put(ref, getTypeInfo(struct, null))
            }
            for (interfac in module.getAllExportedInterfaces().values) {
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamTypes.put(ref, getTypeInfo(interfac, null))
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
            upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterFunctionSignature()))
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
                upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterFunctionSignature()))
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

        val arguments = validateArguments(function.arguments, typeInfo, function.typeParameters.associateBy(TypeParameter::name)) ?: return null
        val returnType = validateType(function.returnType, typeInfo, function.typeParameters.associateBy(TypeParameter::name)) ?: return null

        //TODO: Validate that type parameters don't share a name with something important
        val variableTypes = getArgumentVariableTypes(arguments)
        val block = validateBlock(function.block, variableTypes, typeInfo, function.typeParameters.associateBy(TypeParameter::name), HashSet(), function.id) ?: return null
        if (returnType != block.type) {
            errors.add(Issue("Stated return type ${function.returnType} does not match the block's actual return type ${block.type}", function.returnTypeLocation, IssueLevel.ERROR))
        }

        return ValidatedFunction(function.id, function.typeParameters, arguments, returnType, block, function.annotations)
    }

    private fun validateType(type: UnvalidatedType, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): Type? {
        return when (type) {
            is UnvalidatedType.Integer -> Type.INTEGER
            is UnvalidatedType.Boolean -> Type.BOOLEAN
            is UnvalidatedType.List -> {
                val parameter = validateType(type.parameter, typeInfo, typeParametersInScope) ?: return null
                if (parameter.isThreaded()) {
                    errors.add(Issue("Lists cannot have a threaded parameter type", null, IssueLevel.ERROR))
                }
                Type.List(parameter)
            }
            is UnvalidatedType.Maybe -> {
                val parameter = validateType(type.parameter, typeInfo, typeParametersInScope) ?: return null
                if (parameter.isThreaded()) {
                    errors.add(Issue("Tries cannot have a threaded parameter type", null, IssueLevel.ERROR))
                }
                Type.Maybe(parameter)
            }
            is UnvalidatedType.FunctionType -> {
                // TODO: Add validation of these
                val typeParameters = type.typeParameters
                val newTypeParameterScope = HashMap<String, TypeParameter>(typeParametersInScope)
                for (typeParameter in typeParameters) {
                    if (newTypeParameterScope.containsKey(typeParameter.name)) {
                        // TODO: Do stuff here?
//                        error("Already using type parameter name ${typeParameter.name}; type is $type, existing type parameters are $newTypeParameterScope")
                    }
                    newTypeParameterScope.put(typeParameter.name, typeParameter)
                }
                val argTypes = type.argTypes.map { argType -> validateType(argType, typeInfo, newTypeParameterScope) ?: return null }
                val outputType = validateType(type.outputType, typeInfo, newTypeParameterScope) ?: return null
                Type.FunctionType(typeParameters, argTypes, outputType)
            }
            is UnvalidatedType.NamedType -> {
                if (type.parameters.isEmpty()
                        && type.ref.moduleRef == null
                        && type.ref.id.namespacedName.size == 1
                        && typeParametersInScope.containsKey(type.ref.id.namespacedName[0])) {
                    if (type.isThreaded) {
                        error("Type parameters shouldn't be marked as threaded")
                    }
                    Type.ParameterType(typeParametersInScope[type.ref.id.namespacedName[0]]!!)
                } else {
                    val resolved = typeInfo.resolver.resolve(type.ref)
                    if (resolved == null) {
                        errors.add(Issue("Unresolved type reference: ${type.ref}", type.location, IssueLevel.ERROR))
                        return null
                    }
                    val shouldBeThreaded = resolved.isThreaded

                    if (shouldBeThreaded && !type.isThreaded) {
                        errors.add(Issue("Type $type is threaded and should be marked as such with '~'", type.location, IssueLevel.ERROR))
                        return null
                    }
                    if (type.isThreaded && !shouldBeThreaded) {
                        errors.add(Issue("Type $type is not threaded and should not be marked with '~'", type.location, IssueLevel.ERROR))
                        return null
                    }
                    val parameters = type.parameters.map { parameter -> validateType(parameter, typeInfo, typeParametersInScope) ?: return null }
                    Type.NamedType(resolved.entityRef, type.ref, type.isThreaded, parameters)
                }
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

    private fun validateArguments(arguments: List<UnvalidatedArgument>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): List<Argument>? {
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
        data class Struct(val typeParameters: List<TypeParameter>, val memberTypes: Map<String, UnvalidatedType>, val usesRequires: Boolean, override val idLocation: Location?): TypeInfo()
        data class Interface(val typeParameters: List<TypeParameter>, val methodTypes: Map<String, UnvalidatedType.FunctionType>, override val idLocation: Location?): TypeInfo()
        data class Union(val typeParameters: List<TypeParameter>, val optionTypes: Map<String, Optional<UnvalidatedType>>, override val idLocation: Location?): TypeInfo()
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

    private fun getTypeInfo(union: UnvalidatedUnion): TypeInfo.Union {
        return TypeInfo.Union(union.typeParameters, getUnvalidatedUnionTypeMap(union.options), union.idLocation)
    }

    private fun getUnvalidatedUnionTypeMap(options: List<UnvalidatedOption>): Map<String, Optional<UnvalidatedType>> {
        val typeMap = HashMap<String, Optional<UnvalidatedType>>()
        for (option in options) {
            if (typeMap.containsKey(option.name)) {
                // TODO: Handle this...
            } else {
                typeMap.put(option.name, Optional.ofNullable(option.type))
            }
        }
        return typeMap
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

    // TODO: Move this closer to the function that creates it...
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
        fun isDataType(type: Type): Boolean {
            return when (type) {
                Type.INTEGER -> true
                Type.BOOLEAN -> true
                is Type.List -> isDataType(type.parameter)
                is Type.Maybe -> isDataType(type.parameter)
                is Type.FunctionType -> false
                is Type.ParameterType -> {
                    val typeClass = type.parameter.typeClass
                    if (typeClass == null) {
                        false
                    } else {
                        // TODO: May need to refine this in the future
                        typeClass == TypeClass.Data
                    }
                }
                is Type.NamedType -> {
                    // TODO: We might want some caching here
                    if (type.threaded) {
                        false
                    } else {
                        val typeInfo = getTypeInfo(type.ref)!!
                        when (typeInfo) {
                            is Validator.TypeInfo.Struct -> {
                                // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
                                // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
                                typeInfo.memberTypes.values.all { isDataType(it) }
                            }
                            is Validator.TypeInfo.Interface -> typeInfo.methodTypes.isEmpty()
                            is Validator.TypeInfo.Union -> {
                                // TODO: Need to handle recursive references here, too
                                typeInfo.optionTypes.values.all { !it.isPresent || isDataType(it.get()) }
                            }
                        }
                    }
                }
            }
        }
        // TODO: We shouldn't have two functions doing this on Types and UnvalidatedTypes
        private fun isDataType(type: UnvalidatedType): Boolean {
            return when (type) {
                is UnvalidatedType.Integer -> true
                is UnvalidatedType.Boolean -> true
                is UnvalidatedType.List -> isDataType(type.parameter)
                is UnvalidatedType.Maybe -> isDataType(type.parameter)
                is UnvalidatedType.FunctionType -> false
                is UnvalidatedType.NamedType -> {
                    // TODO: We might want some caching here
                    if (type.isThreaded) {
                        false
                    } else {
                        val resolvedRef = resolver.resolve(type.ref)!!
                        val typeInfo = getTypeInfo(resolvedRef.entityRef)!!
                        when (typeInfo) {
                            is Validator.TypeInfo.Struct -> {
                                // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
                                // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
                                typeInfo.memberTypes.values.all { isDataType(it) }
                            }
                            is Validator.TypeInfo.Interface -> typeInfo.methodTypes.isEmpty()
                            is Validator.TypeInfo.Union -> {
                                // TODO: Need to handle recursive references here, too
                                typeInfo.optionTypes.values.all { !it.isPresent || isDataType(it.get()) }
                            }
                        }
                    }
                }
                is UnvalidatedType.Invalid.ThreadedInteger -> false
                is UnvalidatedType.Invalid.ThreadedBoolean -> false
            }
        }
    }

    private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedBlock? {
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
                val assignmentType = validateType(unvalidatedAssignmentType, typeInfo, typeParametersInScope) ?: return null
                if (validatedExpression.type != assignmentType) {
                    fail("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type $assignmentType, " +
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
    private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
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

    private fun validateInlineFunction(expression: Expression.InlineFunction, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, containingFunctionId: EntityId): TypedExpression? {
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

        val returnType = validateType(expression.returnType, typeInfo, typeParametersInScope) ?: return null
        if (validatedBlock.type != returnType) {
            errors.add(Issue("The inline function has a return type $returnType, but the actual type returned is ${validatedBlock.type}", expression.location, IssueLevel.ERROR))
        }

        val functionType = Type.FunctionType(listOf(), validatedArguments.map(Argument::type), validatedBlock.type)

        return TypedExpression.InlineFunction(functionType, validatedArguments, varsToBindWithTypes, returnType, validatedBlock)
    }

    private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
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
                listOf(),
                postBindingArgumentTypes,
                functionType.outputType)

        return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings)
    }

    private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef

        val resolvedRef = typeInfo.resolver.resolve(functionRef) ?: fail("In function $containingFunctionId, could not find a declaration of a function with ID $functionRef")
        val functionInfo = typeInfo.getFunctionInfo(resolvedRef.entityRef)
        if (functionInfo == null) {
            fail("In function $containingFunctionId, resolved a function with ID $functionRef but could not find the signature")
        }
        val signature = functionInfo.signature


        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId)
            }
        }
        val bindingTypes = bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                binding.type
            }
        }

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

        val providedParameters = expression.chosenParameters.map {
            if (it == null) null else (validateType(it, typeInfo, typeParametersInScope) ?: return null)
        }

        val chosenParameters = getFinalParametersForBinding(signature.getFunctionType(), providedParameters, bindingTypes, typeInfo, typeParametersInScope, functionRef, expression.location) ?: return null

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

        val expectedFunctionType = applyChosenParametersToFunctionType(signature.getFunctionType(), chosenParameters, typeInfo, typeParametersInScope) ?: return null

        if (expectedFunctionType.argTypes.size != expression.bindings.size) {
            errors.add(Issue("Tried to bind function $functionRef with ${expression.bindings.size} bindings, but it takes ${expectedFunctionType.argTypes.size} arguments", expression.location, IssueLevel.ERROR))
            return null
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
                listOf(),
                postBindingArgumentTypes,
                expectedFunctionType.outputType)

        return TypedExpression.NamedFunctionBinding(postBindingType, functionRef, resolvedRef.entityRef, bindings, chosenParameters)
    }

    private fun getFinalParametersForBinding(functionType: UnvalidatedType.FunctionType, providedParameters: List<Type?>, bindingTypes: List<Type?>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, functionRef: EntityRef, expressionLocation: Location?): List<Type?>? {
        // Deal with inference first?
        // What's left after that?

        val chosenParameters = if (functionType.typeParameters.size == providedParameters.size) {
            providedParameters
        } else if (functionType.typeParameters.size < providedParameters.size) {
            errors.add(Issue("Too many type parameters were supplied for function binding for $functionRef", expressionLocation, IssueLevel.ERROR))
            return null
        } else {
            // Apply type parameter inference
            val inferenceSourcesByArgument = functionType.getTypeParameterInferenceSources()
            val explicitParametersIterator = providedParameters.iterator()
            val types = inferenceSourcesByArgument.map { inferenceSources ->
                val inferredType = inferenceSources.stream().map { it.findType(bindingTypes) }.filter { it != null }.findFirst()
                if (inferredType.isPresent()) {
                    inferredType.get()
                } else {
                    if (!explicitParametersIterator.hasNext()) {
                        errors.add(Issue("Not enough type parameters were supplied for function binding for ${functionRef}", expressionLocation, IssueLevel.ERROR))
                        return null
                    }
                    val chosenParameter = explicitParametersIterator.next()
                    chosenParameter ?: return null
                }
            }
            if (explicitParametersIterator.hasNext()) {
                errors.add(Issue("The function binding for $functionRef did not supply all type parameters, but supplied more than the appropriate number for type parameter inference", expressionLocation, IssueLevel.ERROR))
                return null
            }
            types
        }
        if (chosenParameters.size != functionType.typeParameters.size) {
            // TODO: Hopefully this is impossible to hit now
            fail("Referenced a function $functionRef with type parameters ${functionType.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
        }
        for ((typeParameter, chosenType) in functionType.typeParameters.zip(chosenParameters)) {
            if (chosenType != null) {
                validateTypeParameterChoice(typeParameter, chosenType, expressionLocation, typeInfo)
            }
        }
        return chosenParameters
    }

    private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
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
            is Validator.TypeInfo.Union -> {
                error("Currently we don't allow follows for unions")
            }
        }
    }

    // TODO: Disentangle the typeParameters and chosenTypes mess here
    /*
    This is called from:

    - parameterizeAndValidateSignature
    - validateFollowExpression
     */
    /**
     * Applies the chosen parameters to the function type, removing them from the function type's type parameters, and
     * then validates the resulting function type.
     *
     * So this is weird because we want to distinguish between the cases where the type involved here has explicitly
     * listed type parameters and where it doesn't.
     */
    private fun parameterizeAndValidateType(unvalidatedType: UnvalidatedType, typeParameters: List<Type.ParameterType>, chosenTypes: List<Type?>, typeInfo: Validator.AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): Type? {
        val type = validateType(unvalidatedType, typeInfo, typeParametersInScope + typeParameters.map(Type.ParameterType::parameter).associateBy(TypeParameter::name)) ?: return null

        if (typeParameters.size != chosenTypes.size) {
            error("Give me a better error message")
        }
        // TODO: Is this all that's needed to handle the case of null chosen types?
        val parameterMap = typeParameters.zip(chosenTypes).filter { it.second != null }.toMap().mapValues { it.value!! }

        return type.replacingParameters(parameterMap)
    }

    private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, typeParametersInScope, consumedThreadedVars, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType
        if (functionType == null) {
            errors.add(Issue("The expression $functionExpression is called like a function, but it has a non-function type ${functionExpression.type}", expression.functionExpression.location, IssueLevel.ERROR))
            return null
        }

        val chosenParameters = expression.chosenParameters.map { TODO() }

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

        return TypedExpression.ExpressionFunctionCall(functionType.outputType, functionExpression, arguments, chosenParameters)
    }

    private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
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
            errors.add(Issue("Entity $functionRef is not a function", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }
        if (expression.arguments.size != signature.argumentTypes.size) {
            errors.add(Issue("The function $functionRef expects ${signature.argumentTypes.size} arguments types (${signature.argumentTypes}), but ${expression.arguments.size} were given", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        //Ground the signature

        val fullTypeParameterCount = signature.typeParameters.size
        val requiredTypeParameterCount = signature.getFunctionType().getRequiredTypeParameterCount()
        val chosenParameters = if (expression.chosenParameters.size == fullTypeParameterCount) {
            expression.chosenParameters.map { chosenParameter -> validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null }
        } else if (expression.chosenParameters.size == requiredTypeParameterCount) {
            val inferenceSourcesByArgument = signature.getFunctionType().getTypeParameterInferenceSources()
            val explicitParametersIterator = expression.chosenParameters.iterator()
            val types = inferenceSourcesByArgument.map { inferenceSources ->
                if (inferenceSources.isEmpty()) {
                    val chosenParameter = explicitParametersIterator.next()
                    validateType(chosenParameter, typeInfo, typeParametersInScope) ?: return null
                } else {
                    // Since we know all our arguments are present, we can cheat a little and just take the first one
                    inferenceSources.stream().map { it.findType(argumentTypes) }.filter { it != null }.findFirst().get()
                }
            }
            if (explicitParametersIterator.hasNext()) {
                error("Validator internal logic for type parameter inference was invalid")
            }
            types
        } else {
            // TODO: Update issue message to account for multiple possible lengths
            errors.add(Issue("Expected ${signature.typeParameters.size} type parameters, but got ${expression.chosenParameters.size}", expression.functionRefLocation, IssueLevel.ERROR))
            return null
        }

        if (signature.typeParameters.size != chosenParameters.size) {
            error("Got the incorrect number of type parameters somehow: ${chosenParameters.size} instead of $fullTypeParameterCount or $requiredTypeParameterCount")
        }
        for ((typeParameter, chosenType) in signature.typeParameters.zip(chosenParameters)) {
            validateTypeParameterChoice(typeParameter, chosenType, expression.location, typeInfo)
        }


        val functionType = applyChosenParametersToFunctionType(signature.getFunctionType(), chosenParameters, typeInfo, typeParametersInScope) ?: return null
        if (argumentTypes != functionType.argTypes) {
            errors.add(Issue("The function $functionRef expects argument types ${functionType.argTypes}, but is given arguments with types $argumentTypes", expression.location, IssueLevel.ERROR))
        }

        return TypedExpression.NamedFunctionCall(functionType.outputType, functionRef, functionResolvedRef.entityRef, arguments, chosenParameters)
    }

    private fun validateTypeParameterChoice(typeParameter: TypeParameter, chosenType: Type, location: Location?, typeInfo: AllTypeInfo) {
        if (chosenType.isThreaded()) {
            errors.add(Issue("Threaded types cannot be used as parameters", location, IssueLevel.ERROR))
        }
        val typeClass = typeParameter.typeClass
        if (typeClass != null) {
            val unused: Any = when (typeClass) {
                TypeClass.Data -> {
                    if (!typeInfo.isDataType(chosenType)) {
                        errors.add(Issue("Type parameter ${typeParameter.name} requires a data type, but $chosenType is not a data type", location, IssueLevel.ERROR))
                    } else {}
                }
            }
        }
    }

    /**
     * Applies the chosen parameters to the function type, removing them from the function type's type parameters, and
     * then validates the resulting function type.
     */
    private fun applyChosenParametersToFunctionType(functionType: UnvalidatedType.FunctionType, chosenParameters: List<Type?>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): Type.FunctionType? {
        val parameterTypes = functionType.typeParameters.map(Type::ParameterType)
        return parameterizeAndValidateType(functionType, parameterTypes, chosenParameters, typeInfo, typeParametersInScope) as Type.FunctionType?
    }

    private fun validateLiteralExpression(expression: Expression.Literal, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): TypedExpression? {
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
    private fun getLiteralTypeChain(initialType: UnvalidatedType, literalLocation: Location?, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): List<Type>? {
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
                val memberType = validateType(unvalidatedMemberType, typeInfo, struct.typeParameters.associateBy(TypeParameter::name)) ?: return null
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

    private fun validateListLiteralExpression(expression: Expression.ListLiteral, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
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

    private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>, consumedThreadedVars: MutableSet<String>, containingFunctionId: EntityId): TypedExpression? {
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
        val members = validateMembers(struct, typeInfo, struct.typeParameters.associateBy(TypeParameter::name)) ?: return null

        val memberTypes = members.associate { member -> member.name to member.type }

        val fakeContainingFunctionId = EntityId(struct.id.namespacedName + "requires")
        val uncheckedRequires = struct.requires
        val requires = if (uncheckedRequires != null) {
            validateBlock(uncheckedRequires, memberTypes, typeInfo, struct.typeParameters.associateBy(TypeParameter::name), HashSet(), fakeContainingFunctionId) ?: return null
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



    private fun validateMembers(struct: UnvalidatedStruct, typeInfo: AllTypeInfo, typeParametersInScope: Map<String, TypeParameter>): List<Member>? {
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
        val methods = validateMethods(interfac.methods, typeInfo, interfac.typeParameters.associateBy(TypeParameter::name)) ?: return null
        return Interface(interfac.id, moduleId, interfac.typeParameters, methods, interfac.annotations)
    }

    private fun validateMethods(methods: List<UnvalidatedMethod>, typeInfo: AllTypeInfo, interfaceTypeParameters: Map<String, TypeParameter>): List<Method>? {
        // TODO: Do some actual validation of methods
        return methods.map { method ->
            val typeParametersVisibleToMethod = interfaceTypeParameters + method.typeParameters.associateBy(TypeParameter::name)
            val arguments = validateArguments(method.arguments, typeInfo, typeParametersVisibleToMethod) ?: return null
            val returnType = validateType(method.returnType, typeInfo, typeParametersVisibleToMethod) ?: return null
            Method(method.name, method.typeParameters, arguments, returnType)
        }
    }

    private fun validateUnions(unions: List<UnvalidatedUnion>, typeInfo: AllTypeInfo): Map<EntityId, Union> {
        val validatedUnions = HashMap<EntityId, Union>()
        for (union in unions) {
            val validatedUnion = validateUnion(union, typeInfo)
            if (validatedUnion != null) {
                validatedUnions.put(union.id, validatedUnion)
            }
        }
        return validatedUnions
    }

    private fun validateUnion(union: UnvalidatedUnion, typeInfo: AllTypeInfo): Union? {
        // TODO: Do some additional validation of unions (e.g. no duplicate option IDs)
        if (union.options.isEmpty()) {
            errors.add(Issue("A union must include at least one option", union.idLocation, IssueLevel.ERROR))
            return null
        }
        val options = validateOptions(union.options, typeInfo, union.typeParameters.associateBy(TypeParameter::name)) ?: return null
        return Union(union.id, moduleId, union.typeParameters, options, union.annotations)
    }

    private fun validateOptions(options: List<UnvalidatedOption>, typeInfo: AllTypeInfo, unionTypeParameters: Map<String, TypeParameter>): List<Option>? {
        return options.map { option ->
            val unvalidatedType = option.type
            val type = if (unvalidatedType == null) null else validateType(unvalidatedType, typeInfo, unionTypeParameters)
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

/**
 * This (somewhat) mimics the behavior of a Guava ListMultimap.
 */
internal fun <K, V> MutableMap<K, MutableList<V>>.multimapPut(key: K, value: V) {
    val existingListMaybe = this[key]
    if (existingListMaybe != null) {
        existingListMaybe.add(value)
        return
    }
    val newList = ArrayList<V>()
    newList.add(value)
    this.put(key, newList)
}
