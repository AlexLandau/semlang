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

// TODO: At some point, we'll want to identify functions in a more errorproof way
fun getOptimizedStructConstructors(mainModule: ValidatedModule): Map<ResolvedEntityRef, NativeFunction> {
    val result = HashMap<ResolvedEntityRef, NativeFunction>()

    val moduleIds = getAllModuleIds(mainModule)
    for (moduleId in moduleIds) {
        addStructConstructorsFromModule(result, moduleId)
    }

    return result
}

// TODO: At some point, we'll want to identify functions in a more errorproof way
fun getOptimizedStructLiteralParsers(mainModule: ValidatedModule): Map<ResolvedEntityRef, (String) -> SemObject> {
    val result = HashMap<ResolvedEntityRef, (String) -> SemObject>()

    val moduleIds = getAllModuleIds(mainModule)
    for (moduleId in moduleIds) {
        addStructLiteralParsersFromModule(result, moduleId)
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

private fun addStructConstructorsFromModule(result: HashMap<ResolvedEntityRef, NativeFunction>, moduleId: ModuleId) {
    // TODO: Fix the version check once we get real module versioning
    if (moduleId.group == "semlang" && moduleId.module == "standard-library" && moduleId.version == "develop") {
        addStandardLibraryStructConstructors(getFunctionAddingConsumer(moduleId, result))
    }
}

private fun addStructLiteralParsersFromModule(result: HashMap<ResolvedEntityRef, (String) -> SemObject>, moduleId: ModuleId) {
    // TODO: Fix the version check once we get real module versioning
    if (moduleId.group == "semlang" && moduleId.module == "standard-library" && moduleId.version == "develop") {
        // TODO: Refactor
        val entityIdsMap = HashMap<EntityId, (String) -> SemObject>()
        addStandardLibraryStructLiteralParsers(entityIdsMap)
        entityIdsMap.forEach { entityId, function ->
            val resolvedRef = ResolvedEntityRef(moduleId, entityId)
            result.put(resolvedRef, function)
        }
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

    // List.firstN
    nativeImplAdder.accept(NativeFunction(EntityId.of("List", "firstN"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val n = args[1] as? SemObject.Natural ?: typeError()
        SemObject.SemList(list.contents.take(n.value.intValueExact()))
    }))

    // List.lastN
    nativeImplAdder.accept(NativeFunction(EntityId.of("List", "lastN"), { args: List<SemObject>, _: InterpreterCallback ->
        val list = args[0] as? SemObject.SemList ?: typeError()
        val n = args[1] as? SemObject.Natural ?: typeError()
        SemObject.SemList(list.contents.takeLast(n.value.intValueExact()))
    }))

    // Int64.plusUnsafe
    nativeImplAdder.accept(NativeFunction(EntityId.of("Int64", "plusUnsafe"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Int64 ?: typeError()
        val right = args[1] as? SemObject.Int64 ?: typeError()
        SemObject.Int64(left.value + right.value)
    }))

    // Int64.plusSafe
    nativeImplAdder.accept(NativeFunction(EntityId.of("Int64", "plusSafe"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Int64 ?: typeError()
        val right = args[1] as? SemObject.Int64 ?: typeError()
        val l = left.value
        val r = right.value
        val sum = l + r
        if (l >= 0 && r >= 0 && sum < 0) {
            SemObject.Try.Failure
        } else if (l < 0 && r < 0 && sum >= 0) {
            SemObject.Try.Failure
        } else {
            SemObject.Try.Success(SemObject.Int64(sum))
        }
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

private fun addStandardLibraryStructConstructors(nativeImplAdder: Consumer<NativeFunction>) {
    nativeImplAdder.accept(NativeFunction(EntityId.of("Int64"), { args: List<SemObject>, _: InterpreterCallback ->
        val integer = args[0] as? SemObject.Integer ?: typeError()

        val bigInt = integer.value
        if (bigInt.bitLength() <= 63) {
            SemObject.Try.Success(SemObject.Int64(bigInt.toLong()))
        } else {
            SemObject.Try.Failure
        }
    }))

}

private fun addStandardLibraryStructLiteralParsers(nativeImplAdder: MutableMap<EntityId, (String) -> SemObject>) {
    nativeImplAdder.put(EntityId.of("Int64"), { literal: String ->
        val longMaybe = literal.toLongOrNull() ?: error("Invalid Int64 literal value: $literal")
        SemObject.Int64(longMaybe)
    })

}

private fun typeError(): Nothing {
    error("Runtime type error")
}
