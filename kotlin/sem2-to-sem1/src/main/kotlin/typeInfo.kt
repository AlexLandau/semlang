package net.semlang.sem2.translate

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.sem2.api.EntityId
import net.semlang.sem2.api.S2Context
import net.semlang.sem2.api.S2Function
import net.semlang.sem2.api.S2FunctionSignature
import net.semlang.validator.TypesInfo
import net.semlang.validator.getTypesInfo

// TODO: This should probably be unified with the sem1 notion of typeInfo; probably just need to find the right location
// for that

// TODO: EntityId is probably not the right ID for this =(
//data class TypeInfo(val functionSignatures: Map<EntityId, S2FunctionSignature>)

fun collectTypeInfo(context: S2Context, moduleName: ModuleName): TypesInfo {
    val fakeContext = RawContext(context.functions.map(::translateForTypeOnly), listOf(), listOf(), listOf())

    val moduleId = ModuleUniqueId(moduleName, "")
    val upstreamModules = listOf<ValidatedModule>() // TODO: Support
    val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
    return getTypesInfo(fakeContext, moduleId, CURRENT_NATIVE_MODULE_VERSION, upstreamModules, moduleVersionMappings, {})

//    val signatures = HashMap<EntityId, S2FunctionSignature>()
//    for (function in context.functions) {
//        signatures.put(function.id, function.getSignature())
//    }
//    // TODO: Collect others
//
//    // Add natives...
//    // TODO: Particularly good case for unifying with sem1... (unless split out into separate definition module? Even so...)
//    for ((functionId, functionSignature) in getAllNativeFunctionLikeDefinitions()) {
//        signatures.put(functionId, functionSignature)
//    }
//
//    return TypesInfo(signatures)
}

private fun translateForTypeOnly(s2Function: S2Function): Function {
    val id = translate(s2Function.id)
    val typeParameters = s2Function.typeParameters.map(::translate)
    val arguments = s2Function.arguments.map(::translate)
    val returnType = translate(s2Function.returnType)

    val fakeBlock = Block(listOf(), Expression.Literal(UnvalidatedType.Integer(), "111"))
    return Function(id, typeParameters, arguments, returnType, fakeBlock, listOf())
}
