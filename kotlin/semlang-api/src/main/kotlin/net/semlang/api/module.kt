package net.semlang.api

import java.util.*
import java.util.regex.Pattern

private val LEGAL_MODULE_PATTERN = Pattern.compile("[0-9a-zA-Z]+([_.-][0-9a-zA-Z]+)*")

data class ModuleId(val group: String, val module: String, val version: String) {
    init {
        // TODO: Consider if these restrictions can/should be relaxed
        for ((string, stringType) in listOf(group to "group",
                module to "name",
                version to "version"))
            if (!LEGAL_MODULE_PATTERN.matcher(string).matches()) {
                // TODO: Update explanation
                throw IllegalArgumentException("Illegal character in module $stringType '$string'; only letters, numbers, dots, hyphens, and underscores are allowed.")
            }
    }

    fun asRef(): ModuleRef {
        return ModuleRef(group, module, version)
    }
}

data class ModuleRef(val group: String?, val module: String, val version: String?) {
    init {
        if (group == null && version != null) {
            error("Version may not be set unless group is also set")
        }
    }
    override fun toString(): String {
        if (version != null) {
            return "$group:$module:$version"
        } else if (group != null) {
            return "$group:$module"
        } else {
            return module
        }
    }
}

data class RawContext(val functions: List<Function>, val structs: List<UnvalidatedStruct>, val interfaces: List<UnvalidatedInterface>)

// Note: Absence of a module indicates a native function, struct, or interface.
data class FunctionWithModule(val function: ValidatedFunction, val module: ValidatedModule)
data class FunctionSignatureWithModule(val function: TypeSignature, val module: ValidatedModule?)
data class StructWithModule(val struct: Struct, val module: ValidatedModule?)
data class InterfaceWithModule(val interfac: Interface, val module: ValidatedModule?)

class EntityResolver(private val idResolutions: Map<EntityId, Set<EntityResolution>>) {
    companion object {
        fun create(ownModuleId: ModuleId,
                   nativeModuleVersion: String, // TODO: This is unused
                   ownFunctions: Collection<EntityId>,
                   ownStructs: Collection<EntityId>,
                   ownInterfaces: Collection<EntityId>,
                   upstreamModules: Collection<ValidatedModule>): EntityResolver {

            val idResolutions = HashMap<EntityId, MutableSet<EntityResolution>>()
            val add = fun(id: EntityId, moduleId: ModuleId, type: FunctionLikeType) {
                if (!idResolutions.containsKey(id)) {
                    idResolutions.put(id, HashSet<EntityResolution>(2))
                }
                idResolutions[id]!!.add(EntityResolution(ResolvedEntityRef(moduleId, id), type))
            }

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

            ownFunctions.forEach { id ->
                add(id, ownModuleId, FunctionLikeType.FUNCTION)
            }

            ownStructs.forEach { id ->
                add(id, ownModuleId, FunctionLikeType.STRUCT_CONSTRUCTOR)
            }

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
            }
            return EntityResolver(idResolutions)
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
    private val resolver = EntityResolver.create(id, nativeModuleVersion, ownFunctions.keys, ownStructs.keys, ownInterfaces.keys, upstreamModules.values)

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

    fun getInternalFunction(functionRef: ResolvedEntityRef): FunctionWithModule {
        val moduleId = functionRef.module
        if (moduleId == this.id) {
            return FunctionWithModule(ownFunctions[functionRef.id] ?: error("Expected ${functionRef.id} to be a function, but it's not"), this)
        }

        if (isNativeModule(moduleId)) {
            error("Can't get Function objects for native functions")
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

    private fun toFunctionSignatures(functions: Map<EntityId, ValidatedFunction>,
                                     structs: Map<EntityId, Struct>,
                                     interfaces: Map<EntityId, Interface>): Map<EntityId, TypeSignature> {
        val allSignatures = HashMap<EntityId, TypeSignature>()

        for (function in functions.values) {
            allSignatures.put(function.id, function.getTypeSignature())
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