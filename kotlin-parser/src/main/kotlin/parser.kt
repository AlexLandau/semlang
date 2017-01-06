package semlang.parser

import org.antlr.v4.runtime.ANTLRFileStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import semlang.antlr.SemlangLexer
import semlang.antlr.SemlangParser
import semlang.antlr.SemlangParserBaseListener
import semlang.api.*
import semlang.api.Function
import java.util.*

fun parseFunction(function: SemlangParser.FunctionContext): Function {
    val id: FunctionId = parseFunctionId(function.function_id())
    val arguments: List<Argument> = parseFunctionArguments(function.function_arguments())
    val returnType: Type = parseType(function.type())
    val block: Block = parseBlock(function.block())
    return Function(id, arguments, returnType, block)
}

fun parseStruct(ctx: SemlangParser.StructContext): Struct {
    val id: FunctionId = parseFunctionId(ctx.function_id())

    // TODO: Make use of type parameters appropriately
    val typeParameters: List<String> = if (ctx.cd_ids() != null) {
        parseCommaDelimitedIds(ctx.cd_ids())
    } else {
        listOf()
    }

    val members: List<Member> = parseMembers(ctx.struct_components())
    return Struct(id, typeParameters, members)
}

fun parseCommaDelimitedIds(cd_ids: SemlangParser.Cd_idsContext): List<String> {
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

fun parseMembers(members: SemlangParser.Struct_componentsContext): List<Member> {
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

fun parseMember(member: SemlangParser.Struct_componentContext): Member {
    val name = member.ID().text
    val type = parseType(member.type())
    return Member(name, type)
}

fun parseBlock(block: SemlangParser.BlockContext): Block {
    val assignments = parseAssignments(block.assignments())
    val returnedExpression = parseExpression(block.return_statement().expression())
    return Block(assignments, returnedExpression)
}

fun parseAssignments(assignments: SemlangParser.AssignmentsContext): List<Assignment> {
    val results = ArrayList<Assignment>()
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

fun parseAssignment(assignment: SemlangParser.AssignmentContext): Assignment {
    val name = assignment.ID().text
    val type = parseType(assignment.type())
    val expression = parseExpression(assignment.expression())
    return Assignment(name, type, expression)
}

fun parseExpression(expression: SemlangParser.ExpressionContext): Expression {
    if (expression.IF() != null) {
        val condition = parseExpression(expression.expression())
        val thenBlock = parseBlock(expression.block(0))
        val elseBlock = parseBlock(expression.block(1))
        return Expression.IfThen(condition, thenBlock, elseBlock)
    }

    if (expression.LITERAL() != null) {
        val type = parseSimpleType(expression.simple_type_id())
        val literal = expression.LITERAL().text.drop(1).dropLast(1)
        return Expression.Literal(type, literal)
    }

    if (expression.ARROW() != null) {
        val inner = parseExpression(expression.expression())
        val name = expression.ID().text
        return Expression.Follow(inner, name)
    }

    if (expression.LPAREN() != null) {
        val functionId = parseFunctionId(expression.function_id())
        val arguments = parseCdExpressions(expression.cd_expressions())
        return Expression.FunctionCall(functionId, arguments)
    }

    if (expression.ID() != null) {
        return Expression.Variable(expression.ID().text)
    }
    throw IllegalArgumentException("Couldn't parseFunction ${expression}")
}

fun parseCdExpressions(cd_expressions: SemlangParser.Cd_expressionsContext): List<Expression> {
    val expressions = ArrayList<Expression>()
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

fun parseFunctionArguments(function_arguments: SemlangParser.Function_argumentsContext): List<Argument> {
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

fun parseFunctionArgument(function_argument: SemlangParser.Function_argumentContext): Argument {
    val name = function_argument.ID().text
    val type = parseType(function_argument.type())
    return Argument(name, type)
}

fun parseFunctionId(function_id: SemlangParser.Function_idContext): FunctionId {
    if (function_id.packag() != null) {
        val packag = parsePackage(function_id.packag())
        return FunctionId(packag, function_id.ID().text)
    } else {
        return FunctionId(Package(listOf()), function_id.ID().text)
    }
}

fun parsePackage(packag: SemlangParser.PackagContext): Package {
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

fun parseType(type: SemlangParser.TypeContext): Type {
    if (type.LESS_THAN() != null) {
        val simpleType = parseSimpleType(type.simple_type_id())
        val parameterTypes = parseCommaDelimitedTypes(type.cd_types())
        return Type.ParameterizedType(simpleType, parameterTypes)
    }

    if (type.simple_type_id() != null) {
        return parseSimpleType(type.simple_type_id())
    }
    throw IllegalArgumentException("Unparsed type " + type)
}

fun parseCommaDelimitedTypes(cd_types: SemlangParser.Cd_typesContext): List<Type> {
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

fun parseSimpleType(simple_type_id: SemlangParser.Simple_type_idContext): Type {
    if (simple_type_id.packag() != null) {
        return Type.NamedType(FunctionId(parsePackage(simple_type_id.packag()), simple_type_id.ID().text))
    }

    val typeId = simple_type_id.ID().text
    if (typeId.equals("Natural")) {
        return Type.NATURAL
    } else if (typeId.equals("Integer")) {
        return Type.INTEGER
    } else if (typeId.equals("Boolean")) {
        return Type.BOOLEAN
    }

    return Type.NamedType(FunctionId(Package(listOf()), typeId))
}

class MyListener : SemlangParserBaseListener() {
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

//data class FunctionsAndStructs(val functions: List<Function>, val structs: List<Struct>)

fun parseFile(filename: String = "../notional/mvp.sem"): InterpreterContext {

    val input = ANTLRFileStream(filename)
    val lexer = SemlangLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = SemlangParser(tokens)
    val tree: SemlangParser.FileContext = parser.file()

    val extractor = MyListener()
    ParseTreeWalker.DEFAULT.walk(extractor, tree)

    return InterpreterContext(indexById(extractor.functions), indexStructsById(extractor.structs))
}

fun indexStructsById(structs: MutableList<Struct>): Map<FunctionId, Struct> {
    return structs.associateBy(Struct::id)
}

fun indexById(functions: MutableList<Function>): Map<FunctionId, Function> {
    return functions.associateBy(Function::id)
}
