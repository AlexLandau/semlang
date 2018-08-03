package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function
import java.util.*

// Note: Only converts new interfaces in the module itself
// TODO: Prevent certain name collisions (e.g. method named "data")
// TODO: This probably also has subtle errors around names shared by multiple modules
fun transformInterfacesToStructs(module: RawContext): RawContext {
    val converter = InterfaceToStructConverter(module)

    return converter.convert()
}

private class InterfaceToStructConverter(private val context: RawContext) {
    // TODO: Remove functionReplacementsToMake
    private val functionReplacementsToMake = HashMap<EntityId, EntityId>()
    private val newFunctions = ArrayList<Function>()
    private val newStructs = ArrayList<UnvalidatedStruct>()
    fun convert(): RawContext {
        generateReplacementEntities()

        return RawContext(getFunctions(), getStructs(), listOf(), context.unions)
    }

    private fun getStructs(): List<UnvalidatedStruct> {
        val transformedOldStructs = context.structs.map { oldStruct ->
            val requires = oldStruct.requires
            if (requires != null) {
                oldStruct.copy(requires = replaceLocalFunctionNameReferences(requires, functionReplacementsToMake))
            } else {
                oldStruct
            }
        }
        // TODO: Check for conflicts?
        return transformedOldStructs + newStructs
    }

    private fun getFunctions(): List<Function> {
        val transformedOldFunctions = context.functions.map { oldFunction ->
            replaceLocalFunctionNameReferences(oldFunction, functionReplacementsToMake)
        }
        // TODO: Check for conflicts?
        return transformedOldFunctions + newFunctions
    }

    private fun generateReplacementEntities() {
        for (interfac in context.interfaces) {
            generateInstanceStruct(interfac)
            generateAdapterFunction(interfac)
        }
    }

    private fun generateAdapterFunction(interfac: UnvalidatedInterface) {
        val adapterFunctionId = interfac.adapterId
        val dataParameterType = interfac.dataType
        val typeParameters = listOf(interfac.dataTypeParameter) + interfac.typeParameters

        val arguments = interfac.methods.map { method ->
            val methodFunctionType = method.functionType
            val newArgumentTypes = listOf(dataParameterType) + methodFunctionType.argTypes

            UnvalidatedArgument(method.name, methodFunctionType.copy(argTypes = newArgumentTypes))
        }

        val outputType = UnvalidatedType.FunctionType(listOf(), listOf(dataParameterType), interfac.instanceType)

        val interfaceParameterTypes = interfac.typeParameters.map { name -> UnvalidatedType.NamedType.forParameter(name) }

        val block = Block(listOf(),
                Expression.InlineFunction(
                        arguments = listOf(UnvalidatedArgument("data", dataParameterType)),
                        returnType = interfac.instanceType,
                        block = Block(listOf(),
                                Expression.NamedFunctionCall(
                                        functionRef = interfac.id.asRef(),
                                        arguments = interfac.methods.map { method ->
                                            Expression.ExpressionFunctionBinding(
                                                    functionExpression = Expression.Variable(method.name),
                                                    chosenParameters = listOf(),
                                                    bindings = listOf(Expression.Variable("data")) + Collections.nCopies(method.arguments.size, null)
                                            )
                                        },
                                        chosenParameters = interfaceParameterTypes // TODO: Probably wrong, work through with real case
                                )
                        )
                )
        )

        val function = Function(adapterFunctionId, typeParameters, arguments, outputType, block, listOf())
        newFunctions.add(function)
    }

    private fun generateInstanceStruct(interfac: UnvalidatedInterface) {
        val members = interfac.methods.map { method ->
            UnvalidatedMember(method.name, method.functionType)
        }
        val struct = UnvalidatedStruct(interfac.id, false, interfac.typeParameters,
                members, null, interfac.annotations)
        newStructs.add(struct)
    }
}


