package net.semlang.interpreter

import net.semlang.api.EntityId
import net.semlang.api.ModuleId
import net.semlang.api.ResolvedEntityRef
import net.semlang.api.ValidatedModule
import java.math.BigInteger
import java.util.function.Consumer

// TODO: At some point, we'll want to identify functions in a more errorproof way
fun getOptimizedFunctions(mainModule: ValidatedModule): Map<ResolvedEntityRef, NativeFunction> {
    val result = HashMap<ResolvedEntityRef, NativeFunction>()

    val moduleIds = getAllModuleIds(mainModule)
    for (moduleId in moduleIds) {
        addFunctionsFromModule(result, moduleId)
    }

    return result
}

/**
 * Returns the module IDs for the given module and all its dependencies, including transitive dependencies.
 */
private fun getAllModuleIds(module: ValidatedModule): Set<ModuleId> {
    val allModuleIds = HashSet<ModuleId>()
    addAllModuleIds(allModuleIds, module)
    return allModuleIds
}

private fun addAllModuleIds(allModuleIds: HashSet<ModuleId>, module: ValidatedModule) {
    allModuleIds.add(module.id)
    for (upstreamModule in module.upstreamModules.values) {
        addAllModuleIds(allModuleIds, upstreamModule)
    }
}

private fun addFunctionsFromModule(result: HashMap<ResolvedEntityRef, NativeFunction>, moduleId: ModuleId) {
    // TODO: Fix the version check once we get real module versioning
    if (moduleId.group == "semlang" && moduleId.module == "standard-library" && moduleId.version == "develop") {
        addStandardLibraryFunctions(getFunctionAddingConsumer(moduleId, result))
    }
}

// Simplify the "addXFunctions" a little by not requiring them to restate their IDs quite so much
private fun getFunctionAddingConsumer(moduleId: ModuleId, allFunctionsMap: HashMap<ResolvedEntityRef, NativeFunction>): Consumer<NativeFunction> {
    return object: Consumer<NativeFunction> {
        override fun accept(function: NativeFunction) {
            val resolvedRef = ResolvedEntityRef(moduleId, function.id)
            allFunctionsMap[resolvedRef] = function
        }
    }
}

private fun addStandardLibraryFunctions(nativeImplAdder: Consumer<NativeFunction>) {
    nativeImplAdder.accept(NativeFunction(EntityId.of("Natural", "plus"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()

        SemObject.Natural(left.value + right.value)
    }))

    nativeImplAdder.accept(NativeFunction(EntityId.of("Natural", "modulo"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()
        if (right.value < BigInteger.ONE) {
            SemObject.Try.Failure
        } else {
            SemObject.Try.Success(SemObject.Natural(left.value.mod(right.value)))
        }
    }))

    nativeImplAdder.accept(NativeFunction(EntityId.of("Natural", "equals"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()

        SemObject.Boolean(left.value.equals(right.value))
    }))

    // List.drop
    nativeImplAdder.accept(NativeFunction(EntityId.of("List", "drop"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val numToDrop = args[1] as? SemObject.Natural ?: typeError()
        SemObject.SemList(list.contents.drop(numToDrop.value.intValueExact()))
    }))

    // List.dropLast
    nativeImplAdder.accept(NativeFunction(EntityId.of("List", "dropLast"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val numToDrop = args[1] as? SemObject.Natural ?: typeError()
        SemObject.SemList(list.contents.dropLast(numToDrop.value.intValueExact()))
    }))

    // TODO: This only really works as intended if it's a struct/BasicSequence... (?) Maybe return Sequence to be a struct?
//    nativeImplAdder.accept(NativeFunction(EntityId.of("Sequence", "getRange"), { args: List<SemObject>, apply: InterpreterCallback ->
//        val sequence = args[0] as? SemObject.Instance ?: typeError()
//        val numElements = args[1] as? SemObject.Natural ?: typeError()
//
//        val elements = ArrayList<SemObject>()
//        var value = sequence.objects[0] // initialValue
//        val successor = sequence.objects[1] as? SemObject.FunctionBinding ?: typeError()
//
//        for (i in 0..numElements.value.intValueExact()) {
//            elements.add(value)
//            value = apply(successor, listOf(value))
//        }
//
//        SemObject.SemList(elements)
//    }))
}

private fun typeError(): Nothing {
    error("Runtime type error")
}
