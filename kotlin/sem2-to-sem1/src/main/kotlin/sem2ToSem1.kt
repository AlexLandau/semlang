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
import net.semlang.validator.TypesInfo
import java.util.*

fun translateSem2ContextToSem1(context: S2Context, moduleName: ModuleName): RawContext {
    return Sem1ToSem2Translator(context, moduleName).translate()
}

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
                block = translate(function.block, function.arguments.map { it.name }.toSet()),
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

    private fun translate(block: S2Block, externalVarNames: Set<String>): Block {
        val varNamesInScope = HashSet<String>(externalVarNames)
        val s1Statements = ArrayList<Statement>()

        for (s2Statement in block.statements) {
            val s1Statement = translate(s2Statement, varNamesInScope)
            s1Statement.name?.let { varNamesInScope.add(it) }
        }
        val returnedExpression = translate(block.returnedExpression, varNamesInScope)

        return Block(statements = s1Statements,
                returnedExpression = returnedExpression,
                location = translate(block.location))
    }

    private fun translate(statement: S2Statement, varNamesInScope: Set<String>): Statement {
        return Statement(
                name = statement.name,
                type = statement.type?.let(::translate),
                expression = translate(statement.expression, varNamesInScope),
                nameLocation = translate(statement.nameLocation))
    }

    private fun translate(expression: S2Expression, varNamesInScope: Set<String>): Expression {
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
                if (varNamesInScope.contains(firstString)) {
                    if (strings.size > 1) {
                        TODO("Using . after variables isn't implemented yet")
                    }
                    return Expression.Variable(firstString, translate(expression.location))
                } else {
                    // Assume it's a function name, treat it as an empty function binding
                    val functionRef = net.semlang.api.EntityRef(null, net.semlang.api.EntityId(expression.strings))
                    val functionInfo = typeInfo.getFunctionInfo(functionRef)
                    if (functionInfo == null) {
                        TODO("Handle this case")
                    }

                    val bindings = Collections.nCopies(functionInfo.type.getNumArgTypes(), null)
                    val chosenParameters = Collections.nCopies(functionInfo.type.typeParameters.size, null)

                    return Expression.NamedFunctionBinding(functionRef, bindings, chosenParameters, translate(expression.location), translate(expression.location))
                }
            }
            is S2Expression.IfThen -> {
                Expression.IfThen(
                        condition = translate(expression.condition, varNamesInScope),
                        thenBlock = translate(expression.thenBlock, varNamesInScope),
                        elseBlock = translate(expression.elseBlock, varNamesInScope),
                        location = translate(expression.location)
                )
            }
            is S2Expression.FunctionCall -> {
                // TODO: If the translated expression is a function binding, compress this
                Expression.ExpressionFunctionCall(
                        functionExpression = translate(expression.expression, varNamesInScope),
                        arguments = expression.arguments.map { translate(it, varNamesInScope) },
                        chosenParameters = expression.chosenParameters.map(::translate),
                        location = translate(expression.location)
                )
            }
            is S2Expression.Literal -> {
                Expression.Literal(
                        type = translate(expression.type),
                        literal = expression.literal,
                        location = translate(expression.location)
                )
            }
            is S2Expression.ListLiteral -> {
                Expression.ListLiteral(
                        contents = expression.contents.map { translate(it, varNamesInScope) },
                        chosenParameter = translate(expression.chosenParameter),
                        location = translate(expression.location)
                )
            }
            is S2Expression.FunctionBinding -> {
                // TODO: If the translated expression is a function binding, compress this
                Expression.ExpressionFunctionBinding(
                        functionExpression = translate(expression.expression, varNamesInScope),
                        bindings = expression.bindings.map { if (it == null) null else translate(it, varNamesInScope) },
                        chosenParameters = expression.chosenParameters.map { if (it == null) null else translate(it) },
                        location = translate(expression.location)
                )
            }
            is S2Expression.Follow -> {
                Expression.Follow(
                        structureExpression = translate(expression.structureExpression, varNamesInScope),
                        name = expression.name,
                        location = translate(expression.location)
                )
            }
            is S2Expression.InlineFunction -> {
                Expression.InlineFunction(
                        arguments = expression.arguments.map(::translate),
                        returnType = translate(expression.returnType),
                        block = translate(expression.block, varNamesInScope),
                        location = translate(expression.location)
                )
            }
        }
    }

    private fun translate(struct: S2Struct): UnvalidatedStruct {
        return UnvalidatedStruct(
                id = translate(struct.id),
                typeParameters = struct.typeParameters.map(::translate),
                members = struct.members.map(::translate),
                requires = struct.requires?.let { translate(it, struct.members.map { it.name }.toSet()) },
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

/*
Okay, let's speculate a bit on how sem2 expressions will work

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
