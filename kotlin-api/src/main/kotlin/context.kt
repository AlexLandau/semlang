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

// Note: Absence of a module indicates a native function, struct, or interface.
data class FunctionWithModule(val function: ValidatedFunction, val module: ValidatedModule)
data class FunctionSignatureWithModule(val function: TypeSignature, val module: ValidatedModule?)
data class StructWithModule(val struct: Struct, val module: ValidatedModule?)
data class InterfaceWithModule(val interfac: Interface, val module: ValidatedModule?)

// TODO: Would storing or returning things in a non-map format improve performance?
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

    fun getInternalFunction(functionId: FunctionId): FunctionWithModule? {
        val ownFunction = ownFunctions[functionId]
        if (ownFunction != null) {
            return FunctionWithModule(ownFunction, this)
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

    fun getExportedFunction(functionId: FunctionId): FunctionWithModule? {
        if (exportedFunctions.contains(functionId)) {
            return getInternalFunction(functionId)
        }
        return null
    }

    // TODO: Refactor common code; possibly have a single map with all IDs
    fun getInternalStruct(id: FunctionId): StructWithModule? {
        val ownStruct = ownStructs[id]
        if (ownStruct != null) {
            return StructWithModule(ownStruct, this)
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

    fun getExportedStruct(id: FunctionId): StructWithModule? {
        if (exportedStructs.contains(id)) {
            return getInternalStruct(id)
        }
        return null
    }

    fun getInternalInterface(id: FunctionId): InterfaceWithModule? {
        val ownInterface = ownInterfaces[id]
        if (ownInterface != null) {
            return InterfaceWithModule(ownInterface, this)
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

    fun getExportedInterface(id: FunctionId): InterfaceWithModule? {
        if (exportedInterfaces.contains(id)) {
            return getInternalInterface(id)
        }
        return null
    }

    fun getAllInternalInterfaces(): Map<FunctionId, Interface> {
        // TODO: Consider caching this
        val allInterfaces = HashMap<FunctionId, Interface>()
        allInterfaces.putAll(ownInterfaces)

        for (module in upstreamModules) {
            // TODO: Do we need to filter out conflicts? Pretty sure we do
            allInterfaces.putAll(module.getAllExportedInterfaces())
        }
        return allInterfaces
    }

    fun getAllExportedInterfaces(): Map<FunctionId, Interface> {
        return exportedInterfaces.associate { id -> id to getExportedInterface(id)!!.interfac }
    }

    // TODO: Refactor
    fun getAllInternalStructs(): Map<FunctionId, Struct> {
        // TODO: Consider caching this
        val allStructs = HashMap<FunctionId, Struct>()
        allStructs.putAll(ownStructs)

        for (module in upstreamModules) {
            // TODO: Do we need to filter out conflicts? Pretty sure we do
            allStructs.putAll(module.getAllExportedStructs())
        }
        return allStructs
    }

    fun getAllExportedStructs(): Map<FunctionId, Struct> {
        return exportedStructs.associate { id -> id to getExportedStruct(id)!!.struct }
    }

    fun getAllExportedFunctions(): Map<FunctionId, ValidatedFunction> {
        return exportedFunctions.associate { id -> id to getExportedFunction(id)!!.function }
    }

    fun getAllInternalFunctionSignatures(): Map<FunctionId, TypeSignature> {
        val allFunctionSignatures = HashMap<FunctionId, TypeSignature>()

        allFunctionSignatures.putAll(getOwnFunctionSignatures())

        for (module in upstreamModules) {
            allFunctionSignatures.putAll(module.getAllExportedFunctionSignatures())
        }
        return allFunctionSignatures
    }

    fun getOwnFunctionSignatures(): Map<FunctionId, TypeSignature> {
        return toFunctionSignatures(ownFunctions, ownStructs, ownInterfaces)
    }

    fun getAllExportedFunctionSignatures(): Map<FunctionId, TypeSignature> {
        return toFunctionSignatures(getAllExportedFunctions(), getAllExportedStructs(), getAllExportedInterfaces())
    }

    private fun toFunctionSignatures(functions: Map<FunctionId, ValidatedFunction>,
                                     structs: Map<FunctionId, Struct>,
                                     interfaces: Map<FunctionId, Interface>): Map<FunctionId, TypeSignature> {
        val allSignatures = HashMap<FunctionId, TypeSignature>()

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

    fun getInternalInterfaceByAdapterId(functionId: FunctionId): InterfaceWithModule? {
        val interfaceId = getInterfaceIdForAdapterId(functionId)
        if (interfaceId == null) {
            return null
        }
        return getInternalInterface(interfaceId)
    }

    fun getInternalFunctionSignature(functionId: FunctionId): FunctionSignatureWithModule? {
        val function = getInternalFunction(functionId)
        if (function != null) {
            return FunctionSignatureWithModule(function.function.toTypeSignature(), function.module)
        }

        val struct = getInternalStruct(functionId)
        if (struct != null) {
            return FunctionSignatureWithModule(struct.struct.getConstructorSignature(), struct.module)
        }

        val interfac = getInternalInterface(functionId)
        if (interfac != null) {
            return FunctionSignatureWithModule(interfac.interfac.getInstanceConstructorSignature(), interfac.module)
        }

        val adapter = getInternalInterfaceByAdapterId(functionId)
        if (adapter != null) {
            return FunctionSignatureWithModule(adapter.interfac.getAdapterConstructorSignature(), adapter.module)
        }
        return null
    }
}
