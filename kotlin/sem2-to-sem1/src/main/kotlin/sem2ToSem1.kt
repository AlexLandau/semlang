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
//    val functions = context.functions.map
//    return RawContext(functions, structs, interfaces, unions)
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
