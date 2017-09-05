package net.semlang.api

import java.util.HashMap

data class RawContext(val functions: List<Function>, val structs: List<UnvalidatedStruct>, val interfaces: List<Interface>)

// Note: Absence of a module indicates a native function, struct, or interface.
data class FunctionWithModule(val function: ValidatedFunction, val module: ValidatedModule)
data class FunctionSignatureWithModule(val function: TypeSignature, val module: ValidatedModule?)
data class StructWithModule(val struct: Struct, val module: ValidatedModule?)
data class InterfaceWithModule(val interfac: Interface, val module: ValidatedModule?)

/**
 * Note: For modules other than our own, the EntityIds should be only those that are exported.
 */
class TypeResolver(val functionIds: Map<ModuleId, Set<EntityId>>,
                   val structIds: Map<ModuleId, Set<EntityId>>,
                   val interfaceIds: Map<ModuleId, Set<EntityId>>) {
    companion object {
        fun create(id: ModuleId,
                   ownFunctions: Collection<EntityId>,
                   ownStructs: Collection<EntityId>,
                   ownInterfaces: Collection<EntityId>,
                   upstreamModules: Collection<ValidatedModule>): TypeResolver {
            val functionIds = HashMap<ModuleId, MutableSet<EntityId>>()
            functionIds.put(id, HashSet<EntityId>())
            functionIds[id]!!.addAll(ownFunctions)

            val structIds = HashMap<ModuleId, MutableSet<EntityId>>()
            structIds.put(id, HashSet<EntityId>())
            structIds[id]!!.addAll(ownStructs)

            val interfaceIds = HashMap<ModuleId, MutableSet<EntityId>>()
            interfaceIds.put(id, HashSet<EntityId>())
            interfaceIds[id]!!.addAll(ownInterfaces)

            upstreamModules.forEach { module ->
                functionIds.put(module.id, HashSet<EntityId>())
                functionIds[module.id]!!.addAll(module.getAllExportedFunctions().keys)
                structIds.put(module.id, HashSet<EntityId>())
                structIds[module.id]!!.addAll(module.getAllExportedStructs().keys)
                interfaceIds.put(module.id, HashSet<EntityId>())
                interfaceIds[module.id]!!.addAll(module.getAllExportedInterfaces().keys)
            }
            return TypeResolver(functionIds, structIds, interfaceIds)
        }
    }

    fun resolveFunction(ref: EntityRef): ResolvedEntityRef? {
        return resolveEntity(ref, getNativeFunctionDefinitions(), functionIds)
    }
    fun resolveStruct(ref: EntityRef): ResolvedEntityRef? {
        System.out.println("Resolving structs for $ref")
        return resolveEntity(ref, getNativeStructs(), structIds)
    }
    fun resolveInterface(ref: EntityRef): ResolvedEntityRef? {
        return resolveEntity(ref, getNativeInterfaces(), interfaceIds)
    }
    fun <T> resolveEntity(ref: EntityRef,
                          nativeEntitiesOfType: Map<EntityId, T>,
                          entitiesOfType: Map<ModuleId, Set<EntityId>>): ResolvedEntityRef? {
        val ref = resolveInternalRef(ref)
        if (ref != null) {
            if (ref.module == null) {
                return if (nativeEntitiesOfType[ref.id] != null) {
                    System.out.println("Native entities of type was non-null")
                    ref
                } else {
                    null
                }
            }
            val entitiesOfTypeInModule = entitiesOfType[ref.module]
            if (entitiesOfTypeInModule != null
                    && entitiesOfTypeInModule.contains(ref.id)) {
                return ref
            }
        }
        return null
    }

    // TODO: There is a version of this where this all happens during validation...
    private fun resolveInternalRef(entityRef: EntityRef): ResolvedEntityRef? {
        val containingModuleIds = getContainingModules(entityRef.id)
        if (entityRef.moduleRef == null) {
            return resolveToOnlyModule(containingModuleIds, entityRef)
        } else if (entityRef.moduleRef.group == null) {
            val filtered = containingModuleIds.filter { id -> id != null && id.module == entityRef.moduleRef.module }
            return resolveToOnlyModule(filtered, entityRef)
        } else if (entityRef.moduleRef.version == null) {
            val filtered = containingModuleIds.filter { id -> id != null && id.module == entityRef.moduleRef.module
                    && id.group == entityRef.moduleRef.group}
            return resolveToOnlyModule(filtered, entityRef)
        } else {
            val filtered = containingModuleIds.filter { id -> id != null && id.module == entityRef.moduleRef.module
                    && id.group == entityRef.moduleRef.group
                    && id.version == entityRef.moduleRef.version}
            return resolveToOnlyModule(filtered, entityRef)
        }
    }

    // Note: The ID shouldn't be an adapter ID; instead, use the ID for the corresponding interface.
    // TODO: What about native methods?
    private fun getContainingModules(id: EntityId): Set<ModuleId?> {
        val containingModules = HashSet<ModuleId?>()

        // TODO: There's a slight error here with adapters... (test "Sequence.Adapter.Adapter" type reference?)
        if (getNativeFunctionDefinitions()[id] != null) {
            containingModules.add(null)
        }
        for (idsMap in listOf(functionIds, structIds, interfaceIds)) {
            for ((moduleId, entityIds) in idsMap.entries) {
                if (entityIds.contains(id)) {
                    containingModules.add(moduleId)
                }
            }
        }

        return containingModules
    }

    private fun resolveToOnlyModule(containingModuleIds: Collection<ModuleId?>, entityRef: EntityRef): ResolvedEntityRef? {
        if (containingModuleIds.size == 1) {
            return ResolvedEntityRef(containingModuleIds.single(), entityRef.id)
        } else if (containingModuleIds.size == 0) {
            return null
        } else {
            error("Expected only one module to have ID ${entityRef}, but found ${containingModuleIds.size}: $containingModuleIds")
        }
    }
}

// TODO: Would storing or returning things in a non-map format improve performance?
// TODO: When we have re-exporting implemented, check somewhere that we don't export multiple refs with the same ID
class ValidatedModule private constructor(val id: ModuleId,
                                          val ownFunctions: Map<EntityId, ValidatedFunction>,
                                          val exportedFunctions: Set<EntityId>,
                                          val ownStructs: Map<EntityId, Struct>,
                                          val exportedStructs: Set<EntityId>,
                                          val ownInterfaces: Map<EntityId, Interface>,
                                          val exportedInterfaces: Set<EntityId>,
                                          val upstreamModules: Map<ModuleId, ValidatedModule>) {
    init {
        upstreamModules.entries.forEach { (id, module) ->
            if (module.id != id) {
                error("Modules must be indexed by their ID, but a module with ID ${module.id} was indexed by $id")
            }
        }
    }
    private val resolver = TypeResolver.create(id, ownFunctions.keys, ownStructs.keys, ownInterfaces.keys, upstreamModules.values)

    companion object {
        fun create(id: ModuleId,
                   ownFunctions: Map<EntityId, ValidatedFunction>,
                   ownStructs: Map<EntityId, Struct>,
                   ownInterfaces: Map<EntityId, Interface>,
                   upstreamModules: List<ValidatedModule>): ValidatedModule {
            val exportedFunctions = findExported(ownFunctions.values)
            val exportedStructs = findExported(ownStructs.values)
            val exportedInterfaces = findExported(ownInterfaces.values)

            // TODO: We'll also need some notion of re-exporting imported things...
            return ValidatedModule(id,
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

    fun getInternalFunction(functionRef: EntityRef): FunctionWithModule? {
        val resolved = resolver.resolveFunction(functionRef)
        if (resolved == null) {
            return null
        }
        if (resolved.module == this.id) {
            return FunctionWithModule(ownFunctions[resolved.id] ?: error("Expected ${resolved.id} to be a function, but it's not"), this)
        }

        if (resolved.module == null) {
            // Native
        }

        val module = upstreamModules[resolved.module] ?: error("Error in resolving $resolved")
        return module.getExportedFunction(functionRef.id)
    }

    fun getExportedFunction(functionId: EntityId): FunctionWithModule? {
        // TODO: This logic will have to change when we allow re-exporting.
        // (Currently, we can just assume that anything exported is in our own module.)
        if (exportedFunctions.contains(functionId)) {
            return getInternalFunction(EntityRef(this.id.asRef(), functionId))
        }
        return null
    }

    // TODO: Refactor common code; possibly have a single map with all IDs
    fun getInternalStruct(ref: EntityRef): StructWithModule? {
        val resolved = resolver.resolveStruct(ref)
        if (resolved == null) {
            return null
        }
        if (resolved.module == this.id) {
            return StructWithModule(ownStructs[resolved.id] ?: error("Expected ${resolved.id} to be a struct, but it's not"), this)
        }

        if (resolved.module == null) {
            return StructWithModule(getNativeStructs()[resolved.id] ?: error("Expected ${resolved.id} to be a native struct, but it's not"), null)
        }

        val module = upstreamModules[resolved.module] ?: error("Error in resolving $resolved")
        return module.getExportedStruct(ref.id)
    }

    fun getExportedStruct(id: EntityId): StructWithModule? {
        // TODO: This logic will have to change when we allow re-exporting.
        // (Currently, we can just assume that anything exported is in our own module.)
        if (exportedStructs.contains(id)) {
            return getInternalStruct(EntityRef(this.id.asRef(), id))
        }
        return null
    }

    fun getInternalInterface(ref: EntityRef): InterfaceWithModule? {
        val resolved = resolver.resolveInterface(ref)
        if (resolved == null) {
            return null
        }
        if (resolved.module == this.id) {
            return InterfaceWithModule(ownInterfaces[resolved.id] ?: error("Expected ${resolved.id} to be an interface, but it's not"), this)
        }

        val module = upstreamModules[resolved.module] ?: error("Error in resolving")
        return module.getExportedInterface(ref.id)
    }

    fun getExportedInterface(id: EntityId): InterfaceWithModule? {
        // TODO: This logic will have to change when we allow re-exporting.
        // (Currently, we can just assume that anything exported is in our own module.)
        if (exportedInterfaces.contains(id)) {
            return getInternalInterface(EntityRef(this.id.asRef(), id))
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

    fun getAllExportedFunctions(): Map<EntityId, ValidatedFunction> {
        return exportedFunctions.associate { id -> id to getExportedFunction(id)!!.function }
    }

    fun getAllInternalFunctionSignatures(): Map<EntityId, TypeSignature> {
        val allFunctionSignatures = HashMap<EntityId, TypeSignature>()

        allFunctionSignatures.putAll(getOwnFunctionSignatures())

        for (module in upstreamModules.values) {
            allFunctionSignatures.putAll(module.getAllExportedFunctionSignatures())
        }
        return allFunctionSignatures
    }

    fun getOwnFunctionSignatures(): Map<EntityId, TypeSignature> {
        return toFunctionSignatures(ownFunctions, ownStructs, ownInterfaces)
    }

    fun getAllExportedFunctionSignatures(): Map<EntityId, TypeSignature> {
        return toFunctionSignatures(getAllExportedFunctions(), getAllExportedStructs(), getAllExportedInterfaces())
    }

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

    fun getInternalInterfaceByAdapterId(functionRef: EntityRef): InterfaceWithModule? {
        val interfaceRef = getInterfaceRefForAdapterRef(functionRef)
        if (interfaceRef == null) {
            return null
        }
        return getInternalInterface(interfaceRef)
    }

    fun getInternalFunctionSignature(functionRef: EntityRef): FunctionSignatureWithModule? {
        val function = getInternalFunction(functionRef)
        if (function != null) {
            return FunctionSignatureWithModule(function.function.toTypeSignature(), function.module)
        }

        val struct = getInternalStruct(functionRef)
        if (struct != null) {
            return FunctionSignatureWithModule(struct.struct.getConstructorSignature(), struct.module)
        }

        val interfac = getInternalInterface(functionRef)
        if (interfac != null) {
            return FunctionSignatureWithModule(interfac.interfac.getInstanceConstructorSignature(), interfac.module)
        }

        val adapter = getInternalInterfaceByAdapterId(functionRef)
        if (adapter != null) {
            return FunctionSignatureWithModule(adapter.interfac.getAdapterConstructorSignature(), adapter.module)
        }
        return null
    }
}
