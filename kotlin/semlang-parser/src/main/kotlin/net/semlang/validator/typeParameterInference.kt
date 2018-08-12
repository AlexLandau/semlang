package net.semlang.validator

import net.semlang.api.Type
import net.semlang.api.UnvalidatedType
import net.semlang.api.UnvalidatedTypeSignature

internal sealed class TypeParameterInferenceSource {
    /*
     * Note: This is not responsible for reporting errors if the types are incompatible with the expected types. That's
     * left to other code to deal with.
     */
    abstract fun findType(argumentTypes: List<Type?>): Type?

    data class ArgumentType(val index: Int): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            return argumentTypes[index]
        }
    }
    data class ListType(val containingSource: TypeParameterInferenceSource): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            val currentType = containingSource.findType(argumentTypes)
            return (currentType as? Type.List ?: return null).parameter
        }
    }
    data class MaybeType(val containingSource: TypeParameterInferenceSource): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            val currentType = containingSource.findType(argumentTypes)
            return (currentType as? Type.Maybe ?: return null).parameter
        }
    }
    data class FunctionTypeArgument(val containingSource: TypeParameterInferenceSource, val argumentIndex: Int): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            val currentType = containingSource.findType(argumentTypes)
            val functionType = currentType as? Type.FunctionType ?: return null
            val groundFunctionType = functionType.getDefaultGrounding()
            return groundFunctionType.argTypes[argumentIndex]
        }
    }
    data class FunctionTypeOutput(val containingSource: TypeParameterInferenceSource): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            val currentType = containingSource.findType(argumentTypes)
            val functionType = currentType as? Type.FunctionType ?: return null
            val groundFunctionType = functionType.getDefaultGrounding()
            return groundFunctionType.outputType
        }
    }
    data class NamedTypeParameter(val containingSource: TypeParameterInferenceSource, val index: Int): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            val currentType = containingSource.findType(argumentTypes)
            return (currentType as? Type.NamedType ?: return null).parameters[index]
        }
    }
}

internal fun Type.FunctionType.Parameterized.getTypeParameterInferenceSources(): List<List<TypeParameterInferenceSource>> {
    val allPossibleSources = ArrayList<MutableList<TypeParameterInferenceSource>>()
    for (parameter in this.typeParameters) {
        allPossibleSources.add(ArrayList())
    }

    fun addPossibleSources(type: Type, sourceSoFar: TypeParameterInferenceSource, indexOffset: Int) {
        val unused: Any = when (type) {
            is Type.INTEGER -> { return }
            is Type.BOOLEAN -> { return }
            is Type.List -> {
                val listSource = TypeParameterInferenceSource.ListType(sourceSoFar)
                addPossibleSources(type.parameter, listSource, indexOffset)
            }
            is Type.Maybe -> {
                val maybeSource = TypeParameterInferenceSource.MaybeType(sourceSoFar)
                addPossibleSources(type.parameter, maybeSource, indexOffset)
            }
            is Type.FunctionType.Ground -> {
                type.argTypes.forEachIndexed { argIndex, argType ->
                    val argTypeSource = TypeParameterInferenceSource.FunctionTypeArgument(sourceSoFar, argIndex)
                    addPossibleSources(argType, argTypeSource, indexOffset)
                }
                val outputTypeSource = TypeParameterInferenceSource.FunctionTypeOutput(sourceSoFar)
                addPossibleSources(type.outputType, outputTypeSource, indexOffset)
            }
            is Type.FunctionType.Parameterized -> {
                // Reminder: In the example <T>(<U>(U, T) -> Bool) -> T,
                // first U is 0, first T is 1, outer T is 0
                val newIndexOffset = indexOffset + type.typeParameters.size
                type.argTypes.forEachIndexed { argIndex, argType ->
                    val argTypeSource = TypeParameterInferenceSource.FunctionTypeArgument(sourceSoFar, argIndex)
                    addPossibleSources(argType, argTypeSource, newIndexOffset)
                }
                val outputTypeSource = TypeParameterInferenceSource.FunctionTypeOutput(sourceSoFar)
                addPossibleSources(type.outputType, outputTypeSource, newIndexOffset)
            }
            is Type.NamedType -> {
//                if (type.ref.moduleRef == null) {
//                    val namespacedName = type.ref.id.namespacedName
//                    if (namespacedName.size == 1) {
//                        val name = namespacedName[0]
//                        possibleSourcesByTypeParameterName.multimapPut(name, sourceSoFar)
//                    }
//                }
                type.parameters.forEachIndexed { parameterIndex, parameter ->
                    val parameterSource = TypeParameterInferenceSource.NamedTypeParameter(sourceSoFar, parameterIndex)
                    addPossibleSources(parameter, parameterSource, indexOffset)
                }
            }
            is Type.InternalParameterType -> {
                val index = type.index + indexOffset
                allPossibleSources[index].add(sourceSoFar)
            }
            is Type.ParameterType -> {
                // Do nothing
            }
        }
    }

    argTypes.forEachIndexed { index, argType ->
        val source = TypeParameterInferenceSource.ArgumentType(index)
        addPossibleSources(argType, source, 0)
    }

    return allPossibleSources
}
