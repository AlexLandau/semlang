package net.semlang.api

import java.util.*
import java.util.regex.Pattern

private val LEGAL_MODULE_PATTERN = Pattern.compile("[0-9a-zA-Z]+([_.-][0-9a-zA-Z]+)*")

data class ModuleName(val group: String, val module: String) {
    init {
        // TODO: Consider if these restrictions can/should be relaxed
        if (!LEGAL_MODULE_PATTERN.matcher(group).matches()) {
            throw IllegalArgumentException("Illegal character in module group '$group'; only letters, numbers, dots, hyphens, and underscores are allowed.")
        }
        if (!LEGAL_MODULE_PATTERN.matcher(module).matches()) {
            throw IllegalArgumentException("Illegal character in module name '$module'; only letters, numbers, dots, hyphens, and underscores are allowed.")
        }
    }

    override fun toString(): String {
        return "$group:$module"
    }
}

data class ModuleNonUniqueId(val name: ModuleName, val versionScheme: String, val version: String) {
    fun requireUnique(): ModuleUniqueId {
        if (versionScheme != ModuleUniqueId.UNIQUE_VERSION_SCHEME) {
            error("We require a unique ID here, but the ID was $this")
        }
        return ModuleUniqueId(name, version)
    }

    companion object {
        fun fromStringTriple(group: String, module: String, version: String): ModuleNonUniqueId {
            val name = ModuleName(group, module)
            val versionParts = version.split(":", limit = 2)
            if (versionParts.size != 2) {
                error("Expected a version string with a scheme, but was: $version for $group:$module")
            }
            val (versionProtocol, bareVersion) = versionParts
            return ModuleNonUniqueId(name, versionProtocol, bareVersion)
        }
    }
}

/**
 * Note: The fake0Version may be a different kind of version for the native module.
 */
data class ModuleUniqueId(val name: ModuleName, val fake0Version: String) {
    companion object {
        val UNIQUE_VERSION_SCHEME = "fake0"
        val UNIQUE_VERSION_SCHEME_PREFIX = "$UNIQUE_VERSION_SCHEME:"
    }
    fun asRef(): ModuleRef {
        return ModuleRef(name.group, name.module, UNIQUE_VERSION_SCHEME_PREFIX + fake0Version)
    }

    fun asNonUniqueId(): ModuleNonUniqueId {
        return ModuleNonUniqueId(name, UNIQUE_VERSION_SCHEME, fake0Version)
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
            return "$group:$module:\"$version\""
        } else if (group != null) {
            return "$group:$module"
        } else {
            return module
        }
    }
}

data class RawContext(val functions: List<Function>, val structs: List<UnvalidatedStruct>, val unions: List<UnvalidatedUnion>)

// Note: Absence of a module indicates a native function, struct, or union.
data class FunctionWithModule(val function: ValidatedFunction, val module: ValidatedModule)
data class FunctionSignatureWithModule(val function: FunctionSignature, val module: ValidatedModule?)
data class StructWithModule(val struct: Struct, val module: ValidatedModule?)
data class UnionWithModule(val union: Union, val module: ValidatedModule?)

enum class ResolutionType {
    Type,
    Function,
}

class EntityResolver(private val typeResolutions: Map<EntityId, Set<EntityResolution>>, private val functionResolutions: Map<EntityId, Set<EntityResolution>>, private val moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>) {
    companion object {
        fun create(ownModuleId: ModuleUniqueId,
                   ownFunctions: Collection<EntityId>,
                   ownStructs: Collection<EntityId>,
                   ownUnions: Map<EntityId, Set<String>>,
                   upstreamModules: Collection<ValidatedModule>,
                   moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>): EntityResolver {

            val typeResolutions = HashMap<EntityId, MutableSet<EntityResolution>>()
            val functionResolutions = HashMap<EntityId, MutableSet<EntityResolution>>()
            val addType = fun(id: EntityId, moduleId: ModuleUniqueId, type: FunctionLikeType, isReference: Boolean) {
                typeResolutions.getOrPut(id, { HashSet(2) })
                        .add(EntityResolution(ResolvedEntityRef(moduleId, id), type, isReference))
            }
            val addFunction = fun(id: EntityId, moduleId: ModuleUniqueId, type: FunctionLikeType, isReference: Boolean) {
                functionResolutions.getOrPut(id, { HashSet(2) })
                        .add(EntityResolution(ResolvedEntityRef(moduleId, id), type, isReference))
            }
            val addTypeAndFunction = fun(id: EntityId, moduleId: ModuleUniqueId, type: FunctionLikeType, isReference: Boolean) {
                addType(id, moduleId, type, isReference)
                addFunction(id, moduleId, type, isReference)
            }

            // TODO: Add different things based on the native version in use
            getNativeFunctionOnlyDefinitions().keys.forEach { id ->
                addFunction(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.NATIVE_FUNCTION, false)
            }
            getNativeStructs().keys.forEach { id ->
                addTypeAndFunction(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.STRUCT_CONSTRUCTOR, false)
            }
            getNativeUnions().forEach { (id, nativeUnion) ->
                addType(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.UNION_TYPE, false)
                for (option in nativeUnion.options) {
                    val optionConstructorId = EntityId(id.namespacedName + option.name)
                    addFunction(optionConstructorId, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.UNION_OPTION_CONSTRUCTOR, false)
                }
                val whenId = EntityId(id.namespacedName + "when")
                addFunction(whenId, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.UNION_WHEN_FUNCTION, false)
            }
            getNativeOpaqueTypes().keys.forEach { id ->
                addType(id, CURRENT_NATIVE_MODULE_ID, FunctionLikeType.OPAQUE_TYPE, true)
            }

            for (id in ownFunctions) {
                addFunction(id, ownModuleId, FunctionLikeType.FUNCTION, false)
            }

            for (id in ownStructs) {
                addTypeAndFunction(id, ownModuleId, FunctionLikeType.STRUCT_CONSTRUCTOR, false)
            }

            for ((id, optionNames) in ownUnions) {
                addType(id, ownModuleId, FunctionLikeType.UNION_TYPE, false)
                for (optionName in optionNames) {
                    val optionConstructorId = EntityId(id.namespacedName + optionName)
                    addFunction(optionConstructorId, ownModuleId, FunctionLikeType.UNION_OPTION_CONSTRUCTOR, false)
                }
                val whenId = EntityId(id.namespacedName + "when")
                addFunction(whenId, ownModuleId, FunctionLikeType.UNION_WHEN_FUNCTION, false)
            }

            for (module in upstreamModules) {
                module.getAllExportedFunctions().keys.forEach { id ->
                    addFunction(id, module.id, FunctionLikeType.FUNCTION, false)
                }
                module.getAllExportedStructs().forEach { id, struct ->
                    addTypeAndFunction(id, module.id, FunctionLikeType.STRUCT_CONSTRUCTOR, false)
                }
                module.getAllExportedUnions().forEach { id, union ->
                    addType(id, module.id, FunctionLikeType.UNION_TYPE, false)
                    for (option in union.options) {
                        val optionConstructorId = EntityId(id.namespacedName + option.name)
                        addFunction(optionConstructorId, module.id, FunctionLikeType.UNION_OPTION_CONSTRUCTOR, false)
                    }
                    val whenId = EntityId(id.namespacedName + "when")
                    addFunction(whenId, module.id, FunctionLikeType.UNION_WHEN_FUNCTION, false)
                }
            }
            return EntityResolver(typeResolutions, functionResolutions, moduleVersionMappings)
        }
    }

    fun resolve(ref: EntityRef, resolutionType: ResolutionType): ResolutionResult {
        val allResolutions = when (resolutionType) {
            ResolutionType.Type -> typeResolutions[ref.id]
            ResolutionType.Function -> functionResolutions[ref.id]
        }
        if (allResolutions == null) {
            return ResolutionResult.Error(getNoResolutionsFoundError(resolutionType, ref))
        }
        val filtered = filterByModuleRef(allResolutions, ref.moduleRef)
        if (filtered.size == 1) {
            return filtered.single()
        } else if (filtered.size == 0) {
            return ResolutionResult.Error(getNoResolutionsFoundError(resolutionType, ref))
        } else {
            return ResolutionResult.Error(getMultipleResolutionsFoundError(resolutionType, ref, filtered))
        }
    }

    private fun getNoResolutionsFoundError(resolutionType: ResolutionType, ref: EntityRef): String {
        return when (resolutionType) {
            ResolutionType.Type -> "Type $ref not found"
            ResolutionType.Function -> "Function $ref not found"
        }
    }

    private fun getMultipleResolutionsFoundError(
        resolutionType: ResolutionType,
        ref: EntityRef,
        possibilities: Collection<EntityResolution>
    ): String {
        val possibilityRefs = possibilities.map { getSimplestRefFor(it.entityRef, resolutionType) }.sortedBy { it.toString() }
        return when (resolutionType) {
            ResolutionType.Type -> "Type $ref is ambiguous; possible types are: $possibilityRefs"
            ResolutionType.Function -> "Function $ref is ambiguous; possible functions are: $possibilityRefs"
        }
    }

    fun getSimplestRefFor(resolvedRef: ResolvedEntityRef, resolutionType: ResolutionType): EntityRef {
        val group = resolvedRef.module.name.group
        val module = resolvedRef.module.name.module
        val id = resolvedRef.id
        val candidateRefs = listOf(
            EntityRef(null, id),
            EntityRef(ModuleRef(null, module, null), id),
            EntityRef(ModuleRef(group, module, null), id)
        )
        val allResolutions = when (resolutionType) {
            ResolutionType.Type -> typeResolutions[id]
            ResolutionType.Function -> functionResolutions[id]
        }
        for (ref in candidateRefs) {
            if (allResolutions == null) {
                return ref
            }

            if (filterByModuleRef(allResolutions, ref.moduleRef).size <= 1) {
                return ref
            }
        }
        return resolvedRef.toUnresolvedRef()
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
        if (id.versionScheme == ModuleUniqueId.UNIQUE_VERSION_SCHEME) {
            return id.version
        }
        return this.moduleVersionMappings[id]?.fake0Version ?: error("No mapping was provided for version $version of module $group:$module")
    }
}
private val EXPORT_ANNOTATION_NAME = EntityId.of("Export")
// TODO: Would storing or returning things in a non-map format improve performance?
// TODO: When we have re-exporting implemented, check somewhere that we don't export multiple refs with the same ID
class ValidatedModule private constructor(val id: ModuleUniqueId,
                                          val nativeModuleVersion: String,
                                          val ownFunctions: Map<EntityId, ValidatedFunction>,
                                          val exportedFunctions: Set<EntityId>,
                                          val ownStructs: Map<EntityId, Struct>,
                                          val exportedStructs: Set<EntityId>,
                                          val ownUnions: Map<EntityId, Union>,
                                          val exportedUnions: Set<EntityId>,
                                          val upstreamModules: Map<ModuleUniqueId, ValidatedModule>,
                                          val moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>) {
    val name get() = id.name
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
            ownFunctions.keys,
            ownStructs.keys,
            ownUnions.mapValues { it.value.options.map(Option::name).toSet() },
            upstreamModules.values,
            moduleVersionMappings
    )

    companion object {
        fun create(id: ModuleUniqueId,
                   nativeModuleVersion: String,
                   ownFunctions: Map<EntityId, ValidatedFunction>,
                   ownStructs: Map<EntityId, Struct>,
                   ownUnions: Map<EntityId, Union>,
                   upstreamModules: Collection<ValidatedModule>,
                   moduleVersionMappings: Map<ModuleNonUniqueId, ModuleUniqueId>): ValidatedModule {
            val exportedFunctions = findExported(ownFunctions.values)
            val exportedStructs = findExported(ownStructs.values)
            val exportedUnions = findExported(ownUnions.values)

            // TODO: We'll also need some notion of re-exporting imported things...
            return ValidatedModule(id, nativeModuleVersion,
                    ownFunctions, exportedFunctions,
                    ownStructs, exportedStructs,
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

    fun resolve(ref: EntityRef, resolutionType: ResolutionType): ResolutionResult {
        return resolver.resolve(ref, resolutionType)
    }

    fun resolve(ref: ResolvedEntityRef, resolutionType: ResolutionType): ResolutionResult {
        return resolver.resolve(EntityRef(ref.module.asRef(), ref.id), resolutionType)
    }

}

// TODO: Rename to something else (EntityType? EntityIdType?) given that opaque types are now part of this
enum class FunctionLikeType {
    NATIVE_FUNCTION,
    FUNCTION,
    STRUCT_CONSTRUCTOR,
    UNION_TYPE,
    UNION_OPTION_CONSTRUCTOR,
    UNION_WHEN_FUNCTION,
    OPAQUE_TYPE
}

data class EntityResolution(val entityRef: ResolvedEntityRef, val type: FunctionLikeType, val isReference: Boolean): ResolutionResult()
sealed class ResolutionResult {
    data class Error(val errorMessage: String): ResolutionResult()
}
