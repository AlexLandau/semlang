package net.semlang.validator

import net.semlang.api.Type
import net.semlang.api.UnvalidatedType

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
            return (currentType as? Type.NamedType ?: return null).parameters.getOrNull(index)
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

sealed class UnvalidatedTypeParameterInferenceSource {
    /*
     * Note: This is not responsible for reporting errors if the types are incompatible with the expected types. That's
     * left to other code to deal with.
     */
    abstract fun findType(argumentTypes: List<UnvalidatedType?>): UnvalidatedType?

    data class ArgumentType(val index: Int): UnvalidatedTypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<UnvalidatedType?>): UnvalidatedType? {
            return argumentTypes[index]
        }
    }
    data class FunctionTypeArgument(val containingSource: UnvalidatedTypeParameterInferenceSource, val argumentIndex: Int): UnvalidatedTypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<UnvalidatedType?>): UnvalidatedType? {
            val currentType = containingSource.findType(argumentTypes)
            val functionType = currentType as? UnvalidatedType.FunctionType ?: return null
            return functionType.argTypes[argumentIndex]
        }
    }
    data class FunctionTypeOutput(val containingSource: UnvalidatedTypeParameterInferenceSource): UnvalidatedTypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<UnvalidatedType?>): UnvalidatedType? {
            val currentType = containingSource.findType(argumentTypes)
            val functionType = currentType as? UnvalidatedType.FunctionType ?: return null
            return functionType.outputType
        }
    }
    data class NamedTypeParameter(val containingSource: UnvalidatedTypeParameterInferenceSource, val index: Int): UnvalidatedTypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<UnvalidatedType?>): UnvalidatedType? {
            val currentType = containingSource.findType(argumentTypes)
            val parameters = (currentType as? UnvalidatedType.NamedType ?: return null).parameters
            if (parameters.size > index) {
                return parameters[index]
            } else {
                return null
            }
        }
    }
}

// TODO: See if this can be merged somehow with the post-validation implementation
fun getTypeParameterInferenceSources(type: UnvalidatedType.FunctionType): List<List<UnvalidatedTypeParameterInferenceSource>> {
    val allPossibleSources = ArrayList<MutableList<UnvalidatedTypeParameterInferenceSource>>()
    for (parameter in type.typeParameters) {
        allPossibleSources.add(ArrayList())
    }
    val nameToIndex: Map<String, Int> = type.typeParameters.mapIndexed { index, typeParameter -> typeParameter.name to index }.toMap()

    fun addPossibleSources(type: UnvalidatedType, sourceSoFar: UnvalidatedTypeParameterInferenceSource) {
        val unused: Any = when (type) {
            is UnvalidatedType.FunctionType -> {
                type.argTypes.forEachIndexed { argIndex, argType ->
                    val argTypeSource = UnvalidatedTypeParameterInferenceSource.FunctionTypeArgument(sourceSoFar, argIndex)
                    addPossibleSources(argType, argTypeSource)
                }
                val outputTypeSource = UnvalidatedTypeParameterInferenceSource.FunctionTypeOutput(sourceSoFar)
                addPossibleSources(type.outputType, outputTypeSource)
            }
            is UnvalidatedType.NamedType -> {
                if (type.ref.moduleRef == null && type.ref.id.namespacedName.size == 1) {
                    val typeName = type.ref.id.namespacedName[0]
                    val index = nameToIndex[typeName]
                    if (index != null) {
                        allPossibleSources[index].add(sourceSoFar)
                    }
                }

                type.parameters.forEachIndexed { parameterIndex, parameter ->
                    val parameterSource = UnvalidatedTypeParameterInferenceSource.NamedTypeParameter(sourceSoFar, parameterIndex)
                    addPossibleSources(parameter, parameterSource)
                }
            }
        }
    }

    type.argTypes.forEachIndexed { index, argType ->
        val source = UnvalidatedTypeParameterInferenceSource.ArgumentType(index)
        addPossibleSources(argType, source)
    }

    return allPossibleSources
}
