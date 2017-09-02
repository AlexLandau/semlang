package net.semlang.api

import java.util.HashMap
import java.util.regex.Pattern

private val ILLEGAL_CHAR_PATTERN = Pattern.compile("[^0-9a-zA-Z_.-]")

data class ModuleId(val group: String, val module: String, val version: String) {
    init {
        // TODO: Consider if these restrictions can/should be relaxed
        for ((string, stringType) in listOf(group to "group",
                module to "name",
                version to "version"))
            if (ILLEGAL_CHAR_PATTERN.matcher(string).find()) {
                throw IllegalArgumentException("Illegal character in module $stringType '$string'; only letters, numbers, dots, hyphens, and underscores are allowed.")
            }
    }
}

data class RawContext(val functions: List<Function>, val structs: List<UnvalidatedStruct>, val interfaces: List<Interface>)

// TODO: We'll want this to be able to reference upstream contexts without repeating their contents
class ValidatedContext private constructor(val ownFunctionImplementations: Map<FunctionId, ValidatedFunction>,
                       val ownFunctionSignatures: Map<FunctionId, TypeSignature>,
                       val ownStructs: Map<FunctionId, Struct>,
                       val ownInterfaces: Map<FunctionId, Interface>,
                       val upstreamContexts: List<ValidatedContext>) {
    val ownInterfacesByAdapterId = ownInterfaces.values.associateBy(Interface::adapterId)

    companion object {
        fun create(ownFunctions: Map<FunctionId, ValidatedFunction>,
                   ownStructs: Map<FunctionId, Struct>,
                   ownInterfaces: Map<FunctionId, Interface>,
                   upstreamContexts: List<ValidatedContext>): ValidatedContext {
            val functionSignatures = ownFunctions.values.map(ValidatedFunction::toTypeSignature).associateBy(TypeSignature::id)
            return ValidatedContext(ownFunctions, functionSignatures, ownStructs, ownInterfaces, upstreamContexts)
        }

        val NATIVE_CONTEXT = ValidatedContext(mapOf(), getNativeFunctionDefinitions(), getNativeStructs(), getNativeInterfaces(), listOf())
    }

    fun getFunctionSignature(id: FunctionId): TypeSignature? {
        return getEntity(id, ownFunctionSignatures, ValidatedContext::getFunctionSignature)
    }
    // TODO: Should this replace the above version?
    fun getFunctionOrConstructorSignature(id: FunctionId): TypeSignature? {
        val functionOnlySignature = getFunctionSignature(id)
        if (functionOnlySignature != null) {
            return functionOnlySignature
        }

        val structMaybe = getStruct(id)
        if (structMaybe != null) {
            return structMaybe.getConstructorSignature()
        }

        val instanceMaybe = getInterface(id)
        if (instanceMaybe != null) {
            return instanceMaybe.getInstanceConstructorSignature()
        }

        val adapterMaybe = getInterfaceByAdapterId(id)
        if (adapterMaybe != null) {
            return adapterMaybe.getAdapterConstructorSignature()
        }

        return null
    }
    fun getFunctionImplementation(id: FunctionId): ValidatedFunction? {
        return getEntity(id, ownFunctionImplementations, ValidatedContext::getFunctionImplementation)
    }
    fun getStruct(id: FunctionId): Struct? {
        return getEntity(id, ownStructs, ValidatedContext::getStruct)
    }
    fun getInterface(id: FunctionId): Interface? {
        return getEntity(id, ownInterfaces, ValidatedContext::getInterface)
    }
    fun getInterfaceByAdapterId(id: FunctionId): Interface? {
        return getEntity(id, ownInterfacesByAdapterId, ValidatedContext::getInterfaceByAdapterId)
    }
    // TODO: These are currently relatively inefficient at scale; should consider caching, package-awareness, etc.
    private fun <T> getEntity(id: FunctionId, ours: Map<FunctionId, T>, theirs: (ValidatedContext, FunctionId) -> T?): T? {
        val ownImpl = ours[id]
        if (ownImpl != null) {
            return ownImpl
        }

        upstreamContexts.forEach { context ->
            val impl = theirs(context, id)
            if (impl != null) {
                return impl
            }
        }
        return null
    }

    fun getAllFunctionSignatures(): Map<FunctionId, TypeSignature> {
        return getAllEntities(ownFunctionSignatures, ValidatedContext::getAllFunctionSignatures)
    }
    fun getAllStructs(): Map<FunctionId, Struct> {
        return getAllEntities(ownStructs, ValidatedContext::getAllStructs)
    }
    fun getAllInterfaces(): Map<FunctionId, Interface> {
        return getAllEntities(ownInterfaces, ValidatedContext::getAllInterfaces)
    }
    // TODO: Cache these, at least
    // TODO: Error on any kind of collision
    private fun <T> getAllEntities(own: Map<FunctionId, T>, theirs: (ValidatedContext) -> Map<FunctionId, T>): Map<FunctionId, T> {
        val results = HashMap<FunctionId, T>()

        upstreamContexts.forEach { context ->
            results.putAll(theirs(context))
        }
        results.putAll(own)

        return results
    }



}

class ValidatedModule private constructor(val id: ModuleId,
                                          val ownFunctions: Map<FunctionId, ValidatedFunction>,
                                          val exportedFunctions: Set<FunctionId>,
                                          val ownStructs: Map<FunctionId, Struct>,
                                          val exportedStructs: Set<FunctionId>,
                                          val ownInterfaces: Map<FunctionId, Interface>,
                                          val exportedInterfaces: Set<FunctionId>,
                                          val upstreamModules: List<ValidatedModule>) {

    companion object {
        fun create(id: ModuleId,
                   ownFunctions: Map<FunctionId, ValidatedFunction>,
                   ownStructs: Map<FunctionId, Struct>,
                   ownInterfaces: Map<FunctionId, Interface>,
                   upstreamModules: List<ValidatedModule>): ValidatedModule {
            val exportedFunctions = findExported(ownFunctions.values)
            val exportedStructs = findExported(ownStructs.values)
            val exportedInterfaces = findExported(ownInterfaces.values)
            // TODO: We'll also need some notion of re-exporting imported things...
            return ValidatedModule(id,
                    ownFunctions, exportedFunctions,
                    ownStructs, exportedStructs,
                    ownInterfaces, exportedInterfaces,
                    upstreamModules)
        }

        private fun findExported(values: Collection<TopLevelEntity>): Set<FunctionId> {
            val exportedIds = HashSet<FunctionId>()
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

    fun getInternalFunction(functionId: FunctionId): ValidatedFunction? {
        val ownFunction = ownFunctions[functionId]
        if (ownFunction != null) {
            return ownFunction
        }

        // TODO: Is there a better approach to dealing with conflicts here?
        // We'll want some kind of import statements for turning modules into a kind of namespacing...
        // and that will probably need to be reflected here
        for (module in upstreamModules) {
            val function = module.getExportedFunction(functionId)
            if (function != null) {
                return function
            }
        }
        return null
    }

    fun getExportedFunction(functionId: FunctionId): ValidatedFunction? {
        if (exportedFunctions.contains(functionId)) {
            return getInternalFunction(functionId)
        }
        return null
    }

    // TODO: Refactor common code; possibly have a single map with all IDs
    fun getInternalStruct(id: FunctionId): Struct? {
        val ownStruct = ownStructs[id]
        if (ownStruct != null) {
            return ownStruct
        }

        // TODO: Is there a better approach to dealing with conflicts here?
        // We'll want some kind of import statements for turning modules into a kind of namespacing...
        // and that will probably need to be reflected here
        for (module in upstreamModules) {
            val struct = module.getExportedStruct(id)
            if (struct != null) {
                return struct
            }
        }
        return null
    }

    fun getExportedStruct(id: FunctionId): Struct? {
        if (exportedStructs.contains(id)) {
            return getInternalStruct(id)
        }
        return null
    }

    fun getInternalInterface(id: FunctionId): Interface? {
        val ownInterface = ownInterfaces[id]
        if (ownInterface != null) {
            return ownInterface
        }

        // TODO: Is there a better approach to dealing with conflicts here?
        // We'll want some kind of import statements for turning modules into a kind of namespacing...
        // and that will probably need to be reflected here
        for (module in upstreamModules) {
            val interfac = module.getExportedInterface(id)
            if (interfac != null) {
                return interfac
            }
        }
        return null
    }

    fun getExportedInterface(id: FunctionId): Interface? {
        if (exportedInterfaces.contains(id)) {
            return getInternalInterface(id)
        }
        return null
    }
}
