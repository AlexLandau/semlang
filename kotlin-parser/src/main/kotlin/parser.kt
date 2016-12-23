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
    val thePackage: Package = Package(listOf("fakepackage", "fixme"))
    val id: FunctionId = FunctionId(thePackage, "FIXME")
    val arguments: List<Argument> = listOf(Argument("fixmeName", Type.NATURAL))
    val returnType: Type = Type.INT_S64
    val block: Block = Block(listOf(
            Assignment("fooVar", Expression.Variable("fixmeName"))
    ),
            Expression.Variable("fooVar"))
    return Function(id, arguments, returnType, block)
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

