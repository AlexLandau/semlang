package net.semlang.validator

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.transforms.invalidate
import net.semlang.transforms.invalidateFunctionType
import java.util.*

class TypesInfo(
        private val upstreamResolver: EntityResolver,
        val localTypes: Map<EntityId, TypeInfo>,
        val duplicateLocalTypeIds: Set<EntityId>,
        val localFunctions: Map<EntityId, FunctionInfo>,
        val duplicateLocalFunctionIds: Set<EntityId>,
        val upstreamTypes: Map<ResolvedEntityRef, TypeInfo>,
        val upstreamFunctions: Map<ResolvedEntityRef, FunctionInfo>,
        private val moduleId: ModuleUniqueId
) {
    fun getTypeInfo(ref: EntityRef): TypeInfo? {
        if (isCompatibleWithThisModule(ref)) {
            val localResult = localTypes[ref.id]
            if (localResult != null) {
                return localResult
            }
        }
        val resolved = upstreamResolver.resolve(ref)
        if (resolved != null) {
            return upstreamTypes[resolved.entityRef]
        }
        return null
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

    fun getFunctionInfo(ref: EntityRef): FunctionInfo? {
        if (isCompatibleWithThisModule(ref)) {
            val localResult = localFunctions[ref.id]
            if (localResult != null) {
                return localResult
            }
        }
        val resolved = upstreamResolver.resolve(ref)
        if (resolved != null) {
            return upstreamFunctions[resolved.entityRef]
        }
        return null
    }

}
data class FunctionInfo(val resolvedRef: ResolvedEntityRef, val type: UnvalidatedType.FunctionType, val idLocation: Location?)
sealed class TypeInfo {
    abstract val resolvedRef: ResolvedEntityRef
    abstract val idLocation: Location?
    abstract val isReference: Boolean
    data class Struct(override val resolvedRef: ResolvedEntityRef, val typeParameters: List<TypeParameter>, val memberTypes: Map<String, UnvalidatedType>, val usesRequires: Boolean, override val isReference: Boolean, override val idLocation: Location? = null): TypeInfo()
    data class Interface(override val resolvedRef: ResolvedEntityRef, val typeParameters: List<TypeParameter>, val methodTypes: Map<String, UnvalidatedType.FunctionType>, override val isReference: Boolean, override val idLocation: Location? = null): TypeInfo()
    data class Union(override val resolvedRef: ResolvedEntityRef, val typeParameters: List<TypeParameter>, val optionTypes: Map<String, Optional<UnvalidatedType>>, override val isReference: Boolean, override val idLocation: Location? = null): TypeInfo()
    data class OpaqueType(override val resolvedRef: ResolvedEntityRef, val typeParameters: List<TypeParameter>, override val isReference: Boolean, override val idLocation: Location? = null): TypeInfo()
}

fun getTypesInfo(context: RawContext, moduleId: ModuleUniqueId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>, moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>, recordIssue: (Issue) -> Unit): TypesInfo {
    return TypeInfoCollector(context, moduleId, nativeModuleVersion, upstreamModules, moduleVersionMappings, recordIssue).apply()
}

private class TypeInfoCollector(
        val context: RawContext,
        val moduleId: ModuleUniqueId,
        val nativeModuleVersion: String,
        val upstreamModules: List<ValidatedModule>,
        val moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>,
        val recordIssue: (Issue) -> Unit
) {
    val localFunctionsMultimap = HashMap<EntityId, MutableList<FunctionInfo>>()
    val localTypesMultimap = HashMap<EntityId, MutableList<TypeInfo>>()
    val upstreamResolver = EntityResolver.create(
            moduleId,
            nativeModuleVersion,
            listOf(),
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


        addLocalStructs()

        addLocalInterfaces()

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

        val upstreamTypes = getUpstreamTypes(nativeModuleVersion, upstreamModules)
        val upstreamFunctions = getUpstreamFunctions(nativeModuleVersion, upstreamModules)

        return TypesInfo(upstreamResolver, uniqueLocalTypes, duplicateLocalTypeIds, uniqueLocalFunctions, duplicateLocalFunctionIds,
                upstreamTypes, upstreamFunctions, moduleId)
    }

    private fun addLocalUnions() {
        for (union in context.unions) {
            val id = union.id

            localTypesMultimap.multimapPut(id, getLocalTypeInfo(union))
            for (option in union.options) {
                val optionId = EntityId(union.id.namespacedName + option.name)
                val functionType = union.getConstructorSignature(option).getFunctionType()
                val resolvedRef = ResolvedEntityRef(moduleId, optionId)
                localFunctionsMultimap.multimapPut(optionId, FunctionInfo(resolvedRef, functionType, union.idLocation))
            }

            val whenId = EntityId(union.id.namespacedName + "when")
            val functionType = union.getWhenSignature().getFunctionType()
            val resolvedRef = ResolvedEntityRef(moduleId, whenId)
            localFunctionsMultimap.multimapPut(whenId, FunctionInfo(resolvedRef, functionType, union.idLocation))
        }
    }

    private fun addLocalInterfaces() {
        for (interfac in context.interfaces) {
            localTypesMultimap.multimapPut(interfac.id, getLocalTypeInfo(interfac))
            val instanceConstructorType = interfac.getInstanceConstructorSignature().getFunctionType()
            val instanceResolvedRef = ResolvedEntityRef(moduleId, interfac.id)
            localFunctionsMultimap.multimapPut(interfac.id, FunctionInfo(instanceResolvedRef, instanceConstructorType, interfac.idLocation))
            val adapterFunctionType = interfac.getAdapterFunctionSignature().getFunctionType()
            val adapterResolvedRef = ResolvedEntityRef(moduleId, interfac.adapterId)
            localFunctionsMultimap.multimapPut(interfac.adapterId, FunctionInfo(adapterResolvedRef, adapterFunctionType, interfac.idLocation))
        }
    }

    private fun addLocalStructs() {
        for (struct in context.structs) {
            val id = struct.id
            localTypesMultimap.multimapPut(id, getLocalTypeInfo(struct))
            // Don't pass in the type parameters because they're already part of the function type
            val constructorType = struct.getConstructorSignature().getFunctionType()
            val resolvedRef = ResolvedEntityRef(moduleId, id)
            localFunctionsMultimap.multimapPut(id, FunctionInfo(resolvedRef, constructorType, struct.idLocation))
        }
    }

    private fun getLocalTypeInfo(struct: UnvalidatedStruct): TypeInfo.Struct {
        val resolvedRef = ResolvedEntityRef(moduleId, struct.id)
        return TypeInfo.Struct(resolvedRef, struct.typeParameters, getUnvalidatedMemberTypeMap(struct.members), struct.requires != null, false, struct.idLocation)
    }
    private fun getTypeInfo(struct: Struct, idLocation: Location?): TypeInfo.Struct {
        return TypeInfo.Struct(struct.resolvedRef, struct.typeParameters, getMemberTypeMap(struct.members), struct.requires != null, false, idLocation)
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

    private fun getLocalTypeInfo(interfac: UnvalidatedInterface): TypeInfo.Interface {
        val resolvedRef = ResolvedEntityRef(moduleId, interfac.id)
        return TypeInfo.Interface(resolvedRef, interfac.typeParameters, getUnvalidatedMethodTypeMap(interfac.methods), false, interfac.idLocation)
    }
    private fun getTypeInfo(interfac: Interface, idLocation: Location?): TypeInfo.Interface {
        return TypeInfo.Interface(interfac.resolvedRef, interfac.typeParameters, getMethodTypeMap(interfac.methods), false, idLocation)
    }
    private fun getUnvalidatedMethodTypeMap(methods: List<UnvalidatedMethod>): Map<String, UnvalidatedType.FunctionType> {
        val typeMap = HashMap<String, UnvalidatedType.FunctionType>()
        for (method in methods) {
            if (typeMap.containsKey(method.name)) {
                // TODO: Test this case
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


    private fun getLocalTypeInfo(union: UnvalidatedUnion): TypeInfo.Union {
        val resolvedRef = ResolvedEntityRef(moduleId, union.id)
        return TypeInfo.Union(resolvedRef, union.typeParameters, getUnvalidatedUnionTypeMap(union.options), false, union.idLocation)
    }
    private fun getTypeInfo(union: Union, idLocation: Location?): TypeInfo.Union {
        return TypeInfo.Union(union.resolvedRef, union.typeParameters, getUnionTypeMap(union.options), false, idLocation)
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

    private fun addLocalFunctions() {
        for (function in context.functions) {
            localFunctionsMultimap.multimapPut(function.id, getLocalFunctionInfo(function))
        }
    }

    private fun getLocalFunctionInfo(function: Function): FunctionInfo {
        val resolvedRef = ResolvedEntityRef(moduleId, function.id)
        val type = function.getType()
        return FunctionInfo(resolvedRef, type, function.idLocation)
    }

    private fun pseudoValidateFunctionType(type: UnvalidatedType.FunctionType): Type.FunctionType {
        return pseudoValidateTypeInternal(type, listOf()) as Type.FunctionType
    }
    private fun pseudoValidateTypeInternal(type: UnvalidatedType, internalTypeParameters: List<String>): Type {
        return when (type) {
            // Note: These errors will be caught later; just do something reasonable
            is UnvalidatedType.Invalid.ReferenceInteger -> Type.INTEGER
            is UnvalidatedType.Invalid.ReferenceBoolean -> Type.BOOLEAN
            is UnvalidatedType.Integer -> Type.INTEGER
            is UnvalidatedType.Boolean -> Type.BOOLEAN
            is UnvalidatedType.List -> {
                val parameter = pseudoValidateTypeInternal(type.parameter, internalTypeParameters)
                Type.List(parameter)
            }
            is UnvalidatedType.Maybe -> {
                val parameter = pseudoValidateTypeInternal(type.parameter, internalTypeParameters)
                Type.Maybe(parameter)
            }
            is UnvalidatedType.FunctionType -> {
                val newInternalTypeParameters = type.typeParameters.map { it.name } + internalTypeParameters

                val argTypes = type.argTypes.map { pseudoValidateTypeInternal(it, newInternalTypeParameters) }
                val outputType = pseudoValidateTypeInternal(type.outputType, newInternalTypeParameters)

                Type.FunctionType.create(type.typeParameters, argTypes, outputType)
            }
            is UnvalidatedType.NamedType -> {
                val ref = type.ref
                if (ref.moduleRef == null
                        && ref.id.namespacedName.size == 1) {
                    val index = internalTypeParameters.indexOf(ref.id.namespacedName[0])
                    if (index >= 0) {
                        return Type.InternalParameterType(index)
                    }
                }

                val parameters = type.parameters.map { pseudoValidateTypeInternal(it, internalTypeParameters) }

                val resolved = upstreamResolver.resolve(ref)
                val resolvedRef = if (resolved != null) {
                    resolved.entityRef
                } else {
                    ResolvedEntityRef(moduleId, ref.id)
                }

                // TODO: Should this also be capable of returning Type.ParameterType?
                return Type.NamedType(resolvedRef, ref, type.isReference, parameters)
            }
        }
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
            for (interfac in module.getAllExportedInterfaces().values) {
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamTypes.put(ref, getTypeInfo(interfac, null))
            }
        }
        return upstreamTypes
    }

    private fun getTypeInfo(opaqueType: OpaqueType, idLocation: Location?): TypeInfo {
        return TypeInfo.OpaqueType(opaqueType.resolvedRef, opaqueType.typeParameters, opaqueType.isReference, idLocation)
    }

    private fun getUpstreamFunctions(nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, FunctionInfo> {
        val upstreamFunctions = HashMap<ResolvedEntityRef, FunctionInfo>()

        // (We don't need the idPosition for functions in upstream modules)
        val functionInfo = fun(ref: ResolvedEntityRef, signature: FunctionSignature): FunctionInfo {
            return FunctionInfo(ref, invalidateFunctionType(signature.getFunctionType()), null)
        }

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        for (struct in getNativeStructs().values) {
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamFunctions.put(ref, functionInfo(ref, struct.getConstructorSignature()))
        }
        for (interfac in getNativeInterfaces().values) {
            val ref = ResolvedEntityRef(nativeModuleId, interfac.id)
            upstreamFunctions.put(ref, functionInfo(ref, interfac.getInstanceConstructorSignature()))
            val adapterRef = ResolvedEntityRef(nativeModuleId, interfac.adapterId)
            upstreamFunctions.put(adapterRef, functionInfo(adapterRef, interfac.getAdapterFunctionSignature()))
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
            for (interfac in module.getAllExportedInterfaces().values) {
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamFunctions.put(ref, functionInfo(ref, interfac.getInstanceConstructorSignature()))
                val adapterRef = ResolvedEntityRef(module.id, interfac.adapterId)
                upstreamFunctions.put(adapterRef, functionInfo(adapterRef, interfac.getAdapterFunctionSignature()))
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
                    is TypeInfo.Interface -> typeInfo.methodTypes.isEmpty()
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
            if (type.isReference) {
                false
            } else {
                val typeInfo = getTypeInfo(type.ref)!!
                when (typeInfo) {
                    is TypeInfo.Struct -> {
                        // TODO: Need to handle recursive references; perhaps precomputing this across the whole type graph would be preferable (or do lazy computation and caching...)
                        // TODO: What does the validation of a Type actually do? And can we do it in its own stage?
                        typeInfo.memberTypes.values.all { isDataType(it) }
                    }
                    is TypeInfo.Interface -> typeInfo.methodTypes.isEmpty()
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
