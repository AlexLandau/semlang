package net.semlang.sem2.translate

import net.semlang.api.getAllNativeFunctionLikeDefinitions
import net.semlang.sem2.api.EntityId
import net.semlang.sem2.api.S2Context
import net.semlang.sem2.api.S2FunctionSignature

// TODO: This should probably be unified with the sem1 notion of typeInfo; probably just need to find the right location
// for that

// TODO: EntityId is probably not the right ID for this =(
data class TypeInfo(val functionSignatures: Map<EntityId, S2FunctionSignature>)

fun collectTypeInfo(context: S2Context): TypeInfo {
    val signatures = HashMap<EntityId, S2FunctionSignature>()
    for (function in context.functions) {
        signatures.put(function.id, function.getSignature())
    }
    // TODO: Collect others

    // Add natives...
    // TODO: Particularly good case for unifying with sem1... (unless split out into separate definition module? Even so...)
    for ((functionId, functionSignature) in getAllNativeFunctionLikeDefinitions()) {
        signatures.put(functionId, functionSignature)
    }

    return TypeInfo(signatures)
}