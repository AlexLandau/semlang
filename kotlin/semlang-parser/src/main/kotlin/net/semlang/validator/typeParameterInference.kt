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
            return (currentType as? Type.FunctionType ?: return null).argTypes[argumentIndex]
        }
    }
    data class FunctionTypeOutput(val containingSource: TypeParameterInferenceSource): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            val currentType = containingSource.findType(argumentTypes)
            return (currentType as? Type.FunctionType ?: return null).outputType
        }
    }
    data class NamedTypeParameter(val containingSource: TypeParameterInferenceSource, val index: Int): TypeParameterInferenceSource() {
        override fun findType(argumentTypes: List<Type?>): Type? {
            val currentType = containingSource.findType(argumentTypes)
            return (currentType as? Type.NamedType ?: return null).parameters[index]
        }
    }
}

internal fun UnvalidatedType.FunctionType.getTypeParameterInferenceSources(): List<List<TypeParameterInferenceSource>> {
    val possibleSourcesByTypeParameterName = HashMap<String, MutableList<TypeParameterInferenceSource>>()

    fun addPossibleSources(type: UnvalidatedType, sourceSoFar: TypeParameterInferenceSource) {
        val unused = when (type) {
            is UnvalidatedType.Invalid.ThreadedInteger -> TODO()
            is UnvalidatedType.Invalid.ThreadedBoolean -> TODO()
            is UnvalidatedType.Integer -> { return }
            is UnvalidatedType.Boolean -> { return }
            is UnvalidatedType.List -> {
                val listSource = TypeParameterInferenceSource.ListType(sourceSoFar)
                addPossibleSources(type.parameter, listSource)
            }
            is UnvalidatedType.Maybe -> {
                val maybeSource = TypeParameterInferenceSource.MaybeType(sourceSoFar)
                addPossibleSources(type.parameter, maybeSource)
            }
            is UnvalidatedType.FunctionType -> {
                type.argTypes.forEachIndexed { argIndex, argType ->
                    val argTypeSource = TypeParameterInferenceSource.FunctionTypeArgument(sourceSoFar, argIndex)
                    addPossibleSources(argType, argTypeSource)
                }
                val outputTypeSource = TypeParameterInferenceSource.FunctionTypeOutput(sourceSoFar)
                addPossibleSources(type.outputType, outputTypeSource)
            }
            is UnvalidatedType.NamedType -> {
                if (type.ref.moduleRef == null) {
                    val namespacedName = type.ref.id.namespacedName
                    if (namespacedName.size == 1) {
                        val name = namespacedName[0]
                        possibleSourcesByTypeParameterName.multimapPut(name, sourceSoFar)
                    }
                }
                type.parameters.forEachIndexed { parameterIndex, parameter ->
                    val parameterSource = TypeParameterInferenceSource.NamedTypeParameter(sourceSoFar, parameterIndex)
                    addPossibleSources(parameter, parameterSource)
                }
            }
        }
    }

    argTypes.forEachIndexed { index, argType ->
        val source = TypeParameterInferenceSource.ArgumentType(index)
        addPossibleSources(argType, source)
    }

    return typeParameters.map { typeParameter -> possibleSourcesByTypeParameterName[typeParameter.name] ?: listOf<TypeParameterInferenceSource>() }
}

/**
 * Returns the number of type parameters for which type inference can't be performed.
 */
internal fun UnvalidatedType.FunctionType.getRequiredTypeParameterCount(): Int {
    val sources = getTypeParameterInferenceSources()
    return sources.count { it.isEmpty() }
}