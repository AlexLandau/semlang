package net.semlang.linker

import net.semlang.api.*
import net.semlang.api.Annotation
import net.semlang.api.Function
import net.semlang.api.RawContext
import net.semlang.api.ResolvedEntityRef
import net.semlang.api.ValidatedFunction
import net.semlang.api.ValidatedModule
import net.semlang.parser.ModuleInfo
import net.semlang.parser.UnvalidatedModule
import java.util.*

/**
 * Links a module with its dependencies, producing a module with no dependencies.
 *
 * Note that interpreters and transpilers need not use up-front linking; they may see benefits from reusing modules
 * across execution environments, which this approach may impair.
 *
 * Note: This accepts a ValidatedModule to reduce the chance that this will be called on a module that breaks visibility
 * rules (which would not be caught after this transformation). It produces an UnvalidatedModule mainly to protect against
 * potential bugs in the implementation.
 *
 * TODO: This currently exposes all internal entities, not exported entities. We should offer both modes.
 */
fun linkModuleWithDependencies(module: ValidatedModule): UnvalidatedModule {
    return Linker(module).link()
}

private class Linker(val rootModule: ValidatedModule) {

    fun link(): UnvalidatedModule {
        // Get the subset of entities in all referenced modules that could be referenced by calling code from the root
        // module.
        val relevantEntities = RelevantEntitiesFinder(rootModule).compute()

        val nameAssignment = NameAssigner(rootModule.id, relevantEntities).assignNames()

        val transformedFunctions = relevantEntities.functions.map { (ref, function) -> nameAssignment.applyToFunction(ref, function) }
        val transformedStructs = relevantEntities.structs.values.map(nameAssignment::applyToStruct)
        val transformedInterfaces = relevantEntities.interfaces.values.map(nameAssignment::applyToInterface)
        val transformedUnions = relevantEntities.unions.values.map(nameAssignment::applyToUnion)

        val moduleInfo = ModuleInfo(rootModule.id, listOf())
        val context = RawContext(transformedFunctions, transformedStructs, transformedInterfaces, transformedUnions)
        return UnvalidatedModule(moduleInfo, context)
    }
}

private data class RelevantEntities(
        val allModules: Map<ModuleId, ValidatedModule>,
        val functions: Map<ResolvedEntityRef, ValidatedFunction>,
        val structs: Map<ResolvedEntityRef, Struct>,
        val interfaces: Map<ResolvedEntityRef, Interface>,
        val unions: Map<ResolvedEntityRef, Union>
)

private data class NameAssignment(val newNames: Map<ResolvedEntityRef, EntityId>, val rootModuleId: ModuleId) {
    private val EXPORT_ANNOTATION = Annotation(EntityId.of("Export"), listOf())

    private fun translateRef(ref: ResolvedEntityRef): EntityRef {
        if (isNativeModule(ref.module)) {
            // TODO: This will need collision protection against redefined names, but adding a full moduleRef to everything
            // would be totally obnoxious
            return EntityRef(null, ref.id)
        } else {
            val newName = newNames[ref] ?: error("There was no new name specified for $ref")
            return EntityRef(null, newName)
        }
    }

    fun applyToFunction(ref: ResolvedEntityRef, function: ValidatedFunction): Function {
        val newId = newNames[ref]!!
        val arguments = function.arguments.map(this::apply)
        val returnType = apply(function.returnType)
        val block = apply(function.block)
        val annotations = handleAnnotations(function.annotations, ref.module)

        return Function(newId, function.typeParameters, arguments, returnType, block, annotations)
    }

    // Remove the "Export" annotation from entities not in the root module
    private fun handleAnnotations(annotations: List<Annotation>, entityModule: ModuleId): List<Annotation> {
        if (entityModule == rootModuleId) {
            return annotations
        }
        return annotations - listOf(EXPORT_ANNOTATION)
    }

    private fun apply(block: TypedBlock): Block {
        val assignments = block.assignments.map(this::apply)
        val returnedExpression = apply(block.returnedExpression)
        return Block(assignments, returnedExpression)
    }

    private fun apply(assignment: ValidatedAssignment): Assignment {
        val expression = apply(assignment.expression)
        return Assignment(assignment.name, null, expression)
    }

    private fun apply(expression: TypedExpression): Expression {
        return when (expression) {
            is TypedExpression.Variable -> {
                Expression.Variable(expression.name)
            }
            is TypedExpression.IfThen -> {
                val condition = apply(expression.condition)
                val thenBlock = apply(expression.thenBlock)
                val elseBlock = apply(expression.elseBlock)
                Expression.IfThen(condition, thenBlock, elseBlock)
            }
            is TypedExpression.NamedFunctionCall -> {
                val functionRef = translateRef(expression.resolvedFunctionRef)
                val arguments = expression.arguments.map(this::apply)
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.NamedFunctionCall(functionRef, arguments, chosenParameters)
            }
            is TypedExpression.ExpressionFunctionCall -> {
                val functionExpression = apply(expression.functionExpression)
                val arguments = expression.arguments.map(this::apply)
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters)
            }
            is TypedExpression.Literal -> {
                val type = apply(expression.type)
                Expression.Literal(type, expression.literal)
            }
            is TypedExpression.ListLiteral -> {
                val contents = expression.contents.map(this::apply)
                val chosenParameter = apply(expression.chosenParameter)
                Expression.ListLiteral(contents, chosenParameter)
            }
            is TypedExpression.NamedFunctionBinding -> {
                val functionRef = translateRef(expression.resolvedFunctionRef)
                val bindings = expression.bindings.map { if (it == null) null else apply(it) }
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.NamedFunctionBinding(functionRef, bindings, chosenParameters)
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                val functionExpression = apply(expression.functionExpression)
                val bindings = expression.bindings.map { if (it == null) null else apply(it) }
                val chosenParameters = expression.chosenParameters.map(this::apply)
                Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters)
            }
            is TypedExpression.Follow -> {
                val structureExpression = apply(expression.structureExpression)
                Expression.Follow(structureExpression, expression.name)
            }
            is TypedExpression.InlineFunction -> {
                val arguments = expression.arguments.map(this::apply)
                val returnType = apply(expression.returnType)
                val block = apply(expression.block)
                Expression.InlineFunction(arguments, returnType, block)
            }
        }
    }

    private fun apply(argument: Argument): UnvalidatedArgument {
        return UnvalidatedArgument(argument.name, apply(argument.type))
    }

    private fun apply(type: Type): UnvalidatedType {
        return when (type) {
            Type.INTEGER -> UnvalidatedType.Integer()
            Type.BOOLEAN -> UnvalidatedType.Boolean()
            is Type.List -> UnvalidatedType.List(apply(type.parameter))
            is Type.Maybe -> UnvalidatedType.Maybe(apply(type.parameter))
            is Type.FunctionType -> {
                val argTypes = type.argTypes.map(this::apply)
                val outputType = apply(type.outputType)
                UnvalidatedType.FunctionType(argTypes, outputType)
            }
            is Type.NamedType -> {
                val newRef = translateRef(type.ref)
                val parameters = type.parameters.map(this::apply)
                UnvalidatedType.NamedType(newRef, type.isThreaded(), parameters)
            }
            is Type.ParameterType -> {
                val newRef = EntityRef.of(type.parameter.name)
                UnvalidatedType.NamedType(newRef, false)
            }
        }
    }

    fun applyToStruct(struct: Struct): UnvalidatedStruct {
        val newId = newNames[struct.resolvedRef]!!
        val members = struct.members.map(this::apply)
        val requires = struct.requires?.let(this::apply)
        val annotations = handleAnnotations(struct.annotations, struct.moduleId)

        return UnvalidatedStruct(newId, struct.isThreaded, struct.typeParameters, members, requires, annotations)
    }

    private fun apply(member: Member): UnvalidatedMember {
        val type = apply(member.type)
        return UnvalidatedMember(member.name, type)
    }

    fun applyToInterface(interfac: Interface): UnvalidatedInterface {
        val newId = newNames[interfac.resolvedRef]!!
        val methods = interfac.methods.map(this::apply)
        val annotations = handleAnnotations(interfac.annotations, interfac.moduleId)

        return UnvalidatedInterface(newId, interfac.typeParameters, methods, annotations)
    }

    private fun apply(method: Method): UnvalidatedMethod {
        val arguments = method.arguments.map(this::apply)
        val returnType = apply(method.returnType)
        return UnvalidatedMethod(method.name, method.typeParameters, arguments, returnType)
    }

    fun applyToUnion(union: Union): UnvalidatedUnion {
        val newId = newNames[union.resolvedRef]!!
        val options = union.options.map(this::apply)
        val annotations = handleAnnotations(union.annotations, union.moduleId)

        return UnvalidatedUnion(newId, union.typeParameters, options, annotations)
    }

    private fun apply(option: Option): UnvalidatedOption {
        val type = option.type?.let { apply(it) }
        return UnvalidatedOption(option.name, type)
    }
}

private class NameAssigner(val rootModuleId: ModuleId, val relevantEntities: RelevantEntities) {
    val newNameMap = HashMap<ResolvedEntityRef, EntityId>()
    val allNewNames = HashSet<EntityId>()

    fun assignNames(): NameAssignment {
        // We want to keep everything in the root module the same.
        // For now, we do this by just doing two passes.
        for (ref in relevantEntities.functions.keys + relevantEntities.structs.keys + relevantEntities.interfaces.keys + relevantEntities.unions.keys) {
            if (ref.module == rootModuleId) {
                if (allNewNames.contains(ref.id)) {
                    error("EntityId collision in the root module: $ref")
                }
                newNameMap.put(ref, ref.id)
                allNewNames.add(ref.id)
            }
        }
        for ((ref, interfac) in relevantEntities.interfaces) {
            if (ref.module == rootModuleId) {
                val adapterId = interfac.adapterId
                if (allNewNames.contains(adapterId)) {
                    error("EntityId collision in the root module: $adapterId")
                }
                newNameMap.put(ref.copy(id = adapterId), adapterId)
                allNewNames.add(adapterId)
            }
        }
        for ((ref, union) in relevantEntities.unions) {
            if (ref.module == rootModuleId) {
                val whenId = EntityId(ref.id.namespacedName + "when")
                if (allNewNames.contains(whenId)) {
                    error("EntityId collision in the root module: $whenId")
                }
                newNameMap.put(ref.copy(id = whenId), whenId)
                allNewNames.add(whenId)
                for (option in union.options) {
                    val optionId = EntityId(ref.id.namespacedName + option.name)
                    if (allNewNames.contains(optionId)) {
                        error("EntityId collision in the root module: $optionId")
                    }
                    newNameMap.put(ref.copy(id = optionId), optionId)
                    allNewNames.add(optionId)
                }
            }
        }

        val modulePrefixes = assignModulePrefixes()

        for (ref in relevantEntities.functions.keys + relevantEntities.structs.keys + relevantEntities.interfaces.keys + relevantEntities.unions.keys) {
            if (ref.module == rootModuleId) {
                // Already handled
                continue
            }
            val initialName = getInitialName(ref, modulePrefixes)
            val finalName = ensureUnique(initialName)
            if (allNewNames.contains(finalName)) {
                error("Implementation failure in ensureUnique")
            }
            newNameMap.put(ref, finalName)
            allNewNames.add(finalName)
            // TODO: Rewrite this to be more efficient? Or can we get rid of adapter functions?
            val interfaceMaybe = relevantEntities.interfaces[ref]
            if (interfaceMaybe != null) {
                val oldAdapterId = interfaceMaybe.adapterId
                val adapterRef = ref.copy(id = oldAdapterId)
                val finalAdapterName = EntityId(finalName.namespacedName + "Adapter")
                if (allNewNames.contains(finalAdapterName)) {
                    error("Chose $finalName as the new name for interface $ref, but we're already using the adapter name $finalAdapterName")
                }

                newNameMap.put(adapterRef, finalAdapterName)
                allNewNames.add(finalAdapterName)
            }
            // TODO: Ditto here
            val unionMaybe = relevantEntities.unions[ref]
            if (unionMaybe != null) {
                val oldWhenId = unionMaybe.whenId
                val whenRef = ref.copy(id = oldWhenId)
                val finalWhenId = EntityId(finalName.namespacedName + "when")
                if (allNewNames.contains(finalWhenId)) {
                    error("")
                }
                newNameMap.put(whenRef, finalWhenId)
                allNewNames.add(finalWhenId)
                for (option in unionMaybe.options) {
                    val oldOptionId = EntityId(unionMaybe.id.namespacedName + option.name)
                    val optionRef = ref.copy(id = oldOptionId)
                    val finalOptionId = EntityId(finalName.namespacedName + option.name)
                    if (allNewNames.contains(finalOptionId)) {
                        error("")
                    }
                    newNameMap.put(optionRef, finalOptionId)
                    allNewNames.add(finalOptionId)
                }
            }
        }
        return NameAssignment(newNameMap, rootModuleId)
    }

    private fun assignModulePrefixes(): Map<ModuleId, List<String>> {
        val modulesByName = groupModulesByName()
        val modulePrefixes = HashMap<ModuleId, List<String>>()
        for (moduleId in relevantEntities.allModules.keys) {
            val modulesWithName = modulesByName[moduleId.module]!!
            val modulePrefix = if (modulesWithName.size == 1) {
                listOf(moduleId.module)
            } else {
                // TODO: Improve this by preferring group/module or module/version when possible
                listOf(moduleId.group, moduleId.module, moduleId.version)
            }
            val sanitizedModulePrefix = modulePrefix.map(::sanitize)
            modulePrefixes.put(moduleId, sanitizedModulePrefix)
        }
        return modulePrefixes
    }

    private fun groupModulesByName(): Map<String, List<ModuleId>> {
        return relevantEntities.allModules.keys.groupBy(ModuleId::module)
    }

    private fun getInitialName(ref: ResolvedEntityRef, modulePrefixes: Map<ModuleId, List<String>>): EntityId {
        val modulePrefix = modulePrefixes[ref.module]!!
        return EntityId(modulePrefix + ref.id.namespacedName)
    }

    fun ensureUnique(entityId: EntityId): EntityId {
        if (!allNewNames.contains(entityId)) {
            return entityId
        }
        var suffixNumber = 2
        while (true) {
            val names = entityId.namespacedName
            val tweakedLastName = names.last() + "_" + suffixNumber
            val tweakedNames = names.dropLast(1) + tweakedLastName
            val tweakedEntityId = EntityId(tweakedNames)

            if (!allNewNames.contains(tweakedEntityId)) {
                return tweakedEntityId
            }

            suffixNumber++
        }
    }
}

// From module name to entityId portion
fun sanitize(s: String): String {
    return s.replace("-", "_").replace(".", "_")
}

private class RelevantEntitiesFinder(val rootModule: ValidatedModule) {
    val allModules = HashMap<ModuleId, ValidatedModule>()
    val functions = HashMap<ResolvedEntityRef, ValidatedFunction>()
    val structs = HashMap<ResolvedEntityRef, Struct>()
    val interfaces = HashMap<ResolvedEntityRef, Interface>()
    val unions = HashMap<ResolvedEntityRef, Union>()

    val functionsQueue: Queue<ResolvedEntityRef> = ArrayDeque<ResolvedEntityRef>()
    val structsQueue: Queue<ResolvedEntityRef> = ArrayDeque<ResolvedEntityRef>()
    val interfacesQueue: Queue<ResolvedEntityRef> = ArrayDeque<ResolvedEntityRef>()
    val unionsQueue: Queue<ResolvedEntityRef> = ArrayDeque<ResolvedEntityRef>()

    fun compute(): RelevantEntities {
        initializeQueue()
        resolveQueues()

        return RelevantEntities(allModules, functions, structs, interfaces, unions)
    }

    private fun resolveQueues() {
        while (true) {
            if (functionsQueue.isNotEmpty()) {
                val functionRef = functionsQueue.remove()
                resolveFunction(functionRef)
            } else if (structsQueue.isNotEmpty()) {
                val structRef = structsQueue.remove()
                resolveStruct(structRef)
            } else if (interfacesQueue.isNotEmpty()) {
                val interfaceRef = interfacesQueue.remove()
                resolveInterface(interfaceRef)
            } else if (unionsQueue.isNotEmpty()) {
                val unionRef = unionsQueue.remove()
                resolveUnion(unionRef)
            } else {
                return
            }
        }
    }

    private fun resolveUnion(unionRef: ResolvedEntityRef) {
        if (unions.containsKey(unionRef)) {
            // The union has already been added; skip it
            return
        }
        if (isNativeModule(unionRef.module)) {
            return
        }

        val containingModule = allModules[unionRef.module]!!
        val union = containingModule.getInternalUnion(unionRef).union

        for (option in union.options) {
            if (option.type != null) {
                enqueueType(option.type!!, containingModule)
            }
        }

        unions.put(unionRef, union)
    }

    private fun resolveInterface(interfaceRef: ResolvedEntityRef) {
        if (interfaces.containsKey(interfaceRef)) {
            // The interface has already been added; skip it
            return
        }
        if (isNativeModule(interfaceRef.module)) {
            return
        }

        val containingModule = allModules[interfaceRef.module]!!
        val interfac = containingModule.getInternalInterface(interfaceRef).interfac

        for (method in interfac.methods) {
            enqueueType(method.functionType, containingModule)
        }

        interfaces.put(interfaceRef, interfac)
    }

    private fun resolveStruct(structRef: ResolvedEntityRef) {
        if (structs.containsKey(structRef)) {
            // The struct has already been added; skip it
            return
        }
        if (isNativeModule(structRef.module)) {
            return
        }

        val containingModule = allModules[structRef.module]!!
        val struct = containingModule.getInternalStruct(structRef).struct

        for (member in struct.members) {
            enqueueType(member.type, containingModule)
        }
        val requires = struct.requires
        if (requires != null) {
            enqueueBlock(requires, containingModule)
        }

        structs.put(structRef, struct)
    }

    private fun resolveFunction(functionRef: ResolvedEntityRef) {
        if (functions.containsKey(functionRef)) {
            // The function has already been added; skip it
            return
        }
        val containingModule = allModules[functionRef.module]!!
        val function = containingModule.getInternalFunction(functionRef).function

        for (argument in function.arguments) {
            enqueueType(argument.type, containingModule)
        }
        enqueueType(function.returnType, containingModule)
        enqueueBlock(function.block, containingModule)

        functions.put(functionRef, function)
    }

    // TODO: The type enqueueings here are probably redundant; confirm or show otherwise once testing is sufficient
    private fun enqueueBlock(block: TypedBlock, containingModule: ValidatedModule) {
        for (assignment in block.assignments) {
            enqueueType(assignment.type, containingModule)
            enqueueExpression(assignment.expression, containingModule)
        }
        enqueueType(block.type, containingModule)
        enqueueExpression(block.returnedExpression, containingModule)
    }

    private fun enqueueExpression(expression: TypedExpression, containingModule: ValidatedModule) {
        val unused = when (expression) {
            is TypedExpression.Variable -> {
                // Do nothing
            }
            is TypedExpression.IfThen -> {
                enqueueExpression(expression.condition, containingModule)
                enqueueBlock(expression.thenBlock, containingModule)
                enqueueBlock(expression.elseBlock, containingModule)
            }
            is TypedExpression.NamedFunctionCall -> {
                enqueueFunctionRef(expression.resolvedFunctionRef, containingModule)
                for (type in expression.chosenParameters) {
                    enqueueType(type, containingModule)
                }
                for (argument in expression.arguments) {
                    enqueueExpression(argument, containingModule)
                }
            }
            is TypedExpression.ExpressionFunctionCall -> {
                enqueueExpression(expression.functionExpression, containingModule)
                for (type in expression.chosenParameters) {
                    enqueueType(type, containingModule)
                }
                for (argument in expression.arguments) {
                    enqueueExpression(argument, containingModule)
                }
            }
            is TypedExpression.Literal -> {
                enqueueType(expression.type, containingModule)
            }
            is TypedExpression.ListLiteral -> {
                for (item in expression.contents) {
                    enqueueExpression(item, containingModule)
                }
            }
            is TypedExpression.NamedFunctionBinding -> {
                enqueueFunctionRef(expression.resolvedFunctionRef, containingModule)
                for (type in expression.chosenParameters) {
                    enqueueType(type, containingModule)
                }
                for (binding in expression.bindings) {
                    if (binding != null) {
                        enqueueExpression(binding, containingModule)
                    }
                }
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                enqueueExpression(expression.functionExpression, containingModule)
                for (type in expression.chosenParameters) {
                    enqueueType(type, containingModule)
                }
                for (binding in expression.bindings) {
                    if (binding != null) {
                        enqueueExpression(binding, containingModule)
                    }
                }
            }
            is TypedExpression.Follow -> {
                enqueueExpression(expression.structureExpression, containingModule)
            }
            is TypedExpression.InlineFunction -> {
                for (argument in expression.arguments) {
                    enqueueType(argument.type, containingModule)
                }
                enqueueBlock(expression.block, containingModule)
            }
        }
    }

    private fun enqueueFunctionRef(functionRef: ResolvedEntityRef, containingModule: ValidatedModule) {
        // TODO: This is another place where it would help to either store the EntityResolution or have another map in the resolver
        val resolved = containingModule.resolve(functionRef)!!
        val ignored: Any = when (resolved.type) {
            FunctionLikeType.NATIVE_FUNCTION -> {
                // Do nothing
            }
            FunctionLikeType.FUNCTION -> {
                functionsQueue.add(resolved.entityRef)
            }
            FunctionLikeType.STRUCT_CONSTRUCTOR -> {
                structsQueue.add(resolved.entityRef)
            }
            FunctionLikeType.INSTANCE_CONSTRUCTOR -> {
                interfacesQueue.add(resolved.entityRef)
            }
            FunctionLikeType.ADAPTER_CONSTRUCTOR -> {
                val adapterId = resolved.entityRef.id
                val interfaceId = EntityId(adapterId.namespacedName.dropLast(1))
                val interfaceRef = resolved.entityRef.copy(id = interfaceId)
                interfacesQueue.add(interfaceRef)
            }
            FunctionLikeType.OPAQUE_TYPE -> {
                // Currently these are all native types
            }
            FunctionLikeType.UNION_TYPE -> {
                unionsQueue.add(resolved.entityRef)
            }
            FunctionLikeType.UNION_OPTION_CONSTRUCTOR -> {
                val optionId = resolved.entityRef.id
                val unionId = EntityId(optionId.namespacedName.dropLast(1))
                val unionRef = resolved.entityRef.copy(id = unionId)
                unionsQueue.add(unionRef)
            }
            FunctionLikeType.UNION_WHEN_FUNCTION -> {
                val whenId = resolved.entityRef.id
                val unionId = EntityId(whenId.namespacedName.dropLast(1))
                val unionRef = resolved.entityRef.copy(id = unionId)
                unionsQueue.add(unionRef)
            }
        }
        val moduleId = resolved.entityRef.module
        if (!isNativeModule(moduleId) && !allModules.containsKey(moduleId)) {
            val theModule = containingModule.upstreamModules[moduleId]!!
            allModules.put(moduleId, theModule)
        }
    }

    private fun enqueueType(type: Type, containingModule: ValidatedModule) {
        val unused: Any = when (type) {
            Type.INTEGER -> { /* Do nothing */ }
            Type.BOOLEAN -> { /* Do nothing */ }
            is Type.List -> {
                enqueueType(type.parameter, containingModule)
            }
            is Type.Maybe -> {
                enqueueType(type.parameter, containingModule)
            }
            is Type.FunctionType -> {
                for (inputType in type.argTypes) {
                    enqueueType(inputType, containingModule)
                }
                enqueueType(type.outputType, containingModule)
            }
            is Type.NamedType -> {
                enqueueFunctionRef(type.ref, containingModule)
            }
            is Type.ParameterType -> { /* Do nothing */ }
        }
    }

    private fun initializeQueue() {
        allModules.put(rootModule.id, rootModule)
        for (id in rootModule.ownFunctions.keys) {
            functionsQueue.add(ResolvedEntityRef(rootModule.id, id))
        }
        for (id in rootModule.ownStructs.keys) {
            structsQueue.add(ResolvedEntityRef(rootModule.id, id))
        }
        for (id in rootModule.ownInterfaces.keys) {
            interfacesQueue.add(ResolvedEntityRef(rootModule.id, id))
        }
        for (id in rootModule.ownUnions.keys) {
            unionsQueue.add(ResolvedEntityRef(rootModule.id, id))
        }
    }
}
