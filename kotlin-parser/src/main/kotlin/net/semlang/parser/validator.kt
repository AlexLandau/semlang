package net.semlang.parser

import net.semlang.api.*
import net.semlang.api.Function
import java.util.*

// TODO: Remove once unused
private fun fail(e: Exception, text: String): Nothing {
    throw IllegalStateException(text, e)
}

// TODO: Remove once unused
private fun fail(text: String): Nothing {
    val fullMessage = "Validation error, position not recorded: " + text
    error(fullMessage)
}

data class GroundedTypeSignature(val id: EntityId, val argumentTypes: List<Type>, val outputType: Type)

/*
 * Warning: Doesn't validate that composed literals satisfy their requires blocks, which requires running semlang code to
 *   check (albeit code that can always be run in a vacuum)
 */
fun validateModule(context: RawContext, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): ValidationResult {
    val validator = Validator(moduleId, nativeModuleVersion, upstreamModules)
    return validator.validate(context)
}

enum class IssueLevel {
    WARNING,
    ERROR,
}

data class Issue(val message: String, val location: Location?, val level: IssueLevel)

sealed class ValidationResult {
    abstract fun assumeSuccess(): ValidatedModule
    data class Success(val module: ValidatedModule, val warnings: List<Issue>): ValidationResult() {
        override fun assumeSuccess(): ValidatedModule {
            return module
        }
    }
    data class Failure(val errors: List<Issue>, val warnings: List<Issue>): ValidationResult() {
        override fun assumeSuccess(): ValidatedModule {
            error("Encountered errors in validation: $errors")
        }
    }
}

private class Validator(val moduleId: ModuleId, val nativeModuleVersion: String, val upstreamModules: List<ValidatedModule>) {
    val warnings = ArrayList<Issue>()
    val errors = ArrayList<Issue>()

    fun validate(context: RawContext): ValidationResult {
        val typeInfo = collectTypeInfo(context, moduleId, nativeModuleVersion, upstreamModules)

        val ownFunctions = validateFunctions(context.functions, typeInfo)
        val ownStructs = validateStructs(context.structs, typeInfo)
        val ownInterfaces = validateInterfaces(context.interfaces, typeInfo)

        val createdModule = ValidatedModule.create(moduleId, nativeModuleVersion, ownFunctions, ownStructs, ownInterfaces, upstreamModules)
        // TODO: Will be different once good tests are in place and fixed...
        if (errors.isEmpty()) {
            return ValidationResult.Success(createdModule, warnings)
        } else {
            return ValidationResult.Failure(errors, warnings)
        }
    }

    private fun collectTypeInfo(context: RawContext, moduleId: ModuleId, nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): AllTypeInfo {
        /*
         * Some notes about how we deal with error cases here:
         * - Name collisions involving other modules (which are assumed valid at this point) aren't a problem at this
         *   level. Instead, there will be an error found at the location of the reference, when the reference is
         *   ambiguous.
         * - Name collisions within the module may occur either for both types and functions, or for functions only.
         * - Collisions should be noted in both locations. References to these types/functions (when they resolve to the
         *   current module) will then be their own errors.
         */
        val resolver = EntityResolver.create(moduleId,
                nativeModuleVersion,
                context.functions.map(Function::id),
                context.structs.map(UnvalidatedStruct::id),
                context.interfaces.map(UnvalidatedInterface::id),
                upstreamModules)

        val localTypes = HashMap<EntityId, TypeInfo>()
        val duplicateLocalTypeIds = HashSet<EntityId>()

        val localFunctions = HashMap<EntityId, FunctionInfo>()
        val duplicateLocalFunctionIds = HashSet<EntityId>()

        // For sanity-checking the results of the spaghetti logic below
        val seenTypeIds = HashSet<EntityId>()
        val seenFunctionIds = HashSet<EntityId>()

        val addDuplicateIdError = fun(id: EntityId, idLocation: Location?) { errors.add(Issue("Duplicate ID ${id}", idLocation, IssueLevel.ERROR)) }

        context.structs.forEach { struct ->
            val id = struct.id
            seenTypeIds.add(id)
            seenFunctionIds.add(id)

            var isDuplicate = false
            // Check for duplicate declarations
            if (duplicateLocalTypeIds.contains(id)) {
                isDuplicate = true
            } else if (localTypes.containsKey(id)) {
                isDuplicate = true
                duplicateLocalTypeIds.add(id)
                addDuplicateIdError(id, localTypes[id]?.idLocation)
                localTypes.remove(id)
            }
            if (duplicateLocalFunctionIds.contains(id)) {
                isDuplicate = true
            } else if (localFunctions.containsKey(id)) {
                isDuplicate = true
                duplicateLocalFunctionIds.add(id)
                addDuplicateIdError(id, localFunctions[id]?.idLocation)
                localFunctions.remove(id)
            }
            if (isDuplicate) {
                addDuplicateIdError(id, struct.idLocation)
            }

            if (!duplicateLocalTypeIds.contains(id)) {
                // Collect the type info
                localTypes.put(id, getTypeInfo(struct))
            }
            if (!duplicateLocalFunctionIds.contains(id)) {
                // Collect the constructor function info
                localFunctions.put(id, FunctionInfo(struct.getConstructorSignature(), struct.idLocation))
            }
        }

        context.interfaces.forEach { interfac ->
            seenTypeIds.add(interfac.id)
            seenFunctionIds.add(interfac.id)
            seenTypeIds.add(interfac.adapterId)
            seenFunctionIds.add(interfac.adapterId)

            // Check for duplicate declarations
            for (id in listOf(interfac.id, interfac.adapterId)) {
                var isDuplicate = false
                if (duplicateLocalTypeIds.contains(id)) {
                    isDuplicate = true
                } else if (localTypes.containsKey(id)) {
                    isDuplicate = true
                    duplicateLocalTypeIds.add(id)
                    addDuplicateIdError(id, localTypes[id]?.idLocation)
                    localTypes.remove(id)
                }
                if (duplicateLocalFunctionIds.contains(id)) {
                    isDuplicate = true
                } else if (localFunctions.containsKey(id)) {
                    isDuplicate = true
                    duplicateLocalFunctionIds.add(id)
                    addDuplicateIdError(id, localFunctions[id]?.idLocation)
                    localFunctions.remove(id)
                }
                if (isDuplicate) {
                    addDuplicateIdError(id, interfac.idLocation)
                }
            }

            if (!duplicateLocalTypeIds.contains(interfac.id)) {
                localTypes.put(interfac.id, getTypeInfo(interfac))
            }
            if (!duplicateLocalFunctionIds.contains(interfac.id)) {
                localFunctions.put(interfac.id, FunctionInfo(interfac.getInstanceConstructorSignature(), interfac.idLocation))
            }
            if (!duplicateLocalTypeIds.contains(interfac.adapterId)) {
                localTypes.put(interfac.adapterId, getTypeInfo(interfac.adapterStruct))
            }
            if (!duplicateLocalFunctionIds.contains(interfac.adapterId)) {
                localFunctions.put(interfac.adapterId, FunctionInfo(interfac.getAdapterConstructorSignature(), interfac.idLocation))
            }
        }

        context.functions.forEach { function ->
            val id = function.id
            seenFunctionIds.add(id)

            if (duplicateLocalFunctionIds.contains(id)) {
                addDuplicateIdError(id, function.idLocation)
            } else if (localFunctions.containsKey(id)) {
                addDuplicateIdError(id, function.idLocation)
                duplicateLocalFunctionIds.add(id)
                addDuplicateIdError(id, localFunctions[id]?.idLocation)
                localFunctions.remove(id)
            }

            if (!duplicateLocalFunctionIds.contains(id)) {
                localFunctions.put(id, FunctionInfo(function.getTypeSignature(), function.idLocation))
            }
        }

        // TODO: Check a few invariants here...
        // Hopefully we only register one error per duplicate, but that can be dealt with later
        for (id in duplicateLocalTypeIds) {
            if (localTypes.containsKey(id)) {
                error("localTypes has key $id that is also listed in duplicates")
            }
        }
        for (id in seenTypeIds) {
            if (!localTypes.containsKey(id) && !duplicateLocalTypeIds.contains(id)) {
                error("We saw a type ID $id that did not end up in the local types or duplicates")
            }
        }
        for (id in duplicateLocalFunctionIds) {
            if (localFunctions.containsKey(id)) {
                error("localTypes has key $id that is also listed in duplicates")
            }
        }
        for (id in seenFunctionIds) {
            if (!localFunctions.containsKey(id) && !duplicateLocalFunctionIds.contains(id)) {
                error("We saw a function ID $id that did not end up in the local functions or duplicates")
            }
        }
        if ((duplicateLocalTypeIds.isNotEmpty() || duplicateLocalFunctionIds.isNotEmpty()) && errors.isEmpty()) {
            error("Should have at least one error when there are duplicate IDs")
        }

        val upstreamTypes = getUpstreamTypes(nativeModuleVersion, upstreamModules)
        val upstreamFunctions = getUpstreamFunctions(nativeModuleVersion, upstreamModules)

        return AllTypeInfo(resolver, localTypes, duplicateLocalTypeIds, localFunctions, duplicateLocalFunctionIds, upstreamTypes, upstreamFunctions)
    }

    private fun getUpstreamTypes(nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, TypeInfo> {
        val upstreamTypes = HashMap<ResolvedEntityRef, TypeInfo>()

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        getNativeStructs().values.forEach { struct ->
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamTypes.put(ref, getTypeInfo(struct, null))
        }
        getNativeInterfaces().values.forEach { interfac ->
            val ref = ResolvedEntityRef(nativeModuleId, interfac.id)
            upstreamTypes.put(ref, getTypeInfo(interfac, null))
            val adapterRef = ResolvedEntityRef(nativeModuleId, interfac.adapterId)
            upstreamTypes.put(adapterRef, getTypeInfo(interfac.adapterStruct, null))
        }

        upstreamModules.forEach { module ->
            module.getAllExportedStructs().values.forEach { struct ->
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamTypes.put(ref, getTypeInfo(struct, null))
            }
            module.getAllExportedInterfaces().values.forEach { interfac ->
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamTypes.put(ref, getTypeInfo(interfac, null))
                val adapterRef = ResolvedEntityRef(module.id, interfac.adapterId)
                upstreamTypes.put(adapterRef, getTypeInfo(interfac.adapterStruct, null))
            }
        }
        return upstreamTypes
    }

    private fun getUpstreamFunctions(nativeModuleVersion: String, upstreamModules: List<ValidatedModule>): Map<ResolvedEntityRef, FunctionInfo> {
        val upstreamFunctions = HashMap<ResolvedEntityRef, FunctionInfo>()

        // (We don't need the idPosition for functions in upstream modules)
        val functionInfo = fun(signature: TypeSignature): FunctionInfo { return FunctionInfo(signature, null) }

        // TODO: Fix this to use nativeModuleVersion
        val nativeModuleId = CURRENT_NATIVE_MODULE_ID
        getNativeStructs().values.forEach { struct ->
            val ref = ResolvedEntityRef(nativeModuleId, struct.id)
            upstreamFunctions.put(ref, functionInfo(struct.getConstructorSignature()))
        }
        getNativeInterfaces().values.forEach { interfac ->
            val ref = ResolvedEntityRef(nativeModuleId, interfac.id)
            upstreamFunctions.put(ref, functionInfo(interfac.getInstanceConstructorSignature()))
            val adapterRef = ResolvedEntityRef(nativeModuleId, interfac.adapterId)
            upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterConstructorSignature()))
        }
        getNativeFunctionOnlyDefinitions().values.forEach { function ->
            val ref = ResolvedEntityRef(nativeModuleId, function.id)
            upstreamFunctions.put(ref, functionInfo(function))
        }

        upstreamModules.forEach { module ->
            module.getAllExportedStructs().values.forEach { struct ->
                val ref = ResolvedEntityRef(module.id, struct.id)
                upstreamFunctions.put(ref, functionInfo(struct.getConstructorSignature()))
            }
            module.getAllExportedInterfaces().values.forEach { interfac ->
                val ref = ResolvedEntityRef(module.id, interfac.id)
                upstreamFunctions.put(ref, functionInfo(interfac.getInstanceConstructorSignature()))
                val adapterRef = ResolvedEntityRef(module.id, interfac.adapterId)
                upstreamFunctions.put(adapterRef, functionInfo(interfac.getAdapterConstructorSignature()))
            }
            module.getAllExportedFunctions().values.forEach { function ->
                val ref = ResolvedEntityRef(module.id, function.id)
                upstreamFunctions.put(ref, functionInfo(function.getTypeSignature()))
            }
        }
        return upstreamFunctions
    }

    private fun validateFunctions(functions: List<Function>, typeInfo: AllTypeInfo): Map<EntityId, ValidatedFunction> {
        val validatedFunctions = HashMap<EntityId, ValidatedFunction>()
        functions.forEach { function ->
            val validatedFunction = validateFunction(function, typeInfo)
            if (validatedFunction != null && !typeInfo.duplicateLocalFunctionIds.contains(validatedFunction.id)) {
                validatedFunctions.put(function.id, validatedFunction)
            } else if (errors.isEmpty()) {
                fail("Something bad happened")
            }
        }
        return validatedFunctions
    }

    private fun validateFunction(function: Function, typeInfo: AllTypeInfo): ValidatedFunction? {
        //TODO: Validate that no two arguments have the same name
        //TODO: Validate that type parameters don't share a name with something important
        val variableTypes = getArgumentVariableTypes(function.arguments)
        val block = validateBlock(function.block, variableTypes, typeInfo, function.id) ?: return null
        if (function.returnType != block.type) {
            errors.add(Issue("Stated return type ${function.returnType} does not match the block's actual return type ${block.type}", function.returnTypeLocation, IssueLevel.ERROR))
        }

        return ValidatedFunction(function.id, function.typeParameters, function.arguments, function.returnType, block, function.annotations)
    }

    private fun getArgumentVariableTypes(arguments: List<Argument>): Map<String, Type> {
        return arguments.associate { argument -> Pair(argument.name, argument.type) }
    }


    private sealed class TypeInfo {
        abstract val idLocation: Location?
        data class Struct(val typeParameters: List<String>, val members: Map<String, Member>, val usesRequires: Boolean, override val idLocation: Location?): TypeInfo()
        data class Interface(val typeParameters: List<String>, val methods: Map<String, Method>, override val idLocation: Location?): TypeInfo()
    }
    private data class FunctionInfo(val signature: TypeSignature, val idLocation: Location?)

    private fun getTypeInfo(struct: UnvalidatedStruct): TypeInfo.Struct {
        return TypeInfo.Struct(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null, struct.idLocation)
    }
    // TODO: Null position
    private fun getTypeInfo(struct: Struct, idLocation: Location?): TypeInfo.Struct {
        return TypeInfo.Struct(struct.typeParameters, struct.members.associateBy(Member::name), struct.requires != null, idLocation)
    }

    private fun getTypeInfo(interfac: UnvalidatedInterface): TypeInfo.Interface {
        return TypeInfo.Interface(interfac.typeParameters, interfac.methods.associateBy(Method::name), interfac.idLocation)
    }
    // TODO: Null position
    private fun getTypeInfo(interfac: Interface, idLocation: Location?): TypeInfo.Interface {
        return TypeInfo.Interface(interfac.typeParameters, interfac.methods.associateBy(Method::name), idLocation)
    }

    private inner class AllTypeInfo(val resolver: EntityResolver,
                                    val localTypes: Map<EntityId, TypeInfo>,
                                    val duplicateLocalTypeIds: Set<EntityId>,
                                    val localFunctions: Map<EntityId, FunctionInfo>,
                                    val duplicateLocalFunctionIds: Set<EntityId>,
                                    val upstreamTypes: Map<ResolvedEntityRef, TypeInfo>,
                                    val upstreamFunctions: Map<ResolvedEntityRef, FunctionInfo>) {
        fun getTypeInfo(resolvedRef: ResolvedEntityRef): TypeInfo? {
            return if (resolvedRef.module == moduleId) {
                if (duplicateLocalTypeIds.contains(resolvedRef.id)) {
                    fail("There are multiple declarations of the type name ${resolvedRef.id}")
                }
                localTypes[resolvedRef.id]
            } else {
                upstreamTypes[resolvedRef]
            }
        }
        fun getFunctionInfo(resolvedRef: ResolvedEntityRef): FunctionInfo? {
            return if (resolvedRef.module == moduleId) {
                if (duplicateLocalFunctionIds.contains(resolvedRef.id)) {
                    fail("There are multiple declarations of the function name ${resolvedRef.id}")
                }
                localFunctions[resolvedRef.id]
            } else {
                upstreamFunctions[resolvedRef]
            }
        }
    }

    private fun validateBlock(block: Block, externalVariableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedBlock? {
        val variableTypes = HashMap(externalVariableTypes)
        val validatedAssignments = ArrayList<ValidatedAssignment>()
        block.assignments.forEach { assignment ->
            if (variableTypes.containsKey(assignment.name)) {
                errors.add(Issue("The already-assigned variable ${assignment.name} cannot be reassigned", assignment.nameLocation, IssueLevel.ERROR))
            }
            if (isInvalidVariableName(assignment.name, typeInfo)) {
                errors.add(Issue("Invalid variable name ${assignment.name}", assignment.nameLocation, IssueLevel.ERROR))
            }

            val validatedExpression = validateExpression(assignment.expression, variableTypes, typeInfo, containingFunctionId) ?: return null
            if (assignment.type != null && (validatedExpression.type != assignment.type)) {
                fail("In function $containingFunctionId, the variable ${assignment.name} is supposed to be of type ${assignment.type}, " +
                        "but the expression given has actual type ${validatedExpression.type}")
            }

            validatedAssignments.add(ValidatedAssignment(assignment.name, validatedExpression.type, validatedExpression))
            variableTypes.put(assignment.name, validatedExpression.type)
        }
        val returnedExpression = validateExpression(block.returnedExpression, variableTypes, typeInfo, containingFunctionId) ?: return null
        return TypedBlock(returnedExpression.type, validatedAssignments, returnedExpression)
    }

    //TODO: Construct this more sensibly from more centralized lists
    private val INVALID_VARIABLE_NAMES: Set<String> = setOf("Integer", "Natural", "Boolean", "function", "let", "return", "if", "else", "struct", "requires")

    private fun isInvalidVariableName(name: String, typeInfo: AllTypeInfo): Boolean {
        val nameAsEntityRef = EntityId.of(name).asRef()
        return typeInfo.resolver.resolve(nameAsEntityRef) != null
                || INVALID_VARIABLE_NAMES.contains(name)
    }

    private fun validateExpression(expression: Expression, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression? {
        return when (expression) {
            is Expression.Variable -> validateVariableExpression(expression, variableTypes, containingFunctionId)
            is Expression.IfThen -> validateIfThenExpression(expression, variableTypes, typeInfo, containingFunctionId)
            is Expression.Follow -> validateFollowExpression(expression, variableTypes, typeInfo, containingFunctionId)
            is Expression.NamedFunctionCall -> validateNamedFunctionCallExpression(expression, variableTypes, typeInfo, containingFunctionId)
            is Expression.ExpressionFunctionCall -> validateExpressionFunctionCallExpression(expression, variableTypes, typeInfo, containingFunctionId)

            is Expression.Literal -> validateLiteralExpression(expression, typeInfo)
            is Expression.ListLiteral -> validateListLiteralExpression(expression, variableTypes, typeInfo, containingFunctionId)
            is Expression.NamedFunctionBinding -> validateNamedFunctionBinding(expression, variableTypes, typeInfo, containingFunctionId)
            is Expression.ExpressionFunctionBinding -> validateExpressionFunctionBinding(expression, variableTypes, typeInfo, containingFunctionId)
        }
    }

    private fun validateExpressionFunctionBinding(expression: Expression.ExpressionFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType ?: fail("The function $containingFunctionId tries to call $functionExpression like a function, but it has a non-function type ${functionExpression.type}")

        val preBindingArgumentTypes = functionType.argTypes

        if (preBindingArgumentTypes.size != expression.bindings.size) {
            fail("In function $containingFunctionId, tried to bind $functionExpression with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments")
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeInfo, containingFunctionId)
            }
        }

        val postBindingArgumentTypes = ArrayList<Type>()
        for (entry in preBindingArgumentTypes.zip(bindings)) {
            val type = entry.first
            val binding = entry.second
            if (binding == null) {
                postBindingArgumentTypes.add(type)
            } else {
                if (binding.type != type) {
                    fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
                }
            }
        }
        val postBindingType = Type.FunctionType(
                postBindingArgumentTypes,
                functionType.outputType)

        return TypedExpression.ExpressionFunctionBinding(postBindingType, functionExpression, bindings, expression.chosenParameters)
    }

    private fun validateNamedFunctionBinding(expression: Expression.NamedFunctionBinding, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression {
        val functionRef = expression.functionRef

        val resolvedRef = typeInfo.resolver.resolve(functionRef) ?: fail("In function $containingFunctionId, could not find a declaration of a function with ID $functionRef")
        val functionInfo = typeInfo.getFunctionInfo(resolvedRef.entityRef)
        if (functionInfo == null) {
            fail("In function $containingFunctionId, resolved a function with ID $functionRef but could not find the signature")
        }
        val signature = functionInfo.signature
        val chosenParameters = expression.chosenParameters
        if (chosenParameters.size != signature.typeParameters.size) {
            fail("In function $containingFunctionId, referenced a function $functionRef with type parameters ${signature.typeParameters}, but used an incorrect number of type parameters, passing in $chosenParameters")
        }
        val typeParameters = signature.typeParameters

        val parameterMap = makeParameterMap(typeParameters, chosenParameters, resolvedRef.entityRef.id)
        val preBindingArgumentTypes = signature.argumentTypes.map { type -> type.replacingParameters(parameterMap) }

        if (preBindingArgumentTypes.size != expression.bindings.size) {
            fail("In function $containingFunctionId, tried to bind function $functionRef with ${expression.bindings.size} bindings, but it takes ${preBindingArgumentTypes.size} arguments")
        }

        val bindings = expression.bindings.map { binding ->
            if (binding == null) {
                null
            } else {
                validateExpression(binding, variableTypes, typeInfo, containingFunctionId)
            }
        }

        val postBindingArgumentTypes = ArrayList<Type>()
        for (entry in preBindingArgumentTypes.zip(bindings)) {
            val type = entry.first
            val binding = entry.second
            if (binding == null) {
                postBindingArgumentTypes.add(type)
            } else {
                if (binding.type != type) {
                    fail("In function $containingFunctionId, a binding is of type ${binding.type} but the expected argument type is $type")
                }
            }
        }
        val postBindingType = Type.FunctionType(
                postBindingArgumentTypes,
                signature.outputType.replacingParameters(parameterMap))

        return TypedExpression.NamedFunctionBinding(postBindingType, functionRef, bindings, chosenParameters)
    }

    private fun validateFollowExpression(expression: Expression.Follow, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression? {
        val innerExpression = validateExpression(expression.expression, variableTypes, typeInfo, containingFunctionId) ?: return null

        val parentNamedType = innerExpression.type as? Type.NamedType ?: fail("In function $containingFunctionId, we try to dereference an expression $innerExpression of non-struct, non-interface type ${innerExpression.type}")

        val resolvedParentType = typeInfo.resolver.resolve(parentNamedType.ref) ?: error("In function $containingFunctionId, we try to dereference an expression $innerExpression of unrecognized type ${innerExpression.type}")
        val parentTypeInfo = typeInfo.getTypeInfo(resolvedParentType.entityRef) ?: error("No type info for ${resolvedParentType.entityRef}")

        return when (parentTypeInfo) {
            is TypeInfo.Struct -> {
                val member = parentTypeInfo.members[expression.name]
                if (member == null) {
                    fail("In function $containingFunctionId, we try to dereference a non-existent member '${expression.name}' of the struct type $parentNamedType")
                }

                // Type parameters come from the struct definition itself
                // Chosen types come from the struct type known for the variable
                val typeParameters = parentTypeInfo.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
                val chosenTypes = parentNamedType.getParameterizedTypes()
                val type = parameterizeType(member.type, typeParameters, chosenTypes, resolvedParentType.entityRef.id)
                //TODO: Ground this if needed

                return TypedExpression.Follow(type, innerExpression, expression.name)

            }
            is TypeInfo.Interface -> {
                val interfac = parentTypeInfo
                val interfaceType = parentNamedType
                val method = interfac.methods[expression.name]
                if (method == null) {
                    fail("In function $containingFunctionId, we try to reference a non-existent method '${expression.name}' of the interface type $interfaceType")
                }

                val typeParameters = interfac.typeParameters.map { paramName -> Type.NamedType.forParameter(paramName) }
                val chosenTypes = interfaceType.getParameterizedTypes()
                val type = parameterizeType(method.functionType, typeParameters, chosenTypes, resolvedParentType.entityRef.id)

                return TypedExpression.Follow(type, innerExpression, expression.name)
            }
        }
    }

    private fun validateExpressionFunctionCallExpression(expression: Expression.ExpressionFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression? {
        val functionExpression = validateExpression(expression.functionExpression, variableTypes, typeInfo, containingFunctionId) ?: return null

        val functionType = functionExpression.type as? Type.FunctionType ?: fail("The function $containingFunctionId tries to call $functionExpression like a function, but it has a non-function type ${functionExpression.type}")

        val arguments = ArrayList<TypedExpression>()
        expression.arguments.forEach { untypedArgument ->
            val argument = validateExpression(untypedArgument, variableTypes, typeInfo, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        if (argumentTypes != functionType.argTypes) {
            fail("The function $containingFunctionId tries to call the result of $functionExpression with argument types $argumentTypes, but the function expects argument types ${functionType.argTypes}")
        }

        return TypedExpression.ExpressionFunctionCall(functionType.outputType, functionExpression, arguments, expression.chosenParameters)
    }

    private fun validateNamedFunctionCallExpression(expression: Expression.NamedFunctionCall, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression? {
        val functionRef = expression.functionRef
        val functionResolvedRef = typeInfo.resolver.resolve(functionRef) ?: fail("The function $containingFunctionId references a function $functionRef that was not found")

        val arguments = ArrayList<TypedExpression>()
        expression.arguments.forEach { untypedArgument ->
            val argument = validateExpression(untypedArgument, variableTypes, typeInfo, containingFunctionId) ?: return null
            arguments.add(argument)
        }
        val argumentTypes = arguments.map(TypedExpression::type)

        val signature = typeInfo.getFunctionInfo(functionResolvedRef.entityRef)?.signature
        if (signature == null) {
            fail("The function $containingFunctionId references a function $functionRef that was not found")
        }
        //TODO: Maybe compare argument size before grounding?

        //Ground the signature
        val groundSignature = ground(signature, expression.chosenParameters, functionRef.id)
        if (argumentTypes != groundSignature.argumentTypes) {
            errors.add(Issue("The function $functionRef expects argument types ${groundSignature.argumentTypes}, but is given arguments with types $argumentTypes", expression.location, IssueLevel.ERROR))
        }

        return TypedExpression.NamedFunctionCall(groundSignature.outputType, functionRef, arguments, expression.chosenParameters)
    }

    private fun ground(signature: TypeSignature, chosenTypes: List<Type>, functionId: EntityId): GroundedTypeSignature {
        val groundedArgumentTypes = signature.argumentTypes.map { t -> parameterizeType(t, signature.typeParameters, chosenTypes, functionId) }
        val groundedOutputType = parameterizeType(signature.outputType, signature.typeParameters, chosenTypes, functionId)
        return GroundedTypeSignature(signature.id, groundedArgumentTypes, groundedOutputType)
    }

    // TODO: We're disagreeing in multiple places on List<Type> vs. List<String>, should fix that at some point
    private fun makeParameterMap(parameters: List<Type>, chosenTypes: List<Type>, functionId: EntityId): Map<Type, Type> {
        if (parameters.size != chosenTypes.size) {
            fail("Disagreement in type parameter list lengths for function $functionId; ${parameters.size} required, but ${chosenTypes.size} provided")
        }
        val map: MutableMap<Type, Type> = HashMap()

        parameters.zip(chosenTypes).forEach { pair ->
            val (parameter, chosenType) = pair
            val existingValue = map.get(parameter)
            if (existingValue != null) {
                // I think this is correct behavior...
                fail("Not anticipating seeing the same parameter type twice")
            }
            map.put(parameter, chosenType)
        }

        return map
    }

    private fun parameterizeType(typeWithWrongParameters: Type, typeParameters: List<Type>, chosenTypes: List<Type>, functionId: EntityId): Type {
        val parameterMap = makeParameterMap(typeParameters, chosenTypes, functionId)
        return typeWithWrongParameters.replacingParameters(parameterMap)
    }

    private fun validateLiteralExpression(expression: Expression.Literal, typeInfo: AllTypeInfo): TypedExpression {
        val typeChain = getLiteralTypeChain(expression.type, expression.location, typeInfo)

        if (typeChain != null) {
            val nativeLiteralType = typeChain[0]

            val validator = getTypeValidatorFor(nativeLiteralType) ?: error("No literal validator for type $nativeLiteralType")
            val isValid = validator.validate(expression.literal)
            if (!isValid) {
                errors.add(Issue("Invalid literal value '${expression.literal}' for type '${expression.type}'", expression.location, IssueLevel.ERROR))
            }
        } else if (errors.isEmpty()) {
            fail("Something went wrong")
        }
        // TODO: Someday we need to check for invalid literal values at validation time
        return TypedExpression.Literal(expression.type, expression.literal)
    }

    /**
     * A little explanation:
     *
     * We can have literals for either types with native literals or structs with a single
     * member of a type that can have a literal.
     *
     * In the former case, we return a singleton list with just that type.
     *
     * In the latter case, we return a list starting with the innermost type (one with a
     * native literal implementation) and then following the chain in successive layers
     * outwards to the original type.
     */
    private fun getLiteralTypeChain(initialType: Type, literalLocation: Location?, typeInfo: AllTypeInfo): List<Type>? {
        var type = initialType
        val list = ArrayList<Type>()
        list.add(type)
        while (getTypeValidatorFor(type) == null) {
            if (type is Type.NamedType) {
                val resolvedType = typeInfo.resolver.resolve(type.ref) ?: fail("Could not resolve type ${type.ref}")
                val struct = typeInfo.getTypeInfo(resolvedType.entityRef) as? TypeInfo.Struct ?: fail("Trying to get a literal of a non-struct named type $resolvedType")

                if (struct.typeParameters.isNotEmpty()) {
                    fail("Can't have a literal of a type with type parameters: $type")
                }
                if (struct.members.size != 1) {
                    fail("Can't have a literal of a struct type with more than one member")
                }
                val memberType = struct.members.values.single().type
                if (list.contains(memberType)) {
                    errors.add(Issue("Error: Literal type involves cycle of structs: ${list}", literalLocation, IssueLevel.ERROR))
                    return null
                }
                type = memberType
                list.add(type)
            } else {
                error("")
            }
        }

        list.reverse()
        return list
    }

    private fun validateListLiteralExpression(expression: Expression.ListLiteral, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression? {
        val listType = Type.List(expression.chosenParameter)

        val contents = expression.contents.map { item ->
            validateExpression(item, variableTypes, typeInfo, containingFunctionId) ?: return null
        }
        contents.forEach { item ->
            if (item.type != expression.chosenParameter) {
                error("Put an expression $item of type ${item.type} in a list literal of type ${listType}")
            }
        }

        return TypedExpression.ListLiteral(listType, contents, expression.chosenParameter)
    }

    private fun validateIfThenExpression(expression: Expression.IfThen, variableTypes: Map<String, Type>, typeInfo: AllTypeInfo, containingFunctionId: EntityId): TypedExpression? {
        val condition = validateExpression(expression.condition, variableTypes, typeInfo, containingFunctionId) ?: return null

        if (condition.type != Type.BOOLEAN) {
            fail("In function $containingFunctionId, an if-then expression has a non-boolean condition expression: $condition")
        }

        val elseBlock = validateBlock(expression.elseBlock, variableTypes, typeInfo, containingFunctionId) ?: return null
        val thenBlock = validateBlock(expression.thenBlock, variableTypes, typeInfo, containingFunctionId) ?: return null

        val type = try {
            typeUnion(thenBlock.type, elseBlock.type)
        } catch (e: RuntimeException) {
            fail(e, "In function $containingFunctionId, could not reconcile 'then' and 'else' block types")
        }

        return TypedExpression.IfThen(type, condition, thenBlock, elseBlock)
    }

    private fun typeUnion(type1: Type, type2: Type): Type {
        // TODO: Handle actual type unions, inheritance as these things get added
        // TODO: Then move this stuff to the API level
        if (type1 == type2) {
            return type1
        }
        fail("Types $type1 and $type2 cannot be unioned")
    }

    private fun validateVariableExpression(expression: Expression.Variable, variableTypes: Map<String, Type>, containingFunctionId: EntityId): TypedExpression? {
        val type = variableTypes[expression.name]
        if (type != null) {
            return TypedExpression.Variable(type, expression.name)
        } else {
            errors.add(Issue("Unknown variable ${expression.name}", expression.location, IssueLevel.ERROR))
            return null
        }
    }

    private fun validateStructs(structs: List<UnvalidatedStruct>, typeInfo: AllTypeInfo): Map<EntityId, Struct> {
        val validatedStructs = HashMap<EntityId, Struct>()
        for (struct in structs) {
            val validatedStruct = validateStruct(struct, typeInfo)
            if (validatedStruct != null) {
                validatedStructs.put(struct.id, validatedStruct)
            }
        }
        return validatedStructs
    }

    private fun validateStruct(struct: UnvalidatedStruct, typeInfo: AllTypeInfo): Struct? {
        validateMemberNames(struct)
        val memberTypes = struct.members.associate { member -> member.name to member.type }

        val fakeContainingFunctionId = EntityId(struct.id.namespacedName + "requires")
        val uncheckedRequires = struct.requires
        val requires = if (uncheckedRequires != null) {
            validateBlock(uncheckedRequires, memberTypes, typeInfo, fakeContainingFunctionId) ?: return null
        } else {
            null
        }
        if (requires != null && requires.type != Type.BOOLEAN) {
            val message = "Struct ${struct.id} has a requires block with inferred type ${requires.type}, but the type should be Boolean"
            val position = struct.requires!!.location
            val issue: Issue = Issue(message, position, IssueLevel.ERROR)
            errors.add(issue)
        }

        return Struct(struct.id, struct.typeParameters, struct.members, requires, struct.annotations)
    }

    private fun validateMemberNames(struct: UnvalidatedStruct) {
        val allNames = HashSet<String>()
        for (member in struct.members) {
            if (allNames.contains(member.name)) {
                // TODO: Improve position of message
                errors.add(Issue("Struct ${struct.id} has multiple members named ${member.name}", struct.idLocation, IssueLevel.ERROR))
            }
            allNames.add(member.name)
        }
    }

    private fun validateInterfaces(interfaces: List<UnvalidatedInterface>, typeInfo: AllTypeInfo): Map<EntityId, Interface> {
        val validatedInterfaces = HashMap<EntityId, Interface>()
        for (interfac in interfaces) {
            validatedInterfaces.put(interfac.id, validateInterface(interfac, typeInfo))
        }
        return validatedInterfaces
    }

    private fun validateInterface(interfac: UnvalidatedInterface, typeInfo: AllTypeInfo): Interface {
        // TODO: Do some actual validation of interfaces
        return Interface(interfac.id, interfac.typeParameters, interfac.methods, interfac.annotations)
    }
}