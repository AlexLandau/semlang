package net.semlang.sem2.translate

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.sem2.api.*
import net.semlang.validator.TypesInfo
import net.semlang.validator.getTypesInfo

fun collectTypeInfo(context: S2Context, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): TypesInfo {
    val fakeContext = RawContext(
            context.functions.map(::translateForTypeOnly),
            context.structs.map(::translateForTypeOnly),
            context.interfaces.map(::translateForTypeOnly),
            context.unions.map(::translateForTypeOnly))

    // TODO: Support module versions correctly...
    val moduleId = ModuleUniqueId(moduleName, "")
    val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
    return getTypesInfo(fakeContext, moduleId, CURRENT_NATIVE_MODULE_VERSION, upstreamModules, moduleVersionMappings, {})
}

private val fakeBlock = Block(listOf(), Expression.Literal(UnvalidatedType.Integer(), "111"))

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

private fun translateForTypeOnly(s2Interface: S2Interface): UnvalidatedInterface {
    val id = translate(s2Interface.id)
    val typeParameters = s2Interface.typeParameters.map(::translate)
    val methods = s2Interface.methods.map(::translate)

    return UnvalidatedInterface(id, typeParameters, methods, listOf())
}

private fun translateForTypeOnly(s2Union: S2Union): UnvalidatedUnion {
    val id = translate(s2Union.id)
    val typeParameters = s2Union.typeParameters.map(::translate)
    val options = s2Union.options.map(::translate)

    return UnvalidatedUnion(id, typeParameters, options, listOf())
}
