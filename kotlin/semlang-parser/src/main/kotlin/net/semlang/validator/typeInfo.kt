package net.semlang.validator

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.api.parser.Issue
import net.semlang.api.parser.IssueLevel
import net.semlang.api.parser.Location
import net.semlang.transforms.invalidate
import net.semlang.transforms.invalidateFunctionType
import java.util.*

class TypesSummary(
    val localTypes: Map<EntityId, TypeInfo>,
    val localFunctions: Map<EntityId, FunctionInfo>,
    val duplicateTypeIds: Set<EntityId>,
    val duplicateFunctionIds: Set<EntityId>
)

// TODO: Test the case where different files have overlapping entity IDs, which we'd need to catch here and handle more nicely
fun combineTypesSummaries(summaries: Collection<TypesSummary>): TypesSummary {
    val allTypes = HashMap<EntityId, TypeInfo>()
    val allFunctions = HashMap<EntityId, FunctionInfo>()
    val duplicateTypeIds = HashSet<EntityId>()
    val duplicateFunctionIds = HashSet<EntityId>()
    for (summary in summaries) {
        for ((id, type) in summary.localTypes) {
            if (allTypes.containsKey(id)) {
                duplicateTypeIds.add(id)
            } else {
                allTypes[id] = type
            }
        }
        for ((id, fn) in summary.localFunctions) {
            if (allFunctions.containsKey(id)) {
                duplicateFunctionIds.add(id)
            } else {
                allFunctions[id] = fn
            }
        }
    }
    return TypesSummary(allTypes, allFunctions, duplicateTypeIds, duplicateFunctionIds)
}

class TypesInfo(
    private val upstreamResolver: EntityResolver,
    val localTypes: Map<EntityId, ResolvedTypeInfo>,
    val duplicateLocalTypeIds: Set<EntityId>,
    val localFunctions: Map<EntityId, ResolvedFunctionInfo>,
    val duplicateLocalFunctionIds: Set<EntityId>,
    val upstreamTypes: Map<ResolvedEntityRef, ResolvedTypeInfo>,
    val upstreamFunctions: Map<ResolvedEntityRef, ResolvedFunctionInfo>,
    private val moduleId: ModuleUniqueId
) {
    fun getTypeInfo(ref: EntityRef): ResolvedTypeInfo? {
        if (isCompatibleWithThisModule(ref)) {
            val localResult = localTypes[ref.id]
            if (localResult != null) {
                return localResult
            }
        }
        val resolved = upstreamResolver.resolve(ref, ResolutionType.Type)
        if (resolved != null) {
            return upstreamTypes[resolved.entityRef]
        }
        return null
    }
//    fun getTypeInfo(ref: EntityRef): TypeInfo? {
//        val resolvedInfo = getResolvedTypeInfo(ref)
//        if (resolvedInfo == null) {
//            return null
//        }
//        return resolvedInfo.info
//    }

    private fun isCompatibleWithThisModule(ref: EntityRef): Boolean {
        val moduleRef = ref.moduleRef
        if (moduleRef == null) {
            return true
        }
        // TODO: Possibly also allow a "self" version here, though it seems unnecessary... Maybe assume it's some other
        // version if a version is specified? (As-is, specifying our own version can't possibly work)
        if (moduleRef.module != moduleId.name.module
                || (moduleRef.group != null && moduleRef.group != moduleId.name.group)
                || (moduleRef.version != null && moduleRef.version != moduleId.fake0Version)) {
            return false
        }
        return true
    }

    fun getFunctionInfo(ref: EntityRef): ResolvedFunctionInfo? {
        if (isCompatibleWithThisModule(ref)) {
            val localResult = localFunctions[ref.id]
            if (localResult != null) {
                return localResult
            }
        }
        val resolved = upstreamResolver.resolve(ref, ResolutionType.Function)
        if (resolved != null) {
            return upstreamFunctions[resolved.entityRef]
        }
        return null
    }
//    fun getFunctionInfo(ref: EntityRef): FunctionInfo? {
//        val resolvedInfo = getResolvedFunctionInfo(ref)
//        if (resolvedInfo == null) {
//            return null
//        }
//        return resolvedInfo.info
//    }

}

// TODO: Figure out how/when to validate these types (and how to report errors)
/*

1) Once we have all the (unvalidated) type info from everywhere, we could do a single pass to determine which types make
sense and which don't. It's worth pointing out that this determination of whether types are valid or unambiguous depends
on which module you're in... but then, so does the resolver that the type info is building.

 */
data class FunctionInfo(val id: EntityId, val type: UnvalidatedType.FunctionType, val idLocation: Location?)
sealed class TypeInfo {
    abstract val id: EntityId
    abstract val idLocation: Location?
    abstract val isReference: Boolean
    data class Struct(override val id: EntityId, val typeParameters: List<TypeParameter>, val memberTypes: Map<String, UnvalidatedType>, val usesRequires: Boolean, override val isReference: Boolean, override val idLocation: Location?): TypeInfo()
    data class Union(override val id: EntityId, val typeParameters: List<TypeParameter>, val optionTypes: Map<String, Optional<UnvalidatedType>>, override val isReference: Boolean, override val idLocation: Location?): TypeInfo()
    data class OpaqueType(override val id: EntityId, val typeParameters: List<TypeParameter>, override val isReference: Boolean, override val idLocation: Location?): TypeInfo()
}
data class ResolvedFunctionInfo(
    val resolvedRef: ResolvedEntityRef,
    val type: ResolvedType.Function
)
sealed class ResolvedTypeInfo {
    abstract val resolvedRef: ResolvedEntityRef
    abstract val isReference: Boolean
    data class Struct(override val resolvedRef: ResolvedEntityRef, override val isReference: Boolean, val typeParameters: List<TypeParameter>, val memberTypes: Map<String, ResolvedType>, val usesRequires: Boolean): ResolvedTypeInfo()
    data class Union(override val resolvedRef: ResolvedEntityRef, override val isReference: Boolean, val typeParameters: List<TypeParameter>, val optionTypes: Map<String, Optional<ResolvedType>>): ResolvedTypeInfo()
    data class OpaqueType(override val resolvedRef: ResolvedEntityRef, override val isReference: Boolean, val typeParameters: List<TypeParameter>): ResolvedTypeInfo()
}


fun getTypesInfo(context: RawContext, moduleId: ModuleUniqueId, upstreamModules: List<ValidatedModule>, moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>, recordIssue: (Issue) -> Unit): TypesInfo {
    val typesSummary = getTypesSummary(context, recordIssue)
    return getTypesInfoFromSummary(typesSummary, moduleId, upstreamModules, moduleVersionMappings, recordIssue)
}

fun getTypesInfoFromSummary(summary: TypesSummary, moduleId: ModuleUniqueId, upstreamModules: List<ValidatedModule>, moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>, recordIssue: (Issue) -> Unit): TypesInfo {
    return TypesSummaryToInfoConverter(summary, moduleId, upstreamModules, moduleVersionMappings, recordIssue).apply()
}

fun getTypesSummary(context: RawContext, recordIssue: (Issue) -> Unit): TypesSummary {
    return TypesSummaryCollector(context, recordIssue).apply()
}

private class TypesSummaryCollector(
    val context: RawContext,
    val recordIssue: (Issue) -> Unit
) {
    val localFunctionsMultimap = HashMap<EntityId, MutableList<FunctionInfo>>()
    val localTypesMultimap = HashMap<EntityId, MutableList<TypeInfo>>()

    fun apply(): TypesSummary {
        /*
         * Some notes about how we deal with error cases here:
         * - Name collisions involving other modules (which are assumed valid at this point) aren't a problem at this
         *   level. Instead, there will be an error found at the location of the reference, when the reference is
         *   ambiguous.
         * - Name collisions within the module may occur either for both types and functions, or for functions only.
         * - Collisions should be noted in both locations. References to these types/functions (when they resolve to the
         *   current module) will then be their own errors.
         */


        addLocalStructs()

        addLocalFunctions()

        addLocalUnions()

        val addDuplicateIdError = fun(id: EntityId, idLocation: Location?) { recordIssue(Issue("Duplicate ID $id", idLocation, IssueLevel.ERROR)) }

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
        val uniqueLocalFunctions = java.util.HashMap<EntityId, FunctionInfo>()
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

        return TypesSummary(uniqueLocalTypes, uniqueLocalFunctions, duplicateLocalTypeIds, duplicateLocalFunctionIds)
    }

    private fun addLocalUnions() {
        for (union in context.unions) {
            val id = union.id

            localTypesMultimap.multimapPut(id, getLocalTypeInfo(union))
            for (option in union.options) {
                val optionId = EntityId(union.id.namespacedName + option.name)
                val functionType = union.getConstructorSignature(option).getFunctionType()
                localFunctionsMultimap.multimapPut(optionId, FunctionInfo(optionId, functionType, union.idLocation))
            }

            val whenId = EntityId(union.id.namespacedName + "when")
            val functionType = union.getWhenSignature().getFunctionType()
            localFunctionsMultimap.multimapPut(whenId, FunctionInfo(whenId, functionType, union.idLocation))
        }
    }

    private fun addLocalStructs() {
        for (struct in context.structs) {
            val id = struct.id
            localTypesMultimap.multimapPut(id, getLocalTypeInfo(struct))
            // Don't pass in the type parameters because they're already part of the function type
            val constructorType = struct.getConstructorSignature().getFunctionType()
            localFunctionsMultimap.multimapPut(id, FunctionInfo(id, constructorType, struct.idLocation))
        }
    }

    private fun getLocalTypeInfo(struct: UnvalidatedStruct): TypeInfo.Struct {
        return TypeInfo.Struct(struct.id, struct.typeParameters, getUnvalidatedMemberTypeMap(struct.members), struct.requires != null, false, struct.idLocation)
    }

    private fun getUnvalidatedMemberTypeMap(members: List<UnvalidatedMember>): Map<String, UnvalidatedType> {
        val typeMap = HashMap<String, UnvalidatedType>()
        for (member in members) {
            if (typeMap.containsKey(member.name)) {
                // This will be reported as an error later
            } else {
                typeMap.put(member.name, member.type)
            }
        }
        return typeMap
    }


    private fun getLocalTypeInfo(union: UnvalidatedUnion): TypeInfo.Union {
        return TypeInfo.Union(union.id, union.typeParameters, getUnvalidatedUnionTypeMap(union.options), false, union.idLocation)
    }

    private fun getUnvalidatedUnionTypeMap(options: List<UnvalidatedOption>): Map<String, Optional<UnvalidatedType>> {
        val typeMap = HashMap<String, Optional<UnvalidatedType>>()
        for (option in options) {
            if (typeMap.containsKey(option.name)) {
                // TODO: Test this case
                error("Duplicate option name ${option.name}")
            } else {
                typeMap.put(option.name, Optional.ofNullable(option.type))
            }
        }
        return typeMap
    }

    private fun addLocalFunctions() {
        for (function in context.functions) {
            localFunctionsMultimap.multimapPut(function.id, getLocalFunctionInfo(function))
        }
    }

    private fun getLocalFunctionInfo(function: Function): FunctionInfo {
        val type = function.getType()
        return FunctionInfo(function.id, type, function.idLocation)
    }

}

private class TypesSummaryToInfoConverter(
    val summary: TypesSummary,
    val moduleId: ModuleUniqueId,
    val upstreamModules: List<ValidatedModule>,
    val moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>,
    val recordIssue: (Issue) -> Unit
) {
    val localFunctionsMultimap = HashMap<EntityId, MutableList<ResolvedFunctionInfo>>()
    val localTypesMultimap = HashMap<EntityId, MutableList<ResolvedTypeInfo>>()
    val upstreamResolver = EntityResolver.create(
        moduleId,
        listOf(),
        listOf(),
        mapOf(),
        upstreamModules,
        moduleVersionMappings
    )

    fun apply(): TypesInfo {
        /*
         * Some notes about how we deal with error cases here:
         * - Name collisions involving other modules (which are assumed valid at this point) aren't a problem at this
         *   level. Instead, there will be an error found at the location of the reference, when the reference is
         *   ambiguous.
         * - Name collisions within the module may occur either for both types and functions, or for functions only.
         * - Collisions should be noted in both locations. References to these types/functions (when they resolve to the
         *   current module) will then be their own errors.
         */

        addLocalTypes()
        addLocalFunctions()

        val addDuplicateIdError = fun(id: EntityId, idLocation: Location?) { recordIssue(Issue("Duplicate ID ${id}", idLocation, IssueLevel.ERROR)) }

        val duplicateLocalTypeIds = HashSet<EntityId>()
        val uniqueLocalTypes = HashMap<EntityId, ResolvedTypeInfo>()
        for ((id, typeInfoList) in localTypesMultimap.entries) {
            if (typeInfoList.size > 1) {
                for (typeInfo in typeInfoList) {
                    addDuplicateIdError(id, typeInfo.info.idLocation)
                }
                duplicateLocalTypeIds.add(id)
            } else {
                uniqueLocalTypes.put(id, typeInfoList[0])
            }
        }

        val duplicateLocalFunctionIds = HashSet<EntityId>()
        val uniqueLocalFunctions = HashMap<EntityId, ResolvedFunctionInfo>()
        for ((id, functionInfoList) in localFunctionsMultimap.entries) {
            if (functionInfoList.size > 1) {
                for (functionInfo in functionInfoList) {
                    addDuplicateIdError(id, functionInfo.info.idLocation)
                }
                duplicateLocalFunctionIds.add(id)
            } else {
                uniqueLocalFunctions.put(id, functionInfoList[0])
            }
        }

        val upstreamTypes = getUpstreamTypes(upstreamModules)
        val upstreamFunctions = getUpstreamFunctions(upstreamModules)

        return TypesInfo(upstreamResolver, uniqueLocalTypes, duplicateLocalTypeIds, uniqueLocalFunctions, duplicateLocalFunctionIds,
            upstreamTypes, upstreamFunctions, moduleId)
    }

//    fun validate(typeLabel: UnvalidatedTypeLabel): Type {
//        val type = typeLabel.type
//        when (type) {
//            is UnvalidatedType.FunctionType -> {
//
//            }
//            is UnvalidatedType.NamedType -> {
//                if (isCompatibleWithThisModule(type.ref) && localTypesMultimap.containsKey(type.ref.id)) {
//                    val localTypesInfo = localTypesMultimap[type.ref.id]!!
//                }
//                val resolution = upstreamResolver.resolve(type.ref, ResolutionType.Type)
//                if (resolution == null) {
//                    TODO()
//                } else {
//                    val parameters = type.parameters.zip(typeLabel.location.parameterLocations).map { validate(UnvalidatedTypeLabel(it.first,
//                        it.second)) }
//                    Type.NamedType(resolution.entityRef, type.ref, resolution.isReference, parameters)
//                }
//            }
//        }
//    }

    private fun validate(typeLabel: UnvalidatedTypeLabel): Type? {
        return validateType(typeLabel.type, typeLabel.location, listOf())
    }
    private fun validateType(type: UnvalidatedType, typeLocation: TypeLocation, internalParameters: List<String>): Type? {
        return when (type) {
            is UnvalidatedType.FunctionType -> {
                val newInternalParameters = ArrayList<String>()
                // Add the new parameters to the front of the list
                for (typeParameter in type.typeParameters) {
                    newInternalParameters.add(typeParameter.name)
                }
                newInternalParameters.addAll(internalParameters)

                val argTypes = type.argTypes.zip(typeLocation.argLocations).map { (argType, argLocation) ->
                    validateType(argType, argLocation, newInternalParameters) ?: return null
                }
                val outputType = validateType(type.outputType, typeLocation.outputLocation!!, newInternalParameters) ?: return null
                Type.FunctionType.create(type.isReference(), type.typeParameters, argTypes, outputType)
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
//                    val externalParameterType = typeParametersInScope[typeName]
//                    if (externalParameterType != null) {
//                        return Type.ParameterType(externalParameterType)
//                    }
                }

                val typeInfo: TypeInfo? = if (isCompatibleWithThisModule(type.ref) && summary.localTypes.containsKey(type.ref.id)) {
                    summary.localTypes[type.ref.id]!!

                } else {
                    val resolution = upstreamResolver.resolve(type.ref, ResolutionType.Type)
                    if (resolution == null) {
                        null
                    } else {
//                        val parameters = type.parameters.zip(typeLabel.location.parameterLocations).map { validate(UnvalidatedTypeLabel(it.first,
//                            it.second)) }
//                        Type.NamedType(resolution.entityRef, type.ref, resolution.isReference, parameters)
                        // TODO: All this can be done way more efficiently, I bet (and I probably need to shift containingModules)
                        var foundTypeInfo: TypeInfo? = null
                        for (module in upstreamModules) {
                            if (module.id == resolution.entityRef.module) {
                                // TODO: Get this working, I guess
//                                val struct = module.getExportedStruct(resolution.entityRef.id)
//                                if (struct != null) {
//                                    foundTypeInfo = this.getTypeInfo(struct.struct, null)
//                                }
                            }
                        }
                        foundTypeInfo
                    }
                }

//                val typeInfo = typesInfo.getTypeInfo(type.ref)

                if (typeInfo == null) {
                    recordIssue(Issue("Unresolved type reference: ${type.ref}", typeLocation.location, IssueLevel.ERROR))
                    return null
                }
                val shouldBeReference = typeInfo.isReference

                if (shouldBeReference && !type.isReference()) {
                    recordIssue(Issue("Type $type is a reference type and should be marked as such with '&'", typeLocation.location, IssueLevel.ERROR))
                    return null
                }
                if (type.isReference() && !shouldBeReference) {
                    recordIssue(Issue("Type $type is not a reference type and should not be marked with '&'", typeLocation.location, IssueLevel.ERROR))
                    return null
                }
                val parameters = type.parameters.zip(typeLocation.parameterLocations).map { (parameter, parameterLocation) ->
                    validateType(parameter, parameterLocation, internalParameters) ?: return null
                }
                Type.NamedType(typeInfo.resolvedRef, type.ref, type.isReference(), parameters)
            }
        }
    }

    // TODO: Reconcile with this method in the other location
    private fun isCompatibleWithThisModule(ref: EntityRef): Boolean {
        val moduleRef = ref.moduleRef
        if (moduleRef == null) {
            return true
        }
        // TODO: Possibly also allow a "self" version here, though it seems unnecessary... Maybe assume it's some other
        // version if a version is specified? (As-is, specifying our own version can't possibly work)
        if (moduleRef.module != moduleId.name.module
            || (moduleRef.group != null && moduleRef.group != moduleId.name.group)
            || (moduleRef.version != null && moduleRef.version != moduleId.fake0Version)) {
            return false
        }
        return true
    }

    private fun addLocalTypes() {
        for (type in summary.localTypes.values) {
            val resolvedRef = ResolvedEntityRef(moduleId, type.id)
            when (type) {
                is TypeInfo.Struct -> {
                    // TODO: If we have a struct with a nonexistent type, where do we report that error?
                    val validatedMembers = type.memberTypes.mapValues { validate(it.value) }
                }
                is TypeInfo.Union -> TODO()
                is TypeInfo.OpaqueType -> TODO()
            }
            localTypesMultimap.multimapPut(type.id, ResolvedTypeInfo(resolvedRef, type))
        }
    }

    private fun addLocalFunctions() {
        for (function in summary.localFunctions.values) {
            val resolvedRef = ResolvedEntityRef(moduleId, function.id)
            localFunctionsMultimap.multimapPut(function.id, ResolvedFunctionInfo(resolvedRef, function))
        }
    }

    private fun getTypeInfo(struct: Struct, idLocation: Location?): ResolvedTypeInfo {
        return ResolvedTypeInfo.Struct(struct.resolvedRef,
            false,
            struct.typeParameters,
            getMemberTypeMap(struct.members),
            struct.requires != null
//            TypeInfo.Struct(struct.id, struct.typeParameters, getMemberTypeMap(struct.members), struct.requires != null, false, idLocation)
        )
    }
    private fun getMemberTypeMap(members: List<Member>): Map<String, Type> {
        val typeMap = HashMap<String, Type>()
        for (member in members) {
            if (typeMap.containsKey(member.name)) {
                error("Duplicate member name ${member.name}")
            } else {
                typeMap.put(member.name, member.type)
            }
        }
        return typeMap
    }

    private fun getTypeInfo(union: Union, idLocation: Location?): ResolvedTypeInfo {
        return ResolvedTypeInfo.Union(union.resolvedRef,
            false,
            union.typeParameters,
            getUnionTypeMap(union.options)
        )
    }

    private fun getUnionTypeMap(options: List<Option>): Map<String, Optional<Type>> {
        val typeMap = HashMap<String, Optional<Type>>()
        for (option in options) {
            if (typeMap.containsKey(option.name)) {
                // TODO: Test this case
                error("Duplicate option name ${option.name}")
            } else {
                typeMap.put(option.name, Optional.ofNullable(option.type))
            }
        }
        return typeMap
    }

    private fun getUpstreamTypes(upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, ResolvedTypeInfo> {
        val upstreamTypes = HashMap<ResolvedEntityRef, ResolvedTypeInfo>()

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        for (struct in getNativeStructs().values) {
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamTypes.put(ref, getTypeInfo(struct, null))
        }
        for (union in getNativeUnions().values) {
            val ref = ResolvedEntityRef(nativeModuleId, union.id)
            upstreamTypes.put(ref, getTypeInfo(union, null))
        }
        for (opaqueType in getNativeOpaqueTypes().values) {
            val ref = opaqueType.resolvedRef
            upstreamTypes.put(ref, getTypeInfo(opaqueType, null))
        }


        for (module in upstreamModules) {
            for (struct in module.getAllExportedStructs().values) {
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamTypes.put(ref, getTypeInfo(struct, null))
            }
        }
        return upstreamTypes
    }

    private fun getTypeInfo(opaqueType: OpaqueType, idLocation: Location?): ResolvedTypeInfo {
        return ResolvedTypeInfo.OpaqueType(
            opaqueType.resolvedRef,
            opaqueType.isReference,
            opaqueType.typeParameters
//            TypeInfo.OpaqueType(opaqueType.id, opaqueType.typeParameters, opaqueType.isReference, idLocation)
        )
    }

    private fun getUpstreamFunctions(upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, ResolvedFunctionInfo> {
        val upstreamFunctions = HashMap<ResolvedEntityRef, ResolvedFunctionInfo>()

        // (We don't need the idPosition for functions in upstream modules)
        val functionInfo = fun(ref: ResolvedEntityRef, signature: FunctionSignature): ResolvedFunctionInfo {
            return ResolvedFunctionInfo(ref, FunctionInfo(signature.id, invalidateFunctionType(signature.getFunctionType()), null))
        }

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        for (struct in getNativeStructs().values) {
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamFunctions.put(ref, functionInfo(ref, struct.getConstructorSignature()))
        }
        for (function in getNativeFunctionOnlyDefinitions().values) {
            val ref = ResolvedEntityRef(nativeModuleId, function.id)
            upstreamFunctions.put(ref, functionInfo(ref, function))
        }

        for (module in upstreamModules) {
            for (struct in module.getAllExportedStructs().values) {
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamFunctions.put(ref, functionInfo(ref, struct.getConstructorSignature()))
            }
            for (function in module.getAllExportedFunctions().values) {
                val ref = ResolvedEntityRef(module.id, function.id)
                val signature = FunctionSignature.create(function.id, function.arguments.map {it.type}, function.returnType, function.typeParameters)
                upstreamFunctions.put(ref, functionInfo(ref, signature))
            }
        }
        return upstreamFunctions
    }
}


fun TypesInfo.isDataType(type: Type): Boolean {
    return when (type) {
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
            if (type.isReference()) {
                false
            } else {
                val typeInfo = getTypeInfo(type.originalRef)!!
                when (typeInfo) {
                    is ResolvedTypeInfo.Struct -> {
                        // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
                        // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
                        typeInfo.memberTypes.values.all { isDataType(it) }
                    }
                    is ResolvedTypeInfo.Union -> {
                        // TODO: Need to handle recursive references here, too
                        typeInfo.optionTypes.values.all { !it.isPresent || isDataType(it.get()) }
                    }
                    is ResolvedTypeInfo.OpaqueType -> {
                        isNativeModule(type.ref.module) &&
                                (type.ref.id == NativeOpaqueType.BOOLEAN.id ||
                                        type.ref.id == NativeOpaqueType.INTEGER.id ||
                                        (type.ref.id == NativeOpaqueType.LIST.id && isDataType(type.parameters[0])) ||
                                        (type.ref.id == NativeOpaqueType.MAYBE.id && isDataType(type.parameters[0])))
                    }
                }
            }
        }
        is Type.InternalParameterType -> TODO()
    }
}
// TODO: We shouldn't have two functions doing this on Types and UnvalidatedTypes
//private fun TypesInfo.isDataType(type: UnvalidatedType): Boolean {
//    return when (type) {
//        is UnvalidatedType.FunctionType -> false
//        is UnvalidatedType.NamedType -> {
//            // TODO: We might want some caching here
//            if (type.isReference()) {
//                false
//            } else {
//                val typeInfo = getTypeInfo(type.ref)!!
//                when (typeInfo) {
//                    is TypeInfo.Struct -> {
//                        // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
//                        // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
//                        typeInfo.memberTypes.values.all { isDataType(it) }
//                    }
//                    is TypeInfo.Union -> {
//                        // TODO: Need to handle recursive references here, too
//                        typeInfo.optionTypes.values.all { !it.isPresent || isDataType(it.get()) }
//                    }
//                    is TypeInfo.OpaqueType -> {
//                        // TODO: This is incorrect (should check the module)
//                        // TODO: Maybe check all parameters unconditionally?
//                        type.ref.id == NativeOpaqueType.BOOLEAN.id ||
//                                type.ref.id == NativeOpaqueType.INTEGER.id ||
//                                (type.ref.id == NativeOpaqueType.LIST.id && isDataType(type.parameters[0])) ||
//                                (type.ref.id == NativeOpaqueType.MAYBE.id && isDataType(type.parameters[0]))
//                    }
//                }
//            }
//        }
//    }
//}

data class TypesMetadata(
    val typeChains: Map<ResolvedEntityRef, TypeChain>
)
data class TypeChain(val originalType: Type.NamedType, val typeChainLinks: List<TypeChainLink>) {
    fun getTypesList(): List<Type> {
        return listOf(originalType) + typeChainLinks.map { it.type }
    }
}
// TODO: Figure out if we end up using the name or not
// Not sure if UnvalidatedType is right here
data class TypeChainLink(val name: String, val type: Type)
fun getTypesMetadata(typeInfo: TypesInfo): TypesMetadata {
    class MetadataCollector {
        val typeChains = HashMap<ResolvedEntityRef, TypeChain>()
        val cyclicReferences = HashSet<ResolvedEntityRef>()

        fun collect(): TypesMetadata {
            for (localType in typeInfo.localTypes.values) {
                evaluateAndCollect(localType)
            }
            for (upstreamType in typeInfo.upstreamTypes.values) {
                evaluateAndCollect(upstreamType)
            }
            return TypesMetadata(typeChains)
        }

        private fun evaluateAndCollect(type: ResolvedTypeInfo) {
            when (type) {
                is ResolvedTypeInfo.Struct -> {
                    computeTypeChain(type.resolvedRef, type)
                }
                is ResolvedTypeInfo.Union -> { /* Do nothing */ }
                is ResolvedTypeInfo.OpaqueType -> { /* Do nothing */ }
            }
        }

        // TODO: Reuse already-computed information more
        // TODO: Support parameterized types
        private fun computeTypeChain(resolvedRef: ResolvedEntityRef, info: ResolvedTypeInfo.Struct) {
            val typeChain = ArrayList<TypeChainLink>()
            val refsSoFar = HashSet<ResolvedEntityRef>()
            var curRef = resolvedRef
            var curInfo = info
            while (true) {
                refsSoFar.add(curRef)
                if (curInfo.memberTypes.size != 1) {
                    break
                }

                val (memberName, memberType) = curInfo.memberTypes.entries.single()
                typeChain.add(TypeChainLink(memberName, memberType))
                if (memberType !is Type.NamedType) {
                    break
                }
                // TODO: Parameterized types will make things kind of hard here (i.e. Foo<T> is a List<T>, or a T)
                // Get some new type info for the new thing

                val memberInfo = typeInfo.getTypeInfo(memberType.ref)
                if (memberInfo == null) {
                    // Unknown type
                    // TODO: Record an error
                    return
                }
                if (memberInfo !is ResolvedTypeInfo.Struct) {
                    break
                }
                if (refsSoFar.contains(memberInfo.resolvedRef)) {
                    // Cycle of references
                    // TODO: Better separation of handling this case, probably
                    cyclicReferences.add(memberInfo.resolvedRef)
                    return
                }
                curRef = memberInfo.resolvedRef
                curInfo = memberInfo
            }
            // TODO: Type parameters are discarded here, probably wrong
            typeChains[resolvedRef] = TypeChain(Type.NamedType(resolvedRef, resolvedRef.toUnresolvedRef(), info.isReference, listOf()), typeChain)
        }
    }
    return MetadataCollector().collect()
}
