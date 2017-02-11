package semlang.parser

import org.antlr.v4.runtime.ANTLRFileStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import semlang.antlr.SemlangLexer
import semlang.antlr.SemlangParser
import semlang.antlr.SemlangParserBaseListener
import semlang.api.*
import semlang.api.Function
import java.io.File
import java.util.*

private fun parseFunction(function: SemlangParser.FunctionContext): Function {
    val id: FunctionId = parseFunctionId(function.function_id())

    val typeParameters: List<String> = if (function.cd_ids() != null) {
        parseCommaDelimitedIds(function.cd_ids())
    } else {
        listOf()
    }
    val arguments: List<Argument> = parseFunctionArguments(function.function_arguments())
    val returnType: Type = parseType(function.type())

    val ambiguousBlock: AmbiguousBlock = parseBlock(function.block())
    val argumentVariableIds = arguments.map { arg -> FunctionId.of(arg.name) }
    val block = scopeBlock(argumentVariableIds, ambiguousBlock)

    return Function(id, typeParameters, arguments, returnType, block)
}

private fun scopeBlock(externalVariableIds: List<FunctionId>, ambiguousBlock: AmbiguousBlock): Block {
    val localVariableIds = ArrayList(externalVariableIds)
    val assignments: MutableList<Assignment> = ArrayList()
    for (assignment in ambiguousBlock.assignments) {
        val expression = scopeExpression(localVariableIds, assignment.expression)
        localVariableIds.add(FunctionId.of(assignment.name))
        assignments.add(Assignment(assignment.name, assignment.type, expression))
    }
    val returnedExpression = scopeExpression(localVariableIds, ambiguousBlock.returnedExpression)
    return Block(assignments, returnedExpression)
}

fun scopeExpression(varIds: ArrayList<FunctionId>, expression: AmbiguousExpression): Expression {
    return when (expression) {
        is AmbiguousExpression.Follow -> Expression.Follow(
                scopeExpression(varIds, expression.expression),
                expression.id)
        is AmbiguousExpression.VariableOrFunctionReference -> {
            if (varIds.contains(expression.nameOrFunctionId)) {
                if (expression.chosenParameters.isNotEmpty()) {
                    error("Known variable name ${expression.nameOrFunctionId} shouldn't be used with type parameters")
                }
                return Expression.Variable(expression.nameOrFunctionId.functionName)
            } else {
                return Expression.FunctionReference(expression.nameOrFunctionId, expression.chosenParameters)
            }
        }
        is AmbiguousExpression.IfThen -> Expression.IfThen(
                scopeExpression(varIds, expression.condition),
                thenBlock = scopeBlock(varIds, expression.thenBlock),
                elseBlock = scopeBlock(varIds, expression.elseBlock)
        )
        is AmbiguousExpression.FunctionCall -> {
            if (varIds.contains(expression.functionIdOrVariable)) {
                return Expression.VariableFunctionCall(
                        variableName = expression.functionIdOrVariable.functionName,
                        arguments = expression.arguments.map { expr -> scopeExpression(varIds, expr) },
                        chosenParameters = expression.chosenParameters)
            } else {
                return Expression.NamedFunctionCall(
                        functionId = expression.functionIdOrVariable,
                        arguments = expression.arguments.map { expr -> scopeExpression(varIds, expr) },
                        chosenParameters = expression.chosenParameters)
            }
        }
        is AmbiguousExpression.Literal -> Expression.Literal(expression.type, expression.literal)
    }
}


private fun parseStruct(ctx: SemlangParser.StructContext): Struct {
    val id: FunctionId = parseFunctionId(ctx.function_id())

    val typeParameters: List<String> = if (ctx.cd_ids() != null) {
        parseCommaDelimitedIds(ctx.cd_ids())
    } else {
        listOf()
    }

    val members: List<Member> = parseMembers(ctx.struct_components())
    return Struct(id, typeParameters, members)
}

private fun parseCommaDelimitedIds(cd_ids: SemlangParser.Cd_idsContext): List<String> {
    val results = ArrayList<String>()
    var inputs = cd_ids
    while (true) {
        if (inputs.ID() != null) {
            results.add(inputs.ID().text)
        }
        if (inputs.cd_ids() == null) {
            break
        }
        inputs = inputs.cd_ids()
    }
    return results
}

private fun parseMembers(members: SemlangParser.Struct_componentsContext): List<Member> {
    val results = ArrayList<Member>()
    var inputs = members
    while (true) {
        if (inputs.struct_component() != null) {
            results.add(parseMember(inputs.struct_component()))
        }
        if (inputs.struct_components() == null) {
            break
        }
        inputs = inputs.struct_components()
    }
    return results
}

private fun parseMember(member: SemlangParser.Struct_componentContext): Member {
    val name = member.ID().text
    val type = parseType(member.type())
    return Member(name, type)
}

private fun parseBlock(block: SemlangParser.BlockContext): AmbiguousBlock {
    val assignments = parseAssignments(block.assignments())
    val returnedExpression = parseExpression(block.return_statement().expression())
    return AmbiguousBlock(assignments, returnedExpression)
}

private fun parseAssignments(assignments: SemlangParser.AssignmentsContext): List<AmbiguousAssignment> {
    val results = ArrayList<AmbiguousAssignment>()
    var inputs = assignments
    while (true) {
        if (inputs.assignment() != null) {
            results.add(parseAssignment(inputs.assignment()))
        }
        if (inputs.assignments() == null) {
            break
        }
        inputs = inputs.assignments()
    }
    return results
}

private fun parseAssignment(assignment: SemlangParser.AssignmentContext): AmbiguousAssignment {
    val name = assignment.ID().text
    val type = parseType(assignment.type())
    val expression = parseExpression(assignment.expression())
    return AmbiguousAssignment(name, type, expression)
}

private fun parseExpression(expression: SemlangParser.ExpressionContext): AmbiguousExpression {
    if (expression.IF() != null) {
        val condition = parseExpression(expression.expression())
        val thenBlock = parseBlock(expression.block(0))
        val elseBlock = parseBlock(expression.block(1))
        return AmbiguousExpression.IfThen(condition, thenBlock, elseBlock)
    }

    if (expression.LITERAL() != null) {
        val type = parseTypeGivenParameters(expression.simple_type_id(), listOf())
        val literal = expression.LITERAL().text.drop(1).dropLast(1)
        return AmbiguousExpression.Literal(type, literal)
    }

    if (expression.ARROW() != null) {
        val inner = parseExpression(expression.expression())
        val name = expression.ID().text
        return AmbiguousExpression.Follow(inner, name)
    }

    if (expression.LPAREN() != null) {
        val functionId = parseFunctionId(expression.function_id())
        val arguments = parseCommaDelimitedExpressions(expression.cd_expressions())
        val groundParameters = if (expression.LESS_THAN() != null) {
            parseCommaDelimitedTypes(expression.cd_types())
        } else {
            listOf()
        }
        return AmbiguousExpression.FunctionCall(functionId, arguments, groundParameters)
    }

    if (expression.function_id() != null) {
        val chosenParameters = if (expression.LESS_THAN() != null) {
            parseCommaDelimitedTypes(expression.cd_types())
        } else {
            listOf()
        }
        return AmbiguousExpression.VariableOrFunctionReference(parseFunctionId(expression.function_id()), chosenParameters)
    }
    throw IllegalArgumentException("Couldn't parseFunction $expression")
}

private fun parseCommaDelimitedExpressions(cd_expressions: SemlangParser.Cd_expressionsContext): List<AmbiguousExpression> {
    val expressions = ArrayList<AmbiguousExpression>()
    var inputs = cd_expressions
    while (true) {
        if (inputs.expression() != null) {
            expressions.add(parseExpression(inputs.expression()))
        }
        if (inputs.cd_expressions() == null) {
            break
        }
        inputs = inputs.cd_expressions()
    }
    return expressions
}

private fun parseFunctionArguments(function_arguments: SemlangParser.Function_argumentsContext): List<Argument> {
    val arguments = ArrayList<Argument>()
    var inputs = function_arguments
    while (true) {
        if (inputs.function_argument() != null) {
            arguments.add(parseFunctionArgument(inputs.function_argument()))
        }
        if (inputs.function_arguments() == null) {
            break
        }
        inputs = inputs.function_arguments()
    }
    return arguments
}

private fun parseFunctionArgument(function_argument: SemlangParser.Function_argumentContext): Argument {
    val name = function_argument.ID().text
    val type = parseType(function_argument.type())
    return Argument(name, type)
}

private fun parseFunctionId(function_id: SemlangParser.Function_idContext): FunctionId {
    if (function_id.packag() != null) {
        val packag = parsePackage(function_id.packag())
        return FunctionId(packag, function_id.ID().text)
    } else {
        return FunctionId.of(function_id.ID().text)
    }
}

private fun parsePackage(packag: SemlangParser.PackagContext): Package {
    val parts = ArrayList<String>()
    var inputs = packag
    while (true) {
        if (inputs.ID() != null) {
            parts.add(inputs.ID().text)
        }
        if (inputs.packag() == null) {
            break
        }
        inputs = inputs.packag()
    }
    return Package(parts)
}

private fun parseType(type: SemlangParser.TypeContext): Type {
    if (type.ARROW() != null) {
        //Function type
        val argumentTypes = parseCommaDelimitedTypes(type.cd_types())
        val outputType = parseType(type.type())
        return Type.FunctionType(argumentTypes, outputType)
    }

    if (type.LESS_THAN() != null) {
        val parameterTypes = parseCommaDelimitedTypes(type.cd_types())
        return parseTypeGivenParameters(type.simple_type_id(), parameterTypes)
    }

    if (type.simple_type_id() != null) {
        return parseTypeGivenParameters(type.simple_type_id(), listOf())
    }
    throw IllegalArgumentException("Unparsed type " + type)
}

private fun parseCommaDelimitedTypes(cd_types: SemlangParser.Cd_typesContext): List<Type> {
    val results = ArrayList<Type>()
    var inputs = cd_types
    while (true) {
        if (inputs.type() != null) {
            results.add(parseType(inputs.type()))
        }
        if (inputs.cd_types() == null) {
            break
        }
        inputs = inputs.cd_types()
    }
    return results
}

private fun parseTypeGivenParameters(simple_type_id: SemlangParser.Simple_type_idContext, parameters: List<Type>): Type {
    if (simple_type_id.packag() != null) {
        return Type.NamedType(FunctionId(parsePackage(simple_type_id.packag()), simple_type_id.ID().text), parameters)
    }

    val typeId = simple_type_id.ID().text
    if (typeId == "Natural") {
        return Type.NATURAL
    } else if (typeId == "Integer") {
        return Type.INTEGER
    } else if (typeId == "Boolean") {
        return Type.BOOLEAN
    } else if (typeId == "List") {
        if (parameters.size != 1) {
            error("List should only accept a single parameter; parameters were: $parameters")
        }
        return Type.List(parameters[0])
    }

    return Type.NamedType(FunctionId.of(typeId), parameters)
}

private class MyListener : SemlangParserBaseListener() {
    val structs: MutableList<Struct> = ArrayList()
    val functions: MutableList<Function> = ArrayList()

    override fun enterFunction(ctx: SemlangParser.FunctionContext?) {
        super.enterFunction(ctx)
        if (ctx != null) {
            functions.add(parseFunction(ctx))
        }
    }

    override fun enterStruct(ctx: SemlangParser.StructContext?) {
        super.enterStruct(ctx)
        if (ctx != null) {
            val struct = parseStruct(ctx)
            structs.add(struct)
        }
    }
}

fun parseFile(file: File): InterpreterContext {
    return parseFileNamed(file.absolutePath)
}

fun parseFileNamed(filename: String): InterpreterContext {
    val input = ANTLRFileStream(filename)
    val lexer = SemlangLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = SemlangParser(tokens)
    val tree: SemlangParser.FileContext = parser.file()

    val extractor = MyListener()
    ParseTreeWalker.DEFAULT.walk(extractor, tree)

    return InterpreterContext(indexById(extractor.functions), indexStructsById(extractor.structs))
}

private fun indexStructsById(structs: MutableList<Struct>): Map<FunctionId, Struct> {
    return structs.associateBy(Struct::id)
}

private fun indexById(functions: MutableList<Function>): Map<FunctionId, Function> {
    return functions.associateBy(Function::id)
}

