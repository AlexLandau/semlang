package semlang.parser

import org.antlr.v4.runtime.ANTLRFileStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.atn.PredictionMode
import java.util.*

sealed class Token {

}

//TODO: Move this to another project
fun tokenize(code: String): List<Token> {
    val results: MutableList<Token> = ArrayList();
    var index: Long = 0

    while (index < code.length) {
        //TODO: Continue with this, or use a parser framework?
    }

    val input = ANTLRFileStream("notional/mvp.sem")
    val lexer = ExprLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = ExprParser(tokens)
    parser.getInterpreter().setPredictionMode(PredictionMode.SLL)
    try {
        parser.stat()  // STAGE 1
    } catch (ex: Exception) {
        tokens.reset() // rewind input stream
        parser.reset()
        parser.getInterpreter().setPredictionMode(PredictionMode.LL)
        parser.stat()  // STAGE 2
        // if we parse ok, it's LL not SLL
    }


    return results
}


