package net.semlang.sem2.translate

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.sem2.api.*
import net.semlang.transforms.invalidate
import net.semlang.validator.*

fun collectTypesSummary(context: S2Context): TypesSummary {
    val fakeContext = RawContext(
        context.functions.map(::translateForTypeOnly),
        context.structs.map(::translateForTypeOnly),
        context.unions.map(::translateForTypeOnly))

    return getTypesSummary(fakeContext, {})
}

fun collectTypeInfo(context: S2Context, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): TypesInfo {
    val summary = collectTypesSummary(context)

    // TODO: Support module versions correctly...
    val moduleId = ModuleUniqueId(moduleName, "")
    val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
    return getTypesInfoFromSummary(summary, moduleId, upstreamModules, moduleVersionMappings, {})
}

private val fakeBlock = Block(listOf(
    Statement.Bare(Expression.Literal(invalidate(NativeOpaqueType.INTEGER.getType()), "111"))
))

private fun translateForTypeOnly(s2Function: S2Function): Function {
    val id = translate(s2Function.id)
    val typeParameters = s2Function.typeParameters.map(::translate)
    val arguments = s2Function.arguments.map(::translate)
    val returnType = translate(s2Function.returnType)

    return Function(id, typeParameters, arguments, returnType, fakeBlock, listOf())
}

private fun translateForTypeOnly(s2Struct: S2Struct): UnvalidatedStruct {
    val id = translate(s2Struct.id)
    val typeParameters = s2Struct.typeParameters.map(::translate)
    val members = s2Struct.members.map(::translate)

    val requires = if (s2Struct.requires != null) fakeBlock else null

    return UnvalidatedStruct(id, typeParameters, members, requires, listOf())
}

private fun translateForTypeOnly(s2Union: S2Union): UnvalidatedUnion {
    val id = translate(s2Union.id)
    val typeParameters = s2Union.typeParameters.map(::translate)
    val options = s2Union.options.map(::translate)

    return UnvalidatedUnion(id, typeParameters, options, listOf())
}
