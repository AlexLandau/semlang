package semlang.parser.test

import org.junit.Assert.assertEquals
import org.junit.Test
import semlang.interpreter.SemObject
import semlang.interpreter.SemlangForwardInterpreter
import semlang.parser.tokenize
import java.math.BigInteger

class Tests {
    @Test
    fun seeTokens() {
//        val stream = tokenize("foo")
//        stream.fill()
//        System.out.println(stream.getTokens())
        System.out.println(tokenize("../notional/mvp.sem"))
    }

    @Test
    fun runMvpFunction() {
        val function = tokenize("../notional/mvp.sem").first()
        val functionsMap = mapOf(function.id to function)
        val interpreter = SemlangForwardInterpreter(functionsMap)
        val result123 = interpreter.interpret(function.id, listOf(int(1), int(2), int(3)))
        assertEquals(SemObject.Boolean(false), result123)
        val result345 = interpreter.interpret(function.id, listOf(int(3), int(4), int(5)))
        assertEquals(SemObject.Boolean(true), result345)
    }

    private fun int(i: Int): SemObject {
        return SemObject.Integer(BigInteger.valueOf(i.toLong()))
    }
}
