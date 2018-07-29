package net.semlang.validator

import net.semlang.api.*
import net.semlang.api.Function

data class TypeInfo(
        val functionTypes: Map<ResolvedEntityRef, FunctionInfo>
)
data class FunctionInfo(val type: Type.FunctionType)

fun getTypeInfo(context: RawContext, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): TypeInfo {
    return TypeInfoCollector(context, moduleId, nativeModuleVersion, upstreamModules).apply()
}

private class TypeInfoCollector(val context: RawContext, val moduleId: ModuleId, val nativeModuleVersion: String, val upstreamModules: List<ValidatedModule>) {
    val functionTypes = HashMap<ResolvedEntityRef, FunctionInfo>()
    val upstreamResolver = EntityResolver.create(
            moduleId,
            nativeModuleVersion,
            listOf(),
            mapOf(),
            listOf(),
            mapOf(),
            upstreamModules
    )

    fun apply(): TypeInfo {
        addFunctions()
        return TypeInfo(functionTypes)
    }

    private fun addFunctions() {
        for (function in context.functions) {
            val resolvedFunctionRef = ResolvedEntityRef(moduleId, function.id)
            functionTypes.put(resolvedFunctionRef, getFunctionInfo(function))
        }
    }

    private fun getFunctionInfo(function: Function): FunctionInfo {
        val typeParameters = function.typeParameters.map { it.name }

        val argTypes = function.arguments.map { argument -> pseudoValidateType(argument.type, typeParameters) }
        val outputType = pseudoValidateType(function.returnType, typeParameters)

        val type = Type.FunctionType(function.typeParameters, argTypes, outputType)
        return FunctionInfo(type)
    }

    private fun pseudoValidateType(type: UnvalidatedType, internalTypeParameters: List<String>): Type {
        return when (type) {
            is UnvalidatedType.Invalid.ThreadedInteger -> error("Invalid type ~Integer")
            is UnvalidatedType.Invalid.ThreadedBoolean -> error("Invalid type ~Boolean")
            is UnvalidatedType.Integer -> Type.INTEGER
            is UnvalidatedType.Boolean -> Type.BOOLEAN
            is UnvalidatedType.List -> {
                val parameter = pseudoValidateType(type.parameter, internalTypeParameters)
                Type.List(parameter)
            }
            is UnvalidatedType.Maybe -> {
                val parameter = pseudoValidateType(type.parameter, internalTypeParameters)
                Type.Maybe(parameter)
            }
            is UnvalidatedType.FunctionType -> {
                val newInternalTypeParameters = type.typeParameters.map { it.name } + internalTypeParameters

                val argTypes = type.argTypes.map { pseudoValidateType(it, newInternalTypeParameters) }
                val outputType = pseudoValidateType(type.outputType, newInternalTypeParameters)

                Type.FunctionType(type.typeParameters, argTypes, outputType)
            }
            is UnvalidatedType.NamedType -> {
                val ref = type.ref
                if (ref.moduleRef == null
                        && ref.id.namespacedName.size == 1) {
                    val index = internalTypeParameters.indexOf(ref.id.namespacedName[0])
                    if (index >= 0) {
                        return Type.InternalParameterType(index)
                    }
                }

                val parameters = type.parameters.map { pseudoValidateType(it, internalTypeParameters) }

                val resolved = upstreamResolver.resolve(ref)
                val resolvedRef = if (resolved != null) {
                    resolved.entityRef
                } else {
                    ResolvedEntityRef(moduleId, ref.id)
                }

                // TODO: Should this also be capable of returning Type.ParameterType?
                return Type.NamedType(resolvedRef, ref, type.isThreaded, parameters)
            }
        }
    }
}
