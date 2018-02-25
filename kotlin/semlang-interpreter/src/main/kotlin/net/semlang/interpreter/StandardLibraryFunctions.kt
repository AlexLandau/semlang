package net.semlang.interpreter

import net.semlang.api.EntityId
import net.semlang.api.ModuleId
import net.semlang.api.ResolvedEntityRef
import net.semlang.api.ValidatedModule
import java.util.function.Consumer

// TODO: At some point, we'll want to identify functions in a more errorproof way
fun getOptimizedFunctions(mainModule: ValidatedModule): Map<ResolvedEntityRef, NativeFunction> {
    val result = HashMap<ResolvedEntityRef, NativeFunction>()

    // TODO: Also pull in transitive dependencies
    val moduleIds = mainModule.upstreamModules.keys
    for (moduleId in moduleIds) {
        addFunctionsFromModule(result, moduleId)
    }

    return result
}

private fun addFunctionsFromModule(result: HashMap<ResolvedEntityRef, NativeFunction>, moduleId: ModuleId) {
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

    nativeImplAdder.accept(NativeFunction(EntityId.of("Natural", "equals"), { args: List<SemObject>, _: InterpreterCallback ->
        val left = args[0] as? SemObject.Natural ?: typeError()
        val right = args[1] as? SemObject.Natural ?: typeError()

        SemObject.Boolean(left.value.equals(right.value))
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
