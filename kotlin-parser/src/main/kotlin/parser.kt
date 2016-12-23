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

fun parse(function: SemlangParser.FunctionContext): Function {
    val id: FunctionId = parseFunctionId(function.function_id())
    val arguments: List<Argument> = parseFunctionArguments(function.function_arguments())
    val returnType: Type = parseType(function.type())
    val block: Block = parseBlock(function.block())
//            Block(listOf(
//            Assignment("fooVar", Expression.Variable("fixmeName"))
//    ),
//            Expression.Variable("fooVar"))
    return Function(id, arguments, returnType, block)
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
    if (expression.EQUALS() != null) {
        val left = parseExpression(expression.expression(0))
        val right = parseExpression(expression.expression(1))
        return Expression.Equals(left, right)
    }
    if (expression.LPAREN() != null) {
        val functionId = parseFunctionId(expression.function_id())
        val arguments = parseCdExpressions(expression.cd_expressions())
        return Expression.FunctionCall(functionId, arguments)
    }

    return Expression.Variable(expression.ID().text)
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
    val typeId = type.ID().text
    if (typeId.toLowerCase().equals("natural")) {
        return Type.NATURAL
    } else if (typeId.toLowerCase().equals("integer")) {
        return Type.INTEGER
    } else if (typeId.toLowerCase().equals("boolean")) {
        return Type.BOOLEAN
    }
    throw IllegalArgumentException("Unparsed type " + typeId)
}

class MyListener : SemlangParserBaseListener() {
    val functions: MutableList<Function> = ArrayList()

    override fun enterFunction(ctx: SemlangParser.FunctionContext?) {
        super.enterFunction(ctx)
        if (ctx != null) {
            functions.add(parse(ctx))
        }
    }
}

fun tokenize(code: String): List<Function> {

    val input = ANTLRFileStream("../notional/mvp.sem")
//    val lexer = SemlangLexer(input)
//    val tokens = CommonTokenStream(lexer)

    val lexer = SemlangLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = SemlangParser(tokens)
    val tree: SemlangParser.FunctionContext = parser.function() // maybe should be File

    val extractor = MyListener()
    System.err.println("About to walk the tree");
    ParseTreeWalker.DEFAULT.walk(extractor, tree) // initiate walk of tree with listener in use of default walker
    System.err.println("Just walked the tree");

    return extractor.functions

//    val parser = ExprParser(tokens)
//    parser.getInterpreter().setPredictionMode(PredictionMode.SLL)
//    try {
//        parser.stat()  // STAGE 1
//    } catch (ex: Exception) {
//        tokens.reset() // rewind input stream
//        parser.reset()
//        parser.getInterpreter().setPredictionMode(PredictionMode.LL)
//        parser.stat()  // STAGE 2
//        // if we parse ok, it's LL not SLL
//    }
}

