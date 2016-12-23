package semlang.parser.test

import org.junit.Test
import semlang.parser.tokenize

class Tests {
    @Test
    fun seeTokens() {
//        val stream = tokenize("foo")
//        stream.fill()
//        System.out.println(stream.getTokens())
        System.out.println(tokenize("foo"))
    }
}
