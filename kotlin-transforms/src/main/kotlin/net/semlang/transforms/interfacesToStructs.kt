package net.semlang.transforms

import net.semlang.api.*
import net.semlang.transforms.replaceLocalFunctionNameReferences

// Note: Only converts new interfaces in the module itself
// TODO: I really shouldn't be returning ValidatedModules in these...
// TODO: Prevent certain name collisions
// TODO: This probably also has subtle errors around names shared by multiple modules
fun transformInterfacesToStructs(module: ValidatedModule): ValidatedModule {
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
private class InterfaceToStructConverter(private val module: ValidatedModule) {
    private val functionReplacementsToMake = HashMap<EntityId, EntityId>()
    private val newFunctions = ArrayList<ValidatedFunction>()
    private val newStructs = ArrayList<Struct>()
    fun convert(): ValidatedModule {
        generateReplacementEntities()

        return ValidatedModule.create(module.id, module.nativeModuleVersion,
                getFunctions(),
                getStructs(),
                mapOf(),
                module.upstreamModules.values)
    }

    private fun getStructs(): Map<EntityId, Struct> {
        val transformedOldStructs = module.ownStructs.mapValues { (_, oldStruct) ->
            val requires = oldStruct.requires
            if (requires != null) {
                oldStruct.copy(requires = replaceLocalFunctionNameReferences(requires, functionReplacementsToMake))
            } else {
                oldStruct
            }
        }
        // TODO: Check for conflicts?
        return transformedOldStructs + newStructs.associateBy(Struct::id)
    }

    private fun getFunctions(): Map<EntityId, ValidatedFunction> {
        val transformedOldFunctions = module.ownFunctions.mapValues { (_, oldFunction) ->
            replaceLocalFunctionNameReferences(oldFunction, functionReplacementsToMake)
        }
        // TODO: Check for conflicts?
        return transformedOldFunctions + newFunctions.associateBy(ValidatedFunction::id)
    }

    private fun generateReplacementEntities() {
        module.ownInterfaces.values.forEach { interfac ->
            generateInstanceStruct(interfac)
            generateAdapterStruct(interfac)
            generateAdaptFunction(interfac)
        }
    }

    private fun generateAdaptFunction(interfac: Interface) {
        val adaptFunctionId = EntityId(interfac.id.namespacedName + "adapt")
        val dataParameterType = Type.NamedType.forParameter(interfac.adapterStruct.typeParameters[0])
        val adapterType = Type.NamedType(interfac.adapterId.asRef(), interfac.adapterStruct.typeParameters.map { param -> Type.NamedType.forParameter(param) })
        val arguments = listOf(
            Argument("data", dataParameterType),
            Argument("adapter", adapterType)
        )
        val instanceType = Type.NamedType(interfac.id.asRef(), interfac.typeParameters.map { param -> Type.NamedType.forParameter(param) })
        val block = TypedBlock(instanceType, listOf(),
                TypedExpression.NamedFunctionCall(
                        instanceType,
                        interfac.id.asRef(),
                        interfac.methods.map { method ->
                            val adapterFunctionType = Type.FunctionType(listOf(dataParameterType) + method.functionType.argTypes,
                                    method.returnType)
                            TypedExpression.ExpressionFunctionBinding(
                                    method.functionType,
                                    TypedExpression.Follow(
                                            adapterFunctionType,
                                            TypedExpression.Variable(adapterType, "adapter"),
                                            method.name
                                    ),
                                    listOf(TypedExpression.Variable(dataParameterType, "data"))
                                    + method.arguments.map { argument -> null },
                                    listOf()
                            )
                        },
                        listOf()
                )
        )
        val function = ValidatedFunction(adaptFunctionId, interfac.adapterStruct.typeParameters, arguments,
                instanceType, block, listOf())
        newFunctions.add(function)
        functionReplacementsToMake[interfac.id] = adaptFunctionId
    }

    private fun generateAdapterStruct(interfac: Interface) {
        newStructs.add(interfac.adapterStruct)
    }

    private fun generateInstanceStruct(interfac: Interface) {
        val members = interfac.methods.map { method ->
            Member(method.name, method.functionType)
        }
        val struct = Struct(interfac.id, interfac.typeParameters,
                members, null, interfac.annotations)
        newStructs.add(struct)
    }
}


