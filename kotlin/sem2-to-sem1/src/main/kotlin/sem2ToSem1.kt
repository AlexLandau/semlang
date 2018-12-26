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
import java.util.*

fun translateSem2ContextToSem1(context: S2Context, moduleName: ModuleName): RawContext {
    return Sem1ToSem2Translator(context, moduleName).translate()
}

private data class TypedBlock(val block: Block, val type: UnvalidatedType?)
private data class TypedStatement(val statement: Statement, val type: UnvalidatedType?)
private data class TypedExpression(val expression: Expression, val type: UnvalidatedType?)

private class Sem1ToSem2Translator(val context: S2Context, val moduleName: ModuleName) {
    lateinit var typeInfo: TypesInfo

    fun translate(): RawContext {
        this.typeInfo = collectTypeInfo(context, moduleName)

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
            s1Statement.name?.let { varNamesInScope.put(it, statementType) }
        }
        val (returnedExpression, blockType) = translate(block.returnedExpression, varNamesInScope)

        val s1Block = Block(statements = s1Statements,
                returnedExpression = returnedExpression,
                location = translate(block.location))
        return TypedBlock(s1Block, blockType)
    }

    private fun translate(statement: S2Statement, varTypes: Map<String, UnvalidatedType?>): TypedStatement {
        val (expression, expressionType) = translate(statement.expression, varTypes)
        val statement = Statement(
                name = statement.name,
                type = statement.type?.let(::translate),
                expression = expression,
                nameLocation = translate(statement.nameLocation))
        return TypedStatement(statement, expressionType)
    }

    private fun translate(expression: S2Expression, varTypes: Map<String, UnvalidatedType?>): TypedExpression {
        return when (expression) {
            is S2Expression.DottedSequence -> {
                // If this precedes a FunctionCall or FunctionBinding (i.e. is a functionExpression), those should perhaps
                // use separate functions to indicate that this can be something different?
                // Alternatively, I *think* we can translate special things to be Bindings and then have FunctionCall/Binding
                // translation look at the expressions to combine the special cases into simpler expressions

                // First, look at the first string in the sequence, which is the only one that is a candidate to be a
                // variable (in which case we consider follow expressions and __)
                val strings = expression.strings
                if (strings.isEmpty()) {
                    error("A DottedSequence should not be empty; bug in the compiler")
                }
                val firstString = strings[0]

                // Okay, thing to worry about with this design/approach:
                // If I want to take this approach AND support both foo.method() and foo.member, I need to know foo's type
                // This is... also true generally if I want to turn list.get(i) into List.get(list, i)!
                // Which means we need to track types in this stage

                // TODO: Implement things other than variables
                if (varTypes.containsKey(firstString)) {
                    val variable = Expression.Variable(firstString, translate(expression.location))
                    // TODO: I think we can drop the special case here...
                    if (strings.size > 1) {
//                        TODO("Using . after variables isn't implemented yet")
                        // After this, we'll go through a string of expression manipulations...
                        var curInnerExpression: Expression = variable
                        var curInnerExpressionType: UnvalidatedType? = varTypes[firstString]
                        for (string in strings.drop(1)) {
                            // TODO: If the type is a struct/interface and the name is a field name, we should do a follow
                            val newExpression: Expression
                            val newExpressionType: UnvalidatedType?
                            val innerExpressionTypeInfo = if (curInnerExpressionType is UnvalidatedType.NamedType) {
                                typeInfo.getTypeInfo(curInnerExpressionType)
                            } else {
                                null
                            }
                            if (innerExpressionTypeInfo is TypeInfo.Struct && innerExpressionTypeInfo.memberTypes.containsKey(string)) {
                                TODO("Handle struct member")
                            } else if (innerExpressionTypeInfo is TypeInfo.Interface && innerExpressionTypeInfo.methodTypes.containsKey(string)) {
                                TODO("Handle interface method")
                            } else {
                                // Otherwise...
                                val namespace = when (curInnerExpressionType) {
                                    is UnvalidatedType.Invalid.ReferenceInteger -> listOf("Integer")
                                    is UnvalidatedType.Invalid.ReferenceBoolean -> listOf("Boolean")
                                    is UnvalidatedType.Integer -> listOf("Integer")
                                    is UnvalidatedType.Boolean -> listOf("Boolean")
                                    is UnvalidatedType.List -> listOf("List")
                                    is UnvalidatedType.Maybe -> listOf("Maybe")
                                    is UnvalidatedType.FunctionType -> listOf()
                                    is UnvalidatedType.NamedType -> {
                                        curInnerExpressionType.ref.id.namespacedName
                                    }
                                    null -> listOf()
                                }
                                val namespacedName = namespace + string
                                val functionRef = net.semlang.api.EntityRef(null, net.semlang.api.EntityId(namespacedName))
                                val functionInfo = typeInfo.getFunctionInfo(functionRef)
                                val numBindings = if (functionInfo == null) 1 else functionInfo.type.getNumArguments()
                                val bindings = listOf(curInnerExpression) + Collections.nCopies(numBindings - 1, null)
                                val chosenParameters = listOf<UnvalidatedType?>() // Probably not correct...
                                newExpression = Expression.NamedFunctionBinding(functionRef, bindings, chosenParameters)
                                newExpressionType = functionInfo?.type
                            }

                            curInnerExpression = newExpression
                            curInnerExpressionType = newExpressionType
                        }
                        return TypedExpression(curInnerExpression, curInnerExpressionType)
                    }
                    return TypedExpression(variable, varTypes[firstString])
                } else {
                    // Assume it's a function name, treat it as an empty function binding
                    val functionRef = net.semlang.api.EntityRef(null, net.semlang.api.EntityId(expression.strings))
                    val functionInfo = typeInfo.getFunctionInfo(functionRef)
                    if (functionInfo == null) {
                        TODO("Handle this case")
                    }

                    val bindings = Collections.nCopies(functionInfo.type.getNumArguments(), null)
                    val chosenParameters = Collections.nCopies(functionInfo.type.typeParameters.size, null)

                    return TypedExpression(Expression.NamedFunctionBinding(
                            functionRef = functionRef,
                            bindings = bindings,
                            chosenParameters = chosenParameters,
                            location = translate(expression.location),
                            functionRefLocation = translate(expression.location)
                    ), functionInfo.type)
                }
            }
            is S2Expression.IfThen -> {
                // Shortcut: Just assume the then-block has the correct type
                val (thenBlock, typeInfo) = translate(expression.thenBlock, varTypes)
                TypedExpression(Expression.IfThen(
                        condition = translate(expression.condition, varTypes).expression,
                        thenBlock = thenBlock,
                        elseBlock = translate(expression.elseBlock, varTypes).block,
                        location = translate(expression.location)
                ), typeInfo)
            }
            is S2Expression.FunctionCall -> {
                // TODO: If the translated expression is a function binding, compress this
                val (functionExpression, functionType) = translate(expression.expression, varTypes)
                val returnType = (functionType as? UnvalidatedType.FunctionType)?.outputType
                TypedExpression(Expression.ExpressionFunctionCall(
                        functionExpression = functionExpression,
                        arguments = expression.arguments.map { translate(it, varTypes).expression },
                        chosenParameters = expression.chosenParameters.map(::translate),
                        location = translate(expression.location)
                ), returnType)
            }
            is S2Expression.Literal -> {
                val type = translate(expression.type)
                TypedExpression(Expression.Literal(
                        type = type,
                        literal = expression.literal,
                        location = translate(expression.location)
                ), type)
            }
            is S2Expression.ListLiteral -> {
                val chosenParameter = translate(expression.chosenParameter)
                TypedExpression(Expression.ListLiteral(
                        contents = expression.contents.map { translate(it, varTypes).expression },
                        chosenParameter = chosenParameter,
                        location = translate(expression.location)
                ), UnvalidatedType.List(chosenParameter))
            }
            is S2Expression.FunctionBinding -> {
                // TODO: If the translated expression is a function binding, compress this
                val (functionExpression, functionType) = translate(expression.expression, varTypes)
                // TODO: The output function type needs to be adjusted based on the parameters and bindings
                TypedExpression(Expression.ExpressionFunctionBinding(
                        functionExpression = functionExpression,
                        bindings = expression.bindings.map { if (it == null) null else translate(it, varTypes).expression },
                        chosenParameters = expression.chosenParameters.map { if (it == null) null else translate(it) },
                        location = translate(expression.location)
                ), functionType)
            }
            is S2Expression.Follow -> {
                val (structureExpression, structureType) = translate(expression.structureExpression, varTypes)
                val elementType = if (structureType is UnvalidatedType.NamedType) {
                    val typeInfo = typeInfo.getTypeInfo(structureType)
                    if (typeInfo is TypeInfo.Struct) {
                        typeInfo.memberTypes[expression.name]
                    } else if (typeInfo is TypeInfo.Interface) {
                        typeInfo.methodTypes[expression.name]
                    } else {
                        null
                    }
                } else {
                    null
                }
                TypedExpression(Expression.Follow(
                        structureExpression = structureExpression,
                        name = expression.name,
                        location = translate(expression.location)
                ), elementType)
            }
            is S2Expression.InlineFunction -> {
                val arguments = expression.arguments.map(::translate)
                val returnType = translate(expression.returnType)
                val functionType = UnvalidatedType.FunctionType(listOf(), arguments.map {it.type}, returnType)
                TypedExpression(Expression.InlineFunction(
                        arguments = arguments,
                        returnType = returnType,
                        block = translate(expression.block, varTypes).block,
                        location = translate(expression.location)
                ), functionType)
            }
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

    private fun translate(member: S2Member): UnvalidatedMember {
        return UnvalidatedMember(
                name = member.name,
                type = translate(member.type)
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

    private fun translate(method: S2Method): UnvalidatedMethod {
        return UnvalidatedMethod(
                name = method.name,
                typeParameters = method.typeParameters.map(::translate),
                arguments = method.arguments.map(::translate),
                returnType = translate(method.returnType)
        )
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

    private fun translate(option: S2Option): UnvalidatedOption {
        return UnvalidatedOption(
                name = option.name,
                type = option.type?.let(::translate),
                idLocation = translate(option.idLocation))
    }
}

// Note: Primitive (non-named) types like Integer and Boolean are treated as opaque types for now
private fun TypesInfo.getTypeInfo(type: UnvalidatedType): TypeInfo? {
    return when (type) {
        is UnvalidatedType.Invalid.ReferenceInteger -> TODO()
        is UnvalidatedType.Invalid.ReferenceBoolean -> TODO()
        is UnvalidatedType.Integer -> TODO()
        is UnvalidatedType.Boolean -> TODO()
        is UnvalidatedType.List -> TODO()
        is UnvalidatedType.Maybe -> TODO()
        is UnvalidatedType.FunctionType -> TODO()
        is UnvalidatedType.NamedType -> TODO()
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
