package net.semlang.transforms

import net.semlang.api.*
import net.semlang.api.Function

// Note: Only converts new interfaces in the module itself
// TODO: Prevent certain name collisions
// TODO: This probably also has subtle errors around names shared by multiple modules
fun transformInterfacesToStructs(module: RawContext): RawContext {
    val converter = InterfaceToStructConverter(module)

    return converter.convert()
}

/*
 * Say we have an interface:
 *
 * Interface {
 *   foo(blah: Integer): Boolean
 *   bar(): Natural
 * }
 *
 * This would normally generate the methods:
 * Interface.Adapter<T>(foo: (T, Integer) -> Boolean, bar: (T) -> Natural): Interface.Adapter<T>
 * Interface<T>(data: T, adapter: Interface.Adapter<T>): Interface
 *
 * So instead we have:
 *
 * struct Interface.Adapter<T> {
 *   // The usual
 *   foo: (T, Integer) -> Boolean
 *   bar: (T) -> Natural
 * }
 * struct Interface {
 *   // Same semantics as the instance type
 *   foo: (Integer) -> Boolean
 *   bar: () -> Natural
 * }
 * // We replace all existing uses of the instance constructor with a function like this
 * function Interface.adapt<T>(data: T, adapter: Interface.Adapter<T>): Interface {
 *   return Interface(
 *     // All arguments after data remain unbound
 *     adapter->foo|(data, _),
 *     adapter->bar|(data)
 *   )
 * }
 */
private class InterfaceToStructConverter(private val context: RawContext) {
    private val functionReplacementsToMake = HashMap<EntityId, EntityId>()
    private val newFunctions = ArrayList<Function>()
    private val newStructs = ArrayList<UnvalidatedStruct>()
    fun convert(): RawContext {
        generateReplacementEntities()

        return RawContext(getFunctions(), getStructs(), listOf())
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
            generateAdapterStruct(interfac)
            generateAdaptFunction(interfac)
        }
    }

    private fun generateAdaptFunction(interfac: UnvalidatedInterface) {
        val adaptFunctionId = EntityId(interfac.id.namespacedName + "adapt")
        val dataParameterType = UnvalidatedType.NamedType.forParameter(interfac.getAdapterStruct().typeParameters[0])
        val adapterType = UnvalidatedType.NamedType(interfac.adapterId.asRef(), false, interfac.getAdapterStruct().typeParameters.map { param -> UnvalidatedType.NamedType.forParameter(param) })
        val arguments = listOf(
            UnvalidatedArgument("data", dataParameterType),
            UnvalidatedArgument("adapter", adapterType)
        )
        val instanceType = UnvalidatedType.NamedType(interfac.id.asRef(), false, interfac.typeParameters.map { param -> UnvalidatedType.NamedType.forParameter(param) })
        val block = Block(listOf(),
                Expression.NamedFunctionCall(
                        interfac.id.asRef(),
                        interfac.methods.map { method ->
                            val adapterFunctionType = UnvalidatedType.FunctionType(listOf(dataParameterType) + method.functionType.argTypes,
                                    method.returnType)
                            Expression.ExpressionFunctionBinding(
                                    Expression.Follow(
                                            Expression.Variable("adapter"),
                                            method.name,
                                            null
                                    ),
                                    listOf(Expression.Variable("data"))
                                            + method.arguments.map { argument -> null },
                                    listOf(),
                                    null
                            )
                        },
                        instanceType.parameters,
                        null,
                        null
                ),
                null
        )
        val function = Function(adaptFunctionId, interfac.getAdapterStruct().typeParameters, arguments,
                instanceType, block, listOf())
        newFunctions.add(function)
        functionReplacementsToMake[interfac.id] = adaptFunctionId
    }

    private fun generateAdapterStruct(interfac: UnvalidatedInterface) {
        newStructs.add(interfac.getAdapterStruct())
    }

    private fun generateInstanceStruct(interfac: UnvalidatedInterface) {
        val members = interfac.methods.map { method ->
            UnvalidatedMember(method.name, method.functionType)
        }
        val struct = UnvalidatedStruct(interfac.id, interfac.typeParameters,
                members, null, interfac.annotations)
        newStructs.add(struct)
    }
}


