package net.semlang.api

import java.util.*

data class RawContext(val functions: List<Function>, val structs: List<UnvalidatedStruct>, val interfaces: List<Interface>)

// Note: Absence of a module indicates a native function, struct, or interface.
data class FunctionWithModule(val function: ValidatedFunction, val module: ValidatedModule)
data class FunctionSignatureWithModule(val function: TypeSignature, val module: ValidatedModule?)
data class StructWithModule(val struct: Struct, val module: ValidatedModule?)
data class InterfaceWithModule(val interfac: Interface, val module: ValidatedModule?)

/**
 * Note: For modules other than our own, the EntityIds should be only those that are exported.
 */
// TODO: Currently this assumes the native module ID corresponds to our current set of native constructs.
// When we have some notion of backwards-compatibility for semlang native modules, this will no longer be
// the case.
//class TypeResolver(val nativeModuleId: ModuleId,
//                   val functionIds: Map<ModuleId, Set<EntityId>>,
//                   val structIds: Map<ModuleId, Set<EntityId>>,
//                   val interfaceIds: Map<ModuleId, Set<EntityId>>) {
//    companion object {
//        fun create(id: ModuleId,
//                   nativeModuleVersion: String,
//                   ownFunctions: Collection<EntityId>,
//                   ownStructs: Collection<EntityId>,
//                   ownInterfaces: Collection<EntityId>,
//                   upstreamModules: Collection<ValidatedModule>): TypeResolver {
//            val nativeModuleId = ModuleId(NATIVE_MODULE_GROUP, NATIVE_MODULE_NAME, nativeModuleVersion)
//
//            val functionIds = HashMap<ModuleId, MutableSet<EntityId>>()
//            functionIds.put(id, HashSet<EntityId>())
//            functionIds[id]!!.addAll(ownFunctions)
//
//            val structIds = HashMap<ModuleId, MutableSet<EntityId>>()
//            structIds.put(id, HashSet<EntityId>())
//            structIds[id]!!.addAll(ownStructs)
//
//            val interfaceIds = HashMap<ModuleId, MutableSet<EntityId>>()
//            interfaceIds.put(id, HashSet<EntityId>())
//            interfaceIds[id]!!.addAll(ownInterfaces)
//
//            upstreamModules.forEach { module ->
//                functionIds.put(module.id, HashSet<EntityId>())
//                functionIds[module.id]!!.addAll(module.getAllExportedFunctions().keys)
//                structIds.put(module.id, HashSet<EntityId>())
//                structIds[module.id]!!.addAll(module.getAllExportedStructs().keys)
//                interfaceIds.put(module.id, HashSet<EntityId>())
//                interfaceIds[module.id]!!.addAll(module.getAllExportedInterfaces().keys)
//            }
//            return TypeResolver(nativeModuleId, functionIds, structIds, interfaceIds)
//        }
//    }
//
//    fun resolveFunction(ref: EntityRef): ResolvedEntityRef? {
//        return resolveEntity(ref, getNativeFunctionDefinitions(), functionIds)
//    }
//    fun resolveStruct(ref: EntityRef): ResolvedEntityRef? {
//        System.out.println("Resolving structs for $ref")
//        return resolveEntity(ref, getNativeStructs(), structIds)
//    }
//    fun resolveInterface(ref: EntityRef): ResolvedEntityRef? {
//        return resolveEntity(ref, getNativeInterfaces(), interfaceIds)
//    }
//    fun <T> resolveEntity(ref: EntityRef,
//                          nativeEntitiesOfType: Map<EntityId, T>,
//                          entitiesOfType: Map<ModuleId, Set<EntityId>>): ResolvedEntityRef? {
//        val ref = resolveInternalRef(ref)
//        if (ref != null) {
//            if (isNativeModule(ref.module)) {
//                return if (nativeEntitiesOfType[ref.id] != null) {
//                    System.out.println("Native entities of type was non-null")
//                    ref
//                } else {
//                    null
//                }
//            }
//            val entitiesOfTypeInModule = entitiesOfType[ref.module]
//            if (entitiesOfTypeInModule != null
//                    && entitiesOfTypeInModule.contains(ref.id)) {
//                return ref
//            }
//        }
//        return null
//    }
//
//    // TODO: There is a version of this where this all happens during validation...
//    private fun resolveInternalRef(entityRef: EntityRef): ResolvedEntityRef? {
//        val containingModuleIds = getContainingModules(entityRef.id)
//        if (entityRef.moduleRef == null) {
//            return resolveToOnlyModule(containingModuleIds, entityRef)
//        } else if (entityRef.moduleRef.group == null) {
//            val filtered = containingModuleIds.filter { id -> id.module == entityRef.moduleRef.module }
//            return resolveToOnlyModule(filtered, entityRef)
//        } else if (entityRef.moduleRef.version == null) {
//            val filtered = containingModuleIds.filter { id -> id.module == entityRef.moduleRef.module
//                    && id.group == entityRef.moduleRef.group}
//            return resolveToOnlyModule(filtered, entityRef)
//        } else {
//            val filtered = containingModuleIds.filter { id -> id.module == entityRef.moduleRef.module
//                    && id.group == entityRef.moduleRef.group
//                    && id.version == entityRef.moduleRef.version}
//            return resolveToOnlyModule(filtered, entityRef)
//        }
//    }
//
//    // Note: The ID shouldn't be an adapter ID; instead, use the ID for the corresponding interface.
//    // TODO: What about native methods?
//    // TODO: Cache and/or precompute these
//    private fun getContainingModules(id: EntityId): Set<ModuleId> {
//        val containingModules = HashSet<ModuleId>()
//
//        // TODO: There's a slight error here with adapters... (test "Sequence.Adapter.Adapter" type reference?)
//        if (getNativeFunctionDefinitions()[id] != null) {
//            containingModules.add(nativeModuleId)
//        }
//        for (idsMap in listOf(functionIds, structIds, interfaceIds)) {
//            for ((moduleId, entityIds) in idsMap.entries) {
//                if (entityIds.contains(id)) {
//                    containingModules.add(moduleId)
//                }
//            }
//        }
//
//        return containingModules
//    }
//
//    private fun resolveToOnlyModule(containingModuleIds: Collection<ModuleId>, entityRef: EntityRef): ResolvedEntityRef? {
//        if (containingModuleIds.size == 1) {
//            return ResolvedEntityRef(containingModuleIds.single(), entityRef.id)
//        } else if (containingModuleIds.size == 0) {
//            return null
//        } else {
//            error("Expected only one module to have ID ${entityRef}, but found ${containingModuleIds.size}: $containingModuleIds")
//        }
//    }
//
//    fun resolveFunctionLike(ref: EntityRef): EntityResolution? {
//        // TODO: This seems like something we should figure out earlier...
////        val resolvedRef = resolveInternalRef(ref)
//        // Then figure out the type?
//    }
//}

class TypeResolver2(//val nativeModuleId: ModuleId,
                    private val idResolutions: Map<EntityId, Set<EntityResolution>>) {
    companion object {
        fun create(ownModuleId: ModuleId,
                   nativeModuleVersion: String, // TODO: This is unused
                   ownFunctions: Collection<EntityId>,
                   ownStructs: Collection<EntityId>,
                   ownInterfaces: Collection<EntityId>,
                   upstreamModules: Collection<ValidatedModule>): TypeResolver2 {
//            val nativeModuleId = ModuleId(NATIVE_MODULE_GROUP, NATIVE_MODULE_NAME, nativeModuleVersion)

            val idResolutions = HashMap<EntityId, MutableSet<EntityResolution>>()
            val add = fun(id: EntityId, moduleId: ModuleId, type: FunctionLikeType) {
                if (!idResolutions.containsKey(id)) {
                    idResolutions.put(id, HashSet<EntityResolution>(2))
                }
                idResolutions[id]!!.add(EntityResolution(ResolvedEntityRef(moduleId, id), type))
            }

            // TODO: Add natives
            // TODO: Add different things based on the native version in use
            getNativeFunctionOnlyDefinitions().keys.forEach { id ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.NATIVE_FUNCTION)
            }
            getNativeStructs().keys.forEach { id ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.STRUCT_CONSTRUCTOR)
            }
            getNativeInterfaces().keys.forEach { id ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.INSTANCE_CONSTRUCTOR)
                add(getAdapterIdForInterfaceId(id), CURRENT_NATIVE_MODULE_ID, FunctionLikeType.ADAPTER_CONSTRUCTOR)
            }

//            val functionIds = HashMap<ModuleId, MutableSet<EntityId>>()
//            functionIds.put(id, HashSet<EntityId>())
//            functionIds[id]!!.addAll(ownFunctions)
            ownFunctions.forEach { id ->
                add(id, ownModuleId, FunctionLikeType.FUNCTION)
            }

//            val structIds = HashMap<ModuleId, MutableSet<EntityId>>()
//            structIds.put(id, HashSet<EntityId>())
//            structIds[id]!!.addAll(ownStructs)
            ownStructs.forEach { id ->
                add(id, ownModuleId, FunctionLikeType.STRUCT_CONSTRUCTOR)
            }

//            val interfaceIds = HashMap<ModuleId, MutableSet<EntityId>>()
//            interfaceIds.put(id, HashSet<EntityId>())
//            interfaceIds[id]!!.addAll(ownInterfaces)
            ownInterfaces.forEach { id ->
                add(id, ownModuleId, FunctionLikeType.INSTANCE_CONSTRUCTOR)
                add(getAdapterIdForInterfaceId(id), ownModuleId, FunctionLikeType.ADAPTER_CONSTRUCTOR)
            }

            upstreamModules.forEach { module ->
                module.getAllExportedFunctions().keys.forEach { id ->
                    add(id, module.id, FunctionLikeType.FUNCTION)
                }
                module.getAllExportedStructs().keys.forEach { id ->
                    add(id, module.id, FunctionLikeType.STRUCT_CONSTRUCTOR)
                }
                module.getAllExportedInterfaces().keys.forEach { id ->
                    add(id, module.id, FunctionLikeType.INSTANCE_CONSTRUCTOR)
                    add(getAdapterIdForInterfaceId(id), module.id, FunctionLikeType.ADAPTER_CONSTRUCTOR)
                }
//                functionIds.put(module.id, HashSet<EntityId>())
//                functionIds[module.id]!!.addAll(module.getAllExportedFunctions().keys)
//                structIds.put(module.id, HashSet<EntityId>())
//                structIds[module.id]!!.addAll(module.getAllExportedStructs().keys)
//                interfaceIds.put(module.id, HashSet<EntityId>())
//                interfaceIds[module.id]!!.addAll(module.getAllExportedInterfaces().keys)
            }
            return TypeResolver2(idResolutions)
        }
    }

    fun resolve(ref: EntityRef): EntityResolution? {
        val allResolutions = idResolutions[ref.id]
        if (allResolutions == null) {
            return null
        }
        val filtered = filterByModuleRef(allResolutions, ref.moduleRef)
        if (filtered.size == 1) {
            return filtered.single()
        } else if (filtered.size == 0) {
            return null
        } else {
            error("Tried to resolve $ref, but there were multiple possible resolutions: $filtered")
        }
    }

    private fun filterByModuleRef(allResolutions: Set<EntityResolution>, moduleRef: ModuleRef?): Collection<EntityResolution> {
        if (moduleRef == null) {
            return allResolutions
        } else if (moduleRef.group == null) {
            return allResolutions.filter { resolution -> resolution.entityRef.module.module == moduleRef.module }
        } else if (moduleRef.version == null) {
            return allResolutions.filter { resolution -> resolution.entityRef.module.module == moduleRef.module
                                                         && resolution.entityRef.module.group == moduleRef.group }
        } else {
            return allResolutions.filter { resolution -> resolution.entityRef.module.module == moduleRef.module
                                                         && resolution.entityRef.module.group == moduleRef.group
                                                         && resolution.entityRef.module.version == moduleRef.version }
        }
    }
}
// TODO: Would storing or returning things in a non-map format improve performance?
// TODO: When we have re-exporting implemented, check somewhere that we don't export multiple refs with the same ID
class ValidatedModule private constructor(val id: ModuleId,
                                          val nativeModuleVersion: String,
                                          val ownFunctions: Map<EntityId, ValidatedFunction>,
                                          val exportedFunctions: Set<EntityId>,
                                          val ownStructs: Map<EntityId, Struct>,
                                          val exportedStructs: Set<EntityId>,
                                          val ownInterfaces: Map<EntityId, Interface>,
                                          val exportedInterfaces: Set<EntityId>,
                                          val upstreamModules: Map<ModuleId, ValidatedModule>) {
    init {
        if (isNativeModule(id)) {
            error("We should not be creating ValidatedModule objects for the native module")
        }
        upstreamModules.entries.forEach { (id, module) ->
            if (module.id != id) {
                error("Modules must be indexed by their ID, but a module with ID ${module.id} was indexed by $id")
            }
        }
    }
    private val resolver = TypeResolver2.create(id, nativeModuleVersion, ownFunctions.keys, ownStructs.keys, ownInterfaces.keys, upstreamModules.values)

    companion object {
        fun create(id: ModuleId,
                   nativeModuleVersion: String,
                   ownFunctions: Map<EntityId, ValidatedFunction>,
                   ownStructs: Map<EntityId, Struct>,
                   ownInterfaces: Map<EntityId, Interface>,
                   upstreamModules: Collection<ValidatedModule>): ValidatedModule {
            val exportedFunctions = findExported(ownFunctions.values)
            val exportedStructs = findExported(ownStructs.values)
            val exportedInterfaces = findExported(ownInterfaces.values)

            // TODO: We'll also need some notion of re-exporting imported things...
            return ValidatedModule(id, nativeModuleVersion,
                    ownFunctions, exportedFunctions,
                    ownStructs, exportedStructs,
                    ownInterfaces, exportedInterfaces,
                    upstreamModules.associateBy(ValidatedModule::id))
        }

        private fun findExported(values: Collection<TopLevelEntity>): Set<EntityId> {
            val exportedIds = HashSet<EntityId>()
            for (value in values) {
                if (hasExportedAnnotation(value)) {
                    exportedIds.add(value.id)
                }
            }
            return exportedIds
        }

        private fun hasExportedAnnotation(value: TopLevelEntity): Boolean {
            for (annotation in value.annotations) {
                if (annotation.name == "Exported") {
                    return true
                }
            }
            return false
        }
    }

    // TODO: There is a version of this where this all happens during validation...
//    private fun resolveInternalRef(entityRef: EntityRef): ResolvedEntityRef? {
//        val containingModuleIds = getContainingModules(entityRef.id)
//        if (entityRef.moduleRef == null) {
//            return resolveToOnlyModule(containingModuleIds, entityRef)
//        } else if (entityRef.moduleRef.group == null) {
//            val filtered = containingModuleIds.filter { id -> id.module == entityRef.moduleRef.module }
//            return resolveToOnlyModule(filtered, entityRef)
//        } else if (entityRef.moduleRef.version == null) {
//            val filtered = containingModuleIds.filter { id -> id.module == entityRef.moduleRef.module
//                    && id.group == entityRef.moduleRef.group}
//            return resolveToOnlyModule(filtered, entityRef)
//        } else {
//            val filtered = containingModuleIds.filter { id -> id.module == entityRef.moduleRef.module
//                    && id.group == entityRef.moduleRef.group
//                    && id.version == entityRef.moduleRef.version}
//            return resolveToOnlyModule(filtered, entityRef)
//        }
//    }

//    private fun resolveToOnlyModule(containingModuleIds: Collection<ModuleId>, entityRef: EntityRef): ResolvedEntityRef? {
//        if (containingModuleIds.size == 1) {
//            return ResolvedEntityRef(containingModuleIds.single(), entityRef.id)
//        } else if (containingModuleIds.size == 0) {
//            return null
//        } else {
//            error("Expected only one module to have ID ${entityRef}, but found ${containingModuleIds.size}: $containingModuleIds")
//        }
//    }
//
//    // Note: The ID shouldn't be an adapter ID; instead, use the ID for the corresponding interface.
//    private fun getContainingModules(id: EntityId): Set<ModuleId> {
//        val containingModules = HashSet<ModuleId>()
//        if (ownFunctions.containsKey(id) || ownStructs.containsKey(id) || ownInterfaces.containsKey(id)) {
//            containingModules.add(this.id)
//        }
//        upstreamModules.values.forEach { module ->
//            if (module.getExportedFunction(id) != null
//                    || module.getExportedStruct(id) != null
//                    || module.getExportedInterface(id) != null) {
//                containingModules.add(module.id)
//            }
//        }
//        return containingModules
//    }

    // TODO: We don't actually want to be returning a Function when this is native!
    fun getInternalFunction(functionRef: ResolvedEntityRef): FunctionWithModule {
        val moduleId = functionRef.module
        if (moduleId == this.id) {
            return FunctionWithModule(ownFunctions[functionRef.id] ?: error("Expected ${functionRef.id} to be a function, but it's not"), this)
        }

        if (moduleId == null) {
            // Native
        }

        val module = upstreamModules[moduleId] ?: error("Error in resolving $functionRef")
        return module.getExportedFunction(functionRef.id) ?: error("Resolution error")
    }

    fun getExportedFunction(functionId: EntityId): FunctionWithModule? {
        // TODO: This logic will have to change when we allow re-exporting.
        // (Currently, we can just assume that anything exported is in our own module.)
        if (exportedFunctions.contains(functionId)) {
            return getInternalFunction(ResolvedEntityRef(this.id, functionId))
        }
        return null
    }

    fun getInternalStruct(entityRef: ResolvedEntityRef): StructWithModule {
        val moduleId = entityRef.module
        if (moduleId == this.id) {
            return StructWithModule(ownStructs[entityRef.id] ?: error("Resolution error"), this)
        }
        if (isNativeModule(moduleId)) {
            return StructWithModule(getNativeStructs()[entityRef.id] ?: error("Resolution error"), null)
        }
        val module = upstreamModules[moduleId] ?: error("Resolution error")
        return module.getExportedStruct(entityRef.id) ?: error("Resolution error")
    }

    fun getExportedStruct(id: EntityId): StructWithModule? {
        // TODO: This logic will have to change when we allow re-exporting.
        // (Currently, we can just assume that anything exported is in our own module.)
        if (exportedStructs.contains(id)) {
            return getInternalStruct(ResolvedEntityRef(this.id, id))
        }
        return null
    }

    fun getInternalInterface(entityRef: ResolvedEntityRef): InterfaceWithModule {
        val moduleId = entityRef.module
        if (moduleId == this.id) {
            return InterfaceWithModule(ownInterfaces[entityRef.id] ?: error("Expected ${entityRef.id} to be an interface, but it's not"), this)
        }
        if (isNativeModule(moduleId)) {
            return InterfaceWithModule(getNativeInterfaces()[entityRef.id] ?: error("Resolution error"), null)
        }
        val module = upstreamModules[moduleId] ?: error("Error in resolving")
        return module.getExportedInterface(entityRef.id) ?: error("Error in resolving")
    }

    fun getExportedInterface(id: EntityId): InterfaceWithModule? {
        // TODO: This logic will have to change when we allow re-exporting.
        // (Currently, we can just assume that anything exported is in our own module.)
        if (exportedInterfaces.contains(id)) {
            return getInternalInterface(ResolvedEntityRef(this.id, id))
        }
        return null
    }

    fun getAllInternalInterfaces(): Map<EntityId, Interface> {
        // TODO: Consider caching this
        val allInterfaces = HashMap<EntityId, Interface>()
        allInterfaces.putAll(ownInterfaces)

        for (module in upstreamModules.values) {
            // TODO: Do we need to filter out conflicts? Pretty sure we do
            allInterfaces.putAll(module.getAllExportedInterfaces())
        }
        return allInterfaces
    }

    fun getAllExportedInterfaces(): Map<EntityId, Interface> {
        return exportedInterfaces.associate { id -> id to getExportedInterface(id)!!.interfac }
    }

    // TODO: Refactor
    fun getAllInternalStructs(): Map<EntityId, Struct> {
        // TODO: Consider caching this
        val allStructs = HashMap<EntityId, Struct>()
        allStructs.putAll(ownStructs)

        for (module in upstreamModules.values) {
            // TODO: Do we need to filter out conflicts? Pretty sure we do
            allStructs.putAll(module.getAllExportedStructs())
        }
        return allStructs
    }

    fun getAllExportedStructs(): Map<EntityId, Struct> {
        return exportedStructs.associate { id -> id to getExportedStruct(id)!!.struct }
    }

    // TODO: Refactor
    fun getAllInternalFunctions(): Map<EntityId, ValidatedFunction> {
        // TODO: Consider caching this
        val allFunctions = HashMap<EntityId, ValidatedFunction>()
        allFunctions.putAll(ownFunctions)

        for (module in upstreamModules.values) {
            // TODO: Do we need to filter out conflicts? Pretty sure we do
            allFunctions.putAll(module.getAllExportedFunctions())
        }
        return allFunctions
    }

    fun getAllExportedFunctions(): Map<EntityId, ValidatedFunction> {
        return exportedFunctions.associate { id -> id to getExportedFunction(id)!!.function }
    }

//    fun getAllInternalFunctionSignatures(): Map<EntityId, TypeSignature> {
//        val allFunctionSignatures = HashMap<EntityId, TypeSignature>()
//
//        allFunctionSignatures.putAll(getOwnFunctionSignatures())
//
//        for (module in upstreamModules.values) {
//            allFunctionSignatures.putAll(module.getAllExportedFunctionSignatures())
//        }
//        return allFunctionSignatures
//    }

    private fun getOwnFunctionSignatures(): Map<EntityId, TypeSignature> {
        return toFunctionSignatures(ownFunctions, ownStructs, ownInterfaces)
    }

//    private fun getAllExportedFunctionSignatures(): Map<EntityId, TypeSignature> {
//        return toFunctionSignatures(getAllExportedFunctions(), getAllExportedStructs(), getAllExportedInterfaces())
//    }

    private fun toFunctionSignatures(functions: Map<EntityId, ValidatedFunction>,
                                     structs: Map<EntityId, Struct>,
                                     interfaces: Map<EntityId, Interface>): Map<EntityId, TypeSignature> {
        val allSignatures = HashMap<EntityId, TypeSignature>()

        for (function in functions.values) {
            allSignatures.put(function.id, function.toTypeSignature())
        }

        for (struct in structs.values) {
            allSignatures.put(struct.id, struct.getConstructorSignature())
        }

        for (interfac in interfaces.values) {
            allSignatures.put(interfac.id, interfac.getInstanceConstructorSignature())
            allSignatures.put(interfac.adapterId, interfac.getAdapterConstructorSignature())
        }

        return allSignatures
    }

    fun getInternalInterfaceByAdapterId(adapterRef: ResolvedEntityRef): InterfaceWithModule {
        val interfaceId = getInterfaceIdForAdapterId(adapterRef.id) ?: error("Resolution error")
        val interfaceRef = ResolvedEntityRef(adapterRef.module, interfaceId)
        return getInternalInterface(interfaceRef)
    }

//    fun getInternalFunctionSignature(functionRef: EntityRef): FunctionSignatureWithModule? {
//        val function = getInternalFunctionSignatureForFunction(functionRef)
//        if (function != null) {
//            return FunctionSignatureWithModule(function.function, function.module)
//        }
//
//        val struct = getInternalStruct(functionRef)
//        if (struct != null) {
//            return FunctionSignatureWithModule(struct.struct.getConstructorSignature(), struct.module)
//        }
//
//        val interfac = getInternalInterface(functionRef)
//        if (interfac != null) {
//            return FunctionSignatureWithModule(interfac.interfac.getInstanceConstructorSignature(), interfac.module)
//        }
//
//        val adapter = getInternalInterfaceByAdapterId(functionRef)
//        if (adapter != null) {
//            return FunctionSignatureWithModule(adapter.interfac.getAdapterConstructorSignature(), adapter.module)
//        }
//        return null
//    }
//
//    private fun getInternalFunctionSignatureForFunction(functionRef: EntityRef): FunctionSignatureWithModule? {
//        val resolved = resolver.resolveFunction(functionRef)
//        if (resolved == null) {
//            return null
//        }
//        if (resolved.module == this.id) {
//            return FunctionSignatureWithModule((ownFunctions[resolved.id] ?: error("Expected ${resolved.id} to be a function, but it's not")).toTypeSignature(), this.id)
//        }
//
//        if (isNativeModule(resolved.module)) {
//            // TODO: Something we'll have to fix for native module back-compat
//            return FunctionSignatureWithModule(getNativeFunctionDefinitions()[resolved.id] ?: error("Error in resolving $resolved"),
//                    resolved.module)
//        }
//
//        val module = upstreamModules[resolved.module] ?: error("Error in resolving $resolved")
//        return module.getExportedFunction(functionRef.id)?.let {
//            FunctionSignatureWithModule(it.function.toTypeSignature(), it.module)
//        }
//    }

    fun resolve(ref: EntityRef): EntityResolution? {
        return resolver.resolve(ref)
    }

}

enum class FunctionLikeType {
    NATIVE_FUNCTION,
    FUNCTION,
    STRUCT_CONSTRUCTOR,
    INSTANCE_CONSTRUCTOR,
    ADAPTER_CONSTRUCTOR
}

data class EntityResolution(val entityRef: ResolvedEntityRef, val type: FunctionLikeType)
