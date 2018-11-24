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

fun translateSem2ContextToSem1(context: S2Context): RawContext {
    return Sem1ToSem2Translator(context).translate()
}

private class Sem1ToSem2Translator(val context: S2Context) {
    fun translate(): RawContext {
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
                block = translate(function.block),
                annotations = function.annotations.map(::translate),
                idLocation = translate(function.idLocation),
                returnTypeLocation = translate(function.returnTypeLocation)
        )
    }

    private fun translate(id: EntityId): net.semlang.api.EntityId {
        return net.semlang.api.EntityId(id.namespacedName)
    }

    private fun translate(typeParameter: TypeParameter): net.semlang.api.TypeParameter {
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

    private fun translate(location: Location?): net.semlang.api.Location? {
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

    private fun translate(argument: S2Argument): UnvalidatedArgument {
        return UnvalidatedArgument(
                name = argument.name,
                type = translate(argument.type),
                location = translate(argument.location)
        )
    }

    private fun translate(block: S2Block): Block {
        return Block(statements = block.statements.map(::translate),
                returnedExpression = translate(block.returnedExpression),
                location = translate(block.location))
    }

    private fun translate(statement: S2Statement): Statement {
        return Statement(
                name = statement.name,
                type = statement.type?.let(::translate),
                expression = translate(statement.expression),
                nameLocation = translate(statement.nameLocation))
    }

    private fun translate(expression: S2Expression): Expression {
        return when (expression) {
            // TODO: Revamp how Sem2 deals with expressions
            is S2Expression.Variable -> {
                Expression.Variable(name = expression.name, location = translate(expression.location))
            }
            is S2Expression.IfThen -> {
                Expression.IfThen(
                        condition = translate(expression.condition),
                        thenBlock = translate(expression.thenBlock),
                        elseBlock = translate(expression.elseBlock),
                        location = translate(expression.location)
                )
            }
            is S2Expression.NamedFunctionCall -> {
                Expression.NamedFunctionCall(
                        functionRef = translate(expression.functionRef),
                        arguments = expression.arguments.map(::translate),
                        chosenParameters = expression.chosenParameters.map(::translate),
                        location = translate(expression.location)
                )
            }
            is S2Expression.ExpressionFunctionCall -> {
                Expression.ExpressionFunctionCall(
                        functionExpression = translate(expression.functionExpression),
                        arguments = expression.arguments.map(::translate),
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
                        contents = expression.contents.map(::translate),
                        chosenParameter = translate(expression.chosenParameter),
                        location = translate(expression.location)
                )
            }
            is S2Expression.NamedFunctionBinding -> {
                Expression.NamedFunctionBinding(
                        functionRef = translate(expression.functionRef),
                        bindings = expression.bindings.map { if (it == null) null else translate(it) },
                        chosenParameters = expression.chosenParameters.map { if (it == null) null else translate(it) },
                        location = translate(expression.location),
                        functionRefLocation = translate(expression.functionRefLocation)
                )
            }
            is S2Expression.ExpressionFunctionBinding -> {
                Expression.ExpressionFunctionBinding(
                        functionExpression = translate(expression.functionExpression),
                        bindings = expression.bindings.map { if (it == null) null else translate(it) },
                        chosenParameters = expression.chosenParameters.map { if (it == null) null else translate(it) },
                        location = translate(expression.location)
                )
            }
            is S2Expression.Follow -> {
                Expression.Follow(
                        structureExpression = translate(expression.structureExpression),
                        name = expression.name,
                        location = translate(expression.location)
                )
            }
            is S2Expression.InlineFunction -> {
                Expression.InlineFunction(
                        arguments = expression.arguments.map(::translate),
                        returnType = translate(expression.returnType),
                        block = translate(expression.block),
                        location = translate(expression.location)
                )
            }
        }
    }

    private fun translate(type: S2Type): UnvalidatedType {
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

    private fun translate(struct: S2Struct): UnvalidatedStruct {
        return UnvalidatedStruct(
                id = translate(struct.id),
                typeParameters = struct.typeParameters.map(::translate),
                members = struct.members.map(::translate),
                requires = struct.requires?.let(::translate),
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
