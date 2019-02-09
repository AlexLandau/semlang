package net.semlang.sem2.translate

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.api.Annotation
import net.semlang.api.UnvalidatedMember
import net.semlang.sem2.api.*
import net.semlang.sem2.api.EntityId
import net.semlang.sem2.api.EntityRef
import net.semlang.sem2.api.Location
import net.semlang.sem2.api.Position
import net.semlang.sem2.api.Range
import net.semlang.sem2.api.TypeClass
import net.semlang.sem2.api.TypeParameter
import net.semlang.validator.TypeInfo
import net.semlang.validator.TypesInfo
import net.semlang.validator.getTypeParameterInferenceSources
import java.util.*

fun translateSem2ContextToSem1(context: S2Context, moduleName: ModuleName, upstreamModules: List<ValidatedModule>, options: Sem2ToSem1Options = Sem2ToSem1Options()): RawContext {
    return Sem2ToSem1Translator(context, moduleName, upstreamModules, options).translate()
}

data class Sem2ToSem1Options(
        /**
         * If true, the translator will fail with an exception if it's unable to infer the type of an expression.
         *
         * Recommended for testing cases where the translator is expected to succeed; not recommended for e.g. use inside
         * an IDE.
         */
        val failOnUninferredType: Boolean = false,
        /**
         * If true, the translator will add an explicit type to each variable declaration with its inferred type. This
         * can help catch cases where the translator infers types incorrectly.
         *
         * Recommended for testing cases where the translator is expected to succeed; not recommended in production.
         */
        val outputExplicitTypes: Boolean = false
)

private data class TypedBlock(val block: Block, val type: UnvalidatedType?)
private data class TypedStatement(val statement: Statement, val type: UnvalidatedType?)

private sealed class TypedExpression
private data class RealExpression(val expression: Expression, val type: UnvalidatedType?): TypedExpression()
private data class NamespacePartExpression(val names: List<String>): TypedExpression()

private val UnknownType = UnvalidatedType.NamedType(net.semlang.api.EntityRef(null, net.semlang.api.EntityId.of("Unknown")), false, listOf())

private class Sem2ToSem1Translator(val context: S2Context, val moduleName: ModuleName, val upstreamModules: List<ValidatedModule>, val options: Sem2ToSem1Options) {
    lateinit var typeInfo: TypesInfo

    fun translate(): RawContext {
        this.typeInfo = collectTypeInfo(context, moduleName, upstreamModules)

        val functions = context.functions.map(::translate)
        val structs = context.structs.map(::translate)
        val interfaces = context.interfaces.map(::translate)
        val unions = context.unions.map(::translate)
        return RawContext(functions, structs, interfaces, unions)
    }

    private fun translate(function: S2Function): Function {
        return Function(
                id = translate(function.id),
                typeParameters = function.typeParameters.map(::translate),
                arguments = function.arguments.map(::translate),
                returnType = translate(function.returnType),
                block = translate(function.block, function.arguments.map { it.name to translate(it.type) }.toMap()).block,
                annotations = function.annotations.map(::translate),
                idLocation = translate(function.idLocation),
                returnTypeLocation = translate(function.returnTypeLocation)
        )
    }

    private fun translate(annotation: S2Annotation): Annotation {
        return Annotation(
                name = translate(annotation.name),
                values = annotation.values.map(::translate))
    }

    private fun translate(annotationArg: S2AnnotationArgument): AnnotationArgument {
        return when (annotationArg) {
            is S2AnnotationArgument.Literal -> AnnotationArgument.Literal(annotationArg.value)
            is S2AnnotationArgument.List -> AnnotationArgument.List(annotationArg.values.map(::translate))
        }
    }

    private fun translate(block: S2Block, externalVarTypes: Map<String, UnvalidatedType?>): TypedBlock {
        val varNamesInScope = HashMap<String, UnvalidatedType?>(externalVarTypes)
        val s1Statements = ArrayList<Statement>()

        for (s2Statement in block.statements) {
            val (s1Statement, statementType) = translate(s2Statement, varNamesInScope)
            if (options.failOnUninferredType && statementType == null) {
                error("Could not infer type for statement $s2Statement in block $block")
            }
            s1Statement.name?.let { varNamesInScope.put(it, statementType) }
            s1Statements.add(s1Statement)
        }
        val (returnedExpression, blockType) = translateFullExpression(block.returnedExpression, varNamesInScope)
        if (options.failOnUninferredType && blockType == null) {
            error("Could not infer type for returned expression $returnedExpression in block $block")
        }

        if (options.outputExplicitTypes && returnedExpression !is Expression.Variable) {
            // Have the returned expression also make its expected type explicit by putting it into a variable before
            // we return it
            val varName = getUnusedVarName(varNamesInScope.keys)
            val finalAssignment = Statement(varName, blockType, returnedExpression)
            s1Statements.add(finalAssignment)
            return TypedBlock(Block(
                    statements = s1Statements,
                    returnedExpression = Expression.Variable(varName),
                    location = translate(block.location)
            ), blockType)
        }
        return TypedBlock(Block(
                statements = s1Statements,
                returnedExpression = returnedExpression,
                location = translate(block.location)
        ), blockType)
    }

    private fun getUnusedVarName(keys: Set<String>): String {
        if (!keys.contains("result")) {
            return "result"
        }
        var i = 0
        while (true) {
            val candidateName = "result$i"
            if (!keys.contains(candidateName)) {
                return candidateName
            }
            i++
        }
    }

    private fun translate(statement: S2Statement, varTypes: Map<String, UnvalidatedType?>): TypedStatement {
        val (expression, expressionType) = translateFullExpression(statement.expression, varTypes)
        return TypedStatement(Statement(
                name = statement.name,
                type = if (options.outputExplicitTypes) {
                    expressionType
                } else {
                    statement.type?.let<S2Type, UnvalidatedType>(::translate)
                },
                expression = expression,
                nameLocation = translate(statement.nameLocation)
        ), expressionType)
    }

    /**
     * Translates a sem2 expression to sem1 and transforms any resulting partial synthetic expression into a real sem1 expression.
     */
    private fun translateFullExpression(expression: S2Expression, varTypes: Map<String, UnvalidatedType?>): RealExpression {
        val translated = translate(expression, varTypes)
        return when (translated) {
            is RealExpression -> translated
            is NamespacePartExpression -> {
                // Turn this into a function binding
                val functionRef = net.semlang.api.EntityRef(null, net.semlang.api.EntityId(translated.names))
                val typeInfo = typeInfo.getFunctionInfo(functionRef)

                val bindings: List<Expression?>
                val chosenParameters: List<UnvalidatedType?>
                if (typeInfo != null) {
                    bindings = Collections.nCopies(typeInfo.type.getNumArguments(), null)
                    chosenParameters = Collections.nCopies(typeInfo.type.typeParameters.size, null)
                } else {
                    bindings = listOf()
                    chosenParameters = listOf()
                }

                val functionBinding = Expression.NamedFunctionBinding(functionRef, bindings, chosenParameters)
                RealExpression(functionBinding, typeInfo?.type)
            }
        }
    }

    private fun translate(expression: S2Expression, varTypes: Map<String, UnvalidatedType?>): TypedExpression {
        return when (expression) {
            is S2Expression.RawId -> {
                val name = expression.name
                val type = varTypes[name] // Expect this to be null if it's a namespace, not a variable

                if (type != null) {
                    RealExpression(Expression.Variable(name), type)
                } else {
                    NamespacePartExpression(listOf(name))
                }
            }
            is S2Expression.DotAccess -> {
                // Lots of cases here... might be a real expression, might be a namespace
                val subexpression = translate(expression.subexpression, varTypes)
                val name = expression.name

                when (subexpression) {
                    is RealExpression -> {
                        val subexpressionType = subexpression.type
                        if (subexpressionType is UnvalidatedType.NamedType) {
                            val typeInfo = typeInfo.getTypeInfo(subexpressionType.ref)
                            if (typeInfo != null && typeInfo is TypeInfo.Struct) {
                                val memberType = typeInfo.memberTypes[name]
                                if (memberType != null) {
                                    return RealExpression(Expression.Follow(subexpression.expression, name), memberType)
                                }
                            } else if (typeInfo != null && typeInfo is TypeInfo.Interface) {
                                val methodType = typeInfo.methodTypes[name]
                                if (methodType != null) {
                                    return RealExpression(Expression.Follow(subexpression.expression, name), methodType)
                                }
                            }
                        }

                        // It's not a member/method, so assume it's a "local" function binding
                        val namespace = getNamespaceForType(subexpressionType)
                        val namespacedName = namespace + name
                        val functionRef = net.semlang.api.EntityRef(null, net.semlang.api.EntityId(namespacedName))
                        val functionInfo = typeInfo.getFunctionInfo(functionRef)
                        val numBindings = if (functionInfo == null) 1 else functionInfo.type.getNumArguments()
                        val bindings = listOf(subexpression.expression) + Collections.nCopies(numBindings - 1, null)

                        val chosenParameters = if (functionInfo != null) {
                            val argumentTypes = ArrayList<UnvalidatedType?>()
                            argumentTypes.add(subexpressionType)
                            argumentTypes.addAll(Collections.nCopies(functionInfo.type.getNumArguments() - 1, null))
                            val inferenceSources = getTypeParameterInferenceSources(functionInfo.type)
                            val inferredTypeParameters = inferenceSources.map { inferenceSourceList ->
                                inferenceSourceList.map { it.findType(argumentTypes) }.firstOrNull { it != null }
                            }
                            // TODO: Implement explicit type parameters for "local" functions
                            inferredTypeParameters
                        } else {
                            listOf()
                        }

                        val functionType = if (functionInfo == null) null else {
                            val chosenParametersAsMap = functionInfo.type.typeParameters.zip(chosenParameters).filter { it.second != null }.map { it.first.name to it.second!! }.toMap()
                            val withParameters = functionInfo.type.replacingNamedParameterTypes(chosenParametersAsMap)
                            withParameters.copy(argTypes = withParameters.argTypes.drop(1))
                        }

                        if (options.failOnUninferredType && functionType == null) {
                            error("Could not infer a function type for expression $expression")
                        }
                        return RealExpression(Expression.NamedFunctionBinding(
                                functionRef = functionRef,
                                bindings = bindings,
                                chosenParameters = chosenParameters
                        ), functionType)
                    }
                    is NamespacePartExpression -> {
                        // Add to the namespace part!
                        val newNamespace = subexpression.names + name
                        NamespacePartExpression(newNamespace)
                    }
                }
            }
            is S2Expression.IfThen -> {
                // Shortcut: Just assume the then-block has the correct type
                val (thenBlock, typeInfo) = translate(expression.thenBlock, varTypes)
                RealExpression(Expression.IfThen(
                        condition = translateFullExpression(expression.condition, varTypes).expression,
                        thenBlock = thenBlock,
                        elseBlock = translate(expression.elseBlock, varTypes).block,
                        location = translate(expression.location)
                ), typeInfo)
            }
            is S2Expression.FunctionCall -> {
                val (functionExpression, functionType) = translateFullExpression(expression.expression, varTypes)
                val (arguments, argumentTypes) = expression.arguments.map { translateFullExpression(it, varTypes) }.map { it.expression to it.type }.unzip()

                // Steps to do here:
                // Phase 1: Infer any missing type parameters
                // Phase 2: Apply autoboxing and autounboxing to any arguments of incorrect but related types

                val combinedChosenParameters: List<UnvalidatedType>
                val parameterizedFunctionType: UnvalidatedType.FunctionType?
                val returnType: UnvalidatedType?
                if (functionType is UnvalidatedType.FunctionType) {
                    val postBindingTypeParameterSources = getTypeParameterInferenceSources(functionType)
                    val inferredPostBindingTypeParameters = functionType.typeParameters.zip(postBindingTypeParameterSources).map { (parameter, parameterSources) ->
                        parameterSources.map { it.findType(argumentTypes) }.firstOrNull { it != null }
                    }
                    val chosenWithInference: List<UnvalidatedType> = if (expression.chosenParameters.size == functionType.typeParameters.size) {
                        expression.chosenParameters.map(::translate)
                    } else {
                        fillIntoNulls(inferredPostBindingTypeParameters, expression.chosenParameters.map(::translate))
                    }
                    val previouslyChosenParameters = if (functionExpression is Expression.NamedFunctionBinding) {
                        functionExpression.chosenParameters
                    } else { listOf() }
                    combinedChosenParameters = fillIntoNulls(previouslyChosenParameters, chosenWithInference)

                    val chosenParametersAsMap = functionType.typeParameters.zip(chosenWithInference).map { it.first.name to it.second }.toMap()
                    parameterizedFunctionType = functionType.replacingNamedParameterTypes(chosenParametersAsMap)
                    returnType = parameterizedFunctionType.outputType
                } else {
                    combinedChosenParameters = listOf()
                    parameterizedFunctionType = null
                    returnType = null
                }

                if (options.failOnUninferredType && parameterizedFunctionType == null) {
                    error("Could not determine type of expression $expression")
                }

                // Apply autoboxing and autounboxing
                val postBoxingArguments = applyAutoboxingToArguments(parameterizedFunctionType, arguments, argumentTypes)

                // TODO: Also have a case for Expression.ExpressionFunctionBinding (which also needs a previouslyChosenParameters change)
                // TODO: Also do this in the FunctionBinding section
                if (functionExpression is Expression.NamedFunctionBinding && functionType is UnvalidatedType.FunctionType) {
                    // Instead of having a named function binding that we immediately call, just have a NamedFunctionCall
                    val combinedArguments = fillIntoNulls(functionExpression.bindings, postBoxingArguments)

                    RealExpression(Expression.NamedFunctionCall(
                            functionRef = functionExpression.functionRef,
                            arguments = combinedArguments,
                            chosenParameters = combinedChosenParameters,
                            location = translate(expression.location)
                    ), returnType)
                } else {
                    RealExpression(Expression.ExpressionFunctionCall(
                            functionExpression = functionExpression,
                            arguments = postBoxingArguments,
                            chosenParameters = combinedChosenParameters,
                            location = translate(expression.location)
                    ), returnType)
                }
            }
            is S2Expression.Literal -> {
                val type = translate(expression.type)
                RealExpression(Expression.Literal(
                        type = type,
                        literal = expression.literal,
                        location = translate(expression.location)
                ), type)
            }
            is S2Expression.ListLiteral -> {
                val chosenParameter = translate(expression.chosenParameter)
                RealExpression(Expression.ListLiteral(
                        contents = expression.contents.map { translateFullExpression(it, varTypes).expression },
                        chosenParameter = chosenParameter,
                        location = translate(expression.location)
                ), UnvalidatedType.List(chosenParameter))
            }
            is S2Expression.FunctionBinding -> {
                // TODO: If the translated expression is a function binding, compress this
                val (functionExpression, functionType) = translateFullExpression(expression.expression, varTypes)

                val (bindings, bindingTypes) = expression.bindings.map { if (it == null) null else translateFullExpression(it, varTypes) }.map { it?.expression to it?.type }.unzip()


                // TODO: The output function type needs to be adjusted based on the parameters and bindings

                val postBindingType: UnvalidatedType.FunctionType?
                val parameterizedFunctionType: UnvalidatedType.FunctionType?
                if (functionType is UnvalidatedType.FunctionType) {
                    val numParameters = functionType.typeParameters.size
                    val explicitlyChosenParameters = expression.chosenParameters.map { if (it != null) translate(it) else null }

                    val postInferenceParameters = if (explicitlyChosenParameters.size == numParameters) {
                        explicitlyChosenParameters
                    } else {
                        val typeParameterInferenceSources = getTypeParameterInferenceSources(functionType)
                        val inferredParameters = typeParameterInferenceSources.mapIndexed { index, inferenceSources ->
                            inferenceSources.map { it.findType(bindingTypes) }.firstOrNull { it != null }
                        }
                        val chosenIterator = explicitlyChosenParameters.iterator()
                        inferredParameters.map { if (it != null) it else chosenIterator.next() }
                    }

                    val parameterReplacementMap = functionType.typeParameters.zip(postInferenceParameters).mapNotNull { (parameter, chosenParam) -> if (chosenParam == null) null else parameter.name to chosenParam }.toMap()
                    val parametersChosenType = functionType.replacingNamedParameterTypes(parameterReplacementMap)
                    parameterizedFunctionType = parametersChosenType

                    // Now remove the arguments that were bound
                    val newArguments = parametersChosenType.argTypes.zip(bindings).filter { it.second == null }.map { it.first }
                    postBindingType = parametersChosenType.copy(argTypes = newArguments)
                } else {
                    parameterizedFunctionType = null
                    postBindingType = null
                }

                // Apply autoboxing and autounboxing
                val postBoxingBindings = applyAutoboxingToBindings(parameterizedFunctionType, bindings, bindingTypes)

                RealExpression(Expression.ExpressionFunctionBinding(
                        functionExpression = functionExpression,
                        bindings = postBoxingBindings,
                        chosenParameters = expression.chosenParameters.map { if (it == null) null else translate(it) },
                        location = translate(expression.location)
                ), postBindingType)
            }
            is S2Expression.Follow -> {
                val (structureExpression, structureType) = translateFullExpression(expression.structureExpression, varTypes)
                val elementType = if (structureType is UnvalidatedType.NamedType) {
                    val typeInfo = typeInfo.getTypeInfo(structureType.ref)
                    if (typeInfo is TypeInfo.Struct) {
                        val parameterReplacementMap = typeInfo.typeParameters.map { it.name }.zip(structureType.parameters).toMap()
                        typeInfo.memberTypes[expression.name]?.replacingNamedParameterTypes(parameterReplacementMap)
                    } else if (typeInfo is TypeInfo.Interface) {
                        val parameterReplacementMap = typeInfo.typeParameters.map { it.name }.zip(structureType.parameters).toMap()
                        typeInfo.methodTypes[expression.name]?.replacingNamedParameterTypes(parameterReplacementMap)
                    } else {
                        null
                    }
                } else {
                    null
                }
                if (options.failOnUninferredType && elementType == null) {
                    error("Could not determine type for follow expression $expression with structure type $structureType")
                }
                RealExpression(Expression.Follow(
                        structureExpression = structureExpression,
                        name = expression.name,
                        location = translate(expression.location)
                ), elementType)
            }
            is S2Expression.InlineFunction -> {
                val arguments = expression.arguments.map(::translate)

                val varTypesInBlock = varTypes + arguments.map { it.name to it.type }.toMap()

                val (block, blockType) = translate(expression.block, varTypesInBlock)
                // If we don't declare a return type, infer from the block's returned type
                val returnType = expression.returnType?.let { translate(it) } ?: blockType ?: UnknownType
                val functionType = UnvalidatedType.FunctionType(listOf(), arguments.map {it.type}, returnType)
                RealExpression(Expression.InlineFunction(
                        arguments = arguments,
                        returnType = returnType,
                        block = block,
                        location = translate(expression.location)
                ), functionType)
            }
            is S2Expression.PlusOp -> {
                val left = translateFullExpression(expression.left, varTypes)
                val right = translateFullExpression(expression.right, varTypes)
                getOperatorExpression(left, right, "plus", expression, expression.operatorLocation)
            }
            is S2Expression.TimesOp -> {
                val left = translateFullExpression(expression.left, varTypes)
                val right = translateFullExpression(expression.right, varTypes)
                getOperatorExpression(left, right, "times", expression, expression.operatorLocation)
            }
            is S2Expression.EqualsOp -> {
                val left = translateFullExpression(expression.left, varTypes)
                val right = translateFullExpression(expression.right, varTypes)
                // TODO: Support Data.equals
                getOperatorExpression(left, right, "equals", expression, expression.operatorLocation)
            }
            is S2Expression.NotEqualsOp -> {
                val left = translateFullExpression(expression.left, varTypes)
                val right = translateFullExpression(expression.right, varTypes)
                // TODO: Support Data.equals
                val equalityExpression = getOperatorExpression(left, right, "equals", expression, expression.operatorLocation)
                getBooleanNegationOf(equalityExpression)
            }
            is S2Expression.LessThanOp -> {
                val left = translateFullExpression(expression.left, varTypes)
                val right = translateFullExpression(expression.right, varTypes)
                getOperatorExpression(left, right, "lessThan", expression, expression.operatorLocation)
            }
            is S2Expression.GreaterThanOp -> {
                val left = translateFullExpression(expression.left, varTypes)
                val right = translateFullExpression(expression.right, varTypes)
                getOperatorExpression(left, right, "greaterThan", expression, expression.operatorLocation)
            }
            is S2Expression.DotAssignOp -> {
                val left = translateFullExpression(expression.left, varTypes)
                val right = translateFullExpression(expression.right, varTypes)
                getOperatorExpression(left, right, "set", expression, expression.operatorLocation)
            }
        }
    }

    private fun applyAutoboxingToArguments(functionType: UnvalidatedType.FunctionType?, arguments: List<Expression>, argumentTypes: List<UnvalidatedType?>): List<Expression> {
        val results = applyAutoboxingToBindings(functionType, arguments, argumentTypes)
        return results.map { if (it != null) it else error("Null output for an argument when autoboxing a function call: functionType $functionType, arguments $arguments, types $argumentTypes") }
    }
    private fun applyAutoboxingToBindings(functionType: UnvalidatedType.FunctionType?, bindings: List<Expression?>, bindingTypes: List<UnvalidatedType?>): List<Expression?> {
        return if (functionType == null) bindings else {
            val intendedTypes = functionType.argTypes
            if (intendedTypes.size != bindings.size) bindings else {
                bindings.zip(bindingTypes).zip(intendedTypes).map argumentSelector@{ (bindingAndType, intendedType) ->
                    val (binding, bindingType) = bindingAndType
                    if (binding == null) {
                        return@argumentSelector null
                    }

                    if (bindingType != null && !intendedType.equalsIgnoringLocation(bindingType)) {
                        // Try reconciling the types and adjusting the argument accordingly

                        // Try to collect some information about these...
                        val argumentTypeChain = getAutoboxingTypeChain(bindingType)
                        val intendedTypeChain = getAutoboxingTypeChain(intendedType)

                        // Is one a struct that contains the other?
                        val intendedTypeIndexInArgs = argumentTypeChain.getIndexOfTypeIgnoringLocation(intendedType)
                        if (intendedTypeIndexInArgs != null) {
                            // e.g. argument is Natural, intended type is Integer; replace n with n.integer
                            // (in that example, index is 1, and we want 1 entry from the member names, i.e. 0..0)
                            var curExpression: Expression = binding
                            for (memberNameIndex in 0..(intendedTypeIndexInArgs - 1)) {
                                val memberName = argumentTypeChain.memberNames[memberNameIndex]
                                curExpression = Expression.Follow(curExpression, memberName, curExpression.location)
                            }
                            return@argumentSelector curExpression
                        }
                    }

                    binding
                }
            }
        }
    }

    // Note: The first type in the chain will be the original type of the argument. The last type will be the innermost type.
    // The size of memberNames will be one less than the size of types.
    private data class AutoboxTypeChain(val types: List<UnvalidatedType>, val memberNames: List<String>) {
        fun getIndexOfTypeIgnoringLocation(type: UnvalidatedType): Int? {
            types.forEachIndexed { index, ourType ->
                if (ourType.equalsIgnoringLocation(type)) {
                    return index
                }
            }
            return null
        }
    }

    private fun getAutoboxingTypeChain(argumentType: UnvalidatedType): AutoboxTypeChain {
        val typeChain = ArrayList<UnvalidatedType>()
        val memberNames = ArrayList<String>()

        var curType = argumentType
        while (true) {
            typeChain.add(curType)
            // TODO: This might need a way to tweak this number someday
            if (typeChain.size >= 1000) error("Infinite loop in getAutoboxingTypeChain; types: $typeChain, member names: $memberNames")
            val curTypeInfo = (curType as? UnvalidatedType.NamedType)?.ref?.let { typeInfo.getTypeInfo(it) }
            if (curTypeInfo !is TypeInfo.Struct) {
                break
            }
            val memberEntry = curTypeInfo.memberTypes.entries.singleOrNull()
            if (memberEntry == null) {
                break
            }
            memberNames.add(memberEntry.key)
            val parameterReplacementMap = curTypeInfo.typeParameters.map { it.name }.zip((curType as UnvalidatedType.NamedType).parameters).toMap()
            curType = memberEntry.value.replacingNamedParameterTypes(parameterReplacementMap)
        }
        return AutoboxTypeChain(typeChain, memberNames)
    }

    private val BooleanNotRef = net.semlang.api.EntityRef(CURRENT_NATIVE_MODULE_ID.asRef(), net.semlang.api.EntityId.of("Boolean", "not"))
    private fun getBooleanNegationOf(expression: RealExpression): RealExpression {
        return RealExpression(Expression.NamedFunctionCall(
                functionRef = BooleanNotRef,
                arguments = listOf(expression.expression),
                chosenParameters = listOf()
        ), UnvalidatedType.Boolean())
    }

    private fun getOperatorExpression(left: RealExpression, right: RealExpression, operatorName: String, expression: S2Expression, operatorLocation: Location?): RealExpression {
        // At some point we might support doing this with separate types (e.g. Natural + Integer), but for
        // now expect the two to be the same
        val operandType = left.type
        val functionNamespace = getNamespaceForType(operandType)
        val functionRef = net.semlang.api.EntityRef(null, net.semlang.api.EntityId(functionNamespace + operatorName))

        val functionInfo = typeInfo.getFunctionInfo(functionRef)
        val outputType = if (functionInfo != null) {
            functionInfo.type.outputType
        } else {
            null
        }
        if (options.failOnUninferredType && outputType == null) {
            error("Could not determine type for expression $expression")
        }
        return RealExpression(Expression.NamedFunctionCall(
                functionRef = functionRef,
                arguments = listOf(left.expression, right.expression),
                chosenParameters = listOf(),
                location = translate(expression.location),
                functionRefLocation = translate(operatorLocation)
        ), outputType)
    }

    private fun getNamespaceForType(subexpressionType: UnvalidatedType?): List<String> {
        return when (subexpressionType) {
            is UnvalidatedType.Invalid.ReferenceInteger -> listOf("Integer")
            is UnvalidatedType.Invalid.ReferenceBoolean -> listOf("Boolean")
            is UnvalidatedType.Integer -> listOf("Integer")
            is UnvalidatedType.Boolean -> listOf("Boolean")
            is UnvalidatedType.List -> listOf("List")
            is UnvalidatedType.Maybe -> listOf("Maybe")
            is UnvalidatedType.FunctionType -> listOf()
            is UnvalidatedType.NamedType -> {
                subexpressionType.ref.id.namespacedName
            }
            null -> listOf()
        }
    }

    private fun translate(struct: S2Struct): UnvalidatedStruct {
        return UnvalidatedStruct(
                id = translate(struct.id),
                typeParameters = struct.typeParameters.map(::translate),
                members = struct.members.map(::translate),
                requires = struct.requires?.let { translate(it, struct.members.map { it.name to translate(it.type) }.toMap()).block },
                annotations = struct.annotations.map(::translate),
                idLocation = translate(struct.idLocation)
        )
    }

    private fun translate(interfac: S2Interface): UnvalidatedInterface {
        return UnvalidatedInterface(
                id = translate(interfac.id),
                typeParameters = interfac.typeParameters.map(::translate),
                methods = interfac.methods.map(::translate),
                annotations = interfac.annotations.map(::translate),
                idLocation = translate(interfac.idLocation))
    }

    private fun translate(union: S2Union): UnvalidatedUnion {
        return UnvalidatedUnion(
                id = translate(union.id),
                typeParameters = union.typeParameters.map(::translate),
                options = union.options.map(::translate),
                annotations = union.annotations.map(::translate),
                idLocation = translate(union.idLocation)
        )
    }
}

/*
Okay, let's speculate a bit on how sem2 expressions will work
(TODO: Move the following into documentation somewhere about how these things are expected to work.)

We want to be able to use . for pretty much everything:

Namespaces (like in sem1): List.get(myList, 1)
Method-like functions: myList.get(1)
 - This looks for a method in the namespace of the type of the variable that takes a thing of that type as its first arg
Struct access: myPair.left

So these will presumably just be parsed as a.sequence.of.arbitrary.strings.with.unknown.expression.type and leave
figuring out what looks like a variable or a namespace or an entity until translation time, when we have a list of
entity IDs (and we'll track variables in scope as well).

We'll also want different kinds of literals (eventually, at least) and operators, which will correspond to functions
with specific expected names and signatures, as in Kotlin. (Maybe some magic around struct comparisons: myInt + myNatural
and myNatural + myInt should both work via some sort of coercion to Integer.plus(myInt, myNatural->integer).)

Stuff to consider:

- DottedSequences can be followed by () or |()
- Are all () and |() preceded by DottedSequences? Not quite, they can also be connected to each other
- We'll also want some cleaner lambda expression notation with (args) -> output, probably
- Also :: notation for some shortcut references to things, or should we just use .? How about e.g. Pair::left for struct
  references?
- While loops and for loops - these might test our ability to handle references in these situations, or we might just
  disallow references in these for now
- Similarly: val and var as options (vars being necessary for while/for loops to be useful)
- Eventually: Contexts

 */

internal fun translate(id: EntityId): net.semlang.api.EntityId {
    return net.semlang.api.EntityId(id.namespacedName)
}

internal fun translate(typeParameter: TypeParameter): net.semlang.api.TypeParameter {
    return net.semlang.api.TypeParameter(
            name = typeParameter.name,
            typeClass = translate(typeParameter.typeClass))
}

private fun translate(typeClass: TypeClass?): net.semlang.api.TypeClass? {
    return when (typeClass) {
        TypeClass.Data -> net.semlang.api.TypeClass.Data
        null -> null
    }
}

internal fun translate(location: Location?): net.semlang.api.Location? {
    return if (location == null) null else {
        net.semlang.api.Location(
                documentUri = location.documentUri,
                range = translate(location.range))
    }
}

private fun translate(range: Range): net.semlang.api.Range {
    return net.semlang.api.Range(
            start = translate(range.start),
            end = translate(range.end))
}

private fun translate(position: Position): net.semlang.api.Position {
    return net.semlang.api.Position(
            lineNumber = position.lineNumber,
            column = position.column,
            rawIndex = position.rawIndex)
}

internal fun translate(type: S2Type): UnvalidatedType {
    return when (type) {
        is S2Type.Invalid.ReferenceInteger -> UnvalidatedType.Invalid.ReferenceInteger(translate(type.location))
        is S2Type.Invalid.ReferenceBoolean -> UnvalidatedType.Invalid.ReferenceBoolean(translate(type.location))
        is S2Type.Integer -> UnvalidatedType.Integer(translate(type.location))
        is S2Type.Boolean -> UnvalidatedType.Boolean(translate(type.location))
        is S2Type.List -> UnvalidatedType.List(
                parameter = translate(type.parameter),
                location = translate(type.location))
        is S2Type.Maybe -> UnvalidatedType.Maybe(
                parameter = translate(type.parameter),
                location = translate(type.location))
        is S2Type.FunctionType -> UnvalidatedType.FunctionType(
                typeParameters = type.typeParameters.map(::translate),
                argTypes = type.argTypes.map(::translate),
                outputType = translate(type.outputType),
                location = translate(type.location))
        is S2Type.NamedType -> UnvalidatedType.NamedType(
                ref = translate(type.ref),
                isReference = type.isReference,
                parameters = type.parameters.map(::translate),
                location = translate(type.location))
    }
}

private fun translate(ref: EntityRef): net.semlang.api.EntityRef {
    return net.semlang.api.EntityRef(
            moduleRef = ref.moduleRef?.let(::translate),
            id = translate(ref.id)
    )
}

private fun translate(ref: S2ModuleRef): net.semlang.api.ModuleRef {
    return net.semlang.api.ModuleRef(ref.group, ref.module, ref.version)
}

internal fun translate(argument: S2Argument): UnvalidatedArgument {
    return UnvalidatedArgument(
            name = argument.name,
            type = translate(argument.type),
            location = translate(argument.location)
    )
}

internal fun translate(member: S2Member): UnvalidatedMember {
    return UnvalidatedMember(
            name = member.name,
            type = translate(member.type)
    )
}

internal fun translate(method: S2Method): UnvalidatedMethod {
    return UnvalidatedMethod(
            name = method.name,
            typeParameters = method.typeParameters.map(::translate),
            arguments = method.arguments.map(::translate),
            returnType = translate(method.returnType)
    )
}

internal fun translate(option: S2Option): UnvalidatedOption {
    return UnvalidatedOption(
            name = option.name,
            type = option.type?.let(::translate),
            idLocation = translate(option.idLocation))
}

private fun <T> fillIntoNulls(bindings: List<T?>, fillings: List<T>): List<T> {
    try {
        val results = ArrayList<T>()
        val fillingsItr = fillings.iterator()
        for (binding in bindings) {
            if (binding != null) {
                results.add(binding)
            } else {
                results.add(fillingsItr.next())
            }
        }
        // We sometimes make fake function bindings with a minimal number of arguments; add any remaining fillings
        while (fillingsItr.hasNext()) {
            results.add(fillingsItr.next())
        }
        return results
    } catch (e: RuntimeException) {
        throw IllegalArgumentException("fillIntoNulls failed wiith bindings $bindings and fillings $fillings", e)
    }
}
