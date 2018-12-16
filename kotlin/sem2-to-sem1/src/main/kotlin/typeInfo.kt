package net.semlang.sem2.translate

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.sem2.api.S2Context
import net.semlang.sem2.api.S2Function
import net.semlang.validator.TypesInfo
import net.semlang.validator.getTypesInfo

fun collectTypeInfo(context: S2Context, moduleName: ModuleName): TypesInfo {
    val fakeContext = RawContext(context.functions.map(::translateForTypeOnly), listOf(), listOf(), listOf())

    val moduleId = ModuleUniqueId(moduleName, "")
    val upstreamModules = listOf<ValidatedModule>() // TODO: Support upstream modules
    val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
    return getTypesInfo(fakeContext, moduleId, CURRENT_NATIVE_MODULE_VERSION, upstreamModules, moduleVersionMappings, {})
}

private fun translateForTypeOnly(s2Function: S2Function): Function {
    val id = translate(s2Function.id)
    val typeParameters = s2Function.typeParameters.map(::translate)
    val arguments = s2Function.arguments.map(::translate)
    val returnType = translate(s2Function.returnType)

    val fakeBlock = Block(listOf(), Expression.Literal(UnvalidatedType.Integer(), "111"))
    return Function(id, typeParameters, arguments, returnType, fakeBlock, listOf())
}
