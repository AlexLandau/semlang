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
    fun getResolvedTypeInfo(ref: EntityRef): ResolvedTypeInfo? {
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
    fun getTypeInfo(ref: EntityRef): TypeInfo? {
        val resolvedInfo = getResolvedTypeInfo(ref)
        if (resolvedInfo == null) {
            return null
        }
        return resolvedInfo.info
    }

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

    fun getResolvedFunctionInfo(ref: EntityRef): ResolvedFunctionInfo? {
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
    fun getFunctionInfo(ref: EntityRef): FunctionInfo? {
        val resolvedInfo = getResolvedFunctionInfo(ref)
        if (resolvedInfo == null) {
            return null
        }
        return resolvedInfo.info
    }

}

data class FunctionInfo(val id: EntityId, val type: UnvalidatedType.FunctionType, val idLocation: Location?)
sealed class TypeInfo {
    abstract val id: EntityId
    abstract val idLocation: Location?
    abstract val isReference: Boolean
    data class Struct(override val id: EntityId, val typeParameters: List<TypeParameter>, val memberTypes: Map<String, UnvalidatedType>, val usesRequires: Boolean, override val isReference: Boolean, override val idLocation: Location?): TypeInfo()
    data class Union(override val id: EntityId, val typeParameters: List<TypeParameter>, val optionTypes: Map<String, Optional<UnvalidatedType>>, override val isReference: Boolean, override val idLocation: Location?): TypeInfo()
    data class OpaqueType(override val id: EntityId, val typeParameters: List<TypeParameter>, override val isReference: Boolean, override val idLocation: Location?): TypeInfo()
}
data class ResolvedFunctionInfo(val resolvedRef: ResolvedEntityRef, val info: FunctionInfo)
data class ResolvedTypeInfo(val resolvedRef: ResolvedEntityRef, val info: TypeInfo)

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

        val addDuplicateIdError = fun(id: EntityId, idLocation: Location?) { recordIssue(Issue("Duplicate ID ${id}", idLocation, IssueLevel.ERROR)) }

        val duplicateLocalTypeIds = HashSet<EntityId>()
        val uniqueLocalTypes = java.util.HashMap<EntityId, TypeInfo>()
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
        val uniqueLocalTypes = java.util.HashMap<EntityId, ResolvedTypeInfo>()
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

    private fun addLocalTypes() {
        for (type in summary.localTypes.values) {
            val resolvedRef = ResolvedEntityRef(moduleId, type.id)
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
        return ResolvedTypeInfo(struct.resolvedRef,
            TypeInfo.Struct(struct.id, struct.typeParameters, getMemberTypeMap(struct.members), struct.requires != null, false, idLocation)
        )
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

    private fun getTypeInfo(union: Union, idLocation: Location?): ResolvedTypeInfo {
        return ResolvedTypeInfo(union.resolvedRef,
            TypeInfo.Union(union.id, union.typeParameters, getUnionTypeMap(union.options), false, idLocation)
        )
    }

    private fun getUnionTypeMap(options: List<Option>): Map<String, Optional<UnvalidatedType>> {
        val typeMap = HashMap<String, Optional<UnvalidatedType>>()
        for (option in options) {
            if (typeMap.containsKey(option.name)) {
                // TODO: Test this case
                error("Duplicate option name ${option.name}")
            } else {
                typeMap.put(option.name, Optional.ofNullable(option.type?.let(::invalidate)))
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
        return ResolvedTypeInfo(opaqueType.resolvedRef,
            TypeInfo.OpaqueType(opaqueType.id, opaqueType.typeParameters, opaqueType.isReference, idLocation)
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
            if (type.isReference()) {
                false
            } else {
                val typeInfo = getTypeInfo(type.originalRef)!!
                when (typeInfo) {
                    is TypeInfo.Struct -> {
                        // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
                        // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
                        typeInfo.memberTypes.values.all { isDataType(it) }
                    }
                    is TypeInfo.Union -> {
                        // TODO: Need to handle recursive references here, too
                        typeInfo.optionTypes.values.all { !it.isPresent || isDataType(it.get()) }
                    }
                    is TypeInfo.OpaqueType -> false
                }
            }
        }
        is Type.InternalParameterType -> TODO()
    }
}
// TODO: We shouldn't have two functions doing this on Types and UnvalidatedTypes
private fun TypesInfo.isDataType(type: UnvalidatedType): Boolean {
    return when (type) {
        is UnvalidatedType.Integer -> true
        is UnvalidatedType.Boolean -> true
        is UnvalidatedType.List -> isDataType(type.parameter)
        is UnvalidatedType.Maybe -> isDataType(type.parameter)
        is UnvalidatedType.FunctionType -> false
        is UnvalidatedType.NamedType -> {
            // TODO: We might want some caching here
            if (type.isReference()) {
                false
            } else {
                val typeInfo = getTypeInfo(type.ref)!!
                when (typeInfo) {
                    is TypeInfo.Struct -> {
                        // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
                        // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
                        typeInfo.memberTypes.values.all { isDataType(it) }
                    }
                    is TypeInfo.Union -> {
                        // TODO: Need to handle recursive references here, too
                        typeInfo.optionTypes.values.all { !it.isPresent || isDataType(it.get()) }
                    }
                    is TypeInfo.OpaqueType -> false
                }
            }
        }
        is UnvalidatedType.Invalid.ReferenceInteger -> false
        is UnvalidatedType.Invalid.ReferenceBoolean -> false
    }
}
