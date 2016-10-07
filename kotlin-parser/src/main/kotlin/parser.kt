package semlang.parser

import java.util.*

sealed class Token {

}

//TODO: Move this to another project
fun tokenize(code: String): List<Token> {
    val results: MutableList<Token> = ArrayList();
    var index: Long = 0

    val foo = Semlang.DOT;
    while (index < code.length) {
        //TODO: Continue with this, or use a parser framework?
    }

    return results;
}


