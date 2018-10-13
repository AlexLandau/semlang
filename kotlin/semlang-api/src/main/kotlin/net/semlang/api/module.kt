package net.semlang.api

import java.util.*
import java.util.regex.Pattern

private val LEGAL_MODULE_PATTERN = Pattern.compile("[0-9a-zA-Z]+([_.-][0-9a-zA-Z]+)*")

data class ModuleName(val group: String, val module: String) {
    init {
        if (!LEGAL_MODULE_PATTERN.matcher(group).matches()) {
            // TODO: Update explanation
            throw IllegalArgumentException("Illegal character in module group '$group'; only letters, numbers, dots, hyphens, and underscores are allowed.")
        }
        if (!LEGAL_MODULE_PATTERN.matcher(module).matches()) {
            // TODO: Update explanation
            throw IllegalArgumentException("Illegal character in module name '$module'; only letters, numbers, dots, hyphens, and underscores are allowed.")
        }
    }

    override fun toString(): String {
        return "$group:$module"
    }
}

// TODO: Rename back to ModuleId when all references are fixed
data class ModuleNonUniqueId(val name: ModuleName, val versionProtocol: String, val version: String) {
    companion object {
        fun fromStringTriple(group: String, module: String, version: String): ModuleNonUniqueId {
            val name = ModuleName(group, module)
            val (versionProtocol, bareVersion) = version.split(".", limit = 2)
            return ModuleNonUniqueId(name, versionProtocol, bareVersion)
        }
    }
}

/**
 * Note: The fake0Version may be a different kind of version for the native module.
 */
data class ModuleUniqueId(val name: ModuleName, val fake0Version: String) {
    companion object {
        val UNIQUE_VERSION_SCHEME_PREFIX = "fake0:"
    }
    fun asRef(): ModuleRef {
        return ModuleRef(name.group, name.module, UNIQUE_VERSION_SCHEME_PREFIX + fake0Version)
    }
}
//data class ModuleId(val group: String, val module: String, val version: String) {
//    init {
//        // TODO: Consider if these restrictions can/should be relaxed
//        for ((string, stringType) in listOf(group to "group",
//                module to "name")) {
//            if (!LEGAL_MODULE_PATTERN.matcher(string).matches()) {
//                // TODO: Update explanation
//                throw IllegalArgumentException("Illegal character in module $stringType '$string'; only letters, numbers, dots, hyphens, and underscores are allowed.")
//            }
//        }
//    }
//
//    fun asRef(): ModuleRef {
//        return ModuleRef(group, module, version)
//    }
//}

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

data class RawContext(val functions: List<Function>, val structs: List<UnvalidatedStruct>, val interfaces: List<UnvalidatedInterface>, val unions: List<UnvalidatedUnion>)

// Note: Absence of a module indicates a native function, struct, or interface.
data class FunctionWithModule(val function: ValidatedFunction, val module: ValidatedModule)
data class FunctionSignatureWithModule(val function: TypeSignature, val module: ValidatedModule?)
data class StructWithModule(val struct: Struct, val module: ValidatedModule?)
data class InterfaceWithModule(val interfac: Interface, val module: ValidatedModule?)
data class UnionWithModule(val union: Union, val module: ValidatedModule?)

class EntityResolver(private val idResolutions: Map<EntityId, Set<EntityResolution>>, private val moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>) {
    companion object {
        fun create(ownModuleId: ModuleUniqueId,
                   nativeModuleVersion: String, // TODO: This is unused
                   ownFunctions: Collection<EntityId>,
                   ownStructs: Map<EntityId, Boolean>,
                   ownInterfaces: Collection<EntityId>,
                   ownUnions: Map<EntityId, Set<String>>,
                   upstreamModules: Collection<ValidatedModule>,
                   moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>): EntityResolver {

            val idResolutions = HashMap<EntityId, MutableSet<EntityResolution>>()
            val add = fun(id: EntityId, moduleId: ModuleUniqueId, type: FunctionLikeType, isThreaded: Boolean) {
                idResolutions.getOrPut(id, { HashSet(2) })
                        .add(EntityResolution(ResolvedEntityRef(moduleId, id), type, isThreaded))
            }

            // TODO: Add different things based on the native version in use
            getNativeFunctionOnlyDefinitions().keys.forEach { id ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.NATIVE_FUNCTION, false)
            }
            getNativeStructs().keys.forEach { id ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.STRUCT_CONSTRUCTOR, false)
            }
            getNativeInterfaces().keys.forEach { id ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.INSTANCE_CONSTRUCTOR, false)
                add(getAdapterIdForInterfaceId(id), CURRENT_NATIVE_MODULE_ID, FunctionLikeType.ADAPTER_CONSTRUCTOR, false)
            }
            getNativeUnions().forEach { (id, nativeUnion) ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.UNION_TYPE, false)
                for (option in nativeUnion.options) {
                    val optionConstructorId = EntityId(id.namespacedName + option.name)
                    add(optionConstructorId, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.UNION_OPTION_CONSTRUCTOR, false)
                }
                val whenId = EntityId(id.namespacedName + "when")
                add(whenId, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.UNION_WHEN_FUNCTION, false)
            }
            getNativeOpaqueTypes().keys.forEach { id ->
                add(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.OPAQUE_TYPE, true)
            }

            for (id in ownFunctions) {
                add(id, ownModuleId, FunctionLikeType.FUNCTION, false)
            }

            for ((id, isThreaded) in ownStructs) {
                add(id, ownModuleId, FunctionLikeType.STRUCT_CONSTRUCTOR, isThreaded)
            }

            for (id in ownInterfaces) {
                add(id, ownModuleId, FunctionLikeType.INSTANCE_CONSTRUCTOR, false)
                add(getAdapterIdForInterfaceId(id), ownModuleId, FunctionLikeType.ADAPTER_CONSTRUCTOR, false)
            }

            for ((id, optionNames) in ownUnions) {
                add(id, ownModuleId, FunctionLikeType.UNION_TYPE, false)
                for (optionName in optionNames) {
                    val optionConstructorId = EntityId(id.namespacedName + optionName)
                    add(optionConstructorId, ownModuleId, FunctionLikeType.UNION_OPTION_CONSTRUCTOR, false)
                }
                val whenId = EntityId(id.namespacedName + "when")
                add(whenId, ownModuleId, FunctionLikeType.UNION_WHEN_FUNCTION, false)
            }

            for (module in upstreamModules) {
                module.getAllExportedFunctions().keys.forEach { id ->
                    add(id, module.id, FunctionLikeType.FUNCTION, false)
                }
                module.getAllExportedStructs().forEach { id, struct ->
                    add(id, module.id, FunctionLikeType.STRUCT_CONSTRUCTOR, struct.isThreaded)
                }
                module.getAllExportedInterfaces().keys.forEach { id ->
                    add(id, module.id, FunctionLikeType.INSTANCE_CONSTRUCTOR, false)
                    add(getAdapterIdForInterfaceId(id), module.id, FunctionLikeType.ADAPTER_CONSTRUCTOR, false)
                }
                module.getAllExportedUnions().forEach { id, union ->
                    add(id, module.id, FunctionLikeType.UNION_TYPE, false)
                    for (option in union.options) {
                        val optionConstructorId = EntityId(id.namespacedName + option.name)
                        add(optionConstructorId, module.id, FunctionLikeType.UNION_OPTION_CONSTRUCTOR, false)
                    }
                    val whenId = EntityId(id.namespacedName + "when")
                    add(whenId, module.id, FunctionLikeType.UNION_WHEN_FUNCTION, false)
                }
            }
            return EntityResolver(idResolutions, moduleVersionMappings)
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
            return allResolutions.filter { resolution -> resolution.entityRef.module.name.module == moduleRef.module }
        } else if (moduleRef.version == null) {
            return allResolutions.filter { resolution -> resolution.entityRef.module.name.module == moduleRef.module
                                                         && resolution.entityRef.module.name.group == moduleRef.group }
        } else {
            val preferredVersion = getPreferredVersion(moduleRef.group, moduleRef.module, moduleRef.version)
            return allResolutions.filter { resolution -> resolution.entityRef.module.name.module == moduleRef.module
                                                         && resolution.entityRef.module.name.group == moduleRef.group
                                                         && resolution.entityRef.module.fake0Version == preferredVersion }
        }
    }

    private fun getPreferredVersion(group: String, module: String, version: String): String {
        val id = ModuleNonUniqueId.fromStringTriple(group, module, version)
        return this.moduleVersionMappings[id]?.fake0Version ?: error("No mapping was provided for version $version of module $group:$module")
    }
}
private val EXPORT_ANNOTATION_NAME = EntityId.of("Export")
// TODO: Would storing or returning things in a non-map format improve performance?
// TODO: When we have re-exporting implemented, check somewhere that we don't export multiple refs with the same ID
// TODO: For module version reform, we probably want mappings from ModuleNonUniqueIds to ModuleUniqueIds (that would
//   come from the repository)
class ValidatedModule private constructor(val id: ModuleUniqueId,
                                          val nativeModuleVersion: String,
                                          val ownFunctions: Map<EntityId, ValidatedFunction>,
                                          val exportedFunctions: Set<EntityId>,
                                          val ownStructs: Map<EntityId, Struct>,
                                          val exportedStructs: Set<EntityId>,
                                          val ownInterfaces: Map<EntityId, Interface>,
                                          val exportedInterfaces: Set<EntityId>,
                                          val ownUnions: Map<EntityId, Union>,
                                          val exportedUnions: Set<EntityId>,
                                          val upstreamModules: Map<ModuleUniqueId, ValidatedModule>,
                                          val moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>) {
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
    private val resolver = EntityResolver.create(
            id,
            nativeModuleVersion,
            ownFunctions.keys,
            ownStructs.mapValues { it.value.isThreaded },
            ownInterfaces.keys,
            ownUnions.mapValues { it.value.options.map(Option::name).toSet() },
            upstreamModules.values,
            moduleVersionMappings
    )

    companion object {
        fun create(id: ModuleUniqueId,
                   nativeModuleVersion: String,
                   ownFunctions: Map<EntityId, ValidatedFunction>,
                   ownStructs: Map<EntityId, Struct>,
                   ownInterfaces: Map<EntityId, Interface>,
                   ownUnions: Map<EntityId, Union>,
                   upstreamModules: Collection<ValidatedModule>,
                   moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>): ValidatedModule {
            val exportedFunctions = findExported(ownFunctions.values)
            val exportedStructs = findExported(ownStructs.values)
            val exportedInterfaces = findExported(ownInterfaces.values)
            val exportedUnions = findExported(ownUnions.values)

            // TODO: We'll also need some notion of re-exporting imported things...
            return ValidatedModule(id, nativeModuleVersion,
                    ownFunctions, exportedFunctions,
                    ownStructs, exportedStructs,
                    ownInterfaces, exportedInterfaces,
                    ownUnions, exportedUnions,
                    upstreamModules.associateBy(ValidatedModule::id),
                    moduleVersionMappings)
        }

        private fun findExported(values: Collection<TopLevelEntity>): Set<EntityId> {
            val exportedIds = HashSet<EntityId>()
            for (value in values) {
                if (hasExportAnnotation(value)) {
                    exportedIds.add(value.id)
                }
            }
            return exportedIds
        }

        private fun hasExportAnnotation(value: TopLevelEntity): Boolean {
            for (annotation in value.annotations) {
                if (annotation.name == EXPORT_ANNOTATION_NAME) {
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

    fun getInternalUnion(entityRef: ResolvedEntityRef): UnionWithModule {
        val moduleId = entityRef.module
        if (moduleId == this.id) {
            return UnionWithModule(ownUnions[entityRef.id] ?: error("Expected ${entityRef.id} to be a union, but it's not"), this)
        }
        if (isNativeModule(moduleId)) {
            return UnionWithModule(getNativeUnions()[entityRef.id] ?: error("Resolution error"), null)
        }
        val module = upstreamModules[moduleId] ?: error("Error in resolving")
        return module.getExportedUnion(entityRef.id) ?: error("Error in resolving")
    }

    fun getExportedUnion(id: EntityId): UnionWithModule? {
        // TODO: This logic will have to change when we allow re-exporting.
        // (Currently, we can just assume that anything exported is in our own module.)
        if (exportedUnions.contains(id)) {
            return getInternalUnion(ResolvedEntityRef(this.id, id))
        }
        return null
    }

    fun getAllInternalUnions(): Map<EntityId, Union> {
        // TODO: Consider caching this
        val allUnions = HashMap<EntityId, Union>()
        allUnions.putAll(ownUnions)

        for (module in upstreamModules.values) {
            // TODO: Do we need to filter out conflicts? Pretty sure we do
            allUnions.putAll(module.getAllExportedUnions())
        }
        return allUnions
    }

    fun getAllExportedUnions(): Map<EntityId, Union> {
        return exportedUnions.associate { id -> id to getExportedUnion(id)!!.union }
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

    fun getInternalInterfaceByAdapterId(adapterRef: ResolvedEntityRef): InterfaceWithModule {
        val interfaceId = getInterfaceIdForAdapterId(adapterRef.id) ?: error("Resolution error")
        val interfaceRef = ResolvedEntityRef(adapterRef.module, interfaceId)
        return getInternalInterface(interfaceRef)
    }

    fun resolve(ref: EntityRef): EntityResolution? {
        return resolver.resolve(ref)
    }

    fun resolve(ref: ResolvedEntityRef): EntityResolution? {
        return resolver.resolve(EntityRef(ref.module.asRef(), ref.id))
    }

}

// TODO: Rename to something else (EntityType? EntityIdType?) given that opaque types are now part of this
enum class FunctionLikeType {
    NATIVE_FUNCTION,
    FUNCTION,
    STRUCT_CONSTRUCTOR,
    INSTANCE_CONSTRUCTOR,
    ADAPTER_CONSTRUCTOR,
    UNION_TYPE,
    UNION_OPTION_CONSTRUCTOR,
    UNION_WHEN_FUNCTION,
    OPAQUE_TYPE
}

data class EntityResolution(val entityRef: ResolvedEntityRef, val type: FunctionLikeType, val isThreaded: Boolean)
